package fi.pipedchild.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.*

/** Small memory-only image cache with no cookies, credentials, redirects,
 * or disk persistence. Approval is checked even on a cache hit. */
internal class ThumbnailRepository(
    private val transport: suspend (String) -> ByteArray? = { fetchThumbnail(it) },
) {
    private val permits = Semaphore(3)
    private val cache by lazy {
        object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }

    suspend fun load(url: String, consent: ThumbnailConsent): Bitmap? {
        if (!consent.allows(url)) return null
        cache.get(url)?.let { return it }
        return permits.withPermit {
            cache.get(url)?.let { return@withPermit it }
            try {
                val bytes = transport(url) ?: return@withPermit null
                if (bytes.size > MAX_THUMBNAIL_BYTES) return@withPermit null
                val bitmap = withContext(Dispatchers.Default) { decodeThumbnail(bytes) } ?: return@withPermit null
                cache.put(url, bitmap)
                bitmap
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // keep the title and placeholder, never expose network details
            }
        }
    }
}

internal const val MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024

internal fun decodeThumbnail(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
    var sample = 1
    while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 360) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private val thumbnailHttp by lazy {
    OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
}

internal suspend fun fetchThumbnail(url: String): ByteArray? = suspendCancellableCoroutine { continuation ->
    val call = thumbnailHttp.newCall(Request.Builder().url(url).build())
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, exception: IOException) {
            continuation.resume(null)
        }
        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use {
                    if (!it.isSuccessful || it.body == null ||
                        it.body!!.contentLength() > MAX_THUMBNAIL_BYTES ||
                        it.body!!.contentType()?.type != "image") null
                    else it.body!!.byteStream().use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= MAX_THUMBNAIL_BYTES) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        if (output.size() > MAX_THUMBNAIL_BYTES) null else output.toByteArray()
                    }
                }
            } catch (exception: Exception) { null }
            continuation.resume(result)
        }
    })
}
