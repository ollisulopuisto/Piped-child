package fi.pipedchild.core.player

import fi.pipedchild.core.model.PipedStream
import fi.pipedchild.core.model.StreamsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StreamPicker precedence and the proxy-coverage constraint, against the
 * URL shapes observed live from pipedapi.ducks.party on 2026-11-19:
 * YouTube formats arrive pre-proxied on piped-proxy.ducks.party, LBRY
 * formats arrive as direct player.odycdn.com URLs (excluded here), and
 * dash/hls can be absent entirely.
 */
class StreamPickerTest {

    private val proxy = "https://proxy.example.com"

    private fun adaptiveVideo(host: String) = PipedStream(
        url = "https://$host/videoplayback?itag=137",
        format = "MPEG_4",
        quality = "1080p",
        codec = "avc1.640028",
        mimeType = "video/mp4",
        itag = 137,
        videoOnly = true,
        initStart = 0,
        initEnd = 740,
        indexStart = 741,
        indexEnd = 1200,
    )

    private fun adaptiveAudio(host: String) = PipedStream(
        url = "https://$host/videoplayback?itag=140",
        format = "M4A",
        quality = "128kbps",
        codec = "mp4a.40.2",
        mimeType = "audio/mp4",
        itag = 140,
        videoOnly = false,
        initStart = 0,
        initEnd = 631,
        indexStart = 632,
        indexEnd = 900,
    )

    private fun muxed(host: String) = PipedStream(
        url = "https://$host/videoplayback?itag=18",
        format = "MPEG_4",
        quality = "360p",
        codec = null,
        mimeType = "video/mp4",
        itag = 18,
        videoOnly = false,
    )

    private fun base(
        dash: String? = null,
        hls: String? = null,
        video: List<PipedStream> = emptyList(),
        audio: List<PipedStream> = emptyList(),
        proxyUrl: String? = proxy,
    ) = StreamsResponse(
        title = "t",
        duration = 212,
        dash = dash,
        hls = hls,
        proxyUrl = proxyUrl,
        videoStreams = video,
        audioStreams = audio,
    )

    @Test
    fun `server DASH url wins when it is proxy-covered`() {
        val choice = StreamPicker.pick(
            base(dash = "https://proxy.example.com/dash.mpd", video = listOf(muxed("proxy.example.com"))),
        )
        assertEquals(PlaybackChoice.DashUrl("https://proxy.example.com/dash.mpd"), choice)
    }

    @Test
    fun `googlevideo DASH url is accepted as rewritable`() {
        val choice = StreamPicker.pick(base(dash = "https://rr1.googlevideo.com/dash.mpd"))
        assertEquals(PlaybackChoice.DashUrl("https://rr1.googlevideo.com/dash.mpd"), choice)
    }

    @Test
    fun `DASH url on an uncovered host is skipped`() {
        val choice = StreamPicker.pick(
            base(dash = "https://player.odycdn.com/manifest.mpd", video = listOf(muxed("proxy.example.com"))),
        )
        assertEquals(
            PlaybackChoice.Progressive("https://proxy.example.com/videoplayback?itag=18"),
            choice,
        )
    }

    @Test
    fun `missing server DASH generates an MPD from adaptive formats`() {
        val choice = StreamPicker.pick(
            base(
                video = listOf(adaptiveVideo("proxy.example.com"), muxed("proxy.example.com")),
                audio = listOf(adaptiveAudio("proxy.example.com")),
            ),
        )
        assertTrue(choice is PlaybackChoice.DashManifest)
        val mpd = (choice as PlaybackChoice.DashManifest).mpd
        assertTrue(mpd.contains("<MPD"))
        // the muxed progressive stream must not become an AdaptationSet
        assertTrue(!mpd.contains("itag=18"))
    }

    @Test
    fun `HLS is used when no DASH is possible`() {
        val choice = StreamPicker.pick(
            base(hls = "https://proxy.example.com/master.m3u8", video = listOf(muxed("proxy.example.com"))),
        )
        assertEquals(PlaybackChoice.Hls("https://proxy.example.com/master.m3u8"), choice)
    }

