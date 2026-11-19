package fi.pipedchild.tv

import fi.pipedchild.core.network.PipedApiClient
import fi.pipedchild.core.network.InMemoryTokenStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelTest {
    private val response = """{
      "title":"Example", "duration":120,
      "proxyUrl":"https://proxy.example.com",
      "videoStreams":[{"url":"https://proxy.example.com/videoplayback?itag=18",
        "format":"MPEG_4","videoOnly":false}]
    }"""

    private fun vm(body: String = response): PlaybackViewModel = PlaybackViewModel(
        PipedApiClient("https://api.example.com", InMemoryTokenStore(), MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }),
    )

    @Test
    fun `watch URL parsing accepts only the local watch route with one video id`() {
        assertEquals("abc-123_X", videoIdFromWatchUrl("/watch?v=abc-123_X"))
        listOf("https://evil.example/watch?v=abc", "/watch?v=one&v=two", "/channel?v=abc",
            "/watch?v=..", "/watch?v=a%2Fb", "::::", "/watch").forEach {
            assertNull(videoIdFromWatchUrl(it), it)
        }
    }

    @Test
    fun `metadata never starts media before explicit proxy consent`() = runTest {
        val vm = vm()
        vm.loadOnce("example")
        val waiting = assertIs<PlaybackUiState.AwaitingConsent>(vm.state.value)
        assertEquals("https://proxy.example.com", waiting.media.proxyBase)
        vm.approveProxy()
        assertIs<PlaybackUiState.Ready>(vm.state.value)
        vm.shutdown()
    }

    @Test
    fun `invalid or missing proxy never becomes playable`() = runTest {
        for (body in listOf("{}", response.replace("https://proxy.example.com", "http://proxy.example.com"))) {
            val vm = vm(body)
            vm.loadOnce("example")
            assertIs<PlaybackUiState.Error>(vm.state.value)
            vm.approveProxy()
            assertIs<PlaybackUiState.Error>(vm.state.value)
            vm.shutdown()
        }
    }

    @Test
    fun `outside CDN formats do not become ready`() = runTest {
        val vm = vm(response.replace("https://proxy.example.com/videoplayback", "https://cdn.example.com/videoplayback"))
        vm.loadOnce("example")
        assertIs<PlaybackUiState.Error>(vm.state.value)
        vm.shutdown()
    }

    @Test
    fun `cancellation propagates instead of becoming a UI error`() = runTest {
        val vm = PlaybackViewModel(PipedApiClient("https://api.example.com", InMemoryTokenStore(),
            MockEngine { awaitCancellation() }))
        val job = launch { vm.loadOnce("example") }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertIs<PlaybackUiState.Loading>(vm.state.value)
        vm.shutdown()
    }

    @Test
    fun `back cancels metadata and discards media approval`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = vm()
            vm.load("example").join()
            assertIs<PlaybackUiState.AwaitingConsent>(vm.state.value)
            vm.approveProxy()
            vm.closeSelection()
            assertIs<PlaybackUiState.Loading>(vm.state.value)
            vm.approveProxy()
            assertIs<PlaybackUiState.Loading>(vm.state.value)
            vm.shutdown()
        } finally { Dispatchers.resetMain() }
    }

    private fun sponsorVm(status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"segments":[{"segment":[10,20],"category":"sponsor","actionType":"skip"}]}""",
        beforeResponse: suspend () -> Unit = {}, streamBody: String = response, requests: MutableList<String> = mutableListOf(),
    ) = PlaybackViewModel(PipedApiClient("https://api.example.com", InMemoryTokenStore(), MockEngine { request ->
        requests += request.url.toString()
        if (request.url.encodedPath.startsWith("/sponsors/")) {
            assertEquals("api.example.com", request.url.host)
            assertEquals("[\"sponsor\",\"interaction\",\"selfpromo\",\"music_offtopic\"]", request.url.parameters["category"])
            beforeResponse()
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        } else respond(streamBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }))

    @Test fun `sponsor request occurs only after Play and goes through selected instance`() = runBlocking {
        val requests = mutableListOf<String>()
        val vm = sponsorVm(requests = requests)
        vm.loadOnce("example")
        val media = assertIs<PlaybackUiState.AwaitingConsent>(vm.state.value).media
        vm.loadSponsorsOnce(media)
        assertEquals(1, requests.size)
        vm.approveProxy()
        vm.loadSponsorsOnce(media)
        assertEquals(2, requests.size)
        assertEquals(1, assertIs<SponsorUiState.Ready>(vm.sponsors.value).segments.size)
        assertIs<PlaybackUiState.Ready>(vm.state.value)
        vm.shutdown()
    }

    @Test fun `opting out issues no sponsor request and cannot be changed after playback starts`() = runBlocking {
        val requests = mutableListOf<String>()
        val vm = sponsorVm(requests = requests)
        vm.loadOnce("example")
        vm.setSponsorBlockEnabled(false)
        vm.approveProxy()
        vm.setSponsorBlockEnabled(true)
        val media = assertIs<PlaybackUiState.Ready>(vm.state.value).media
        assertFalse(media.sponsorBlockEnabled)
        vm.loadSponsorsOnce(media)
        assertEquals(1, requests.size)
        assertEquals(SponsorUiState.Disabled, vm.sponsors.value)
        vm.shutdown()
    }

    @Test fun `sponsor API and parsing errors never block playback`() = runBlocking {
        for ((status, body) in listOf(HttpStatusCode.NotFound to "{}", HttpStatusCode.OK to "not-json")) {
            val vm = sponsorVm(status, body)
            vm.loadOnce("example")
            vm.approveProxy()
            vm.loadSponsorsOnce(assertIs<PlaybackUiState.Ready>(vm.state.value).media)
            assertEquals(SponsorUiState.Unavailable, vm.sponsors.value)
            assertIs<PlaybackUiState.Ready>(vm.state.value)
            vm.shutdown()
        }
    }

    @Test fun `slow sponsor API times out without delaying Ready`() = runBlocking {
        val vm = sponsorVm(beforeResponse = { awaitCancellation() })
        vm.loadOnce("example")
        vm.approveProxy()
        val media = assertIs<PlaybackUiState.Ready>(vm.state.value).media
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.loadSponsorsOnce(media) }
        assertEquals(SponsorUiState.Loading, vm.sponsors.value)
        assertIs<PlaybackUiState.Ready>(vm.state.value)
        job.join()
        assertEquals(SponsorUiState.Unavailable, vm.sponsors.value)
        vm.shutdown()
    }

    @Test fun `Back while sponsors load discards late completion`() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val vm = sponsorVm(beforeResponse = { ready.complete(Unit); finish.await() })
        vm.loadOnce("example")
        vm.approveProxy()
        val media = assertIs<PlaybackUiState.Ready>(vm.state.value).media
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.loadSponsorsOnce(media) }
        withTimeout(10000) { ready.await() }
        vm.closeSelection()
        finish.complete(Unit)
        job.join()
        assertEquals(SponsorUiState.Disabled, vm.sponsors.value)
        assertIs<PlaybackUiState.Loading>(vm.state.value)
        vm.shutdown()
    }

    @Test fun `sponsor cancellation propagates`() = runBlocking {
        val vm = sponsorVm(beforeResponse = { awaitCancellation() })
        vm.loadOnce("example")
        vm.approveProxy()
        val media = assertIs<PlaybackUiState.Ready>(vm.state.value).media
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.loadSponsorsOnce(media) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        vm.shutdown()
    }

    @Test fun `nonempty sponsor response decodes`() = runBlocking {
        val client = PipedApiClient("https://api.example.com", InMemoryTokenStore(), MockEngine {
            respond("""{"segments":[{"segment":[10,20],"category":"sponsor","actionType":"skip"}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertEquals(1, client.sponsors("example", listOf("sponsor")).segments.size)
        client.close()
    }

    @Test fun `live playback never requests SponsorBlock`() = runBlocking {
        val requests = mutableListOf<String>()
        val vm = sponsorVm(streamBody = response.replace("\"duration\":120", "\"duration\":120,\"livestream\":true,\"hls\":\"https://proxy.example.com/live.m3u8\""), requests = requests)
        vm.loadOnce("example")
        vm.approveProxy()
        val media = assertIs<PlaybackUiState.Ready>(vm.state.value).media
        assertTrue(media.livestream)
        vm.loadSponsorsOnce(media)
        assertEquals(1, requests.size)
        assertEquals(SponsorUiState.Disabled, vm.sponsors.value)
        vm.shutdown()
    }
}
