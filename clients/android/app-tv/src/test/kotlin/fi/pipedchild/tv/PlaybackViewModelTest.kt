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
}
