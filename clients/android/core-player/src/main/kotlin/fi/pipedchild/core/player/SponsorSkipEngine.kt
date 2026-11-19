package fi.pipedchild.core.player

import fi.pipedchild.core.model.SponsorSegment

/** Per-playback, once-only automatic skips. No networking or player dependency.
 * The default category set matches the web client's selectedSkip fallback.
 * Rewinding into an already skipped interval is deliberate and is respected.
 * Unknown/mute/full actions and malformed intervals are ignored, never guessed.
 */
class SponsorSkipEngine(
    segments: List<SponsorSegment>,
    categories: Set<String> = DEFAULT_CATEGORIES,
) {
    companion object {
        val DEFAULT_CATEGORIES: Set<String> = setOf("sponsor", "interaction", "selfpromo", "music_offtopic")
    }
    private data class Interval(val start: Long, val end: Long)
    private val intervals: List<Interval> = buildList {
        val valid = segments.mapNotNull { segment ->
            if (segment.category !in categories || segment.actionType !in listOf(null, "skip")) return@mapNotNull null
            if (segment.segment.size != 2) return@mapNotNull null
            val (start, end) = segment.segment
            if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start ||
                end >= Long.MAX_VALUE.toDouble() / 1000) return@mapNotNull null
            Interval((start * 1000).toLong(), (end * 1000).toLong()).takeIf { it.end > it.start }
        }.sortedBy { it.start }
        for (interval in valid) {
            val previous = lastOrNull()
            if (previous != null && interval.start <= previous.end) {
                removeAt(lastIndex)
                add(Interval(previous.start, maxOf(previous.end, interval.end)))
            } else add(interval)
        }
    }
    private val skipped = mutableSetOf<Int>()

    /** A seek target in milliseconds, or null. Unknown duration never seeks.
     * Whole-video intervals are ignored as a safety boundary; ends are clamped
     * to the actual player's duration, not a community-reported duration.
     * Call only for playing, seekable VOD, not live/DVR/ad timelines.
     */
    fun targetMs(positionMs: Long, durationMs: Long): Long? {
        if (durationMs <= 0 || positionMs < 0 || positionMs >= durationMs) return null
        val index = intervals.indexOfFirst { positionMs >= it.start && positionMs < it.end }
        if (index < 0 || index in skipped) return null
        val interval = intervals[index]
        if (interval.start == 0L && interval.end >= durationMs) return null
        skipped += index
        return minOf(interval.end, durationMs)
    }
}
