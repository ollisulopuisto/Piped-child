package fi.pipedchild.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Selection-order tests mirroring the web client's VideoPlayer.vue. */
class PlaybackSelectorTest {

    private fun adaptiveAudio() = PipedStream(
        url = "https://x/audio", mimeType = "audio/mp4", codec = "mp4a.40.2", itag = 140, videoOnly = false,
    )

    private fun adaptiveVideo(height: Int = 1080) = PipedStream(
        url = "https://x/v$height", mimeType = "video/mp4", codec = "avc1.640028",
        itag = 137, height = height, videoOnly = true,
    )

    private fun muxed(height: Int) = PipedStream(
        url = "https://x/m$height", mimeType = "video/mp4", quality = "${height}p", height = height,
    )

    @Test
    fun `livestream always selects HLS on android`() {
        val r = StreamsResponse(livestream = true, hls = "https://x/live.m3u8", dash = "https://x/dash.mpd")
        assertEquals(
            PlaybackSelector.Source.Hls("https://x/live.m3u8"),
            PlaybackSelector.select(r, PlaybackSelector.Platform.ANDROID),
        )
    }

    @Test
    fun `livestream without hls is an actionable error not a crash`() {
        val r = StreamsResponse(livestream = true)
        assertFailsWith<PipedError.PlaybackFormatUnsupported> {
            PlaybackSelector.select(r, PlaybackSelector.Platform.ANDROID)
        }
    }

    @Test
    fun `adaptive audio plus server dash selects dash with rewrite=false like the web client`() {
        val r = StreamsResponse(
            dash = "https://x/manifest.mpd?host=y",
            audioStreams = listOf(adaptiveAudio()),
            videoStreams = listOf(adaptiveVideo()),
        )
        val s = PlaybackSelector.select(r, PlaybackSelector.Platform.ANDROID)
        assertIs<PlaybackSelector.Source.Dash>(s)
        assertEquals("https://x/manifest.mpd?host=y&rewrite=false", s.manifestUrl)
    }

    @Test
    fun `server dash WITHOUT adaptive audio is passed through unchanged like the web client`() {
        val r = StreamsResponse(dash = "https://x/manifest.mpd?host=y")
        val s = PlaybackSelector.select(r, PlaybackSelector.Platform.ANDROID)
        assertIs<PlaybackSelector.Source.Dash>(s)
        // Web only sets rewrite=false in the adaptive-audio branch
        assertEquals("https://x/manifest.mpd?host=y", s.manifestUrl)
    }

    @Test
    fun `adaptive audio without server dash selects client-generated dash`() {
        val r = StreamsResponse(
            duration = 212,
            audioStreams = listOf(adaptiveAudio()),
            videoStreams = listOf(adaptiveVideo(), muxed(360)),
        )
        val s = PlaybackSelector.select(r, PlaybackSelector.Platform.ANDROID)
        assertIs<PlaybackSelector.Source.GeneratedDash>(s)
        assertEquals(212, s.durationSeconds)
        assertEquals(3, s.formats.size) // audio + video + muxed; generator filters the muxed one
    }

    @Test
    fun `preferHls bypasses dash like the web preference`() {
        val r = StreamsResponse(
            dash = "https://x/m.mpd",
            hls = "https://x/m.m3u8",
            audioStreams = listOf(adaptiveAudio()),
        )
        assertEquals(
            PlaybackSelector.Source.Hls("https://x/m.m3u8"),
            PlaybackSelector.select(r, PlaybackSelector.Platform.ANDROID, preferHls = true),
        )
    }

    @Test
    fun `ios never selects dash even when adaptive audio exists`() {
        val r = StreamsResponse(
            dash = "https://x/m.mpd",
            audioStreams = listOf(adaptiveAudio()),
            videoStreams = listOf(adaptiveVideo(), muxed(720), muxed(360)),
        )
        val s = PlaybackSelector.select(r, PlaybackSelector.Platform.IOS)
        assertIs<PlaybackSelector.Source.Progressive>(s)
        // Web's findLast semantics: the LAST codec==null stream, not the biggest
        assertEquals(360, s.stream.height)
    }

    @Test
    fun `progressive fallback picks the LAST codec-null stream like web findLast`() {
        val r = StreamsResponse(videoStreams = listOf(adaptiveVideo(), muxed(720), muxed(360)))
        val s = PlaybackSelector.select(r, PlaybackSelector.Platform.ANDROID)
        assertIs<PlaybackSelector.Source.Progressive>(s)
        assertEquals(360, s.stream.height)
    }

    @Test
    fun `nothing playable throws the typed error`() {
        val r = StreamsResponse()
        assertFailsWith<PipedError.PlaybackFormatUnsupported> {
            PlaybackSelector.select(r, PlaybackSelector.Platform.ANDROID)
        }
    }

    @Test
    fun `withRewriteFalse replaces an existing rewrite=true instead of skipping`() {
        assertEquals(
            "https://x/m.mpd?host=y&rewrite=false",
            PlaybackSelector.withRewriteFalse("https://x/m.mpd?host=y&rewrite=true"),
        )
    }

    @Test
    fun `withRewriteFalse keeps unrelated params containing rewrite`() {
        assertEquals(
            "https://x/m.mpd?rewritten=1&rewrite=false",
            PlaybackSelector.withRewriteFalse("https://x/m.mpd?rewritten=1"),
        )
    }

    @Test
    fun `withRewriteFalse preserves fragments`() {
        assertEquals(
            "https://x/m.mpd?rewrite=false#t=30",
            PlaybackSelector.withRewriteFalse("https://x/m.mpd#t=30"),
        )
        assertEquals(
            "https://x/m.mpd?host=y&rewrite=false#frag",
            PlaybackSelector.withRewriteFalse("https://x/m.mpd?host=y&rewrite=true#frag"),
        )
    }

    @Test
    fun `withRewriteFalse does not duplicate rewrite=false`() {
        assertEquals(
            "https://x/m.mpd?rewrite=false",
            PlaybackSelector.withRewriteFalse("https://x/m.mpd?rewrite=false"),
        )
    }
}
