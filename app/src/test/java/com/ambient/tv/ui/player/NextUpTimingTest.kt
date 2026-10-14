package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.PlaybackSegment
import org.junit.Assert.*
import org.junit.Test

class NextUpTimingTest {
    private val credits = PlaybackSegment("credits", 80_000, 90_000)
    private fun at(position: Long, segments: List<PlaybackSegment> = listOf(credits), enabled: Boolean = true) =
        nextUpTiming(position, 120_000, segments, enabled, 30)
    @Test fun creditStartShowsNextUpBeforeTheUsualLeadTime() {
        assertFalse(at(79_999).offer)
        assertTrue(at(80_000).offer)
        assertEquals(40_000L, at(80_000).remainingMs)
        assertEquals(40, at(80_000).progressWindowSeconds)
    }
    @Test fun postCreditSceneHidesThePreviewEvenInsideNormalLeadTime() {
        assertTrue(at(89_999).offer)
        assertFalse(at(90_000).offer)
        assertFalse(at(110_000).offer)
        assertNull(at(110_000).credits)
    }
    @Test fun aSecondCreditsSectionCanOfferNextUpAgain() {
        val segments = listOf(credits, PlaybackSegment("credits", 115_000, 120_000))
        assertFalse(at(110_000, segments).offer)
        assertTrue(at(115_000, segments).offer)
    }
    @Test fun absentOrDisabledMarkersUseTheConfiguredLeadTime() {
        assertFalse(at(89_999, emptyList()).offer)
        assertTrue(at(90_000, emptyList()).offer)
        assertFalse(at(80_000, enabled = false).offer)
        assertTrue(at(90_000, enabled = false).offer)
    }
    @Test fun openEndedCreditsKeepTheActualEndAsCountdownDeadline() {
        val result = at(80_000, listOf(credits.copy(endMs = -1)))
        assertTrue(result.offer)
        assertEquals(40_000L, result.remainingMs)
    }
    @Test fun transcodeClockIsAbsoluteRatherThanRelativeToItsStreamStart() {
        val result = nextUpTiming(5_000 + 75_000, 120_000, listOf(credits), true, 30)
        assertTrue(result.offer)
        assertEquals(40_000L, result.remainingMs)
    }
    @Test fun introMarkersNeverChangeNextUpTiming() {
        assertFalse(at(80_000, listOf(PlaybackSegment("intro", 0, 10_000))).offer)
    }
}
