package fi.pipedchild.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import fi.pipedchild.core.network.InMemoryTokenStore
import fi.pipedchild.core.network.PipedApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
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
        val vm = HomeViewModel(client(trendingJson), scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
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
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.loadOnce()
        val s = vm.state.value
        assertIs<HomeUiState.Error>(s)
        assertTrue(s.message.isNotBlank(), "error state must carry a user-readable message")
    }

    @Test
    fun `garbage body maps to an error state, never a crash`() = runTest {
        val vm = HomeViewModel(client("not json"), scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.loadOnce()
        assertIs<HomeUiState.Error>(vm.state.value)
    }
}
