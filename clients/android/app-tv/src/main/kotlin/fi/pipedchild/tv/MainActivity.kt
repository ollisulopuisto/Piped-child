@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package fi.pipedchild.tv

import android.os.Bundle
import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import fi.pipedchild.core.model.StreamItem
import fi.pipedchild.core.network.PipedApiClient

/** The API is disclosed before metadata requests. Playback separately
 * asks for consent to the exact media proxy returned by that API. */
fun consentText(instanceUrl: String): String =
    "Piped Child contacts this Piped instance:\n" +
        "$instanceUrl\n\n" +
        "It receives this device's IP address, trending, submitted search queries and video metadata requests. " +
        "Before playing, the app separately asks you to approve the media proxy. " +
        "No analytics, no ads, no Google sign-in. Thumbnail image origins are " +
        "listed for separate approval before images load. Settings lets you change the API instance."

class HomeViewModelFactory(private val instanceUrl: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        HomeViewModel(PipedApiClient(instanceUrl)) as T
}

class MainActivity : ComponentActivity() {

    private val instanceUrl by lazy { InstancePreferences(this).current() }
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && InstancePreferences(this).current() != instanceUrl) {
            // A fresh activity means a fresh ViewModelStore and clients.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
    private val viewModel: HomeViewModel by viewModels { HomeViewModelFactory(instanceUrl) }

    private val playbackModel: PlaybackViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PlaybackViewModel(PipedApiClient(instanceUrl)) as T
        }
    }

    private val searchModel: SearchViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SearchViewModel(PipedApiClient(instanceUrl)) as T
        }
    }

    private var consented by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consented = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_CONSENTED_INSTANCE, null) == instanceUrl
        setContent {
            TvTheme {
                var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
                var lastSelected by rememberSaveable { mutableStateOf<String?>(null) }
                var searching by rememberSaveable { mutableStateOf(false) }
                val searchState = rememberSaveableStateHolder()
                val gridState = rememberLazyGridState()
                val headerFocus = remember { FocusRequester() }
                if (consented) {
                    LaunchedEffect(instanceUrl) { viewModel.refresh() }
                }
                val playing = selectedId
                if (playing != null && consented) {
                    PlaybackScreen(playing, playbackModel, onBack = { selectedId = null })
                } else if (searching && consented) {
                    searchState.SaveableStateProvider("search") {
                        SearchScreen(searchModel, onBack = { searching = false }, onVideoSelected = { url ->
                            videoIdFromWatchUrl(url)?.let { selectedId = it }
                        })
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Row {
                        Button(
                            onClick = { settings.launch(Intent(this@MainActivity, InstanceSettingsActivity::class.java)) },
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                                .focusRequester(headerFocus).then(remoteFocus()),
                        ) { Text("Instance settings") }
                        if (consented) Button(onClick = { searching = true },
                            modifier = Modifier.padding(vertical = 8.dp).then(remoteFocus())) { Text("Search videos") }
                        }
                        Box(Modifier.weight(1f)) {
                            if (consented) {
                                HomeScreen(viewModel, gridState, lastSelected,
                                    onNavigateUp = { headerFocus.requestFocus() }, onVideoSelected = { url ->
                                        videoIdFromWatchUrl(url)?.let { id ->
                                            lastSelected = url
                                            selectedId = id
                                        }
                                    })
                            } else {
                                FirstRunScreen(instanceUrl, onContinue = {
                                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                        .putString(KEY_CONSENTED_INSTANCE, instanceUrl).apply()
                                    consented = true
                                })
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
fun FirstRunScreen(instanceUrl: String, onContinue: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(48.dp)) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(consentText(instanceUrl))
            Card(onClick = onContinue, modifier = initialFocus()) {
                Text("Continue", modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
internal fun HomeScreen(
    viewModel: HomeViewModel,
    gridState: LazyGridState,
    lastSelected: String?,
    repository: ThumbnailRepository = viewModel.thumbnailRepository,
    onNavigateUp: (() -> Unit)? = null,
    onVideoSelected: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val imageConsent by viewModel.thumbnailConsent.collectAsState()
    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        when (val s = state) {
            is HomeUiState.Loading -> Text(
                "Loading…",
                modifier = Modifier.align(Alignment.Center),
            )
            is HomeUiState.Error -> Column(modifier = Modifier.align(Alignment.Center)) {
                Text(s.message)
                Card(onClick = { viewModel.refresh() }, modifier = initialFocus()) {
                    Text("Try again", modifier = Modifier.padding(16.dp))
                }
            }
            is HomeUiState.Content -> {
                val pending = imageConsent.pending(s.items.mapNotNull { it.thumbnail })
                if (pending.isNotEmpty()) {
                    // Show one complete origin at a time so a remote user
                    // never approves undisplayed hosts below a scroll fold.
                    ThumbnailConsentScreen(listOf(pending.first())) { allow ->
                        viewModel.decideThumbnails(
                            if (allow) listOf(pending.first()) else pending + imageConsent.approved, allow,
                        )
                    }
                } else {
                    TrendingGrid(s.items, gridState, lastSelected, imageConsent,
                        repository, onNavigateUp, onVideoSelected)
                }
            }
        }
    }
}

@Composable
private fun TrendingGrid(
    items: List<StreamItem>,
    gridState: LazyGridState,
    lastSelected: String?,
    imageConsent: ThumbnailConsent,
    repository: ThumbnailRepository,
    onNavigateUp: (() -> Unit)?,
    onVideoSelected: (String) -> Unit,
) {
    val inputMode = LocalInputModeManager.current
    val requesters = remember(items.map { it.url }) { List(items.size) { FocusRequester() } }
    val scope = rememberCoroutineScope()
    val windowInfo = LocalWindowInfo.current
    val target = items.indexOfFirst { it.url == lastSelected }.coerceAtLeast(0)
    suspend fun focusItem(index: Int) {
        if (index !in items.indices) return
        inputMode.requestInputMode(InputMode.Keyboard)
        val layout = gridState.layoutInfo
        val visible = layout.visibleItemsInfo.firstOrNull { it.index == index }
        if (visible == null || visible.offset.y < layout.viewportStartOffset ||
            visible.offset.y + visible.size.height > layout.viewportEndOffset) {
            gridState.scrollToItem(index)
        }
        withFrameNanos { }
        requesters[index].requestFocus()
    }
    // One restore per entry to Home, not when an off-screen card is
    // recomposed during ordinary scrolling.
    LaunchedEffect(Unit) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        focusItem(target)
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.url }) { index, item ->
            var focused by remember { mutableStateOf(false) }
            Card(
                onClick = { onVideoSelected(item.url) },
                modifier = Modifier.focusRequester(requesters[index])
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (event.key == Key.DirectionUp && index < 4 && onNavigateUp != null) {
                            onNavigateUp()
                            return@onPreviewKeyEvent true
                        }
                        val next = when (event.key) {
                            Key.DirectionLeft -> if (index % 4 > 0) index - 1 else index
                            Key.DirectionRight -> if (index % 4 < 3 && index < items.lastIndex) index + 1 else index
                            Key.DirectionUp -> if (index >= 4) index - 4 else index
                            Key.DirectionDown -> if (index + 4 < items.size) index + 4 else index
                            else -> return@onPreviewKeyEvent false
                        }
                        scope.launch { focusItem(next) }
                        true
                    }
                    .onFocusChanged { focused = it.isFocused }
                    .border(if (focused) 4.dp else 0.dp, if (focused) Color.White else Color.Transparent),
            ) {
                Column {
                    VideoThumbnail(item.thumbnail, imageConsent, repository)
                    Text(
                        item.title,
                        modifier = Modifier.padding(12.dp),
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** Initial remote target. The grid uses its own one-time restoration. */
@Composable
internal fun initialFocus(): Modifier = remoteFocus(initial = true)

@Composable
internal fun remoteFocus(initial: Boolean = false): Modifier {
    val requester = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    var focused by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(Unit) {
        if (!initial) return@LaunchedEffect
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        inputMode.requestInputMode(InputMode.Keyboard)
        // Input mode may update focus properties on the next frame.
        withFrameNanos { }
        requester.requestFocus()
    }
    return Modifier.focusRequester(requester).onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent)
}
