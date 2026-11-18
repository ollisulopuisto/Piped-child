package fi.pipedchild.core.model

/**
 * Maps HTTP status + error body to the spec §10 taxonomy. Shared by the
 * Ktor client (Android) and mirrored by the Swift client.
 */
object PipedErrorMapper {

    private val extractionSignals = listOf(
        "LOGIN_REQUIRED",
        "not a bot",
        "Sign in to confirm",
        "unable to extract",
        "Could not get",
    )

    fun fromHttp(status: Int, body: ApiErrorBody?): PipedError {
        val message = body?.message ?: body?.error ?: ""
        return when {
            status == 401 || status == 403 -> PipedError.AuthenticationFailure
            status == 404 -> PipedError.VideoUnavailable
            status == 429 -> PipedError.RateLimited
            status >= 500 && extractionSignals.any { message.contains(it, ignoreCase = true) } ->
                PipedError.ExtractionFailure(message)
            status >= 500 -> PipedError.InstanceUnavailable(status)
            else -> PipedError.InstanceUnavailable(status)
        }
    }
}
