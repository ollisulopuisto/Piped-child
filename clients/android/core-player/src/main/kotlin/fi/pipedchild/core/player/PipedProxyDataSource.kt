package fi.pipedchild.core.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.io.IOException

/** Media3 bridge to the pure proxy policy. HTTP redirects are disabled:
 * checking the initial URL is not enough when a manifest or segment
 * redirects to an undisclosed origin. No cookies or credentials are used. */
class PipedProxyDataSource internal constructor(
    private val proxyBase: String,
    private val delegate: DataSource,
) : DataSource {
    class Factory(private val proxyBase: String) : DataSource.Factory {
        private val http = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        override fun createDataSource(): DataSource =
            PipedProxyDataSource(proxyBase, OkHttpDataSource.Factory(http).createDataSource())
    }

    override fun open(dataSpec: DataSpec): Long {
        val rangeHeader = dataSpec.httpRequestHeaders.entries
            .firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value ?: rangeHeaderFor(dataSpec)
        return when (val r = ProxyRewriter.resolve(dataSpec.uri.toString(), proxyBase, rangeHeader)) {
            is ProxyRewriter.Result.Open -> {
                val builder = dataSpec.buildUpon().setUri(r.uri)
                if (r.rangeConverted) {
                    builder.setPosition(0).setLength(C.LENGTH_UNSET.toLong())
                        .setHttpRequestHeaders(dataSpec.httpRequestHeaders.filterKeys {
                            !it.equals("Range", ignoreCase = true)
                        })
                }
                delegate.open(builder.build())
            }
            is ProxyRewriter.Result.Blocked -> throw IOException("Media destination is not approved.")
        }
    }

    private fun rangeHeaderFor(spec: DataSpec): String? {
        if (spec.position == 0L && spec.length == C.LENGTH_UNSET.toLong()) return null
        val end = if (spec.length == C.LENGTH_UNSET.toLong()) "" else "${spec.position + spec.length - 1}"
        return "bytes=${spec.position}-$end"
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
    override fun getUri() = delegate.uri
    override fun getResponseHeaders() = delegate.responseHeaders
    override fun close() = delegate.close()
    override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
}
