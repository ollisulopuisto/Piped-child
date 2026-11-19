package fi.pipedchild.tv

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Internal activity, never exported. Save returns to a fresh MainActivity
 * session so no old client/token/image approval can follow the URL change. */
class InstanceSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = InstancePreferences(this)
        setContent { TvTheme {
            InstanceSettingsScreen(preferences.current(), onSave = { url ->
                if (preferences.save(url)) {
                    setResult(Activity.RESULT_OK)
                    finish()
                    true
                } else false
            }, onCancel = { finish() })
        } }
    }
}

@Composable
internal fun InstanceSettingsScreen(current: String, onSave: (String) -> Boolean, onCancel: () -> Unit) {
    var input by rememberSaveable(current) { mutableStateOf(current) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Piped instance", style = MaterialTheme.typography.headlineSmall)
        Text("Enter the HTTPS API instance root, not the web frontend or an /api/v1 path. " +
            "Saving makes no network request. A change restarts the session and asks for approval " +
            "before contacting the new instance. Tokens and image approvals are not transferred.")
        OutlinedTextField(
            value = input,
            onValueChange = { if (it.length <= 2048) input = it; error = null },
            modifier = Modifier.fillMaxWidth().then(initialFocus()),
            label = { Text("API instance URL") },
            singleLine = true,
            isError = error != null,
        )
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Text("This TV build supports HTTPS only. An API may be unavailable or unable to extract videos " +
            "even when its URL is valid. The app never silently switches to another instance.")
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Button(onClick = {
                val normalized = normalizeTvInstance(input)
                error = when {
                    normalized == null -> "Use an HTTPS host root with a valid port and no credentials, path, query or fragment."
                    !onSave(normalized) -> "The setting could not be saved. Try again."
                    else -> null
                }
            }, modifier = remoteFocus()) { Text("Save instance") }
            OutlinedButton(onClick = onCancel, modifier = remoteFocus()) { Text("Cancel") }
        }
    }
}

