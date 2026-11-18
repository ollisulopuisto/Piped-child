package fi.pipedchild.core.network

import java.util.concurrent.ConcurrentHashMap

/**
 * Stores Piped auth tokens scoped to the instance that issued them.
 *
 * A token MUST never be sent to a different host than the one it was issued
 * by (handoff spec §10), so the store is keyed by normalized instance base
 * URL and the client looks tokens up only under its own auth URL.
 *
 * This interface is the M1-B seam; the Android Keystore-backed
 * implementation lands with the app modules in M2 (it needs the Android
 * SDK), and the iOS Keychain implementation lives in the Swift package.
 */
interface TokenStore {
    /** Token for exactly this instance base URL, or null when not logged in. */
    fun token(instanceBaseUrl: String): String?

    fun put(instanceBaseUrl: String, token: String)

    fun clear(instanceBaseUrl: String)
}

/** Non-persistent store, used by tests and as the M1-B default. */
class InMemoryTokenStore : TokenStore {
    private val tokens = ConcurrentHashMap<String, String>()

    override fun token(instanceBaseUrl: String): String? = tokens[normalizeInstanceUrl(instanceBaseUrl)]

    override fun put(instanceBaseUrl: String, token: String) {
        tokens[normalizeInstanceUrl(instanceBaseUrl)] = token
    }

    override fun clear(instanceBaseUrl: String) {
        tokens.remove(normalizeInstanceUrl(instanceBaseUrl))
    }
}
