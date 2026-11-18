package fi.pipedchild.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
import fi.pipedchild.core.model.PipedStream
import fi.pipedchild.core.model.PlaybackSelector
import fi.pipedchild.core.model.StreamsResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File

/**
 * M1-A feasibility evidence: a client-side generated MPD (port of the web
 * DashUtils.js) parses cleanly through Media3's DashManifestParser, which is
 * the exact parser ExoPlayer uses on Android TV. If Media3 accepts these
 * manifests, the Chromecast playback path only depends on network + codec
 * support, which M2 verifies on hardware.
 */
@RunWith(RobolectricTestRunner::class)
class DashManifestGeneratorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        File(File("..", ".."), "contracts/fixtures/$name").readText()

    private fun adaptiveVideo(itag: Int, codec: String, mime: String, height: Int) = PipedStream(
        url = "https://rr3---sn-x.googlevideo.com/videoplayback?itag=$itag&expire=999&ip=1.2.3.4",
        format = if (mime.contains("webm")) "WEBM" else "MP4",
        quality = "${height}p",
        codec = codec,
        mimeType = mime,
        itag = itag,
        bitrate = height * 10_000L,
        width = height * 16 / 9,
        height = height,
        fps = 30,
        videoOnly = true,
        initStart = 0,
        initEnd = 740,
        indexStart = 741,
        indexEnd = 1408,
    )

    private fun adaptiveAudio(itag: Int, codec: String, mime: String, trackId: String? = null) = PipedStream(
        url = "https://rr3---sn-x.googlevideo.com/videoplayback?itag=$itag&expire=999&ip=1.2.3.4",
        format = "M4A",
        quality = "128 kbps",
        codec = codec,
        mimeType = mime,
        itag = itag,
        bitrate = 128_000,
        videoOnly = false,
        initStart = 0,
        initEnd = 631,
        indexStart = 632,
        indexEnd = 955,
        audioTrackId = trackId,
    )

    private fun parse(mpd: String): DashManifest = DashManifestParser().parse(
        Uri.parse("https://piped-child.test/generated.mpd"),
        ByteArrayInputStream(mpd.toByteArray(Charsets.UTF_8)),
    )

    @Test
    fun `generated MPD from adaptive formats parses in Media3 with all adaptation sets`() {
        val formats = listOf(
            adaptiveAudio(140, "mp4a.40.2", "audio/mp4"),
            adaptiveAudio(251, "opus", "audio/webm"),
            adaptiveVideo(137, "avc1.640028", "video/mp4", 1080),
            adaptiveVideo(248, "vp9", "video/webm", 1080),
        )
        assertTrue(DashManifestGenerator.hasAdaptiveFormats(formats))

        val manifest = parse(DashManifestGenerator.generate(formats, durationSeconds = 212))

        assertEquals(1, manifest.periodCount.toLong())
        val period = manifest.getPeriod(0)
        assertEquals(4, period.adaptationSets.size) // grouped by mimeType, null audioTrackId shared

        val videos = period.adaptationSets.filter { it.type == C.TRACK_TYPE_VIDEO }
        val audios = period.adaptationSets.filter { it.type == C.TRACK_TYPE_AUDIO }
        assertEquals(2, videos.size)
        assertEquals(2, audios.size)

        val itags = period.adaptationSets.flatMap { it.representations }.map { it.format.id }.toSet()
        assertEquals(setOf("140", "251", "137", "248"), itags)

        val baseUrls = period.adaptationSets.flatMap { it.representations }
            .flatMap(Representation::baseUrls).map { it.url }
        assertTrue(baseUrls.all { it.contains("googlevideo.com/videoplayback") })
        // XML escaping must round-trip back to the raw Piped URL
        assertTrue(baseUrls.all { it.contains("&expire=999") })
    }

    @Test
    fun `generated MPD matches web client attributes exactly`() {
        // Review-strengthened: exact attribute fidelity, not just counts.
        val formats = listOf(
            adaptiveAudio(140, "mp4a.40.2", "audio/mp4"),
            adaptiveVideo(137, "avc1.640028", "video/mp4", 1080),
        )
        val mpd = DashManifestGenerator.generate(formats, 212)
        // Web emits maxPlayoutRate="1" and NO sar on video representations
        assertTrue(mpd.contains("maxPlayoutRate=\"1\""))
        assertFalse(mpd.contains("sar="))
        // Exact ranges preserved for Media3's SegmentBase loading
        assertTrue(mpd.contains("indexRange=\"741-1408\""))
        assertTrue(mpd.contains("range=\"0-740\""))
        assertTrue(mpd.contains("indexRange=\"632-955\""))
        assertTrue(mpd.contains("range=\"0-631\""))
        // Envelope per DashUtils: full profile, static, 1.5s buffer, PT duration
        assertTrue(mpd.contains("profiles=\"urn:mpeg:dash:profile:full:2011\""))
        assertTrue(mpd.contains("type=\"static\""))
        assertTrue(mpd.contains("minBufferTime=\"PT1.5S\""))
        assertTrue(mpd.contains("mediaPresentationDuration=\"PT212S\""))
        // Exact BaseURL, XML-escaped
        assertTrue(
            mpd.contains(
                "<BaseURL>https://rr3---sn-x.googlevideo.com/videoplayback" +
                    "?itag=137&amp;expire=999&amp;ip=1.2.3.4</BaseURL>",
            ),
        )
        assertTrue(mpd.contains("scanType=\"progressive\""))
        assertTrue(mpd.contains("startWithSAP=\"1\""))
        assertTrue(mpd.contains("subsegmentAlignment=\"true\""))
    }

    @Test
    fun `per-language audio tracks stay in separate adaptation sets`() {
        val formats = listOf(
            adaptiveAudio(140, "mp4a.40.2", "audio/mp4"),
            adaptiveAudio(140, "mp4a.40.2", "audio/mp4", trackId = "en-US.1"),
            adaptiveVideo(137, "avc1.640028", "video/mp4", 1080),
        )
        val mpd = DashManifestGenerator.generate(formats, 100)
        // JS derives lang from the first two chars of audioTrackId
        assertTrue(mpd.contains("lang=\"en\""))
        val manifest = parse(mpd)
        val audios = manifest.getPeriod(0).adaptationSets.filter { it.type == C.TRACK_TYPE_AUDIO }
        assertEquals(2, audios.size) // untracked + en-US.1
    }

    @Test
    fun `video representation carries dimensions and ranges Media3 needs`() {
        val formats = listOf(adaptiveVideo(137, "avc1.640028", "video/mp4", 1080))
        val manifest = parse(DashManifestGenerator.generate(formats, 212))
        val rep = manifest.getPeriod(0).adaptationSets.single().representations.single()
        assertEquals(1080, rep.format.height)
        assertEquals(1920, rep.format.width)
        assertEquals(30f, rep.format.frameRate)
        assertEquals("avc1.640028", rep.format.codecs)
        assertEquals(1080 * 10_000L, rep.format.bitrate.toLong())
    }

    @Test
    fun `degraded progressive-only fixture yields empty MPD so selector must not choose DASH`() {
        val r = json.decodeFromString<StreamsResponse>(fixture("streams_degraded_360p_OK.json"))
        assertFalse(DashManifestGenerator.hasAdaptiveFormats(r.audioStreams + r.videoStreams))
        // The selector must therefore never return GeneratedDash for this payload
        val source = PlaybackSelector.select(r, PlaybackSelector.Platform.ANDROID)
        assertTrue(source is PlaybackSelector.Source.Progressive)
    }

    @Test
    fun `selector prefers client-generated DASH over progressive when adaptive audio exists`() {
        val r = StreamsResponse(
            duration = 212,
            audioStreams = listOf(adaptiveAudio(140, "mp4a.40.2", "audio/mp4")),
            videoStreams = listOf(
                adaptiveVideo(137, "avc1.640028", "video/mp4", 1080),
                PipedStream(url = "https://x/muxed.mp4", mimeType = "video/mp4", quality = "360p", height = 360),
            ),
        )
        val source = PlaybackSelector.select(r, PlaybackSelector.Platform.ANDROID)
        assertTrue(source is PlaybackSelector.Source.GeneratedDash)
        val gen = source as PlaybackSelector.Source.GeneratedDash
        val manifest = parse(DashManifestGenerator.generate(gen.formats, gen.durationSeconds))
        assertEquals(1, manifest.periodCount.toLong())
    }
}
