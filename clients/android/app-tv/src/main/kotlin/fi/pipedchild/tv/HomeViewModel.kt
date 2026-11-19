package fi.pipedchild.tv

import fi.pipedchild.core.model.PipedError
import fi.pipedchild.core.model.StreamItem
import fi.pipedchild.core.network.PipedApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state of the TV home screen. Error carries a user-readable message
 *  derived from the typed PipedError, never a raw exception: the TV surface
 *  shows an actionable state, not a stack trace (handoff spec §10). */
sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Content(val items: List<StreamItem>) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

/** Maps a typed network error to the message the TV screen shows. */
fun PipedError.toTvMessage(): String = when (this) {
    PipedError.NetworkUnavailable ->
        "No network connection. Check the Wi-Fi and try again."
    is PipedError.InstanceUnavailable ->
        "The video server answered with an error ($httpStatus). Try again."
    is PipedError.ExtractionFailure ->
        "The instance could not fetch videos right now. Try another instance later."
    PipedError.AuthenticationFailure ->
        "Sign-in is needed for this."
    PipedError.VideoUnavailable ->
        "This video is not available."
    PipedError.PlaybackFormatUnsupported ->
        "This video has no playable format."
    PipedError.RateLimited ->
        "Too many requests. Wait a moment and try again."
    is PipedError.MalformedResponse ->
        "The server sent something unexpected."
}

class HomeViewModel(
    private val client: PipedApiClient,
    private val region: String = "US",
    // injected for tests; the app passes the default viewModel scope
    scope: CoroutineScope? = null,
) {
    private val ownScope = scope == null
    private val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch { loadOnce() }
    }

    /** One load cycle, exposed as a suspend function so tests can await it
     *  directly instead of racing a launched coroutine. */
    internal suspend fun loadOnce() {
        _state.value = HomeUiState.Loading
        _state.value = try {
            HomeUiState.Content(client.trending(region))
        } catch (e: PipedError) {
            HomeUiState.Error(e.toTvMessage())
        } catch (e: Exception) {
            // a parse/transport failure that escaped core-network's
            // taxonomy must still never crash the TV screen
            HomeUiState.Error("The server sent something unexpected.")
        }
    }

    fun onCleared() {
        if (ownScope) scope.cancel()
    }
}
