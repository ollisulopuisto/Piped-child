package fi.pipedchild.core.network

import fi.pipedchild.core.model.PipedError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Transient suggestions only. Never records local history or selects a result. */
data class SuggestionsSnapshot(
    val query: String = "",
    val items: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: PipedError? = null,
)

/** Optional request coordinator for future screens, not wired to the TV UI.
 * The caller owns [scope] and client lifetime. Enable only after API endpoint
 * disclosure/consent. Construction and enabling alone make no request. Disable
 * on consent revocation; close on disposal. No persistence, auto retry, separate
 * host, or implicit search submission. Cancellation cannot unsend a request.
 */
class SearchSuggestions(
    private val scope: CoroutineScope,
    private val source: suspend (String) -> List<String>,
    private val debounceMillis: Long = 300,
) : AutoCloseable {
    constructor(scope: CoroutineScope, client: PipedApiClient) : this(scope, { client.suggestions(it) })

    init { require(debounceMillis >= 0) }
    private val lock = Any()
    private val _state = MutableStateFlow(SuggestionsSnapshot())
    val state = _state.asStateFlow()
    private var enabled = false
    private var closed = false
    private var generation = 0L
    private var job: Job? = null

    fun setEnabled(value: Boolean) = synchronized(lock) {
        if (!closed && enabled != value) {
            enabled = value
            invalidate()
        }
    }

    /** Each edit cancels the previous delay/request and clears old results.
     * Errors remain typed; no raw exception or signed URL is shown to a UI. */
    fun submit(query: String) {
        val pending = synchronized(lock) {
            if (closed) return
            invalidate()
            val normalized = query.trim()
            if (!enabled || normalized.isEmpty() || !scope.isActive) return
            val ticket = generation
            _state.value = SuggestionsSnapshot(query = normalized, loading = true)
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    delay(debounceMillis)
                    val result = source(normalized)
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (ticket == generation) _state.value = SuggestionsSnapshot(normalized,
                            result.filter { it.isNotBlank() }.distinct().toList())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PipedError) {
                    synchronized(lock) {
                        if (ticket == generation) _state.value = SuggestionsSnapshot(query = normalized, error = e)
                    }
                } finally {
                    synchronized(lock) {
                        if (ticket == generation) _state.value = _state.value.copy(loading = false)
                    }
                }
            }.also { launched ->
                job = launched
                // A parent can cancel between isActive and start(). A lazy
                // coroutine cancelled before entry never runs its finally.
                launched.invokeOnCompletion {
                    synchronized(lock) {
                        if (ticket == generation) _state.value = _state.value.copy(loading = false)
                    }
                }
            }
        }
        pending.start()
    }

    private fun invalidate() {
        generation++
        job?.cancel()
        job = null
        _state.value = SuggestionsSnapshot()
    }

    override fun close() = synchronized(lock) {
        closed = true
        enabled = false
        invalidate()
    }
}
