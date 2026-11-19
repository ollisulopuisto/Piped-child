package fi.pipedchild.chorekeeper

import java.net.URI
import java.util.Locale

/** An explicitly configured host, never inferred from a Piped account or endpoint. */
class ChoreKeeperEndpoint private constructor(val url: String) {
    override fun equals(other: Any?) = other is ChoreKeeperEndpoint && url == other.url
    override fun hashCode() = url.hashCode()
    override fun toString() = url

    companion object {
        fun parse(value: String): ChoreKeeperEndpoint {
            val uri = try { URI(value.trim()) } catch (e: Exception) {
                throw IllegalArgumentException("Enter an HTTPS host root", e)
            }
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
            require(uri.port == -1 || uri.port in 1..65535)
            val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
            return ChoreKeeperEndpoint("https://${uri.host.lowercase(Locale.ROOT)}$port")
        }
    }
}

sealed interface ConnectionStatus {
    data object Disabled : ConnectionStatus
    data class NeedsConsent(val endpoint: ChoreKeeperEndpoint) : ConnectionStatus
    /** Only transport outcomes can be returned by a read-only source. */
    sealed interface Probe : ConnectionStatus
    data object Available : Probe
    data object Unreachable : Probe
    data object AuthenticationRequired : Probe
    data class ApiError(val statusCode: Int) : Probe
}

/**
 * Boundary only. No HTTP implementation, login, credentials, session creation,
 * bank mutation, or playback policy exists in this module yet.
 *
 * A future source must perform only a read, classify network/auth/API failures,
 * refuse redirects and unexpected destinations, and propagate cancellation.
 * Available means that read succeeded, not that playback is permitted.
 */
fun interface ChoreKeeperStatusSource {
    suspend fun check(endpoint: ChoreKeeperEndpoint): ConnectionStatus.Probe
}

/**
 * In-memory opt-in gate. Configuration never contacts the server. Approval is
 * specific to the current normalized endpoint and lasts only for this object.
 *
 * Revocation prevents future checks and discards stale results. It cannot undo
 * bytes already sent by a check in flight; the caller owns coroutine cancellation.
 * No Piped account or playback data is accepted by this API.
 */
class ChoreKeeperConnection(private val source: ChoreKeeperStatusSource) {
    private val lock = Any()
    private var endpoint: ChoreKeeperEndpoint? = null
    private var approved = false
    private var generation = 0L

    fun configure(value: ChoreKeeperEndpoint) = synchronized(lock) {
        if (endpoint != value) {
            endpoint = value
            approved = false
            generation++
        }
    }

    fun approve(value: ChoreKeeperEndpoint): Boolean = synchronized(lock) {
        if (endpoint != value) false else {
            approved = true
            true
        }
    }

    fun disable() = synchronized(lock) {
        endpoint = null
        approved = false
        generation++
    }

    private fun currentStatus(): ConnectionStatus = endpoint?.let {
        ConnectionStatus.NeedsConsent(it)
    } ?: ConnectionStatus.Disabled

    suspend fun check(): ConnectionStatus {
        val snapshot = synchronized(lock) {
            val destination = endpoint
            if (destination == null || !approved) return currentStatus()
            destination to generation
        }
        val result = source.check(snapshot.first)
        return synchronized(lock) {
            if (generation != snapshot.second) currentStatus() else result
        }
    }
}
