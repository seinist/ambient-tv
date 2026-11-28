package com.ambient.tv.livetv

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.Before
import android.content.Context
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ProgrammeReminderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val now get() = System.currentTimeMillis() / 1000
    private fun programme(id: String, start: Long = now + 60, youtube: Boolean = false) =
        LiveTvReminder("source", "channel", "Source", id, "Title", start, youtube = youtube,
            stopEpochSeconds = start + 3600)

    @Before fun reset() {
        context.getSharedPreferences("live_tv_reminders", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun legacyReminderDecodesWithDefaults() {
        val legacy = """{"sourceId":"s","channelUuid":"c","channelName":"Channel","eventId":"e","title":"Title","startEpochSeconds":123}"""
        val entry = Json.decodeFromString<LiveTvReminder>(legacy)
        assertFalse(entry.youtube)
        assertFalse(entry.due)
        assertEquals(0L, entry.snoozedUntilMs)
        assertEquals("s|e", entry.id)
    }

    @Test fun bothProvidersPersistAndDueQueueSurvivesReconstruction() {
        val store = LiveTvReminderStore(context)
        val live = programme("same", now - 60)
        val youtube = programme("same", now - 60, true)
        assertTrue(store.toggle(live))
        assertTrue(store.toggle(youtube))
        assertNotNull(store.markDue(live.id))
        assertNotNull(store.markDue(youtube.id))
        val restored = LiveTvReminderStore(context)
        assertEquals(setOf(live.id, youtube.id), restored.reminders.value.filter { it.due }.map { it.id }.toSet())
        assertNull(restored.markDue(live.id))
        restored.remove(live.id)
        assertEquals(youtube.id, LiveTvReminderStore(context).reminders.value.single().id)
    }

    @Test fun snoozePreventsDuplicateAlarmAndPersistsDeadline() {
        val store = LiveTvReminderStore(context)
        val item = programme("e", now - 1, true)
        store.toggle(item)
        assertNotNull(store.markDue(item.id))
        store.snooze(item.id)
        val restored = LiveTvReminderStore(context).find(item.id)!!
        assertFalse(restored.due)
        assertTrue(restored.snoozedUntilMs > System.currentTimeMillis())
        assertNull(store.markDue(item.id))
    }

    @Test fun autoSwitchUsesActualStartRatherThanReminderLead() {
        val item = programme("e")
        assertEquals(item.startEpochSeconds * 1000, item.alarmAtMs(5, true))
        assertEquals(item.startEpochSeconds * 1000 - 300000L, item.alarmAtMs(5, false))
    }

    @Test fun guideChangesRescheduleOnlyMatchingProviderAndEvent() {
        val store = LiveTvReminderStore(context)
        val item = programme("e")
        val unrelated = programme("e", youtube = true)
        store.toggle(item)
        store.toggle(unrelated)
        val start = item.startEpochSeconds + 120
        store.reconcileGuide("source", listOf(LiveTvProgramme("e", "channel", "Source", "Moved",
            startEpochSeconds = start, stopEpochSeconds = start + 1800)))
        val moved = store.find(item.id)!!
        assertEquals(start, moved.startEpochSeconds)
        assertEquals(start + 1800, moved.stopEpochSeconds)
        assertEquals(unrelated, store.find(unrelated.id))
    }

    @Test fun longProgrammeDoesNotExpireAfterOneHourButExpiredProgrammeDoes() {
        val item = programme("e", now - 7200).copy(stopEpochSeconds = now + 60)
        assertTrue(item.isRelevant(System.currentTimeMillis()))
        assertFalse(item.copy(stopEpochSeconds = now - 1).isRelevant(System.currentTimeMillis()))
    }
}
