package fi.pipedchild.tv

import java.net.URI

/** Only explicit, credential-free HTTPS origins can be approved. */
internal fun thumbnailOrigin(url: String): String? = runCatching {
    val uri = URI(url)
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrEmpty())
    require(uri.rawUserInfo == null && uri.rawFragment == null)
    require(uri.port == -1 || uri.port in 1..65535)
    URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port,
        null, null, null).toASCIIString()
}.getOrNull()

/** Consent stays in the home ViewModel session, not on disk. Unknown
 * origins on a later response ask again; declining never blocks videos. */
internal data class ThumbnailConsent(
    val approved: Set<String> = emptySet(),
    val declined: Set<String> = emptySet(),
) {
    fun pending(urls: List<String>): List<String> = urls.mapNotNull(::thumbnailOrigin)
        .distinct().filter { it !in approved && it !in declined }.sorted()

    fun decide(origins: List<String>, allow: Boolean): ThumbnailConsent {
        val valid = origins.mapNotNull(::thumbnailOrigin).toSet()
        return if (allow) copy(approved = approved + valid) else copy(approved = approved - valid, declined = declined + valid)
    }

    fun allows(url: String): Boolean = thumbnailOrigin(url)?.let { it in approved } == true
}
