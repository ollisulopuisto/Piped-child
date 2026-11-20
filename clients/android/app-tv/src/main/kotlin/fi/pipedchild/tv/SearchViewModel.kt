package fi.pipedchild.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.pipedchild.core.network.PipedApiClient
import fi.pipedchild.core.network.SearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Explicit-submit, video-only TV search. No constructor or edit requests,
 * suggestions, history or image loads. Created only after API consent. */
class SearchViewModel(
    private val client: PipedApiClient,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val repository = SearchRepository(client)
    val state = repository.state
    private val _input = MutableStateFlow("")
    val input = _input.asStateFlow()
    private var job: Job? = null
    private val scope get() = testScope ?: viewModelScope

    fun edit(value: String) { if (value.length <= 512) _input.value = value }

    fun submit(): Job {
        job?.cancel()
        val query = _input.value
        return scope.launch { repository.search(query) }.also { job = it }
    }

    fun loadNext(): Job {
        if (job?.isActive == true) return job!!
        return scope.launch { repository.loadNext() }.also { job = it }
    }

    /** Leaving cancels network work but keeps the last submitted results and
     * cursor for a return from playback. Cancellation cannot unsend a request. */
    fun stop() { job?.cancel() }
    internal fun shutdown() { stop(); client.close() }
    override fun onCleared() { shutdown(); super.onCleared() }
}
