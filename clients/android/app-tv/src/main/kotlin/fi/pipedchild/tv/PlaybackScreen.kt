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
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import fi.pipedchild.core.player.PlayerManager
import fi.pipedchild.core.player.SponsorSkipEngine
import fi.pipedchild.core.player.applySponsorSkip
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
        is PlaybackUiState.AwaitingConsent -> ProxyConsentScreen(s.media, viewModel::approveProxy, onBack, viewModel::setSponsorBlockEnabled)
        is PlaybackUiState.Ready -> {
            val sponsors by viewModel.sponsors.collectAsState()
            LaunchedEffect(s.media) { viewModel.loadSponsorsOnce(s.media) }
            PlayerSurface(s.media, sponsors, onBack)
        }
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
internal fun ProxyConsentScreen(
    media: PlaybackMedia, onPlay: () -> Unit, onBack: () -> Unit,
    onSponsorBlockChange: (Boolean) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(media.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
        Text("Play through this media proxy?\n${media.proxyBase}\n\n" +
            "It will receive this device's IP address and video requests. " +
            "Other media destinations and redirects are blocked. No video bytes are loaded before Play.")
        Button(onClick = onPlay, modifier = initialFocus()) { Text("Play") }
        if (!media.livestream) {
            OutlinedButton(onClick = { onSponsorBlockChange(!media.sponsorBlockEnabled) }, modifier = remoteFocus()) {
                Text("SponsorBlock: " + if (media.sponsorBlockEnabled) "on" else "off")
            }
            Text("Skips sponsors, self-promotion, interaction reminders and non-music sections. Uses the approved API instance.",
                style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onBack, modifier = remoteFocus()) { Text("Back") }
    }
}

/** Playback exists only while the surface is STARTED. Stop/background,
 * Back and composition disposal all release ExoPlayer. A fresh start
 * creates a fresh player and resumes from the saved in-session position. */
@Composable
private fun PlayerSurface(media: PlaybackMedia, sponsors: SponsorUiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember(media) { mutableStateOf<ExoPlayer?>(null) }
    var error by remember(media) { mutableStateOf<String?>(null) }
    var position by remember(media) { mutableLongStateOf(C.TIME_UNSET) }
    var playWhenReady by remember(media) { mutableStateOf(true) }

    val skipEngine = remember(media, sponsors) {
        (sponsors as? SponsorUiState.Ready)?.let { SponsorSkipEngine(it.segments) }
    }
    LaunchedEffect(player, skipEngine) {
        val current = player ?: return@LaunchedEffect
        val engine = skipEngine ?: return@LaunchedEffect
        while (isActive) {
            if (!media.livestream) applySponsorSkip(current, engine)
            delay(250)
        }
    }

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
                // TIME_UNSET preserves Media3's default live edge on a
                // first start; a saved position resumes only after Stop.
                if (position != C.TIME_UNSET) created.seekTo(position)
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
    } else Box(Modifier.fillMaxSize()) {
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
        if (sponsors == SponsorUiState.Unavailable) {
            SponsorUnavailableNotice(Modifier.align(Alignment.TopCenter).padding(16.dp))
        }
    }
}

@Composable
internal fun SponsorUnavailableNotice(modifier: Modifier = Modifier) {
    Surface(modifier = modifier) {
        Text("SponsorBlock unavailable. Playing without skips.", modifier = Modifier.padding(12.dp))
    }
}
