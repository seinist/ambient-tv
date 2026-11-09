package com.ambient.tv.ui.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLiveDvrWindowTest {
    @Test fun liveFlagOrStaleProgressAloneNeverEnablesDvr() {
        assertNull(youtubeLiveDvrWindow(isLive = true, seekable = false, durationMs = 180_000L))
        assertNull(youtubeLiveDvrWindow(isLive = true, seekable = true, durationMs = 0L))
        assertNull(youtubeLiveDvrWindow(isLive = true, seekable = true, durationMs = -1L))
        assertNull(youtubeLiveDvrWindow(isLive = true, seekable = true, durationMs = Long.MAX_VALUE))
        assertNull(youtubeLiveDvrWindow(isLive = false, seekable = true, durationMs = 180_000L))
    }

    @Test fun defaultLivePositionIsNotTheEndOfTheSeekbar() {
        val window = youtubeLiveDvrWindow(
            isLive = true, seekable = true, durationMs = 120_000L,
            livePositionMs = 110_000L,
        )!!
        assertEquals(120_000L, window.durationMs) // not defaultPositionMs / saved resume
        assertEquals(0L, window.decoderPositionMs(0))
        assertEquals(120_000L, window.decoderPositionMs(window.durationMs))
        assertEquals(110_000L, window.livePositionMs)
        assertEquals(30_000L, window.behindLiveMs(80_000L))
        assertTrue(window.canJumpToLive(80_000L))
        assertFalse(window.canJumpToLive(109_000L))
        assertFalse(window.canJumpToLive(110_000L))
        // 109s is near the safe default, NOT at the real end of the manifest.
        assertFalse(window.targetsLiveEdge(109_000L))
        assertTrue(window.targetsLiveEdge(119_000L))
        assertFalse(window.targetsLiveEdge(118_999L))
    }

    @Test fun refreshedManifestMovesTheRightEdgeWithoutUsingWatchProgressAsDuration() {
        val previous = youtubeLiveDvrWindow(true, true, durationMs = 120_000L, livePositionMs = 110_000L)!!
        val updated = youtubeLiveDvrWindow(true, true, durationMs = 145_000L, livePositionMs = 130_000L)!!
        assertEquals(120_000L, previous.durationMs)
        assertEquals(145_000L, updated.durationMs)
        assertEquals(0L, updated.displayPositionMs(0L))
        assertEquals(145_000L, updated.decoderPositionMs(updated.durationMs))
        assertEquals(45_000L, updated.displayPositionMs(45_000L))
        assertEquals(25_000L, updated.behindLiveMs(105_000L))
        assertTrue(updated.targetsLiveEdge(145_000L))
        assertTrue(previous.targetsLiveEdge(130_000L)) // beyond old end also reaches live
    }

    @Test fun upstreamAbsoluteSeekableStartIsExposedAsUiZero() {
        // SABR may have 90s of seekable content inside a much longer absolute broadcast.
        val window = youtubeLiveDvrWindow(
            true, true, durationMs = 10_090_000L,
            livePositionMs = 10_085_000L, windowStartMs = 10_000_000L,
        )!!
        assertEquals(90_000L, window.durationMs)
        assertEquals(0L, window.displayPositionMs(10_000_000L))
        assertEquals(5_000L, window.displayPositionMs(10_005_000L))
        assertEquals(10_000_000L, window.decoderPositionMs(0L))
        assertEquals(10_090_000L, window.decoderPositionMs(90_000L))
        assertEquals(10_000_000L, window.clampDecoderPositionMs(0L))
        assertEquals(10_090_000L, window.clampDecoderPositionMs(Long.MAX_VALUE))
        assertTrue(window.targetsWindowStart(0L))
        assertTrue(window.targetsLiveEdge(10_089_000L))
        assertFalse(window.targetsLiveEdge(10_085_000L))
    }

    @Test fun nativeWindowKeepsReportedDecoderDurationNotSavedPlaybackPoint() {
        val window = youtubeLiveDvrWindow(isLive = true, seekable = true, durationMs = 180_000L)!!
        assertEquals(180_000L, window.durationMs)
        assertEquals(180_000L, window.livePositionMs)
        assertTrue(window.canJumpToLive(160_000L))
        assertEquals(0L, window.behindLiveMs(200_000L))
        assertTrue(window.targetsLiveEdge(180_000L))
        assertFalse(window.targetsLiveEdge(170_000L))
    }

    @Test fun missingOrInvalidSafeLivePositionFallsBackToRealWindowEnd() {
        val missing = youtubeLiveDvrWindow(true, true, 120_000L, livePositionMs = -1L)!!
        val future = youtubeLiveDvrWindow(true, true, 120_000L, livePositionMs = 130_000L)!!
        val start = youtubeLiveDvrWindow(true, true, 120_000L, livePositionMs = 0L)!!
        assertEquals(120_000L, missing.livePositionMs)
        assertEquals(120_000L, future.livePositionMs)
        assertEquals(120_000L, start.livePositionMs)
        assertNull(youtubeLiveDvrWindow(true, true, 120_000L, windowStartMs = 120_000L))
    }
}
