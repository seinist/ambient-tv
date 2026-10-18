package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import org.junit.Assert.*
import org.junit.Test

class SleepTimerDefaultsTest {
    @Test fun autoStartKeysMapToTimers() {
        assertEquals(SleepTimerKind.OFF, autoSleepTimerConfig(AppPreferences.SLEEP_AUTO_START_OFF, false, 0).kind)
        val sixty = autoSleepTimerConfig("60", false, 1_000)
        assertEquals(SleepTimerKind.DURATION, sixty.kind)
        assertEquals(60, sixty.minutes)
        assertEquals(1_000 + 3_600_000L, sixty.deadlineRealtimeMs)
        assertEquals(SleepTimerKind.END_OF_ITEM, autoSleepTimerConfig(AppPreferences.SLEEP_AUTO_START_END, false, 0).kind)
        val inactivity = autoSleepTimerConfig(AppPreferences.SLEEP_AUTO_START_INACTIVITY, false, 0)
        assertEquals(SleepTimerKind.USER_INACTIVITY, inactivity.kind)
        assertEquals(60, inactivity.minutes)
        assertEquals(SleepTimerKind.OFF, autoSleepTimerConfig("garbage", false, 0).kind)
        assertEquals(SleepTimerKind.OFF, autoSleepTimerConfig("0", false, 0).kind)
    }

    @Test fun liveTvMapsEndOfTitleToOneHour() {
        val live = autoSleepTimerConfig(AppPreferences.SLEEP_AUTO_START_END, true, 5_000)
        assertEquals(SleepTimerKind.DURATION, live.kind)
        assertEquals(LIVE_END_OF_ITEM_MINUTES, live.minutes)
        assertEquals(5_000 + 3_600_000L, live.deadlineRealtimeMs)
    }

    @Test fun onlyAFreshAcquireStartsASession() {
        assertTrue(sleepSessionStarts(hadPlayer = false, replacingSource = false, keptAtMs = null, nowMs = 0))
        // Zapping / next episode: a player is still there or the source is being replaced.
        assertFalse(sleepSessionStarts(hadPlayer = true, replacingSource = false, keptAtMs = null, nowMs = 0))
        assertFalse(sleepSessionStarts(hadPlayer = false, replacingSource = true, keptAtMs = null, nowMs = 0))
        // Internal retry after stop(keepSleepTimer = true) continues the session for a while only.
        assertFalse(sleepSessionStarts(false, false, keptAtMs = 10_000, nowMs = 10_000 + KEEP_TIMER_WINDOW_MS))
        assertTrue(sleepSessionStarts(false, false, keptAtMs = 10_000, nowMs = 10_001 + KEEP_TIMER_WINDOW_MS))
    }

    @Test fun warningMinutesNeverShowZero() {
        assertEquals(1, sleepWarningMinutesLeft(10_000, 9_999))
        assertEquals(1, sleepWarningMinutesLeft(60_000, 0))
        assertEquals(2, sleepWarningMinutesLeft(60_001, 0))
        assertEquals(1, sleepWarningMinutesLeft(0, 5_000))
    }

    @Test fun preWarningFiresOncePerDeadlineAndExtendMovesIt() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.DURATION, 30), 0)
        val deadline = 1_800_000L
        assertNull(timer.warningDue(deadline - 60_001, 60_000))
        assertEquals(deadline, timer.warningDue(deadline - 60_000, 60_000))
        assertNull(timer.warningDue(deadline - 30_000, 60_000))
        val extended = timer.extend(SLEEP_EXTEND_MINUTES, deadline - 30_000)
        assertEquals(deadline + 1_800_000L, extended.deadlineRealtimeMs)
        assertFalse(timer.expired(deadline, PlaybackStatus(playing = true), true))
        assertEquals(deadline + 1_800_000L, timer.warningDue(deadline + 1_800_000L - 10_000, 60_000))
    }

    @Test fun noWarningWhenDisabledOrNotTimed() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.DURATION, 1), 0)
        assertNull(timer.warningDue(30_000, 0))
        timer.arm(SleepTimerConfig(SleepTimerKind.USER_INACTIVITY, 30), 0)
        assertNull(timer.warningDue(1_790_000, 60_000))
        assertEquals(SleepTimerKind.USER_INACTIVITY, timer.extend(30, 0).kind)
    }

    @Test fun rearmingAllowsANewWarningForTheSameDeadline() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.DURATION, 1, 60_000), 0)
        assertEquals(60_000L, timer.warningDue(30_000, 60_000))
        timer.arm(SleepTimerConfig(SleepTimerKind.DURATION, 1, 60_000), 0)
        assertEquals(60_000L, timer.warningDue(30_000, 60_000))
    }
}

class SleepTimerNextItemTest {
    @Test fun automaticEndOfTitleContinuesWithTheNextTitleAndKeepsTheDailyStop() {
        val auto = SleepTimerConfig(SleepTimerKind.END_OF_ITEM, alsoStopRealtimeMs = 9_000L)
        val next = sleepTimerForNextItem(auto, auto = true)
        assertEquals(SleepTimerKind.END_OF_ITEM, next.kind)
        assertEquals(9_000L, next.alsoStopRealtimeMs)
    }

    @Test fun manualEndOfTitleEndsWithItsTitle() {
        assertEquals(SleepTimerKind.OFF, sleepTimerForNextItem(SleepTimerConfig(SleepTimerKind.END_OF_ITEM), auto = false).kind)
        val clock = sleepTimerForNextItem(SleepTimerConfig(SleepTimerKind.END_OF_ITEM, alsoStopRealtimeMs = 5L), auto = false)
        assertEquals(SleepTimerKind.CLOCK_TIME, clock.kind)
        assertEquals(5L, clock.deadlineRealtimeMs)
        val duration = SleepTimerConfig(SleepTimerKind.DURATION, 30, 1_800_000L)
        assertSame(duration, sleepTimerForNextItem(duration, auto = false))
    }
}
