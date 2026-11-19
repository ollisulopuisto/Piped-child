package fi.pipedchild.core.player

import fi.pipedchild.core.model.PipedError
import fi.pipedchild.core.model.PipedStream
import fi.pipedchild.core.model.PlaybackSelector
import fi.pipedchild.core.model.StreamsResponse

/** Media3 inputs, after privacy filtering and shared source selection. */
sealed interface PlaybackChoice {
    data class DashUrl(val url: String) : PlaybackChoice
    data class DashManifest(val mpd: String) : PlaybackChoice
    data class Hls(val url: String) : PlaybackChoice
    data class Progressive(val url: String) : PlaybackChoice
}

/** Privacy adapter around the existing cross-platform [PlaybackSelector].
 * Only exact-origin HTTPS proxy URLs and googlevideo URLs rewritable through
 * that proxy are candidates. Direct LBRY/CDN streams are excluded.
 *
 * A generated VOD MPD must contain both known audio and video formats. If
 * privacy filtering removes one track, fall back to muxed video instead of
 * presenting an audio-only or silent player. Unknown-MIME progressive
 * entries with the verified MPEG_4 format remain eligible. */
object StreamPicker {
    private fun covered(url: String, proxyBase: String): Boolean =
        ProxyRewriter.resolve(url, proxyBase, null) is ProxyRewriter.Result.Open

    fun pick(response: StreamsResponse, preferHls: Boolean = false): PlaybackChoice? {
        val proxy = response.proxyUrl?.let(ProxyRewriter::validProxyBase) ?: return null
        val audio = response.audioStreams.filter {
            covered(it.url, proxy) && it.mimeType?.startsWith("audio/") == true
        }
        fun PipedStream.adaptiveVideo() = videoOnly && !codec.isNullOrBlank() && mimeType?.startsWith("video/") == true
        fun PipedStream.progressive() = !videoOnly && codec == null &&
            (mimeType == "video/mp4" || format == "MPEG_4")
        val video = response.videoStreams.filter {
            covered(it.url, proxy) && (it.adaptiveVideo() || it.progressive())
        }
        val hasVideo = video.any { it.adaptiveVideo() }
        val filtered = response.copy(
            dash = response.dash?.takeIf { covered(it, proxy) },
            hls = response.hls?.takeIf { covered(it, proxy) },
            audioStreams = if (hasVideo) audio else emptyList(),
            videoStreams = video,
        )
        return try {
            when (val source = PlaybackSelector.select(filtered, PlaybackSelector.Platform.ANDROID, preferHls)) {
                is PlaybackSelector.Source.Dash -> PlaybackChoice.DashUrl(source.manifestUrl)
                is PlaybackSelector.Source.GeneratedDash -> PlaybackChoice.DashManifest(
                    DashManifestGenerator.generate(source.formats, source.durationSeconds),
                )
                is PlaybackSelector.Source.Hls -> PlaybackChoice.Hls(source.manifestUrl)
                is PlaybackSelector.Source.Progressive -> PlaybackChoice.Progressive(source.stream.url)
            }
        } catch (e: PipedError.PlaybackFormatUnsupported) {
            null
        }
    }
}
