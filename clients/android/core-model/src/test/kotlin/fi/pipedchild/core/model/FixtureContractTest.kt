package fi.pipedchild.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixture-driven contract tests. Fixtures are shared with the iOS client;
 *  the same scenarios must pass there (handoff spec §12). */
class FixtureContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String {
        val path = java.nio.file.Path.of("..", "..", "contracts", "fixtures", name)
        return java.nio.file.Files.readString(path)
    }

    @Test
    fun `trending fixture parses into stream items with video ids`() {
        val items = json.decodeFromString<List<StreamItem>>(fixture("trending_OK.json"))
        assertTrue(items.isNotEmpty(), "trending must not be empty")
        items.forEach {
            assertTrue(it.videoId.isNotBlank())
            assertTrue(it.title.isNotBlank())
        }
    }

    @Test
    fun `search fixture parses items and nextpage`() {
        val r = json.decodeFromString<SearchResponse>(fixture("search_videos_OK.json"))
        assertTrue(r.items.isNotEmpty())
    }

    @Test
    fun `degraded streams fixture yields progressive fallback on both platforms`() {
        val r = json.decodeFromString<StreamsResponse>(fixture("streams_degraded_360p_OK.json"))
        assertNull(r.dash)
        assertNull(r.hls)
        val android = PlaybackSelector.select(r, PlaybackSelector.Platform.ANDROID)
        val ios = PlaybackSelector.select(r, PlaybackSelector.Platform.IOS)
        assertTrue(android is PlaybackSelector.Source.Progressive, "no DASH/HLS -> progressive on Android")
        assertTrue(ios is PlaybackSelector.Source.Progressive, "no DASH/HLS -> progressive on iOS")
        assertEquals("360p", (ios as PlaybackSelector.Source.Progressive).stream.quality)
        assertTrue(r.subtitles.isNotEmpty(), "subtitles must survive degraded responses")
        assertTrue(r.previewFrames.isNotEmpty())
    }

    @Test
    fun `lbry fixture prefers manifest then lbry progressive`() {
        val r = json.decodeFromString<StreamsResponse>(fixture("streams_degraded_lbry_OK.json"))
        assertNull(r.hls)
        // Android (web-faithful): LAST codec==null stream = the 360p entry.
        val android = PlaybackSelector.select(r, PlaybackSelector.Platform.ANDROID)
        assertTrue(android is PlaybackSelector.Source.Progressive)
        assertEquals("360p", (android as PlaybackSelector.Source.Progressive).stream.quality)
        // iOS (documented deviation): the LBRY HLS entry in videoStreams is an
        // m3u8 manifest, playable on AVPlayer, so it beats progressive.
        val ios = PlaybackSelector.select(r, PlaybackSelector.Platform.IOS)
        assertTrue(ios is PlaybackSelector.Source.Hls)
        val hlsUrl = (ios as PlaybackSelector.Source.Hls).manifestUrl
        assertTrue(hlsUrl.substringBefore('?').endsWith(".m3u8"), "expected m3u8, got $hlsUrl")
        // And the recorded master playlist at that URL is verified parseable
        // by Media3 in core-player's HlsManifestParseTest.
    }

    @Test
    fun `extraction error fixture parses as ApiErrorBody and maps to ExtractionFailure`() {
        val body = json.decodeFromString<ApiErrorBody>(fixture("streams_error_login_required.json"))
        val err = PipedErrorMapper.fromHttp(500, body)
        assertTrue(err is PipedError.ExtractionFailure, "bot-block 500 must be actionable, got $err")
    }

    @Test
    fun `empty sponsors fixture parses to no segments`() {
        val r = json.decodeFromString<SponsorsResponse>(fixture("sponsors_empty_OK.json"))
        assertTrue(r.segments.isEmpty())
    }

    @Test
    fun `dearrow fixture parses replacement titles`() {
        val map = json.decodeFromString<Map<String, DeArrowEntry>>(fixture("dearrow_OK.json"))
        val entry = map.values.first()
        assertTrue(entry.titles.isNotEmpty())
    }

    @Test
    fun `comments fixture parses`() {
        val r = json.decodeFromString<CommentsResponse>(fixture("comments_OK.json"))
        assertTrue(r.comments.isNotEmpty() || r.disabled)
    }

    @Test
    fun `search decodes channel playlist and mixed items`() {
        // Devin finding: channel/playlist items carry `name`, not `title`;
        // one such item must not break decoding the whole response.
        val channelOnly = json.decodeFromString<SearchResponse>(
            """{"items":[{"type":"channel","url":"/channel/UCexample","name":"Example channel","videos":42}]}""",
        )
        assertEquals(1, channelOnly.items.size)
        assertTrue(channelOnly.items[0] is SearchItem.Channel)
        assertEquals("Example channel", (channelOnly.items[0] as SearchItem.Channel).name)
        // Piped calls the channel video count `videos` (frontend ChannelItem.vue)
        assertEquals(42, (channelOnly.items[0] as SearchItem.Channel).videos)

        val playlistOnly = json.decodeFromString<SearchResponse>(
            """{"items":[{"type":"playlist","url":"/playlist?list=PLexample","name":"Example playlist","videos":12}]}""",
        )
        assertTrue(playlistOnly.items[0] is SearchItem.Playlist)

        val mixed = json.decodeFromString<SearchResponse>(
            """{"items":[
                {"type":"stream","url":"/watch?v=abc","title":"A video"},
                {"type":"channel","url":"/channel/UCx","name":"A channel"},
                {"type":"playlist","url":"/playlist?list=PLx","name":"A playlist"},
                {"type":"hashtag","url":"/hashtag/x"}
            ]}""",
        )
        assertEquals(4, mixed.items.size)
        assertTrue(mixed.items[0] is SearchItem.Stream)
        assertTrue(mixed.items[1] is SearchItem.Channel)
        assertTrue(mixed.items[2] is SearchItem.Playlist)
        assertTrue(mixed.items[3] is SearchItem.Unknown) // forward-compat
    }

    @Test
    fun `search response survives an encode decode round trip`() {
        // JsonContentPolymorphicSerializer adds no discriminator on encode, so
        // each item declares its own `type`; without it a round-trip decodes
        // everything as Unknown and loses titles.
        val original = SearchResponse(
            items = listOf(
                SearchItem.Stream(url = "/watch?v=abc", title = "A video"),
                SearchItem.Channel(url = "/channel/UCx", name = "A channel", videos = 7),
                SearchItem.Playlist(url = "/playlist?list=PLx", name = "A playlist", videos = 3),
            ),
        )
        val encoded = json.encodeToString(SearchResponse.serializer(), original)
        val decoded = json.decodeFromString<SearchResponse>(encoded)
        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"type\":\"channel\""))
    }

    @Test
    fun `recorded fixtures contain no session-bound URL material`() {
        // Reviewer finding: live-recorded URLs must have ip=/sig=/lsig=/
        // expire=/ipbits= values sanitised so no captured session material is
        // committed. Boundaries prevent false positives like fvip=.
        val sensitive = Regex("""[?&](ip|sig|lsig|expire|ipbits)=(?!SANITIZED[&#"])[^&"\s]+""")
        val dir = java.io.File("..", "..").resolve("contracts/fixtures")
        dir.listFiles()!!.filter { it.isFile }.forEach { file ->
            val hit = sensitive.find(file.readText())
            assertTrue(
                hit == null,
                "${file.name} contains unsanitised session material: ${hit?.value?.take(24)}…",
            )
        }
    }

    @Test
    fun `streams with no playable source throws PlaybackFormatUnsupported`() {
        val r = StreamsResponse(title = "nothing playable")
        assertFailsWith<PipedError.PlaybackFormatUnsupported> {
            PlaybackSelector.select(r, PlaybackSelector.Platform.ANDROID)
        }
    }

    @Test
    fun `streams decodes the API proxyUrl field rather than silently discarding it`() {
        val response = json.decodeFromString<StreamsResponse>(
            """{"title":"Example","proxyUrl":"https://proxy.example.com"}""",
        )
        assertEquals("https://proxy.example.com", response.proxyUrl)
    }
}
