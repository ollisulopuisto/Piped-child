package fi.pipedchild.core.network

import fi.pipedchild.core.model.PipedError
import fi.pipedchild.core.model.SearchItem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SearchRepositoryTest {
    private fun page(id: String, next: String? = null) =
        """{"items":[{"type":"stream","url":"/watch?v=$id","title":"$id"}],"nextpage":${next?.let { "\"$it\"" } ?: "null"}}"""

    @Test fun `construction and blank search do not issue requests`() = runTest {
        var count = 0
        val client = PipedApiClient("https://api.example", engine = MockEngine { count++; error("unexpected") })
        val repo = SearchRepository(client)
        assertEquals(SearchSnapshot(), repo.state.value)
        repo.search("  ")
        repo.loadNext()
        assertEquals(0, count)
        assertEquals(SearchSnapshot(), repo.state.value)
        client.close()
    }

    @Test fun `next page preserves normalized query filter and opaque token`() = runTest {
        val paths = mutableListOf<String>()
        val client = PipedApiClient("https://api.example", engine = MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("cats & dogs", request.url.parameters["q"])
            assertEquals("channels", request.url.parameters["filter"])
            val body = if (paths.size == 1) page("one", "opaque+token") else {
                assertEquals("opaque+token", request.url.parameters["nextpage"])
                page("two")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repo = SearchRepository(client)
        repo.search(" cats & dogs ", SearchFilter.CHANNELS)
        repo.loadNext()
        repo.loadNext()
        assertEquals(listOf("/search", "/nextpage/search"), paths)
        assertEquals(listOf("/watch?v=one", "/watch?v=two"), repo.state.value.items.map { it.url })
        assertFalse(repo.state.value.hasMore)
        client.close()
    }

    @Test fun `repeated continuation stops loops and duplicates are not appended`() = runTest {
        var count = 0
        val client = PipedApiClient("https://api.example", engine = MockEngine {
            count++
            respond(page("one", "same"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repo = SearchRepository(client)
        repo.search("cats")
        repo.loadNext()
        repo.loadNext()
        assertEquals(2, count)
        assertEquals(1, repo.state.value.items.size)
        assertFalse(repo.state.value.hasMore)
        client.close()
    }

    @Test fun `page error retains results and continuation for explicit retry`() = runTest {
        var count = 0
        val client = PipedApiClient("https://api.example", engine = MockEngine {
            count++
            respond(if (count == 1) page("one", "next") else if (count == 2) "{}" else page("two"),
                if (count == 2) HttpStatusCode.TooManyRequests else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repo = SearchRepository(client)
        repo.search("cats")
        repo.loadNext()
        assertEquals(PipedError.RateLimited, repo.state.value.error)
        assertEquals(1, repo.state.value.items.size)
        assertTrue(repo.state.value.hasMore)
        repo.loadNext()
        assertNull(repo.state.value.error)
        assertEquals(2, repo.state.value.items.size)
        client.close()
    }

    @Test fun `stale query completion cannot replace newer results`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val client = PipedApiClient("https://api.example", engine = MockEngine { request ->
            val query = request.url.parameters["q"]!!
            if (query == "old") { started.complete(Unit); finish.await() }
            respond(page(query), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repo = SearchRepository(client)
        val old = launch { repo.search("old") }
        withTimeout(10000) { started.await() }
        repo.search("new")
        finish.complete(Unit)
        old.join()
        assertEquals("new", repo.state.value.query)
        assertEquals("/watch?v=new", repo.state.value.items.single().url)
        client.close()
    }

    @Test fun `duplicate page requests are coalesced and cancellation permits retry`() = runBlocking {
        var pages = 0
        val started = CompletableDeferred<Unit>()
        val client = PipedApiClient("https://api.example", engine = MockEngine { request ->
            if (request.url.encodedPath.startsWith("/nextpage")) {
                pages++
                if (pages == 1) { started.complete(Unit); awaitCancellation() }
            }
            respond(page("one", if (pages == 0) "next" else null), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repo = SearchRepository(client)
        repo.search("cats")
        val pending = launch { repo.loadNext() }
        withTimeout(10000) { started.await() }
        repo.loadNext()
        assertEquals(1, pages)
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertFalse(repo.state.value.loading)
        assertNull(repo.state.value.error)
        repo.loadNext()
        assertEquals(2, pages)
        client.close()
    }

    @Test fun `new search isolates stale page error and resets filter cursor and results`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val client = PipedApiClient("https://api.example", engine = MockEngine { request ->
            val query = request.url.parameters["q"]!!
            if (request.url.encodedPath.startsWith("/nextpage")) {
                started.complete(Unit); finish.await()
                respond("{}", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
            } else respond(page(query, if (query == "old") "next" else null), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repo = SearchRepository(client)
        repo.search("old", SearchFilter.PLAYLISTS)
        val pending = launch { repo.loadNext() }
        withTimeout(10000) { started.await() }
        repo.search("new", SearchFilter.CHANNELS)
        finish.complete(Unit)
        pending.join()
        assertEquals(SearchFilter.CHANNELS, repo.state.value.filter)
        assertEquals("/watch?v=new", repo.state.value.items.single().url)
        assertFalse(repo.state.value.hasMore)
        assertNull(repo.state.value.error)
        client.close()
    }

    @Test fun `empty page can continue and correction metadata belongs to first page`() = runTest {
        var count = 0
        val client = PipedApiClient("https://api.example", engine = MockEngine {
            count++
            val body = when (count) {
                1 -> """{"items":[],"nextpage":"first","suggestion":"cats","corrected":true}"""
                2 -> """{"items":[],"nextpage":"second","suggestion":"wrong","corrected":false}"""
                else -> page("last")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repo = SearchRepository(client)
        repo.search("ctas")
        repo.loadNext()
        assertTrue(repo.state.value.hasMore)
        repo.loadNext()
        assertEquals("cats", repo.state.value.suggestion)
        assertEquals(true, repo.state.value.corrected)
        assertEquals(1, repo.state.value.items.size)
        assertFalse(repo.state.value.hasMore)
        client.close()
    }

    @Test fun `missing and blank next cursors end pagination and first errors stay typed`() = runTest {
        var requests = 0
        val client = PipedApiClient("https://api.example", engine = MockEngine {
            requests++
            respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repo = SearchRepository(client)
        repo.search("cats")
        assertIs<PipedError.InstanceUnavailable>(repo.state.value.error)
        assertFalse(repo.state.value.loading)
        repo.loadNext()
        assertEquals(1, requests)
        client.close()
        for (body in listOf("{}", """{"nextpage":"  "}""")) {
            val other = PipedApiClient("https://api.example", engine = MockEngine {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            })
            val empty = SearchRepository(other)
            empty.search("cats")
            assertFalse(empty.state.value.hasMore)
            other.close()
        }
    }
}
