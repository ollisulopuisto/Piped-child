package fi.pipedchild.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Consent is optional and separate from playback. A user can keep the
 * title-only grid without contacting any image host. */
@Composable
internal fun ThumbnailConsentScreen(origins: List<String>, onDecision: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Load video thumbnails?", style = MaterialTheme.typography.headlineSmall)
        Text("This image origin receives this device's IP address. Images stay in memory only. " +
            "Other image destinations and redirects are blocked. You can use videos without thumbnails.")
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            origins.forEach { Text(it, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Button(onClick = { onDecision(true) }, modifier = initialFocus()) { Text("Load thumbnails") }
            OutlinedButton(onClick = { onDecision(false) }, modifier = remoteFocus()) { Text("Titles only") }
        }
    }
}

@Composable
internal fun VideoThumbnail(url: String?, consent: ThumbnailConsent, repository: ThumbnailRepository) {
    var bitmap by remember(url, consent, repository) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url, consent, repository) {
        bitmap = url?.let { repository.load(it, consent) }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize().testTag("video-thumbnail"), contentScale = ContentScale.Crop) }
    }
}
