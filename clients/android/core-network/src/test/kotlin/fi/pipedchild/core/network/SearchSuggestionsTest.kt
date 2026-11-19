package fi.pipedchild.core.network

import fi.pipedchild.core.model.PipedError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchSuggestionsTest {
    @Test fun `disabled construction enable and blank edits issue no request`() = runTest {
        var requests = 0
        val suggestions = SearchSuggestions(this, { requests++; emptyList() })
        suggestions.submit("cats")
        advanceUntilIdle()
        suggestions.setEnabled(true)
        suggestions.submit("   ")
        advanceUntilIdle()
        assertEquals(0, requests)
        assertEquals(SuggestionsSnapshot(), suggestions.state.value)
        suggestions.close()
    }

    @Test fun `typing debounces cancels old delay and returns distinct nonblank results`() = runTest {
        val queries = mutableListOf<String>()
        val suggestions = SearchSuggestions(this, { queries += it; listOf("cats", "", "cats", "cats music") })
        suggestions.setEnabled(true)
        suggestions.submit(" ca ")
        advanceTimeBy(200)
        suggestions.submit(" cats ")
        advanceTimeBy(299)
        assertTrue(queries.isEmpty())
        runCurrent()
        assertTrue(queries.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("cats"), queries)
        assertEquals(listOf("cats", "cats music"), suggestions.state.value.items)
        assertFalse(suggestions.state.value.loading)
        suggestions.close()
    }

    @Test fun `new edit cancels in-flight request and stale completion cannot win`() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val suggestions = SearchSuggestions(this, { query ->
            if (query == "old") withContext(NonCancellable) { started.complete(Unit); finish.await() }
            listOf(query)
        }, 0)
        suggestions.setEnabled(true)
        suggestions.submit("old")
        runCurrent()
        assertTrue(started.isCompleted)
        suggestions.submit("new")
        runCurrent()
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals("new", suggestions.state.value.query)
        assertEquals(listOf("new"), suggestions.state.value.items)
        suggestions.close()
    }

    @Test fun `disable cancels requests clears results and reenable never sends old text`() = runTest {
        var cancelled = false
        var requests = 0
        val suggestions = SearchSuggestions(this, {
            requests++
            try { awaitCancellation() } finally { cancelled = true }
        }, 0)
        suggestions.setEnabled(true)
        suggestions.submit("cats")
        runCurrent()
        suggestions.setEnabled(false)
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(SuggestionsSnapshot(), suggestions.state.value)
        suggestions.setEnabled(true)
        advanceUntilIdle()
        assertEquals(1, requests)
        suggestions.close()
    }

    @Test fun `typed error allows explicit retry and close permanently stops requests`() = runTest {
        var requests = 0
        val suggestions = SearchSuggestions(this, {
            requests++
            if (requests == 1) throw PipedError.RateLimited
            listOf("cats")
        }, 0)
        suggestions.setEnabled(true)
        suggestions.submit("cats")
        advanceUntilIdle()
        assertEquals(PipedError.RateLimited, suggestions.state.value.error)
        assertFalse(suggestions.state.value.loading)
        suggestions.submit("cats")
        advanceUntilIdle()
        assertNull(suggestions.state.value.error)
        assertEquals(listOf("cats"), suggestions.state.value.items)
        suggestions.close()
        suggestions.setEnabled(true)
        suggestions.submit("dogs")
        advanceUntilIdle()
        assertEquals(2, requests)
        assertEquals(SuggestionsSnapshot(), suggestions.state.value)
    }

    @Test fun `parent scope cancellation settles busy state without a fake error`() = runTest {
        val parent = SupervisorJob(coroutineContext[Job])
        val suggestions = SearchSuggestions(CoroutineScope(coroutineContext + parent), { awaitCancellation() }, 0)
        suggestions.setEnabled(true)
        suggestions.submit("cats")
        runCurrent()
        parent.cancelAndJoin()
        assertFalse(suggestions.state.value.loading)
        assertNull(suggestions.state.value.error)
        suggestions.submit("after scope cancellation")
        assertEquals(SuggestionsSnapshot(), suggestions.state.value)
        suggestions.close()
    }

    @Test fun `adapter uses existing instance endpoint and safely encodes the query`() = runBlocking {
        var requests = 0
        val client = PipedApiClient("https://api.example", engine = MockEngine { request ->
            requests++
            assertEquals("https", request.url.protocol.name)
            assertEquals("api.example", request.url.host)
            assertEquals("/opensearch/suggestions", request.url.encodedPath)
            assertEquals("cats & dogs?", request.url.parameters["query"])
            respond("""["cats & dogs?",["cats","dogs"]]""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val suggestions = SearchSuggestions(this, client)
        suggestions.setEnabled(true)
        suggestions.submit("cats & dogs?")
        withTimeout(10000) { suggestions.state.first { !it.loading } }
        assertEquals(1, requests)
        assertEquals(listOf("cats", "dogs"), suggestions.state.value.items)
        suggestions.close()
        client.close()
    }
}
