package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioSessionTitleDelayTest {
    @Test fun playerDelayBelongsToTheTitleAndLeavesTheProfileAlone() {
        val preferences = AppPreferences(memoryPreferences())
        val session = AudioSession(preferences, "mpv", sourcePassthrough = false).apply { titleScoped = true }
        session.update(session.options.copy(delayMs = 120))
        assertEquals(120, session.options.delayMs)
        assertEquals(120, session.titleDelayMs)
        assertEquals(0, preferences.audioSettings.current.delayMs)

        // Another change while a title delay is active keeps the profile's own delay.
        session.update(session.options.copy(eqEnabled = !session.options.eqEnabled))
        assertEquals(0, preferences.audioSettings.current.delayMs)
        assertEquals(120, session.options.delayMs)

        session.restoreTitleDelay(null)
        assertNull(session.titleDelayMs)
        assertEquals(0, session.options.delayMs)
    }

    @Test fun livePlayerChangesStayInTheSessionUntilExplicitlySaved() {
        val preferences = AppPreferences(memoryPreferences())
        val session = AudioSession(preferences, "vlc", sourcePassthrough = false)
        session.update(session.options.copy(delayMs = -50))
        assertNull(session.titleDelayMs)
        assertEquals(-50, session.options.delayMs)
        assertEquals(0, preferences.audioSettings.current.delayMs)
    }
    @Test fun allPlayerAudioEditsStayLocalWithoutAnExistingTitleProfile() {
        val preferences = AppPreferences(memoryPreferences())
        val defaults = preferences.audioSettings
        val session = AudioSession(preferences, "mpv", false).apply { titleScoped = true }
        session.update(session.options.copy(night = true, eqEnabled = true))
        session.selectProfile("tv")
        session.trackPreference(session.settings.tracks.copy(role = "original"))
        assertEquals(true, session.options.night)
        assertEquals(true, session.options.eqEnabled)
        assertEquals(defaults, preferences.audioSettings)
    }

    @Test fun loadingAndEditingSavedAudioDoesNotOverwriteGlobalDefaults() {
        val preferences = AppPreferences(memoryPreferences())
        val defaults = preferences.audioSettings
        val session = AudioSession(preferences, "mpv", sourcePassthrough = false).apply { titleScoped = true }
        val saved = defaults.update(defaults.current.copy(night = true, delayMs = 90))
        session.restoreProfile(saved)
        assertEquals(true, session.options.night)
        assertEquals(90, session.options.delayMs)
        session.update(session.options.copy(delayMs = 120, eqEnabled = true))
        assertEquals(120, session.options.delayMs)
        assertEquals(true, session.options.eqEnabled)
        assertEquals(defaults, preferences.audioSettings)
        session.restoreProfile(null)
        assertEquals(defaults.current, session.options)
        assertNull(session.titleDelayMs)
    }

    @Test fun savedAudioKeepsRouteSpecificOptionsAndProfileSelectionLocal() {
        val preferences = AppPreferences(memoryPreferences())
        val defaults = preferences.audioSettings
        val session = AudioSession(preferences, "vlc", sourcePassthrough = false)
        val saved = defaults.updateForRoute(defaults.current.copy(night = true), "tv")
        session.restoreProfile(saved)
        session.selectProfile("tv")
        assertEquals(true, session.options.night)
        assertEquals(defaults, preferences.audioSettings)
    }
}
