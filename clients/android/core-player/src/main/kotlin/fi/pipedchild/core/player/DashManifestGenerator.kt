package fi.pipedchild.core.player

import fi.pipedchild.core.model.PipedStream

/**
 * Faithful Kotlin port of the web client's `src/utils/DashUtils.js` (itself
 * based on GilgusMaximus/yt-dash-manifest-generator), which Piped's web
 * frontend uses to build a DASH MPD client-side when an instance's `/streams`
 * response has adaptive formats but no server-side `dash` URL.
 *
 * Behavioural contract (matching the JS, verified by unit tests against
 * Media3's DashManifestParser):
 *  - Progressive/muxed streams (video mimeType, videoOnly == false) and
 *    application-mime streams (storyboards) are EXCLUDED.
 *  - Remaining formats are grouped into AdaptationSets keyed by
 *    (audioTrackId, mimeType), so per-language audio tracks stay separate.
 *  - MPD envelope: isoff full profile, type="static", minBufferTime 1.5s,
 *    mediaPresentationDuration = PT{duration}S.
 *  - Video representations carry width/height/maxPlayoutRate/frameRate;
 *    audio representations carry the 2-channel AudioChannelConfiguration.
 *  - Every representation uses BaseURL + SegmentBase with the Piped init/
 *    index ranges (isoff-on-demand style).
 *
 * Deviation from the JS: the JS emits `AdaptationSet id = audioTrackId`, but
 * DASH restricts AdaptationSet@id to unsignedInt — shaka tolerates a string
 * like "en-US.1", Media3's parser rejects it. The port omits the id;
 * grouping is unaffected. Verified against the real DashUtils.js output by
 * DashUtilsGoldenTest (clients/contracts/golden/).
 */
object DashManifestGenerator {

    private class AdaptationSet(
        val audioTrackId: String?,
        val mimeType: String,
        val isVideo: Boolean,
        val representations: MutableList<PipedStream> = mutableListOf(),
    )

    /** Build the full MPD document. [formats] is audioStreams + videoStreams
     *  (web order). Returns an MPD with zero AdaptationSets when no adaptive
     *  formats exist; callers must treat that as "no DASH possible". */
    fun generate(formats: List<PipedStream>, durationSeconds: Long): String {
        val sets = generateAdaptationSets(formats)
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"")
            append(" profiles=\"urn:mpeg:dash:profile:full:2011\"")
            append(" minBufferTime=\"PT1.5S\"")
            append(" type=\"static\"")
            append(" mediaPresentationDuration=\"PT").append(durationSeconds).append("S\">")
            append("<Period>")
            sets.forEach { appendAdaptationSet(it) }
            append("</Period></MPD>")
        }
    }

    /** True when [formats] contains at least one adaptive (non-muxed,
     *  non-application) stream, i.e. when [generate] yields a usable MPD. */
    fun hasAdaptiveFormats(formats: List<PipedStream>): Boolean =
        formats.any { isAdaptive(it) }

    private fun isAdaptive(f: PipedStream): Boolean {
        val mime = f.mimeType ?: return true // JS: undefined mimeType never matches the skip filters
        if (mime.contains("video") && !f.videoOnly) return false
        if (mime.contains("application")) return false
        return true
    }

    private fun generateAdaptationSets(formats: List<PipedStream>): List<AdaptationSet> {
        val sets = linkedMapOf<Pair<String?, String>, AdaptationSet>()
        for (f in formats) {
            if (!isAdaptive(f)) continue
            val mime = f.mimeType ?: ""
            val key = f.audioTrackId to mime
            val set = sets.getOrPut(key) {
                AdaptationSet(
                    audioTrackId = f.audioTrackId,
                    mimeType = mime,
                    isVideo = mime.contains("video"),
                )
            }
            set.representations.add(f)
        }
        return sets.values.toList()
    }

    private fun StringBuilder.appendAdaptationSet(set: AdaptationSet) {
        append("<AdaptationSet")
        // JS emits id = audioTrackId, but DASH restricts AdaptationSet@id to
        // unsignedInt: shaka tolerates a string like "en-US.1", Media3's
        // parser rejects it. Omit the id; grouping is unaffected.
        // JS: _lang = audioTrackId?.substr(0, 2)
        set.audioTrackId?.let {
            if (it.length >= 2) append(" lang=\"").append(esc(it.substring(0, 2))).append("\"")
        }
        append(" mimeType=\"").append(esc(set.mimeType)).append("\"")
        append(" startWithSAP=\"1\" subsegmentAlignment=\"true\"")
        if (set.isVideo) append(" scanType=\"progressive\"")
        append(">")
        for (f in set.representations) {
            if (set.isVideo) appendVideoRepresentation(f) else appendAudioRepresentation(f)
        }
        append("</AdaptationSet>")
    }

    private fun StringBuilder.appendAudioRepresentation(f: PipedStream) {
        append("<Representation")
        appendIdCodecsBandwidth(f)
        append(">")
        append("<AudioChannelConfiguration")
        append(" schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\"")
        append(" value=\"2\"/>")
        appendBaseUrlAndSegmentBase(f)
        append("</Representation>")
    }

    private fun StringBuilder.appendVideoRepresentation(f: PipedStream) {
        append("<Representation")
        appendIdCodecsBandwidth(f)
        append(" width=\"").append(f.width).append("\"")
        append(" height=\"").append(f.height).append("\"")
        // JS emits maxPlayoutRate="1" and NO sar — matched verbatim.
        append(" maxPlayoutRate=\"1\"")
        append(" frameRate=\"").append(f.fps).append("\">")
        appendBaseUrlAndSegmentBase(f)
        append("</Representation>")
    }

    private fun StringBuilder.appendIdCodecsBandwidth(f: PipedStream) {
        f.itag?.let { append(" id=\"").append(it).append("\"") }
        f.codec?.let { append(" codecs=\"").append(esc(it)).append("\"") }
        append(" bandwidth=\"").append(f.bitrate).append("\"")
    }

    private fun StringBuilder.appendBaseUrlAndSegmentBase(f: PipedStream) {
        append("<BaseURL>").append(esc(f.url)).append("</BaseURL>")
        append("<SegmentBase indexRange=\"").append(f.indexStart).append("-").append(f.indexEnd).append("\">")
        append("<Initialization range=\"").append(f.initStart).append("-").append(f.initEnd).append("\"/>")
        append("</SegmentBase>")
    }

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}
