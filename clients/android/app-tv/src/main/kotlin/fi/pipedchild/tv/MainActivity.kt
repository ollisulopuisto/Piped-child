package fi.pipedchild.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.foundation.lazy.grid.items
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

/** Default instance for the first-run experience (the M1-A capability
 *  matrix recorded healthy trending responses from it on 2026-11-18).
 *  Instance selection in settings lands in a later M2 PR. */
private const val DEFAULT_INSTANCE = "https://pipedapi.ducks.party"

private const val PREFS = "piped_child_tv"

/** Consent record: which instance the user accepted on the first-run
 *  screen. Bumping the suffix re-asks if the endpoint story changes. */
private const val KEY_CONSENTED_INSTANCE = "consented_instance_v1"

/** The first-run consent text (architecture §6): every endpoint the app
 *  will contact, listed BEFORE the first request. Piped proxies video,
 *  images and SponsorBlock/DeArrow through the instance, so the instance
 *  is the only host this app contacts. */
fun consentText(instanceUrl: String): String =
    "Piped Child contacts one Piped instance:\n" +
        "$instanceUrl\n\n" +
        "Videos, thumbnails and SponsorBlock data are proxied through it, " +
        "so it sees this device's IP address. No analytics, no ads, " +
        "no Google sign-in. A setting to change the instance lands in " +
        "a later version."

class HomeViewModelFactory(private val instanceUrl: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        HomeViewModel(PipedApiClient(instanceUrl)) as T
}

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels { HomeViewModelFactory(DEFAULT_INSTANCE) }

    private var consented by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consented = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_CONSENTED_INSTANCE, null) == DEFAULT_INSTANCE
        setContent {
            MaterialTheme {
                if (consented) {
                    LaunchedEffect(Unit) { viewModel.refresh() }
                    HomeScreen(viewModel)
                } else {
                    FirstRunScreen(instanceUrl = DEFAULT_INSTANCE, onContinue = {
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit()
                            .putString(KEY_CONSENTED_INSTANCE, DEFAULT_INSTANCE)
                            .apply()
                        consented = true
                    })
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
            Card(onClick = onContinue) {
                Text("Continue", modifier = Modifier.padding(16.dp))
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
