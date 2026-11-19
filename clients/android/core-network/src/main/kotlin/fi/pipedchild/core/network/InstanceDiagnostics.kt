package fi.pipedchild.core.network

import fi.pipedchild.core.model.PipedError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Responded means the endpoint returned decodable JSON, not usable media,
 * healthy extraction for other videos, or verified device playback. */
sealed interface EndpointDiagnostic {
    data object NotChecked : EndpointDiagnostic
    data object Responded : EndpointDiagnostic
    data class Failed(val error: PipedError) : EndpointDiagnostic
}

/** Separate observations, deliberately no overall healthy/playable flag. */
data class InstanceDiagnosticReport(
    val instanceUrl: String,
    val discovery: EndpointDiagnostic,
    val streamMetadata: EndpointDiagnostic,
)

/** Explicit, one-shot diagnostics for the caller's approved API instance.
 *
 * Construction is quiet. Call check only after endpoint consent and an explicit
 * diagnostic action. The caller owns cancellation and client lifetime. Uses the
 * existing client's timeouts, redirect refusal and public unauthenticated reads.
 * No selected-video guess, automatic retry, fallback host, media/image download,
 * background work or persistence. Not wired to the TV settings UI yet.
 */
class InstanceDiagnostics(private val client: PipedApiClient) {
    /** Tests discovery and, only when supplied by the caller, one video's
     * metadata independently. A discovery failure does not skip that explicit
     * sample. Empty decoded data is a response, not proof of useful content. */
    suspend fun check(region: String, videoId: String? = null): InstanceDiagnosticReport {
        require(region.isNotBlank()) { "region must not be blank" }
        require(videoId == null || (videoId.isNotBlank() && videoId != "." && videoId != "..")) {
            "video id must be a nonblank path segment"
        }
        val discovery = diagnose { client.trending(region) }
        val metadata = if (videoId == null) EndpointDiagnostic.NotChecked
            else diagnose { client.streams(videoId) }
        return InstanceDiagnosticReport(client.baseUrl, discovery, metadata)
    }

    private suspend fun diagnose(request: suspend () -> Unit): EndpointDiagnostic {
        currentCoroutineContext().ensureActive()
        return try {
            request()
            currentCoroutineContext().ensureActive()
            EndpointDiagnostic.Responded
        } catch (error: PipedError) {
            currentCoroutineContext().ensureActive()
            EndpointDiagnostic.Failed(error)
        }
    }
}
