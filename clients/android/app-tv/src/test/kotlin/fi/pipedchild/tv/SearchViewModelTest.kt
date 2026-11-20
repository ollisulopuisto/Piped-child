package fi.pipedchild.tv

import fi.pipedchild.core.network.PipedApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @Test fun `construction and edits stay quiet and explicit search sends only the submitted query`() = runTest {
        var count = 0
        val client = PipedApiClient("https://api.example", engine = MockEngine { request ->
            count++
            assertEquals("/search", request.url.encodedPath)
            assertEquals("cats & dogs", request.url.parameters["q"])
            assertEquals("videos", request.url.parameters["filter"])
            respond("""{"items":[{"type":"stream","url":"/watch?v=cat","title":"Cat"}]}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = SearchViewModel(client, backgroundScope)
        assertEquals(0, count)
        vm.edit(" cats & dogs ")
        assertEquals(0, count)
        vm.submit().join()
        assertEquals(1, count)
        assertEquals("Cat", vm.state.value.items.single().let { (it as fi.pipedchild.core.model.SearchItem.Stream).title })
        vm.shutdown()
    }

    @Test fun `leaving search cancels request without fake error and allows explicit retry`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val cancelled = CompletableDeferred<Unit>()
        val client = PipedApiClient("https://api.example", engine = MockEngine {
            calls++
            if (calls == 1) {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = SearchViewModel(client, this)
        vm.edit("cats")
        val pending = vm.submit()
        started.await()
        vm.stop()
        pending.join()
        withTimeout(10000) { cancelled.await() }
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
        vm.submit().join()
        assertEquals(2, calls)
        vm.shutdown()
    }

    @Test fun `blank submission makes no request and clears the submitted query`() = runTest {
        var requests = 0
        val client = PipedApiClient("https://api.example", engine = MockEngine {
            requests++
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = SearchViewModel(client, backgroundScope)
        vm.edit("cats"); vm.submit().join()
        vm.edit("  "); vm.submit().join()
        assertEquals(1, requests)
        assertEquals("", vm.state.value.query)
        assertTrue(vm.state.value.items.isEmpty())
        vm.shutdown()
    }

    @Test fun `new submission supersedes old request while pagination preserves submitted query`() = runTest {
        val started = CompletableDeferred<Unit>()
        val queries = mutableListOf<String>()
        val client = PipedApiClient("https://api.example", engine = MockEngine { request ->
            val q = request.url.parameters["q"]!!
            queries += q
            if (q == "old") { started.complete(Unit); awaitCancellation() }
            respond(if (request.url.encodedPath == "/search") """{"nextpage":"opaque"}""" else "{}",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = SearchViewModel(client, backgroundScope)
        vm.edit("old"); vm.submit(); started.await()
        vm.edit("new"); vm.submit().join()
        vm.edit("not submitted")
        vm.loadNext().join()
        assertEquals(listOf("old", "new", "new"), queries)
        assertEquals("new", vm.state.value.query)
        vm.shutdown()
    }
}
