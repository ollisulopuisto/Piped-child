package fi.pipedchild.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import fi.pipedchild.core.player.PlayerManager

@Composable
fun PlaybackScreen(videoId: String, viewModel: PlaybackViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    LaunchedEffect(videoId) { viewModel.load(videoId) }
    DisposableEffect(viewModel) { onDispose { viewModel.closeSelection() } }
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        PlaybackUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading video...")
        }
        is PlaybackUiState.AwaitingConsent -> ProxyConsentScreen(s.media, viewModel::approveProxy, onBack)
        is PlaybackUiState.Ready -> PlayerSurface(s.media, onBack)
        is PlaybackUiState.Error -> Column(
            Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Text(s.message)
            Button(onClick = { viewModel.load(videoId) }, modifier = initialFocus()) { Text("Try again") }
            OutlinedButton(onClick = onBack, modifier = remoteFocus()) { Text("Back") }
        }
    }
}

@Composable
internal fun ProxyConsentScreen(media: PlaybackMedia, onPlay: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(media.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
        Text("Play through this media proxy?\n${media.proxyBase}\n\n" +
            "It will receive this device's IP address and video requests. " +
            "Other media destinations and redirects are blocked. No video bytes are loaded before Play.")
        Button(onClick = onPlay, modifier = initialFocus()) { Text("Play") }
        OutlinedButton(onClick = onBack, modifier = remoteFocus()) { Text("Back") }
    }
}

/** Playback exists only while the surface is STARTED. Stop/background,
 * Back and composition disposal all release ExoPlayer. A fresh start
 * creates a fresh player and resumes from the saved in-session position. */
@Composable
private fun PlayerSurface(media: PlaybackMedia, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember(media) { mutableStateOf<ExoPlayer?>(null) }
    var error by remember(media) { mutableStateOf<String?>(null) }
    var position by remember(media) { mutableLongStateOf(0L) }
    var playWhenReady by remember(media) { mutableStateOf(true) }

    DisposableEffect(media, lifecycle) {
        fun stop() {
            player?.let {
                position = it.currentPosition
                playWhenReady = it.playWhenReady
                it.release()
            }
            player = null
        }
        fun start() {
            if (player != null || error != null) return
            try {
                val manager = PlayerManager(context, media.proxyBase)
                val created = manager.createPlayer()
                player = created
                created.addListener(object : Player.Listener {
                    override fun onPlayerError(exception: PlaybackException) {
                        // Do not show signed media URLs or raw network exceptions.
                        error = "This video could not play. Press Back and try another video."
                        stop()
                    }
                })
                created.setMediaSource(manager.mediaSourceFor(media.choice))
                created.seekTo(position)
                created.playWhenReady = playWhenReady
                created.prepare()
            } catch (exception: Exception) {
                error = "This video could not play. Press Back and try another video."
                stop()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }
    if (error != null) {
        Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center) {
            Text(error!!)
            Button(onClick = onBack, modifier = initialFocus()) { Text("Back") }
        }
    } else {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> PlayerView(ctx).apply {
                useController = true
                controllerAutoShow = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                isFocusable = true
                post { requestFocus() }
            } },
            update = { it.player = player },
            onRelease = { it.player = null },
        )
    }
}
