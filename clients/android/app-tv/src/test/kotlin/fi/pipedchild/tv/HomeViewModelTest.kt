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
import kotlinx.coroutines.test.runTest

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
}
