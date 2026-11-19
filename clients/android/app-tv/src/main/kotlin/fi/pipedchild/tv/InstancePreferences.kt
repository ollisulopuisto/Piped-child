package fi.pipedchild.tv

import android.content.Context
import java.net.URI

internal const val DEFAULT_INSTANCE = "https://pipedapi.ducks.party"
internal const val PREFS = "piped_child_tv"
internal const val KEY_CONSENTED_INSTANCE = "consented_instance_v2"
private const val KEY_INSTANCE = "selected_instance_v1"

/** The TV settings UI supports HTTPS instance roots in this increment.
 * Private-network plaintext support in core-network is not exposed until
 * Android's per-domain cleartext configuration has its own verification. */
internal fun normalizeTvInstance(raw: String): String? = runCatching {
    require(raw.length <= 2048)
    val uri = URI(raw.trim())
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank())
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    require(uri.port == -1 || uri.port in 1..65535)
    URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port,
        null, null, null).toASCIIString()
}.getOrNull()

internal class InstancePreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(): String = prefs.getString(KEY_INSTANCE, null)?.let(::normalizeTvInstance) ?: DEFAULT_INSTANCE

    /** A switch removes the API approval, not migrates it. Restarting the
     * activity also drops the old clients, image approvals and memory cache.
     * No request is made to test or auto-discover the submitted instance. */
    fun save(raw: String): Boolean {
        val normalized = normalizeTvInstance(raw) ?: return false
        if (normalized == current()) return true
        return prefs.edit().putString(KEY_INSTANCE, normalized).remove(KEY_CONSENTED_INSTANCE).commit()
    }
}
