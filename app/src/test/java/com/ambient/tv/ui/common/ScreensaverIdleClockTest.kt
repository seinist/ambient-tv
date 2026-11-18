package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverIdleClockTest {
    private val timeout = 10 * 60_000L

    @Test fun withoutPlaybackTheWaitFollowsTheLastInput() {
        val clock = ScreensaverIdleClock(playbackActive = false)
        clock.observe(false, 1_000L)
        assertEquals(timeout - 5_000L, clock.remainingMs(lastInteractionMs = 0L, nowMs = 5_000L, timeoutMs = timeout))
        assertTrue(clock.remainingMs(0L, timeout, timeout) <= 0L)
    }

    @Test fun aBriefReloadAfterLongViewingDoesNotStartItAtOnce() {
        // Last key press long ago; playback ran for an hour, then a reload makes it inactive.
        val clock = ScreensaverIdleClock(playbackActive = true)
        val reloadAt = 60 * 60_000L
        clock.observe(false, reloadAt)
        assertEquals(timeout, clock.remainingMs(lastInteractionMs = 0L, nowMs = reloadAt, timeoutMs = timeout))
        // Playback resumes: nothing is due while it is active, and the next gap waits again.
        clock.observe(true, reloadAt + 2_000L)
        clock.observe(false, reloadAt + 30 * 60_000L)
        assertEquals(timeout, clock.remainingMs(0L, reloadAt + 30 * 60_000L, timeout))
    }

    @Test fun laterInputStillWinsOverAnEarlierPlaybackEnd() {
        val clock = ScreensaverIdleClock(playbackActive = true)
        clock.observe(false, 1_000L)
        assertEquals(5_000L, clock.idleSince(lastInteractionMs = 5_000L))
        assertEquals(timeout - 1_000L, clock.remainingMs(5_000L, 6_000L, timeout))
    }

    @Test fun repeatedObservationsWithoutATransitionChangeNothing() {
        val clock = ScreensaverIdleClock(playbackActive = false)
        clock.observe(false, 50_000L)
        clock.observe(false, 90_000L)
        assertEquals(0L, clock.idleSince(lastInteractionMs = 0L))
    }
}
