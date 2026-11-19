package fi.pipedchild.core.player

import fi.pipedchild.core.model.SponsorSegment
import org.junit.Assert.*
import org.junit.Test

class SponsorSkipEngineTest {
    private fun segment(start: Double, end: Double, category: String = "sponsor", action: String? = "skip") =
        SponsorSegment(listOf(start, end), category = category, actionType = action)

    @Test fun `segment is start-inclusive end-exclusive and skips only once`() {
        val engine = SponsorSkipEngine(listOf(segment(10.0, 20.0)))
        assertNull(engine.targetMs(9999, 60000))
        assertEquals(20000L, engine.targetMs(10000, 60000))
        assertNull(engine.targetMs(12000, 60000))
        assertNull(engine.targetMs(20000, 60000))
        assertNull(engine.targetMs(10000, 60000)) // A deliberate rewind is respected.
    }

    @Test fun `overlap and touching segments become one deterministic skip`() {
        val engine = SponsorSkipEngine(listOf(segment(18.0, 30.0), segment(10.0, 20.0), segment(30.0, 35.0)))
        assertEquals(35000L, engine.targetMs(15000, 60000))
        assertNull(engine.targetMs(25000, 60000))
    }

    @Test fun `malformed timestamps actions and unselected categories never seek`() {
        val invalid = listOf(segment(-1.0, 10.0), segment(20.0, 10.0), segment(10.0, 10.0),
            segment(Double.NaN, 10.0), segment(10.0, Double.POSITIVE_INFINITY),
            segment(10.0, 20.0, action = "mute"), segment(10.0, 20.0, category = "intro"),
            SponsorSegment(listOf(10.0), category = "sponsor"),
            SponsorSegment(listOf(10.0, 20.0, 30.0), category = "sponsor"))
        assertNull(SponsorSkipEngine(invalid).targetMs(15000, 60000))
    }

    @Test fun `unknown duration and out of range positions do not consume skip`() {
        val engine = SponsorSkipEngine(listOf(segment(10.0, 80.0)))
        assertNull(engine.targetMs(15000, -1))
        assertNull(engine.targetMs(-1, 60000))
        assertNull(engine.targetMs(60000, 60000))
        assertEquals(60000L, engine.targetMs(15000, 60000))
    }

    @Test fun `whole-video and implausibly huge intervals are ignored`() {
        assertNull(SponsorSkipEngine(listOf(segment(0.0, 60.0))).targetMs(1000, 60000))
        assertNull(SponsorSkipEngine(listOf(segment(1.0, Double.MAX_VALUE))).targetMs(1000, 60000))
    }

    @Test fun `default categories match web and explicit empty set skips nothing`() {
        assertEquals(setOf("sponsor", "interaction", "selfpromo", "music_offtopic"), SponsorSkipEngine.DEFAULT_CATEGORIES)
        assertNull(SponsorSkipEngine(listOf(segment(10.0, 20.0)), emptySet()).targetMs(15000, 60000))
        assertEquals(20000L, SponsorSkipEngine(listOf(segment(10.0, 20.0, action = null))).targetMs(15000, 60000))
    }
}
