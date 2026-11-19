package fi.pipedchild.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PipedProxyDataSourceTest {
    private class RecordingSource : DataSource {
        var opened: DataSpec? = null
        override fun open(dataSpec: DataSpec): Long { opened = dataSpec; return 100L }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = opened?.uri
        override fun close() {}
        override fun addTransferListener(transferListener: TransferListener) {}
    }

    @Test
    fun `DASH byte positions become exactly one range query without a second byte skip`() {
        val delegate = RecordingSource()
        val source = PipedProxyDataSource("https://proxy.example", delegate)
        source.open(DataSpec.Builder().setUri("https://rr1.googlevideo.com/videoplayback?range=old")
            .setPosition(100).setLength(50).setHttpRequestHeaders(mapOf("range" to "bytes=100-149")).build())
        val opened = delegate.opened!!
        assertEquals("https://proxy.example/videoplayback?host=rr1.googlevideo.com&range=100-149", opened.uri.toString())
        assertEquals(0L, opened.position)
        assertEquals(C.LENGTH_UNSET.toLong(), opened.length)
        assertTrue(opened.httpRequestHeaders.keys.none { it.equals("range", true) })
    }

    @Test
    fun `HLS segment keeps ordinary HTTP position and length`() {
        val delegate = RecordingSource()
        val source = PipedProxyDataSource("https://proxy.example", delegate)
        source.open(DataSpec.Builder().setUri("https://proxy.example/segment.ts").setPosition(100).setLength(50).build())
        assertEquals("https://proxy.example/segment.ts", delegate.opened!!.uri.toString())
        assertEquals(100L, delegate.opened!!.position)
        assertEquals(50L, delegate.opened!!.length)
    }

    @Test
    fun `unapproved URL fails before the HTTP delegate is opened`() {
        val delegate = RecordingSource()
        val source = PipedProxyDataSource("https://proxy.example", delegate)
        try {
            source.open(DataSpec(Uri.parse("https://outside.example/video")))
            fail("must reject outside hosts")
        } catch (expected: IOException) {
            assertNull(delegate.opened)
        }
    }
}
