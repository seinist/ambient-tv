package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class SegmentSkipPolicyTest {
    private val intro = PlaybackSegment("intro", 10_000, 40_000)
    private val credits = PlaybackSegment("credits", 80_000, 90_000)
    @Test fun boundariesExcludeTheEndAndPreservePostCreditScenes() {
        val policy = SegmentSkipPolicy()
        val segments = listOf(intro, credits)
        assertNull(policy.active(segments, 9_999))
        assertEquals(intro, policy.active(segments, 10_000))
        assertNull(policy.active(segments, 40_000))
        assertEquals(credits, policy.active(segments, 89_999))
        assertNull(policy.active(segments, 90_000))
    }
    @Test fun pausedPlaybackDoesNotConsumeTheAutomaticAction() {
        val policy = SegmentSkipPolicy()
        assertNull(policy.automaticTarget(listOf(intro), 20_000, false))
        assertEquals(intro, policy.automaticTarget(listOf(intro), 20_000, true))
        assertNull(policy.automaticTarget(listOf(intro), 20_500, true))
    }
    @Test fun RewindingIntoAnIntroNeverTriggersAnAutomaticSkip() {
        val policy = SegmentSkipPolicy()
        policy.automaticTarget(listOf(intro), 60_000, true)
        assertNull(policy.automaticTarget(listOf(intro), 15_000, true))
        assertEquals(intro, policy.active(listOf(intro), 15_000))
    }
    @Test fun rewindingBeforeIntroAlsoSuppressesAutomaticSkip() {
        val policy = SegmentSkipPolicy()
        policy.automaticTarget(listOf(intro), 60_000, true)
        policy.automaticTarget(listOf(intro), 0, true)
        assertNull(policy.automaticTarget(listOf(intro), 10_000, true))
    }
    @Test fun introAndCreditsAreHandledIndependently() {
        val policy = SegmentSkipPolicy()
        val segments = listOf(intro, credits)
        assertEquals(intro, policy.automaticTarget(segments, 15_000, true))
        assertEquals(credits, policy.automaticTarget(segments, 81_000, true))
        assertNull(policy.automaticTarget(segments, 81_500, true))
    }
    @Test fun invalidAndOutOfRangeMarkersAreNotActionable() {
        val invalid = listOf(intro.copy(startMs = -1), intro.copy(endMs = 10_000),
            intro.copy(endMs = 200_000), intro.copy(type = "commercial"), intro.copy(endMs = -1))
        assertEquals(listOf(intro, credits), normalizePlaybackSegments(invalid + credits + intro + intro, 100_000))
    }
    @Test fun lastSegmentNeverSeeksToTheExactEndOrBackwards() {
        assertEquals(98_500L, safeSegmentSkipTarget(100_000L, 80_000L, 100_000L))
        assertEquals(99_000L, safeSegmentSkipTarget(99_000L, 80_000L, 100_000L))
        assertNull(safeSegmentSkipTarget(100_000L, 99_300L, 100_000L))
        assertNull(safeSegmentSkipTarget(-1L, 90_000L, 100_000L))
        assertEquals(40_000L, safeSegmentSkipTarget(40_000L, 12_000L, 100_000L))
    }

    @Test fun sponsorSegmentsAreAutoHandledOncePerPlayback() {
        val sponsor = PlaybackSegment("sponsor", 80_000L, 100_000L)
        val policy = SegmentSkipPolicy()
        assertNull(policy.automaticTarget(listOf(sponsor), 80_500L, false))
        assertEquals(sponsor, policy.automaticTarget(listOf(sponsor), 80_500L, true))
        assertNull(policy.automaticTarget(listOf(sponsor), 81_000L, true))
        assertEquals(listOf(sponsor), normalizePlaybackSegments(listOf(sponsor), 100_000L))
    }

    @Test fun embyMarkersPairWithoutRelyingOnChapterNames() {
        val segments = playbackSegmentsFromMarkers(listOf("IntroEnd" to 40_000L, "Chapter" to 0L,
            "IntroStart" to 10_000L, "CreditsStart" to 80_000L, "IntroStart" to 95_000L))
        assertEquals(listOf(intro, credits.copy(endMs = -1)), segments)
        assertEquals(segments, normalizePlaybackSegments(segments, 100_000))
    }
    @Test fun missingIntroStartDoesNotInventASegment() {
        assertTrue(playbackSegmentsFromMarkers(listOf("IntroEnd" to 40_000L)).isEmpty())
    }
    @Test fun openEndedCreditsRemainExplicitlyOpenEnded() {
        val open = credits.copy(endMs = -1)
        assertEquals(open, SegmentSkipPolicy().active(listOf(open), 99_000))
        assertEquals(-1L, normalizePlaybackSegments(listOf(open), 100_000).single().endMs)
    }
}
