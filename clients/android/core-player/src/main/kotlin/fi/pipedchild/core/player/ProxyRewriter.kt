package fi.pipedchild.core.player

import java.net.URI
import java.net.URLEncoder

/** A media request can leave only for the exact, disclosed HTTPS proxy
 * origin. googlevideo requests are rewritten using the frontend's host
 * query parameter. Redirects are rejected by the HTTP data source. */
object ProxyRewriter {
    sealed interface Result {
        data class Open(val uri: String, val rangeConverted: Boolean) : Result
        data class Blocked(val uri: String) : Result
    }

    /** Canonical consent destination. Never accept credentials or a
     * query/fragment supplied by the API as part of a proxy base. */
    fun validProxyBase(value: String): String? = runCatching {
        val u = URI(value)
        require(u.scheme.equals("https", ignoreCase = true))
        require(!u.host.isNullOrBlank() && u.rawUserInfo == null)
        require(u.rawQuery == null && u.rawFragment == null)
        require(u.port == -1 || u.port in 1..65535)
        URI("https", null, u.host.lowercase(), if (u.port == 443) -1 else u.port,
            u.path.orEmpty().trimEnd('/'), null, null).toASCIIString()
    }.getOrNull()

    private fun effectivePort(u: URI): Int = if (u.port == -1) 443 else u.port

    fun resolve(uri: String, proxyBase: String, rangeHeader: String?): Result {
        val base = validProxyBase(proxyBase) ?: return Result.Blocked(uri)
        val proxy = URI(base)
        val original = runCatching { URI(uri) }.getOrNull() ?: return Result.Blocked(uri)
        val host = original.host?.lowercase() ?: return Result.Blocked(uri)
        if (!original.scheme.equals("https", ignoreCase = true) || original.rawUserInfo != null ||
            original.rawFragment != null) return Result.Blocked(uri)

        val sameOrigin = host == proxy.host && effectivePort(original) == effectivePort(proxy)
        val googleVideo = (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) &&
            effectivePort(original) == 443
        if (!sameOrigin && !googleVideo) return Result.Blocked(uri)

        // Preserve the raw path and query, including signed URL escapes.
        val path = if (sameOrigin) original.rawPath.orEmpty() else
            proxy.rawPath.orEmpty() + original.rawPath.orEmpty()
        val query = original.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.toMutableList()
        fun setParameter(key: String, value: String) {
            query.removeAll { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") == key }
            query += key + "=" + URLEncoder.encode(value, "UTF-8")
        }
        return runCatching {
            if (!sameOrigin) setParameter("host", host)
            // The frontend converts Range only on the proxy videoplayback
            // endpoint. HLS segments and other paths retain HTTP ranges.
            val convert = rangeHeader != null && path == proxy.rawPath.orEmpty() + "/videoplayback"
            if (convert) {
                require(rangeHeader!!.matches(Regex("bytes=\\d+-\\d*")))
                setParameter("range", rangeHeader.substringAfter('='))
            }
            val url = "https://" + proxy.rawAuthority + path +
                if (query.isEmpty()) "" else "?" + query.joinToString("&")
            Result.Open(url, convert)
        }.getOrElse { Result.Blocked(uri) }
    }
}
