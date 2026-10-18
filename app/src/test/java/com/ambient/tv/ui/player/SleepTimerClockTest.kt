package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SleepTimerClockTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, berlin).toInstant().toEpochMilli()
    private val one = 60

    @Test fun nextTargetIsLaterTodayOrTomorrow() {
        assertEquals(at(2026, 6, 11, 1, 0), nextDailyStopEpochMs(at(2026, 6, 10, 20, 0), berlin, one))
        assertEquals(at(2026, 6, 10, 1, 0), nextDailyStopEpochMs(at(2026, 6, 10, 0, 30), berlin, one))
        // Exactly at the stop time: the next one is tomorrow.
        assertEquals(at(2026, 6, 11, 1, 0), nextDailyStopEpochMs(at(2026, 6, 10, 1, 0), berlin, one))
    }

    @Test fun midnightStopTime() {
        assertEquals(at(2026, 6, 11, 0, 0), nextDailyStopEpochMs(at(2026, 6, 10, 23, 59), berlin, 0))
        assertEquals(at(2026, 6, 11, 0, 0), previousDailyStopEpochMs(at(2026, 6, 11, 0, 0), berlin, 0))
    }

    @Test fun dstSpringForwardAndFallBack() {
        // 2026-03-29: 02:00 → 03:00. 02:30 does not exist and moves forward to 03:30.
        val spring = nextDailyStopEpochMs(at(2026, 3, 28, 23, 0), berlin, 150)
        assertEquals(ZonedDateTime.of(2026, 3, 29, 3, 30, 0, 0, berlin).toInstant().toEpochMilli(), spring)
        // 01:00 is before the switch at 02:00: 23:00 → 01:00 are two real hours.
        assertEquals(2 * 3_600_000L, nextDailyStopEpochMs(at(2026, 3, 28, 23, 0), berlin, one) - at(2026, 3, 28, 23, 0))
        // 2026-10-25: 03:00 → 02:00. From 00:00 to 04:00 local are five real hours.
        val start = at(2026, 10, 25, 0, 0)
        assertEquals(5 * 3_600_000L, nextDailyStopEpochMs(start, berlin, 4 * 60) - start)
    }

    @Test fun nightWindowUsesFallbackDuration() {
        // Started 01:30 with stop at 01:00: same night, stop after the fallback, not tomorrow 01:00.
        val late = at(2026, 6, 10, 1, 30)
        assertEquals(late + 30 * 60_000L, dailyStopEpochMs(late, berlin, one, NIGHT_FALLBACK_MINUTES))
        assertEquals(late + 90 * 60_000L, dailyStopEpochMs(late, berlin, one, nightFallbackMinutes("90")))
        // 6 hours after the stop time the window is over.
        val morning = at(2026, 6, 10, 7, 0)
        assertEquals(at(2026, 6, 11, 1, 0), dailyStopEpochMs(morning, berlin, one, 30))
        // Window across midnight: stop at 23:00, start 02:00.
        val night = at(2026, 6, 11, 2, 0)
        assertEquals(night + 30 * 60_000L, dailyStopEpochMs(night, berlin, 23 * 60, 30))
        assertNull(dailyStopEpochMs(late, berlin, -1, 30))
        assertEquals(30, nightFallbackMinutes("end"))
        assertEquals(30, nightFallbackMinutes("off"))
    }

    @Test fun earliestStopWins() {
        val duration = SleepTimerConfig(SleepTimerKind.DURATION, 60, 3_600_000L)
        assertSame(duration, withDailyStop(duration, 7_200_000L))
        val clock = withDailyStop(duration, 1_800_000L)
        assertEquals(SleepTimerKind.CLOCK_TIME, clock.kind)
        assertEquals(1_800_000L, clock.deadlineRealtimeMs)
        assertEquals(SleepTimerKind.CLOCK_TIME, withDailyStop(SleepTimerConfig(), 5L).kind)
        assertSame(duration, withDailyStop(duration, null))
        val inactivity = withDailyStop(SleepTimerConfig(SleepTimerKind.USER_INACTIVITY, 60), 1_000L)
        assertEquals(SleepTimerKind.USER_INACTIVITY, inactivity.kind)
        assertEquals(1_000L, inactivity.alsoStopRealtimeMs)
    }

    @Test fun clockTimerExpiresAsksWarnsAndExtends() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.CLOCK_TIME, 0, 600_000L), 0)
        assertEquals(600_000L, timer.warningDue(540_000, 60_000))
        assertFalse(timer.expired(599_999, PlaybackStatus(playing = true), true))
        assertTrue(timer.expired(600_000, PlaybackStatus(playing = true), true))
        assertTrue(timer.asksBeforeStop())
        assertEquals(2_400_000L, timer.extend(30, 600_000).deadlineRealtimeMs)
        // Without a moment there is nothing to stop at.
        assertEquals(SleepTimerKind.OFF, timer.arm(SleepTimerConfig(SleepTimerKind.CLOCK_TIME), 0).kind)
    }

    @Test fun dailyStopNextToUntimedTimer() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.END_OF_ITEM, alsoStopRealtimeMs = 600_000L), 0)
        assertEquals(600_000L, timer.warningDue(590_000, 60_000))
        assertFalse(timer.expired(599_999, PlaybackStatus(playing = true), true))
        assertTrue(timer.expired(600_000, PlaybackStatus(playing = true), true))
        assertTrue(timer.asksBeforeStop())
        assertEquals(2_400_000L, timer.extend(30, 600_000).alsoStopRealtimeMs)
        // The end of the item still stops directly, without asking.
        assertTrue(timer.expired(700_000, PlaybackStatus(ended = true), false))
        assertFalse(timer.asksBeforeStop())
        // A manual duration drops the daily stop.
        assertNull(timer.arm(SleepTimerConfig(SleepTimerKind.DURATION, 15, alsoStopRealtimeMs = 1L), 0).alsoStopRealtimeMs)
    }

    @Test fun clockLabelUsesWallTime() {
        val now = at(2026, 6, 10, 23, 40)
        assertEquals("01:00", sleepClockLabel(1_000L + 80 * 60_000L, 1_000L, now, berlin))
    }
}
