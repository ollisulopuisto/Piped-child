package fi.pipedchild.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.pipedchild.core.model.PipedError
import fi.pipedchild.core.network.PipedApiClient
import fi.pipedchild.core.player.PlaybackChoice
import fi.pipedchild.core.player.ProxyRewriter
import fi.pipedchild.core.player.StreamPicker
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Trending uses local watch routes, not arbitrary external URLs. */
fun videoIdFromWatchUrl(value: String): String? = runCatching {
    val uri = URI(value)
    require(uri.scheme == null && uri.rawAuthority == null && uri.path == "/watch")
    require(uri.rawFragment == null)
    val values = uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "v" }
    require(values.size == 1)
    values.single().substringAfter('=', "").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
}.getOrNull()

data class PlaybackMedia(val title: String, val proxyBase: String, val choice: PlaybackChoice)

sealed interface PlaybackUiState {
    data object Loading : PlaybackUiState
    data class AwaitingConsent(val media: PlaybackMedia) : PlaybackUiState
    data class Ready(val media: PlaybackMedia) : PlaybackUiState
    data class Error(val message: String) : PlaybackUiState
}

/** Loads metadata from the already-disclosed API. A separate explicit
 * consent transition is required before any media data source is created.
 * Approval is per selection, not a wildcard for future proxy origins. */
class PlaybackViewModel(private val client: PipedApiClient) : ViewModel() {
    private val _state = MutableStateFlow<PlaybackUiState>(PlaybackUiState.Loading)
    val state = _state.asStateFlow()
    private var loadJob: Job? = null

    fun load(videoId: String): Job {
        loadJob?.cancel()
        return viewModelScope.launch { loadOnce(videoId) }.also { loadJob = it }
    }

    internal suspend fun loadOnce(videoId: String) {
        _state.value = PlaybackUiState.Loading
        val result = try {
            require(videoId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            val streams = client.streams(videoId)
            val proxy = streams.proxyUrl?.let(ProxyRewriter::validProxyBase)
            val choice = StreamPicker.pick(streams)
            if (proxy == null || choice == null) {
                PlaybackUiState.Error("No playable video through an HTTPS Piped proxy. Try another video.")
            } else {
                PlaybackUiState.AwaitingConsent(PlaybackMedia(streams.title ?: "Video", proxy, choice))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PipedError) {
            PlaybackUiState.Error(e.toTvMessage())
        } catch (e: Exception) {
            PlaybackUiState.Error("The video server sent something unexpected.")
        }
        currentCoroutineContext().ensureActive()
        _state.value = result
    }

    fun approveProxy() {
        val waiting = _state.value as? PlaybackUiState.AwaitingConsent ?: return
        _state.value = PlaybackUiState.Ready(waiting.media)
    }

    fun closeSelection() {
        loadJob?.cancel()
        _state.value = PlaybackUiState.Loading
    }

    internal fun shutdown() {
        closeSelection()
        client.close()
    }

    override fun onCleared() {
        shutdown()
        super.onCleared()
    }
}
