package com.ambient.tv.livetv

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZonedDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ScheduledPlaybackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val now get() = System.currentTimeMillis() / 1000
    @Before fun reset() {
        context.getSharedPreferences("live_tv_reminders", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun entry(plan: ScheduledPlayback, start: Long = now + 600) =
        LiveTvReminder(plan.provider, "", plan.provider, plan.targetId, "Title", start, plan = plan)
    private fun movie(weekly: Boolean = false) =
        ScheduledPlayback("plex", "123", "scope", durationMinutes = 90, weekly = weekly)
    @Test fun onlySupportedMoviesAndEpisodesCanBePlanned() {
        assertEquals("plex", scheduledMediaProvider("123", "movie"))
        assertEquals("jellyfin", scheduledMediaProvider("j:abc", "episode"))
        assertEquals("emby", scheduledMediaProvider("e:abc", "movie"))
        for (id in listOf("telegram:1:2", "home-youtube:x", "file:abc", "radio:x", "spotify:x", "romm:x", "")) {
            assertNull(scheduledMediaProvider(id, "movie"))
        }
        for (type in listOf("show", "season", "track", "clip", "photo")) assertNull(scheduledMediaProvider("123", type))
    }
    @Test fun editReplacesOnlyTheSamePlanAndKeepsLiveReminder() {
        val store = LiveTvReminderStore(context)
        val a = entry(movie())
        val live = LiveTvReminder("source", "channel", "TV", "123", "Live", now + 600)
        assertTrue(store.toggle(live))
        assertTrue(store.putPlan(a))
        val moved = a.copy(startEpochSeconds = a.startEpochSeconds + 300)
        assertTrue(store.putPlan(moved))
        val restored = LiveTvReminderStore(context)
        assertEquals(moved, restored.find(a.id))
        assertEquals(live, restored.find(live.id))
        restored.completePlan(a)
        assertEquals(moved, restored.find(a.id))
        restored.completePlan(moved)
        assertNull(restored.find(a.id))
        assertNotNull(restored.find(live.id))
    }
    @Test fun recurringPlanKeepsLocalTimeAcrossDst() {
        val zone = ZoneId.of("Europe/Berlin")
        val start = ZonedDateTime.of(2026, 3, 22, 20, 15, 0, 0, zone)
        val plan = entry(movie(true).copy(zoneId = zone.id), start.toEpochSecond())
        val next = plan.nextOccurrence(start.toInstant().toEpochMilli())!!
        assertEquals(start.plusWeeks(1).toEpochSecond(), next.startEpochSeconds)
        assertEquals(167 * 3600L, next.startEpochSeconds - start.toEpochSecond())
        assertFalse(next.due)
    }
    @Test fun confirmedGameCreatesOnlyAnEndNoticeAndNeverAnAutoLaunch() {
        val store = LiveTvReminderStore(context)
        val game = entry(ScheduledPlayback("games", "game1", "scope", systemId = "snes",
            durationMinutes = 60, remindAtEnd = true))
        assertTrue(store.putPlan(game))
        store.completePlan(game)
        val notice = store.reminders.value.single()
        assertTrue(notice.sessionEnd)
        assertFalse(LiveTvReminderScheduler.shouldAutoSwitch(context, notice))
        assertNull(notice.plan)
        assertTrue(notice.startEpochSeconds in (now + 3598)..(now + 3602))
        store.completePlan(game)
        assertEquals(1, store.reminders.value.size)
    }
    @Test fun intervalConflictsAndStartTimesAreProviderIndependent() {
        val movie = entry(movie(), now + 600)
        val game = entry(ScheduledPlayback("romm", "romm:abcd:1", "scope", durationMinutes = 60), now + 1200)
        assertTrue(movie.overlaps(game))
        assertFalse(movie.overlaps(game.copy(startEpochSeconds = movie.startEpochSeconds + 5400)))
        val automatic = movie.copy(plan = movie.plan!!.copy(automatic = true, leadMinutes = 30))
        assertEquals(movie.startEpochSeconds * 1000, LiveTvReminderScheduler.alarmAt(context, automatic))
        val manual = movie.copy(plan = movie.plan.copy(leadMinutes = 5))
        assertEquals(movie.startEpochSeconds * 1000 - 300000L, LiveTvReminderScheduler.alarmAt(context, manual))
    }
}
