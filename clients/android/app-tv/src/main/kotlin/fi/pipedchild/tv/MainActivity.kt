package fi.pipedchild.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import fi.pipedchild.core.model.StreamItem
import fi.pipedchild.core.network.PipedApiClient

/** Default instance for the first-run experience (the M1-A capability
 *  matrix recorded healthy trending responses from it on 2026-11-18).
 *  Instance selection in settings lands in a later M2 PR. */
private const val DEFAULT_INSTANCE = "https://pipedapi.ducks.party"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = HomeViewModel(PipedApiClient(DEFAULT_INSTANCE))
        setContent {
            MaterialTheme {
                HomeScreen(viewModel)
            }
        }
    }
}

@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsState()
    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        when (val s = state) {
            is HomeUiState.Loading -> Text(
                "Loading…",
                modifier = Modifier.align(Alignment.Center),
            )
            is HomeUiState.Error -> Column(modifier = Modifier.align(Alignment.Center)) {
                Text(s.message)
                Card(onClick = { viewModel.refresh() }) {
                    Text("Try again", modifier = Modifier.padding(16.dp))
                }
            }
            is HomeUiState.Content -> TrendingGrid(s.items)
        }
    }
}

@Composable
private fun TrendingGrid(items: List<StreamItem>) {
    // standard compose lazy grid: on TV the focus system gives d-pad
    // navigation between focusable children out of the box
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items, key = { it.url }) { item ->
            // d-pad focus works on TV through the clickable Card; click
            // opens playback in the next M2 PR
            Card(onClick = { /* playback PR follows */ }) {
                Column {
                    // thumbnail loading lands with the playback PR (coil);
                    // the card is title-first until then
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
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
