package com.ambient.tv.ui.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLiveDvrWindowTest {
    @Test fun liveFlagByItselfNeverEnablesSeeking() {
        assertNull(youtubeLiveDvrWindow(isLive = true, seekable = false, durationMs = 180_000L))
        assertNull(youtubeLiveDvrWindow(isLive = true, seekable = true, durationMs = 0L))
        assertNull(youtubeLiveDvrWindow(isLive = true, seekable = true, durationMs = -1L))
        assertNull(youtubeLiveDvrWindow(isLive = false, seekable = true, durationMs = 180_000L))
    }

    @Test fun media3DefaultLivePositionWinsOverManifestTail() {
        val window = youtubeLiveDvrWindow(
            isLive = true, seekable = true, durationMs = 120_000L,
            livePositionMs = 110_000L,
        )!!
        assertEquals(110_000L, window.livePositionMs)
        assertEquals(30_000L, window.behindLiveMs(80_000L))
        assertTrue(window.canJumpToLive(80_000L))
        assertFalse(window.canJumpToLive(109_000L))
        assertFalse(window.canJumpToLive(110_000L))
        assertTrue(window.targetsLiveEdge(109_000L))
        assertFalse(window.targetsLiveEdge(108_999L))
    }

    @Test fun nativeTimelineCanUseItsReportedSeekableDuration() {
        val window = youtubeLiveDvrWindow(isLive = true, seekable = true, durationMs = 180_000L)!!
        assertEquals(180_000L, window.livePositionMs)
        assertTrue(window.canJumpToLive(160_000L))
        assertEquals(0L, window.behindLiveMs(200_000L))
        assertTrue(window.targetsLiveEdge(180_000L))
        assertFalse(window.targetsLiveEdge(170_000L))
    }

    @Test fun invalidMedia3LiveEdgeFallsBackToWindowEnd() {
        val window = youtubeLiveDvrWindow(isLive = true, seekable = true,
            durationMs = 120_000L, livePositionMs = -1L)!!
        assertEquals(120_000L, window.livePositionMs)
        val clamped = youtubeLiveDvrWindow(isLive = true, seekable = true,
            durationMs = 120_000L, livePositionMs = 130_000L)!!
        assertEquals(120_000L, clamped.livePositionMs)
    }
}
