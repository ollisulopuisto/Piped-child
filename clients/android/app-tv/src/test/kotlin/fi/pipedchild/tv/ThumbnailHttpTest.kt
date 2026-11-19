package fi.pipedchild.tv

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Test
import org.junit.Assert.*

/** HTTP transport tests use loopback HTTP only. The repository's separate
 * approval policy rejects non-HTTPS destinations before this transport. */
class ThumbnailHttpTest {
    @Test
    fun `transport never follows a redirect even on the same origin`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/outside")))
            server.enqueue(MockResponse().addHeader("Content-Type", "image/png").setBody("must not fetch"))
            assertNull(fetchThumbnail(server.url("/redirect").toString()))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `non-image content is rejected before decoding`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().addHeader("Content-Type", "text/html").setBody("not an image"))
            assertNull(fetchThumbnail(server.url("/html").toString()))
        }
    }

    @Test
    fun `chunked image bodies cannot bypass the download size limit`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().addHeader("Content-Type", "image/png")
                .setChunkedBody(Buffer().write(ByteArray(MAX_THUMBNAIL_BYTES + 9000)), 8192))
            assertNull(fetchThumbnail(server.url("/large.png").toString()))
        }
    }

    @Test
    fun `a successful image body is returned without cookies or authorization`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().addHeader("Content-Type", "image/png").setBody("image bytes"))
            assertArrayEquals("image bytes".toByteArray(), fetchThumbnail(server.url("/image.png").toString()))
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
        }
    }
}
