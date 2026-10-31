package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SleepSessionTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val clock = object : SleepClock {
        var rt = 100_000L
        var epoch = ZonedDateTime.of(2026, 6, 10, 22, 0, 0, 0, berlin).toInstant().toEpochMilli()
        var zone: ZoneId = berlin
        override fun realtimeMs() = rt
        override fun epochMs() = epoch
        override fun zone() = zone
        fun advance(ms: Long) { rt += ms; epoch += ms }
    }

    private class Target(private val tvDevice: Boolean = true) : SleepTarget {
        var playing = true
        var ended = false
        var stops = 0
        var pauses = 0
        var standbyAttempts = 0
        override fun sleepStatus() = PlaybackStatus(playing = playing, ended = ended)
        override fun sleepWantsToPlay() = playing
        override fun sleepPause() { pauses++; playing = false }
        override fun sleepPlay() { playing = true }
        override fun sleepStop() { stops++; SleepSession.end(this) }
        override fun sleepAllowsDeviceStandby() = tvDevice
        override fun sleepEnterStandby() { standbyAttempts++ }
    }

    private fun prefs(auto: String = AppPreferences.SLEEP_AUTO_START_OFF, daily: Int = -1, warning: Int = 1) =
        AppPreferences(memoryPreferences()).apply { sleepAutoStartKey = auto; sleepDailyStopMinute = daily; sleepWarningMinutes = warning }

    @Before fun setUp() = SleepSession.resetForTest(clock)

    @Test fun manualOffKeepsTheDailyStopAndSkipTodayEndsIt() {
        val target = Target()
        val p = prefs(auto = "30", daily = 23 * 60)
        SleepSession.begin(target, { p }, isLive = false, hadPlayer = false, replacingSource = false)
        assertEquals(SleepTimerKind.DURATION, SleepSession.config.kind)
        assertEquals(clock.rt + 3_600_000L, SleepSession.config.alsoStopRealtimeMs)
        SleepSession.set(SleepTimerConfig())
        assertEquals(SleepTimerKind.CLOCK_TIME, SleepSession.config.kind)
        assertTrue(SleepSession.dailyStopActive)
        SleepSession.set(SleepTimerConfig(SleepTimerKind.DURATION, 90))
        assertEquals(clock.rt + 3_600_000L, SleepSession.config.timedDeadlineMs)
        SleepSession.skipDailyStopToday()
        assertFalse(SleepSession.dailyStopActive)
        assertEquals(clock.rt + 90 * 60_000L, SleepSession.config.timedDeadlineMs)
    }

    @Test fun dailyStopFollowsAWallClockChange() {
        val target = Target()
        val p = prefs(daily = 23 * 60)
        SleepSession.begin(target, { p }, isLive = false, hadPlayer = false, replacingSource = false)
        val before = SleepSession.config.alsoStopRealtimeMs!!
        clock.epoch += 30 * 60_000L // clock corrected by +30 min
        SleepSession.tick(target)
        assertEquals(before - 30 * 60_000L, SleepSession.config.alsoStopRealtimeMs)
        clock.zone = ZoneId.of("Europe/London")
        SleepSession.tick(target)
        assertEquals(before + 30 * 60_000L, SleepSession.config.alsoStopRealtimeMs)
    }

    @Test fun dailyStopWarnsExtendsAndAsksThenStops() {
        val target = Target()
        val p = prefs(daily = 23 * 60, warning = 2)
        SleepSession.begin(target, { p }, isLive = false, hadPlayer = false, replacingSource = false)
        clock.advance(58 * 60_000L)
        SleepSession.tick(target)
        val warned = SleepSession.warningDeadline
        assertNotNull(warned)
        SleepSession.extend()
        assertNull(SleepSession.warningDeadline)
        assertEquals(warned!! + 30 * 60_000L, SleepSession.config.alsoStopRealtimeMs)
        clock.advance(32 * 60_000L)
        SleepSession.tick(target)
        assertNotNull(SleepSession.promptDeadline)
        assertEquals(1, target.pauses)
        clock.advance(PlaybackSleepTimer.PROMPT_MS)
        SleepSession.tick(target)
        assertEquals(1, target.stops)
        assertEquals(1, target.standbyAttempts)
        assertTrue(SleepSession.standbyRequested)
        SleepSession.cancelStandbyRequest()
        assertFalse(SleepSession.standbyRequested)
        assertNull(SleepSession.target)
        assertEquals(SleepTimerKind.OFF, SleepSession.config.kind)
    }

    @Test fun phoneStopsPlaybackButNeverRequestsStandby() {
        val phone = Target(tvDevice = false)
        SleepSession.begin(phone, { prefs() }, false, false, false)
        SleepSession.set(SleepTimerConfig(SleepTimerKind.DURATION, 1))
        clock.advance(60_000)
        SleepSession.tick(phone)
        assertEquals(1, phone.pauses)
        clock.advance(PlaybackSleepTimer.PROMPT_MS)
        SleepSession.tick(phone)
        assertEquals(1, phone.stops)
        assertEquals(0, phone.standbyAttempts)
        assertFalse(SleepSession.standbyRequested)
    }

    @Test fun stopNowInTheTimerPromptRespectsDeviceType() {
        val tv = Target()
        SleepSession.begin(tv, { prefs() }, false, false, false)
        SleepSession.set(SleepTimerConfig(SleepTimerKind.DURATION, 1))
        clock.advance(60_000)
        SleepSession.tick(tv)
        SleepSession.stopNow()
        assertEquals(1, tv.standbyAttempts)
        assertTrue(SleepSession.standbyRequested)

        SleepSession.resetForTest(clock)
        val phone = Target(tvDevice = false)
        SleepSession.begin(phone, { prefs() }, false, false, false)
        SleepSession.set(SleepTimerConfig(SleepTimerKind.DURATION, 1))
        clock.advance(60_000)
        SleepSession.tick(phone)
        SleepSession.stopNow()
        assertEquals(1, phone.stops)
        assertEquals(0, phone.standbyAttempts)
        assertFalse(SleepSession.standbyRequested)
    }

    @Test fun naturalEndOfItemAlsoRequestsTvStandbyWithoutPrompt() {
        val target = Target().apply { playing = false; ended = true }
        SleepSession.begin(target, { prefs() }, isLive = false, hadPlayer = false, replacingSource = false)
        SleepSession.set(SleepTimerConfig(SleepTimerKind.END_OF_ITEM))
        SleepSession.tick(target)
        assertEquals(0, target.pauses)
        assertEquals(1, target.stops)
        assertEquals(1, target.standbyAttempts)
        assertTrue(SleepSession.standbyRequested)
    }

    @Test fun continuingAfterTheDailyStopDropsItButKeepsTheUserTimer() {
        val target = Target()
        val p = prefs(auto = "120", daily = 23 * 60)
        SleepSession.begin(target, { p }, isLive = false, hadPlayer = false, replacingSource = false)
        clock.advance(60 * 60_000L)
        SleepSession.tick(target)
        assertNotNull(SleepSession.promptDeadline)
        SleepSession.continueAfter(rearm = false)
        assertFalse(SleepSession.dailyStopActive)
        assertEquals(SleepTimerKind.DURATION, SleepSession.config.kind)
        assertTrue(target.playing)
    }

    @Test fun zappingContinuesAndOtherTargetsAreIgnored() {
        val target = Target()
        val other = Target()
        val p = prefs(auto = "60")
        SleepSession.begin(target, { p }, isLive = false, hadPlayer = false, replacingSource = false)
        SleepSession.set(SleepTimerConfig())
        SleepSession.begin(target, { p }, isLive = false, hadPlayer = true, replacingSource = false)
        assertEquals(SleepTimerKind.OFF, SleepSession.config.kind)
        SleepSession.end(other)
        SleepSession.tick(other)
        assertSame(target, SleepSession.target)
        // Another player (Spotify) starting is a new session with the automatic timer.
        SleepSession.begin(other, { p }, isLive = false, hadPlayer = false, replacingSource = false)
        assertEquals(SleepTimerKind.DURATION, SleepSession.config.kind)
    }

    @Test fun continuingAcknowledgesBothExpiredDeadlines() {
        val target = Target()
        SleepSession.begin(target, { prefs(auto = "60", daily = 23 * 60) }, false, false, false)
        clock.advance(60 * 60_000L)
        SleepSession.tick(target)
        assertNotNull(SleepSession.promptDeadline)
        SleepSession.continueAfter(rearm = false)
        clock.advance(1_000)
        SleepSession.tick(target)
        assertNull(SleepSession.promptDeadline)
        assertEquals(SleepTimerKind.OFF, SleepSession.config.kind)
        assertTrue(target.playing)
    }

    @Test fun continuingAlsoAcknowledgesDurationExpiringDuringDailyPrompt() {
        val target = Target()
        SleepSession.begin(target, { prefs(daily = 23 * 60) }, false, false, false)
        SleepSession.set(SleepTimerConfig(SleepTimerKind.DURATION, 61, clock.rt + 3_630_000L))
        clock.advance(3_600_000L)
        SleepSession.tick(target)
        clock.advance(40_000)
        SleepSession.continueAfter(rearm = false)
        SleepSession.tick(target)
        assertNull(SleepSession.promptDeadline)
        assertTrue(target.playing)
    }

    @Test fun rearmAfterCoincidentDeadlinesStartsANewDuration() {
        val target = Target()
        SleepSession.begin(target, { prefs(auto = "60", daily = 23 * 60) }, false, false, false)
        clock.advance(3_600_000L)
        SleepSession.tick(target)
        SleepSession.continueAfter(rearm = true)
        SleepSession.tick(target)
        assertNull(SleepSession.promptDeadline)
        assertFalse(SleepSession.dailyStopActive)
        assertEquals(clock.rt + 3_600_000L, SleepSession.config.deadlineRealtimeMs)
    }

    @Test fun keptTimerSurvivesAnInternalRestart() {
        val target = Target()
        val p = prefs()
        SleepSession.begin(target, { p }, isLive = false, hadPlayer = false, replacingSource = false)
        SleepSession.set(SleepTimerConfig(SleepTimerKind.DURATION, 45))
        SleepSession.end(target, keep = true)
        clock.advance(10_000)
        SleepSession.begin(target, { p }, isLive = false, hadPlayer = false, replacingSource = false)
        assertEquals(45, SleepSession.config.minutes)
    }

    @Test fun automaticEndOfTitleRunsOnForEveryTitle() {
        val target = Target()
        val p = prefs(auto = AppPreferences.SLEEP_AUTO_START_END)
        SleepSession.begin(target, { p }, isLive = false, hadPlayer = false, replacingSource = false)
        SleepSession.itemChanged(target)
        assertEquals(SleepTimerKind.END_OF_ITEM, SleepSession.config.kind)
        SleepSession.set(SleepTimerConfig(SleepTimerKind.END_OF_ITEM))
        SleepSession.itemChanged(target)
        assertEquals(SleepTimerKind.OFF, SleepSession.config.kind)
    }
}

