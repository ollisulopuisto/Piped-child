package fi.pipedchild.core.network

import fi.pipedchild.core.model.PipedError
import fi.pipedchild.core.model.SearchItem
import fi.pipedchild.core.model.SearchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-search, memory-only results. No history or account synchronization. */
data class SearchSnapshot(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.VIDEOS,
    val items: List<SearchItem> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val suggestion: String? = null,
    val corrected: Boolean? = null,
    val error: PipedError? = null,
)

/** Shared search state for future native screens, not wired to TV yet.
 *
 * No constructor request or background scope. Call search/loadNext only after
 * endpoint consent. The caller owns cancellation and the supplied client's
 * lifetime; this object never switches instances or closes a shared client.
 * A new search invalidates older results but cannot unsend an in-flight request.
 * No automatic retries, suggestions requests, disk cache or history writes.
 */
class SearchRepository(private val client: PipedApiClient) {
    private val lock = Any()
    private val _state = MutableStateFlow(SearchSnapshot())
    val state = _state.asStateFlow()
    private var generation = 0L
    private var nextPage: String? = null
    private val consumedPages = mutableSetOf<String>()

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.VIDEOS) {
        val normalized = query.trim()
        val ticket = synchronized(lock) {
            generation++
            nextPage = null
            consumedPages.clear()
            _state.value = if (normalized.isEmpty()) SearchSnapshot(filter = filter)
                else SearchSnapshot(query = normalized, filter = filter, loading = true)
            generation
        }
        if (normalized.isEmpty()) return
        fetch(ticket, null) { client.search(normalized, filter) }
    }

    /** Duplicate page loads are no-ops. Failures retain both results and token
     * for an explicit retry. Successful repeated cursors end pagination rather
     * than creating a request loop. Tokens are forwarded opaquely, never logged. */
    suspend fun loadNext() {
        val request = synchronized(lock) {
            val current = _state.value
            val token = nextPage
            if (current.loading || token == null || !current.hasMore) return
            _state.value = current.copy(loading = true, error = null)
            PageRequest(generation, current.query, current.filter, token)
        }
        fetch(request.generation, request.token) {
            client.searchNextPage(request.query, request.filter, request.token)
        }
    }

    private data class PageRequest(val generation: Long, val query: String, val filter: SearchFilter, val token: String)

    private suspend fun fetch(ticket: Long, token: String?, load: suspend () -> SearchResponse) {
        try {
            val response = load()
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (generation != ticket) return
                if (token != null) consumedPages += token
                val next = response.nextpage?.takeIf { it.isNotBlank() && it !in consumedPages }
                nextPage = next
                val current = _state.value
                // Same URL in different item types is not necessarily the
                // same resource. Unknown empty identities are kept as returned.
                val seen = mutableSetOf<Pair<Class<out SearchItem>, String>>()
                val items = (if (token == null) response.items else current.items + response.items)
                    .filter { it.url.isBlank() || seen.add(it.javaClass to it.url) }
                _state.value = current.copy(items = items.toList(), loading = false,
                    hasMore = next != null, error = null,
                    suggestion = if (token == null) response.suggestion else current.suggestion,
                    corrected = if (token == null) response.corrected else current.corrected)
            }
        } catch (e: CancellationException) {
            finishFailure(ticket, null)
            throw e
        } catch (e: PipedError) {
            finishFailure(ticket, e)
        } catch (e: Exception) {
            // A programming/configuration error is not an API error. Clear
            // the busy flag, then let the owner observe the actual failure.
            finishFailure(ticket, null)
            throw e
        }
    }

    private fun finishFailure(ticket: Long, error: PipedError?) = synchronized(lock) {
        if (generation == ticket) _state.value = _state.value.copy(loading = false, error = error)
    }
}
