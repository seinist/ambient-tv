package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackSleepTimerTest {
    private val playing = PlaybackStatus(playing = true)
    private val paused = PlaybackStatus()

    @Test fun fixedDeadlineDoesNotMoveOnUserInputOrPlaybackChanges() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.DURATION, 15), 10_000)
        timer.userInput(850_000)
        assertFalse(timer.expired(909_999, paused, false))
        assertTrue(timer.expired(910_000, playing, true))
    }

    @Test fun inputTimerResetsOnInteractionEvenWhilePaused() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.USER_INACTIVITY, 30), 0)
        timer.userInput(1_700_000)
        assertFalse(timer.expired(1_800_000, paused, false))
        assertFalse(timer.expired(3_499_999, playing, true))
        assertTrue(timer.expired(3_500_000, playing, true))
    }

    @Test fun playbackUpdatesDoNotResetUserInactivity() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.USER_INACTIVITY, 30), 0)
        assertFalse(timer.expired(900_000, playing.copy(positionMs = 900_000), true))
        assertFalse(timer.expired(1_799_000, playing.copy(positionMs = 0), true))
        assertTrue(timer.expired(1_800_000, playing.copy(positionMs = 1_000), true))
    }

    @Test fun bufferingWithPlayIntentDoesNotCountAsPause() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.PAUSE_INACTIVITY, 10), 0)
        assertFalse(timer.expired(0, paused, true))
        assertFalse(timer.expired(900_000, paused, true))
        assertFalse(timer.expired(901_000, paused, false))
        assertTrue(timer.expired(1_501_000, paused, false))
    }

    @Test fun resumedPlaybackRestartsPauseTimeout() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.PAUSE_INACTIVITY, 10), 0)
        assertFalse(timer.expired(0, paused, false))
        assertFalse(timer.expired(500_000, playing, true))
        assertFalse(timer.expired(550_000, paused, false))
        assertFalse(timer.expired(600_000, paused, false))
        assertTrue(timer.expired(1_150_000, paused, false))
    }

    @Test fun inputDoesNotExtendPauseTimeout() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.PAUSE_INACTIVITY, 10), 0)
        timer.expired(0, paused, false)
        timer.userInput(599_000)
        assertTrue(timer.expired(600_000, paused, false))
    }

    @Test fun errorAndEndCountAsInactiveEvenWithStalePlayIntent() {
        for (status in listOf(paused.copy(error = "Offline"), paused.copy(ended = true))) {
            val timer = PlaybackSleepTimer()
            timer.arm(SleepTimerConfig(SleepTimerKind.PAUSE_INACTIVITY, 10), 0)
            assertFalse(timer.expired(0, status, true))
            assertTrue(timer.expired(600_000, status, true))
        }
    }

    @Test fun endTimerRequiresSuccessfulEnd() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.END_OF_ITEM), 0)
        assertFalse(timer.expired(0, paused.copy(ended = true, error = "Decode failure"), false))
        assertTrue(timer.expired(0, paused.copy(ended = true), false))
    }

    @Test fun disablingAndRearmingClearsOldCountdown() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.PAUSE_INACTIVITY, 10), 0)
        timer.expired(0, paused, false)
        timer.arm(SleepTimerConfig(), 500_000)
        assertFalse(timer.expired(900_000, paused, false))
        timer.arm(SleepTimerConfig(SleepTimerKind.PAUSE_INACTIVITY, 10), 900_000)
        assertFalse(timer.expired(900_000, paused, false))
        assertTrue(timer.expired(1_500_000, paused, false))
    }

    @Test fun suppliedDeadlineAndInvalidDurationsAreHandled() {
        val timer = PlaybackSleepTimer()
        val config = timer.arm(SleepTimerConfig(SleepTimerKind.DURATION, -1, 5_000), 1_000)
        assertEquals(1, config.minutes)
        assertEquals(5_000L, config.deadlineRealtimeMs)
        assertTrue(timer.expired(5_000, playing, true))
        assertEquals(1440, timer.arm(SleepTimerConfig(SleepTimerKind.USER_INACTIVITY, Int.MAX_VALUE), 0).minutes)
    }

    @Test fun onlyRunningTimersAskBeforeStopping() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.DURATION, 15), 0)
        assertTrue(timer.asksBeforeStop())
        timer.arm(SleepTimerConfig(SleepTimerKind.USER_INACTIVITY, 30), 0)
        assertTrue(timer.asksBeforeStop())
        timer.arm(SleepTimerConfig(SleepTimerKind.PAUSE_INACTIVITY, 10), 0)
        assertFalse(timer.asksBeforeStop())
        timer.arm(SleepTimerConfig(SleepTimerKind.END_OF_ITEM), 0)
        assertFalse(timer.asksBeforeStop())
    }
}
