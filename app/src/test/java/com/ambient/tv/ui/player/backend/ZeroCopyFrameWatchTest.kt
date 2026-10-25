package com.ambient.tv.ui.player.backend

import org.junit.Assert.*
import org.junit.Test

/** Audit 25.10. M3/M5: zero-copy evidence only while playing; SDR curve on uncertain HDR mode. */
class ZeroCopyFrameWatchTest {
    private val s = 1_000_000_000L

    @Test fun bufferingNeverCountsAndEndsWithoutRemembering() {
        val watch = ZeroCopyFrameWatch()
        assertEquals(ZeroCopyNoFrames.Wait, watch.poll(1 * s, buffering = true, positionMs = 0))
        // Paused for cache while the clock advances is still no evidence.
        for (t in 2..10) assertEquals(ZeroCopyNoFrames.Wait, watch.poll(t * s, buffering = true, positionMs = t * 1_000L))
        assertEquals(ZeroCopyNoFrames.Fail(remember = false), watch.poll(11 * s, buffering = true, positionMs = 0))
    }

    @Test fun threeSecondsOfRealPlaybackAreRememberedEvidence() {
        val watch = ZeroCopyFrameWatch()
        watch.poll(0 + 1, buffering = false, positionMs = 0)
        assertEquals(ZeroCopyNoFrames.Wait, watch.poll(1 * s, false, 1_000))
        assertEquals(ZeroCopyNoFrames.Wait, watch.poll(2 * s, false, 2_000))
        assertEquals(ZeroCopyNoFrames.Fail(remember = true), watch.poll(3 * s + 1, false, 3_000))
    }

    @Test fun aStalledClockDoesNotCountAndResetStartsOver() {
        val watch = ZeroCopyFrameWatch()
        watch.poll(1, false, 500)
        // Not paused for cache, but time-pos does not advance (stuck demuxer).
        assertEquals(ZeroCopyNoFrames.Wait, watch.poll(5 * s, false, 500))
        assertEquals(ZeroCopyNoFrames.Wait, watch.poll(9 * s, false, 500))
        watch.reset()
        assertEquals(ZeroCopyNoFrames.Wait, watch.poll(20 * s, false, 500))
        assertEquals(ZeroCopyNoFrames.Wait, watch.poll(29 * s, false, 500))
    }

    @Test fun autoCurveUsesGamma22OnlyOnAConfirmedHdrMode() {
        // No ratio (below Android 14): unknown, Auto stays BT.1886.
        assertFalse(mpvSdrAutoDisplayInHdr(null, reliable = true))
        assertFalse(mpvSdrAutoDisplayInHdr(1f, reliable = false))
        assertFalse(mpvSdrAutoDisplayInHdr(1f, reliable = true))
        assertTrue(mpvSdrAutoDisplayInHdr(4f, reliable = true))
    }

    @Test fun reevaluatedCurveOnlyReplacesTheCurveTheAppSet() {
        assertTrue(mpvSdrCurveNeedsUpdate("bt.1886", "gamma2.2", "bt.1886"))
        assertTrue(mpvSdrCurveNeedsUpdate("bt.1886", "gamma2.2", null))
        assertFalse(mpvSdrCurveNeedsUpdate("bt.1886", "bt.1886", "bt.1886"))
        // Something else (HDR output, user property) owns target-trc now: leave it.
        assertFalse(mpvSdrCurveNeedsUpdate("bt.1886", "gamma2.2", "pq"))
    }
}