class SleepSessionSettingsChangeTest {
    @Test fun changingTheDailyStopInSettingsAppliesToTheRunningSession() {
        val berlin = ZoneId.of("Europe/Berlin")
        val clock = object : SleepClock {
            override fun realtimeMs() = 0L
            override fun epochMs() = ZonedDateTime.of(2026, 6, 10, 20, 0, 0, 0, berlin).toInstant().toEpochMilli()
            override fun zone() = berlin
        }
        SleepSession.resetForTest(clock)
        val target = object : SleepTarget {
            override fun sleepStatus() = PlaybackStatus(playing = true)
            override fun sleepWantsToPlay() = true
            override fun sleepPause() = Unit
            override fun sleepPlay() = Unit
            override fun sleepStop() = Unit
        }
        val p = AppPreferences(memoryPreferences())
        SleepSession.begin(target, { p }, isLive = false, hadPlayer = false, replacingSource = false)
        assertFalse(SleepSession.dailyStopActive)
        p.sleepDailyStopMinute = 22 * 60
        SleepSession.tick(target)
        assertEquals(2 * 3_600_000L, SleepSession.config.alsoStopRealtimeMs)
        p.sleepDailyStopMinute = -1
        SleepSession.tick(target)
        assertFalse(SleepSession.dailyStopActive)
    }
}
