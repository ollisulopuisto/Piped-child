package fi.pipedchild.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue
import fi.pipedchild.core.network.InMemoryTokenStore
import fi.pipedchild.core.network.PipedApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class HomeViewModelTest {

    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK): PipedApiClient {
        val engine = MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return PipedApiClient("https://pipedapi.example.com", InMemoryTokenStore(), engine)
    }

    private val trendingJson = """
        [
          {"url": "/watch?v=one", "type": "stream", "title": "First"},
          {"url": "/watch?v=two", "type": "stream", "title": "Second"}
        ]
    """.trimIndent()

    @Test
    fun `trending loads into content state`() = runTest {
        val vm = HomeViewModel(client(trendingJson))
        vm.loadOnce()
        val s = vm.state.value
        assertIs<HomeUiState.Content>(s)
        assertEquals(listOf("First", "Second"), s.items.map { it.title })
    }

    @Test
    fun `server error maps to an error state with a user message`() = runTest {
        val engine = MockEngine {
            respond("""{"error":"extraction failed","message":"bot check"}""",
                HttpStatusCode.InternalServerError,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val vm = HomeViewModel(
            PipedApiClient("https://pipedapi.example.com", InMemoryTokenStore(), engine),
        )
        vm.loadOnce()
        val s = vm.state.value
        assertIs<HomeUiState.Error>(s)
        assertTrue(s.message.isNotBlank(), "error state must carry a user-readable message")
    }

    @Test
    fun `garbage body maps to an error state, never a crash`() = runTest {
        val vm = HomeViewModel(client("not json"))
        vm.loadOnce()
        assertIs<HomeUiState.Error>(vm.state.value)
    }

    @Test
    fun `shutdown closes the owned client so no request outlives the screen`() = runTest {
        val c = client(trendingJson)
        val vm = HomeViewModel(c)
        vm.loadOnce()
        vm.shutdown()
        assertFails { c.trending("US") }
    }

    @Test
    fun `shutdown leaves an unowned client usable`() = runTest {
        val c = client(trendingJson)
        val vm = HomeViewModel(c, ownsClient = false)
        vm.loadOnce()
        vm.shutdown()
        assertEquals(2, c.trending("US").size)
    }

    @Test
    fun `consent text names the instance and rules out trackers before any request`() {
        val text = consentText("https://pipedapi.example.com")
        assertTrue("https://pipedapi.example.com" in text)
        assertTrue("No analytics" in text)
    }

    @Test
    fun `no request leaves before an explicit load`() = runTest {
        // the first-run consent gate (architecture §6) holds only if the
        // ViewModel never fires a request on its own
        val engine = MockEngine {
            respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val c = PipedApiClient("https://pipedapi.example.com", InMemoryTokenStore(), engine)
        HomeViewModel(c)
        assertEquals(0, engine.requestHistory.size, "construction must not hit the network")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a canceled load ends quietly instead of surfacing a spurious error`() = runTest {
        // a destroyed screen cancels its in-flight request; that must not
        // reach the UI as an error state
        val engine = MockEngine { awaitCancellation() }
        val vm = HomeViewModel(
            PipedApiClient("https://pipedapi.example.com", InMemoryTokenStore(), engine),
        )
        val job = launch { vm.loadOnce() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertIs<HomeUiState.Loading>(vm.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a newer refresh wins over a slower in-flight load`() = runTest {
        // overlapping refresh: the stale first response must never
        // overwrite the newer one
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var calls = 0
            val firstStarted = CompletableDeferred<Unit>()
            val engine = MockEngine {
                calls += 1
                if (calls == 1) {
                    firstStarted.complete(Unit)
                    awaitCancellation()
                } else {
                    respond(trendingJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            val vm = HomeViewModel(
                PipedApiClient("https://pipedapi.example.com", InMemoryTokenStore(), engine),
            )
            val first = vm.refresh()
            firstStarted.await()
            val second = vm.refresh()
            first.join()
            second.join()
            assertEquals(2, calls)
            val s = vm.state.value
            assertIs<HomeUiState.Content>(s)
            assertEquals(listOf("First", "Second"), s.items.map { it.title })
            vm.shutdown()
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
