package fi.pipedchild.core.network

import java.net.URI

/**
 * Normalizes and validates a Piped instance base URL.
 *
 * Rules (mirroring the web client's instance handling and the Sentinel
 * protocol check in the frontend):
 * - scheme must be http or https (https everywhere except local self-hosts)
 * - no userinfo (credentials never belong in a URL)
 * - no path, query or fragment: instances live at a host root. The retired
 *   `/api/v1` prefix returns 404 on current instances (api-capability-matrix).
 * - host case and trailing slashes are canonicalized so a token stored under
 *   one spelling is found under another.
 */
internal fun normalizeInstanceUrl(raw: String): String {
    val uri = try {
        URI(raw.trim())
    } catch (e: Exception) {
        throw IllegalArgumentException("instance URL is not parseable: $raw", e)
    }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "https" || scheme == "http") {
        "instance URL must use http or https: $raw"
    }
    require(!uri.host.isNullOrBlank()) { "instance URL has no host: $raw" }
    require(uri.userInfo == null) { "instance URL must not contain credentials: $raw" }
    require(uri.path.isNullOrBlank() || uri.path == "/") {
        "instance URL must not have a path (no /api/v1 prefix on current instances): $raw"
    }
    require(uri.query == null && uri.fragment == null) {
        "instance URL must not have a query or fragment: $raw"
    }
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    return "$scheme://${uri.host.lowercase()}$port"
}
