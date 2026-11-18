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

/** Loopback (::1), unique-local fc00::/7 (the tailnet ULA prefix lives
 *  here) and link-local fe80::/10 (never leaves the link). Zone ids and
 *  embedded-IPv4 tails are not accepted: no verified LAN config needs them. */
private fun isPrivateIpv6(bare: String): Boolean {
    val b = parseIpv6Bytes(bare) ?: return false
    if (b.take(15).all { it == 0.toByte() } && b[15] == 1.toByte()) return true // ::1
    val b0 = b[0].toInt() and 0xff
    if (b0 and 0xfe == 0xfc) return true // fc00::/7 unique-local
    if (b0 == 0xfe && (b[1].toInt() and 0xff and 0xc0) == 0x80) return true // fe80::/10 link-local
    return false
}

/** Parses an IPv6 literal (compressed or full) into 16 bytes, or null when
 *  the input is not a pure IPv6 literal. */
private fun parseIpv6Bytes(s: String): ByteArray? {
    if ('%' in s || '.' in s) return null
    val halves = s.split("::")
    if (halves.size > 2) return null
    fun groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        return part.split(':').map {
            if (it.isEmpty() || it.length > 4 ||
                it.any { c -> !c.isDigit() && c.lowercaseChar() !in 'a'..'f' }
            ) return null
            it.toInt(16)
        }
    }
    val head = groups(halves[0]) ?: return null
    val full = if (halves.size == 2) {
        val tail = groups(halves[1]) ?: return null
        val missing = 8 - head.size - tail.size
        if (missing < 1) return null
        head + List(missing) { 0 } + tail
    } else {
        if (head.size != 8) return null
        head
    }
    return full.flatMap { listOf((it shr 8).toByte(), (it and 0xff).toByte()) }.toByteArray()
}

/** Loopback and non-public hosts where plain http is acceptable for a
 *  self-hosted instance: localhost, .local/.internal, RFC 1918, link-local,
 *  and the Tailscale CGNAT range (the family tailnet routes 100.64/10). */
internal fun isPrivateOrLoopbackHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".localhost") ||
        host.endsWith(".local") || host.endsWith(".internal")
    ) return true
    // URI.getHost() keeps the brackets of an IPv6 literal; classify the
    // bare address
    val bare = host.removePrefix("[").removeSuffix("]")
    if (':' in bare) return isPrivateIpv6(bare)
    // the strict regex keeps "10.1.2.3.example.com" public (CWE-319)
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
