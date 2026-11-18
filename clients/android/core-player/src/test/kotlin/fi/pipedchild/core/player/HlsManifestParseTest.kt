package fi.pipedchild.core.player

import android.net.Uri
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File

/**
 * M1-A feasibility evidence for the HLS path: a real HLS master playlist
 * recorded from a live Piped instance's LBRY proxy on 2026-11-18
 * (fixtures/lbry_hls_master.m3u8) parses through Media3's HlsPlaylistParser.
 * HLS is both the livestream path and the degraded-instance fallback shared
 * with iOS, so it must parse on Android too.
 */
@RunWith(RobolectricTestRunner::class)
class HlsManifestParseTest {

    private fun fixture(name: String): String =
        File(File("..", ".."), "contracts/fixtures/$name").readText()

    @Test
    fun `recorded LBRY HLS master playlist parses with all four variants`() {
        val playlist = HlsPlaylistParser().parse(
            Uri.parse("https://proxy.piped.private.coffee/lbry/master.m3u8"),
            ByteArrayInputStream(fixture("lbry_hls_master.m3u8").toByteArray(Charsets.UTF_8)),
        )
        assertTrue(playlist is HlsMultivariantPlaylist)
        val variants = (playlist as HlsMultivariantPlaylist).variants
        assertEquals(4, variants.size)

        val heights = variants.map { it.format.height }.sortedDescending()
        assertEquals(listOf(1080, 720, 360, 144), heights)
        assertTrue(variants.all { it.format.codecs?.contains("avc1") == true })
        assertTrue(variants.map { it.url.toString() }.all { it.endsWith(".m3u8") })
    }
}
