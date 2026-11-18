package fi.pipedchild.core.network

import java.net.URI

/**
 * Normalizes and validates a Piped instance base URL.
 *
 * Rules (mirroring the web client's instance handling and the Sentinel
 * protocol check in the frontend):
 * - scheme must be http or https, and plain http is accepted ONLY for
 *   loopback and private-network hosts (self-hosting at home or over the
 *   family tailnet). Credentials and tokens are sent to this URL, so http
 *   to a public host would expose them on the wire.
 * - no userinfo (credentials never belong in a URL)
 * - no path, query or fragment: instances live at a host root. The retired
 *   `/api/v1` prefix returns 404 on current instances (api-capability-matrix).
 * - host case and trailing slashes are canonicalized so a token stored under
 *   one spelling is found under another.
 *
 * Error messages deliberately do NOT echo the raw input: a rejected URL can
 * contain credentials, and exception text ends up in logs.
 */
internal fun normalizeInstanceUrl(raw: String): String {
    val uri = try {
        URI(raw.trim())
    } catch (e: Exception) {
        throw IllegalArgumentException("instance URL is not parseable", e)
    }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "https" || scheme == "http") {
        "instance URL must use http or https"
    }
    val host = uri.host
    require(!host.isNullOrBlank()) { "instance URL has no host" }
    require(uri.userInfo == null) { "instance URL must not contain credentials" }
    require(uri.path.isNullOrBlank() || uri.path == "/") {
        "instance URL must not have a path (no /api/v1 prefix on current instances)"
    }
    require(uri.query == null && uri.fragment == null) {
        "instance URL must not have a query or fragment"
    }
    if (scheme == "http") {
        require(isPrivateOrLoopbackHost(host.lowercase())) {
            "plain http is only allowed for loopback and private-network hosts; use https for a public instance"
        }
    }
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    return "$scheme://${host.lowercase()}$port"
}

private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

/** Loopback and non-public hosts where plain http is acceptable for a
 *  self-hosted instance: localhost, .local/.internal, RFC 1918, link-local,
 *  and the Tailscale CGNAT range (the family tailnet routes 100.64/10). */
internal fun isPrivateOrLoopbackHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".localhost") ||
        host.endsWith(".local") || host.endsWith(".internal")
    ) return true
    if (host == "::1") return true
    val m = ipv4.matchEntire(host) ?: return false
    val octets = m.groupValues.drop(1).map { it.toInt() }
    if (octets.any { it > 255 }) return false
    val a = octets[0]
    val b = octets[1]
    return a == 10 || a == 127 ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        (a == 169 && b == 254) ||
        (a == 100 && b in 64..127)
}
