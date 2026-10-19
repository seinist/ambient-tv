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

    @Test fun earliestStopWinsAndManualTimersKeepTheDailyStop() {
        val timer = PlaybackSleepTimer()
        timer.setDailyStop(1_800_000L)
        timer.arm(SleepTimerConfig(SleepTimerKind.DURATION, 60), 0)
        assertEquals(1_800_000L, timer.config.timedDeadlineMs)
        assertTrue(timer.dailyStopIsNext())
        timer.arm(SleepTimerConfig(SleepTimerKind.DURATION, 15), 0)
        assertEquals(900_000L, timer.config.timedDeadlineMs)
        assertFalse(timer.dailyStopIsNext())
        assertEquals(1_800_000L, timer.config.alsoStopRealtimeMs)
        // Manual "off" ends only the user's timer: the daily stop stays as CLOCK_TIME.
        timer.arm(SleepTimerConfig(), 0)
        assertEquals(SleepTimerKind.CLOCK_TIME, timer.config.kind)
        assertEquals(1_800_000L, timer.config.alsoStopRealtimeMs)
        assertEquals(SleepTimerKind.OFF, timer.userConfig.kind)
        timer.setDailyStop(null)
        assertEquals(SleepTimerKind.OFF, timer.config.kind)
    }

    @Test fun dailyStopAloneExpiresAsksAndWarns() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(), 0)
        timer.setDailyStop(600_000L)
        assertEquals(600_000L, timer.warningDue(540_000, 60_000))
        assertFalse(timer.expired(599_999, PlaybackStatus(playing = true), true))
        assertTrue(timer.expired(600_000, PlaybackStatus(playing = true), true))
        assertTrue(timer.stoppedByDailyStop)
        assertTrue(timer.asksBeforeStop())
    }

    @Test fun dailyStopNextToUntimedTimer() {
        val timer = PlaybackSleepTimer()
        timer.arm(SleepTimerConfig(SleepTimerKind.END_OF_ITEM), 0)
        timer.setDailyStop(600_000L)
        assertEquals(SleepTimerKind.END_OF_ITEM, timer.config.kind)
        assertEquals(600_000L, timer.warningDue(590_000, 60_000))
        assertTrue(timer.expired(600_000, PlaybackStatus(playing = true), true))
        assertTrue(timer.asksBeforeStop())
        // The end of the item still stops directly, without asking.
        timer.setDailyStop(2_400_000L)
        assertTrue(timer.expired(700_000, PlaybackStatus(ended = true), false))
        assertFalse(timer.asksBeforeStop())
    }

    @Test fun clockPlanFollowsWallClockAndTimeZone() {
        val now = at(2026, 6, 10, 22, 0)
        val plan = dailyStopPlan(now, berlin, one, 30, nowRealtimeMs = 1_000L)!!
        assertTrue(plan is DailyStopPlan.Clock)
        assertEquals(1_000L + 3 * 3_600_000L, plan.realtimeMs(1_000L, now, berlin))
        // The wall clock jumps an hour ahead: the stop is one hour closer in real time.
        assertEquals(1_000L + 2 * 3_600_000L, plan.realtimeMs(1_000L, now + 3_600_000L, berlin))
        // Time zone change to London (one hour behind): 01:00 there is an hour later.
        assertEquals(1_000L + 4 * 3_600_000L, plan.realtimeMs(1_000L, now, ZoneId.of("Europe/London")))
        // "+30 min" moves the wall-clock stop.
        assertEquals(1_000L + 3 * 3_600_000L + 1_800_000L, plan.extended(30, 1_000L).realtimeMs(1_000L, now, berlin))
    }

    @Test fun nightWindowPlanIsAFixedMoment() {
        val late = at(2026, 6, 10, 1, 30)
        val plan = dailyStopPlan(late, berlin, one, 45, nowRealtimeMs = 5_000L)
        assertEquals(DailyStopPlan.Fixed(5_000L + 45 * 60_000L), plan)
        assertEquals(5_000L + 45 * 60_000L, plan!!.realtimeMs(9_999L, late + 3_600_000L, berlin))
        assertNull(dailyStopPlan(late, berlin, -1, 30, 0L))
    }

    @Test fun clockLabelUsesWallTime() {
        val now = at(2026, 6, 10, 23, 40)
        assertEquals("01:00", sleepClockLabel(1_000L + 80 * 60_000L, 1_000L, now, berlin))
    }
}
