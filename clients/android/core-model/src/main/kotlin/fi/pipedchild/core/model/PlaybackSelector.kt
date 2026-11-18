package fi.pipedchild.core.model

/**
 * Error taxonomy per handoff spec §10. Every API and playback failure maps to
 * exactly one of these; UI surfaces an actionable state, never a crash.
 */
sealed class PipedError : Exception() {
    /** No connectivity at all. */
    object NetworkUnavailable : PipedError()

    /** Instance reachable but 5xx / timeout. */
    data class InstanceUnavailable(val httpStatus: Int) : PipedError()

    /** 500 from /streams with bot-block/extraction message. Instance is
     *  blocked by YouTube; retrying another instance is the remedy. */
    data class ExtractionFailure(val serverMessage: String) : PipedError()

    /** 401: token expired or invalid; user must log in again. */
    object AuthenticationFailure : PipedError()

    /** 404: video/channel/playlist gone or region-blocked. */
    object VideoUnavailable : PipedError()

    /** Response parsed but no playable format for this platform. */
    object PlaybackFormatUnsupported : PipedError()

    /** 429. */
    object RateLimited : PipedError()

    /** Body did not match the contract. */
    data class MalformedResponse(val detail: String) : PipedError()
}

/**
 * Pure playback-source selection, mirrored by the iOS implementation and
 * tested against shared fixtures.
 *
 * The Android branch mirrors the web client's selection order in
 * src/components/VideoPlayer.vue (function createPlayer, lines ~605-675),
 * with the disabled LBRY branch omitted:
 *
 *   1. livestream            -> HLS
 *   2. !preferHls && audioStreams nonempty
 *                            -> DASH: server `dash` URL with `rewrite=false`
 *                               (the web client sets that param ONLY in this
 *                               branch) when present, else client-generated
 *                               MPD (DashManifestGenerator port of
 *                               DashUtils.js). Note: client-side generation
 *                               cannot compensate for progressive-only
 *                               responses — DashUtils excludes muxed streams,
 *                               so the MPD would be empty.
 *   3. !preferHls && `dash`  -> server DASH URL, passed through UNCHANGED
 *                               (the web client does not touch it here)
 *   4. `hls`                 -> HLS
 *   5. progressive MP4       -> the LAST stream with codec == null, exactly
 *                               as the web client's findLast picks it
 *
 * iOS (AVPlayer, no DASH support): `hls` field > HLS manifests inside
 * videoStreams (LBRY HLS entries) > progressive MP4 > error. The middle step
 * deviates from the web client (which has no AVPlayer constraint and picks
 * progressive there); it exists because a muxed HLS manifest is playable on
 * AVPlayer and strictly better than 360p. Extraction failures and degraded
 * payloads degrade to progressive, never to a crash.
 */
object PlaybackSelector {

    enum class Platform { ANDROID, IOS }

    sealed class Source {
        /** Server-provided DASH manifest. `rewrite=false` is applied exactly
         *  where the web client applies it (adaptive-audio branch only). */
        data class Dash(val manifestUrl: String) : Source()

        /** No usable server manifest: generate the MPD client-side from the
         *  adaptive streams, exactly as DashUtils.js does. [formats] is
         *  audioStreams + videoStreams in web order. */
        data class GeneratedDash(val formats: List<PipedStream>, val durationSeconds: Long) : Source()

        data class Hls(val manifestUrl: String) : Source()
        data class Progressive(val stream: PipedStream) : Source()
    }

    fun select(
        response: StreamsResponse,
        platform: Platform,
        preferHls: Boolean = false,
    ): Source {
        if (platform == Platform.ANDROID) {
            if (response.livestream) {
                response.hls?.takeIf { it.isNotBlank() }?.let { return Source.Hls(it) }
                throw PipedError.PlaybackFormatUnsupported
            }
            if (!preferHls) {
                if (response.audioStreams.isNotEmpty()) {
                    response.dash?.takeIf { it.isNotBlank() }?.let {
                        return Source.Dash(withRewriteFalse(it))
                    }
                    return Source.GeneratedDash(
                        formats = response.audioStreams + response.videoStreams,
                        durationSeconds = response.duration,
                    )
                }
                response.dash?.takeIf { it.isNotBlank() }?.let { return Source.Dash(it) }
            }
        }
        response.hls?.takeIf { it.isNotBlank() }?.let { return Source.Hls(it) }
        if (platform == Platform.IOS) {
            // LBRY HLS fallback: AVPlayer plays HLS, so an m3u8 stream entry
            // beats progressive MP4 on iOS (documented deviation from web).
            response.videoStreams
                .firstOrNull { it.url.substringBefore('?').endsWith(".m3u8") }
                ?.let { return Source.Hls(it.url) }
        }
        bestProgressive(response)?.let { return Source.Progressive(it) }
        throw PipedError.PlaybackFormatUnsupported
    }

    /** The web client's progressive pick: the LAST stream with codec == null
     *  (VideoPlayer.vue `findLast(stream => stream.codec == null)`). Matched
     *  verbatim for fidelity, including on degraded payloads where every
     *  stream carries height 0. */
    fun bestProgressive(response: StreamsResponse): PipedStream? =
        response.videoStreams.lastOrNull { it.codec == null && it.url.isNotBlank() }

    /** Sets the `rewrite` query param to `false`, replacing an existing value
     *  (the web client unconditionally sets it). Unrelated params whose names
     *  merely contain "rewrite" and any fragment are preserved. */
    fun withRewriteFalse(dashUrl: String): String {
        val fragment = dashUrl.substringAfter('#', "")
        val withoutFragment = dashUrl.substringBefore('#')
        val queryStart = withoutFragment.indexOf('?')
        val rewritten = if (queryStart < 0) {
            "$withoutFragment?rewrite=false"
        } else {
            val path = withoutFragment.substring(0, queryStart)
            val params = withoutFragment.substring(queryStart + 1)
                .split('&')
                .map { if (it.substringBefore('=') == "rewrite") "rewrite=false" else it }
            val joined = params.joinToString("&")
            if (params.none { it == "rewrite=false" } && params.none { it.startsWith("rewrite=") }) {
                "$path?$joined&rewrite=false"
            } else {
                "$path?$joined"
            }
        }
        return if (fragment.isEmpty()) rewritten else "$rewritten#$fragment"
    }
}
