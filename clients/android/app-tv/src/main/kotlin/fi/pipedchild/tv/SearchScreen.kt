@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package fi.pipedchild.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import fi.pipedchild.core.model.SearchItem

/** Titles only: no third-party image origin is contacted from this screen. */
@Composable
internal fun SearchScreen(viewModel: SearchViewModel, onBack: () -> Unit, onVideoSelected: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    val input by viewModel.input.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val streams = state.items.filterIsInstance<SearchItem.Stream>().filter { videoIdFromWatchUrl(it.url) != null }
    val requesters = remember(streams.map { it.url }) { List(streams.size) { FocusRequester() } }
    DisposableEffect(viewModel) { onDispose { viewModel.stop() } }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Search videos", style = MaterialTheme.typography.headlineSmall)
        Text("Only Search sends your query to the approved API instance. Titles only, no suggestions or search history.")
        OutlinedTextField(input, viewModel::edit, label = { Text("Video query") },
            singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                    inputMode.requestInputMode(InputMode.Keyboard)
                    submitFocus.requestFocus()
                    true
                } else false
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Button(onClick = { viewModel.submit() }, modifier = Modifier.focusRequester(submitFocus).then(remoteFocus())) { Text("Search") }
            OutlinedButton(onClick = onBack, modifier = remoteFocus()) { Text("Back to Trending") }
        }
        if (state.query.isNotEmpty()) Text("Results for: ${state.query}")
        if (state.loading) Text("Searching…")
        state.error?.let { error -> Text(error.toTvMessage(), color = MaterialTheme.colorScheme.error) }
        if (!state.loading && state.query.isNotEmpty() && state.error == null && streams.isEmpty()) {
            Text(if (state.hasMore) "No videos on this page. Load more to continue." else "No videos found.")
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(streams, key = { _, item -> item.url }) { index, item ->
                Card(onClick = { selected = item.url; onVideoSelected(item.url) },
                    modifier = Modifier.fillMaxWidth().focusRequester(requesters[index]).then(remoteFocus())) {
                    Column(Modifier.padding(16.dp)) {
                        Text(item.title, maxLines = 2)
                        item.uploaderName?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (state.hasMore) item {
                Button(onClick = { viewModel.loadNext() }, enabled = !state.loading,
                    modifier = remoteFocus()) { Text(if (state.error != null) "Retry next page" else "Load more") }
            }
        }
    }
    LaunchedEffect(Unit) {
        val index = streams.indexOfFirst { it.url == selected }
        if (index >= 0) {
            listState.scrollToItem(index)
            withFrameNanos { }
            requesters[index].requestFocus()
        } else focus.requestFocus()
    }
}