    @Test
    fun `preferHls puts HLS before DASH like the frontend preference`() {
        val response = base(
            dash = "https://proxy.example.com/dash.mpd",
            hls = "https://proxy.example.com/master.m3u8",
        )
        assertEquals(
            PlaybackChoice.Hls("https://proxy.example.com/master.m3u8"),
            StreamPicker.pick(response, preferHls = true),
        )
    }

    @Test
    fun `progressive fallback takes the last codec-less muxed stream`() {
        val low = muxed("proxy.example.com").copy(quality = "360p", url = "https://proxy.example.com/v?itag=18")
        val high = muxed("proxy.example.com").copy(quality = "720p", url = "https://proxy.example.com/v?itag=22")
        val choice = StreamPicker.pick(base(video = listOf(low, high)))
        assertEquals(PlaybackChoice.Progressive("https://proxy.example.com/v?itag=22"), choice)
    }

    @Test
    fun `LBRY-only response like the live one yields the proxied progressive stream`() {
        // observed 2026-11-19: two player.odycdn.com entries plus one proxied 360p
        val lbryMp4 = muxed("player.odycdn.com").copy(format = "LBRY MP4")
        val lbryHls = muxed("player.odycdn.com").copy(format = "LBRY HLS HLS", url = "https://player.odycdn.com/x.m3u8")
        val proxied = muxed("piped-proxy.ducks.party")
        val choice = StreamPicker.pick(
            base(
                video = listOf(lbryMp4, lbryHls, proxied),
                proxyUrl = "https://piped-proxy.ducks.party",
            ),
        )
        assertEquals(PlaybackChoice.Progressive("https://piped-proxy.ducks.party/videoplayback?itag=18"), choice)
    }

    @Test
    fun `no proxyUrl means no playback decision`() {
        assertNull(StreamPicker.pick(base(video = listOf(muxed("proxy.example.com")), proxyUrl = null)))
    }

    @Test
    fun `only uncovered streams means nothing to play`() {
        assertNull(StreamPicker.pick(base(video = listOf(muxed("player.odycdn.com")))))
    }

    @Test
    fun `generated DASH excludes uncovered adaptive formats`() {
        val choice = StreamPicker.pick(
            base(
                video = listOf(adaptiveVideo("player.odycdn.com"), muxed("proxy.example.com")),
                audio = listOf(adaptiveAudio("proxy.example.com")),
            ),
        )
        // Do not choose silent video or an audio-only MPD over a muxed fallback.
        assertEquals(PlaybackChoice.Progressive("https://proxy.example.com/videoplayback?itag=18"), choice)
    }

    @Test
    fun `live video uses HLS instead of a static VOD MPD`() {
        val response = base(dash = "https://proxy.example.com/dash.mpd", hls = "https://proxy.example.com/live.m3u8")
            .copy(livestream = true)
        assertEquals(PlaybackChoice.Hls("https://proxy.example.com/live.m3u8"), StreamPicker.pick(response))
        assertNull(StreamPicker.pick(response.copy(hls = null)))
    }

    @Test
    fun `missing mime type on the muxed live API fallback does not generate an invalid MPD`() {
        val response = base(video = listOf(muxed("proxy.example.com").copy(mimeType = null)))
        assertEquals(PlaybackChoice.Progressive("https://proxy.example.com/videoplayback?itag=18"), StreamPicker.pick(response))
    }

    @Test
    fun `shared selector applies rewrite false on the adaptive audio branch`() {
        val response = base(dash = "https://proxy.example.com/dash.mpd?rewrite=true",
            video = listOf(adaptiveVideo("proxy.example.com")),
            audio = listOf(adaptiveAudio("proxy.example.com")))
        assertEquals(PlaybackChoice.DashUrl("https://proxy.example.com/dash.mpd?rewrite=false"), StreamPicker.pick(response))
    }
}
