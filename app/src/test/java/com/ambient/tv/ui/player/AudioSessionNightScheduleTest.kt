package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.NightSchedule
import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSessionNightScheduleTest {
    private fun session(): AudioSession {
        val preferences = AppPreferences(memoryPreferences())
        preferences.nightSchedule = NightSchedule(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60, dialogue = 2)
        return AudioSession(preferences, "mpv", sourcePassthrough = false).apply { refreshNightAutomation(NOON) }
    }

    @Test fun windowSwitchesNightModeOnAndOffLive() {
        val session = session()
        val applied = mutableListOf<Boolean>()
        // Night mode may also change the output plan (PCM instead of passthrough): either path applies it.
        session.apply = { _, new -> applied += new.night }
        session.routeChanged = { _, _ -> applied += session.options.night }
        assertFalse(session.options.night)

        session.refreshNightAutomation(LATE)
        assertTrue(session.nightAutomatic)
        assertTrue(session.options.night)
        assertEquals(2, session.options.dialogue)
        // The profile itself is untouched.
        assertFalse(session.settings.currentForRoute(session.route.profile).night)

        session.refreshNightAutomation(NOON)
        assertFalse(session.options.night)
        assertEquals(listOf(true, false), applied)
    }

    @Test fun switchingNightOffInsideTheWindowHoldsUntilItEnds() {
        val session = session()
        session.refreshNightAutomation(LATE)
        session.update(session.options.copy(night = false))
        assertFalse(session.options.night)
        session.refreshNightAutomation(LATE + 30)
        assertFalse(session.options.night)
        // Next night it applies again.
        session.refreshNightAutomation(NOON)
        session.refreshNightAutomation(LATE)
        assertTrue(session.options.night)
    }

    @Test fun otherEditsInsideTheWindowDoNotSaveTheWindowIntoTheProfile() {
        val session = session()
        session.refreshNightAutomation(LATE)
        session.update(session.options.copy(normalize = true))
        val profile = session.settings.currentForRoute(session.route.profile)
        assertTrue(profile.normalize)
        assertFalse(profile.night)
        assertEquals(0, profile.dialogue)
        assertTrue(session.options.night)
    }

    private companion object {
        const val NOON = 12 * 60
        const val LATE = 23 * 60
    }
}
