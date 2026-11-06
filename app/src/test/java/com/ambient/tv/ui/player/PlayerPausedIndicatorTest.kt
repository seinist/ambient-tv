package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPausedIndicatorTest {
    @Test fun pauseBadgeMotionRemainsVisibleInSparsam() {
        assertEquals(180, pausedIndicatorMotionMs(reducedEffects = false))
        assertEquals(120, pausedIndicatorMotionMs(reducedEffects = true))
    }

    @Test fun explicitPauseShowsStatus() {
        assertTrue(shouldShowPlayerPaused(playRequested = false))
    }

    @Test fun waitingForPlaybackOrPlayingDoesNotLookPaused() {
        assertFalse(shouldShowPlayerPaused(playRequested = true))
    }

    @Test fun idleFinishedAndChangingItemsDoNotLookPaused() {
        assertFalse(shouldShowPlayerPaused(playRequested = false, active = false))
        assertFalse(shouldShowPlayerPaused(playRequested = false, ended = true))
        assertFalse(shouldShowPlayerPaused(playRequested = false, changingItem = true))
    }

    @Test fun resumingClearsPauseStatus() {
        assertTrue(shouldShowPlayerPaused(playRequested = false, active = true))
        assertFalse(shouldShowPlayerPaused(playRequested = true, active = true))
    }
}
