package com.ambient.tv.ui.settings

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.ui.common.SettingsLevel
import com.ambient.tv.ui.common.videoSectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLevelTest {
    @Test fun existingInstallationsSeeEverythingAndNewOnesStartSimple() {
        val existing = AppPreferences(memoryPreferences())
        assertEquals("expert", existing.settingsLevelKey)
        val fresh = AppPreferences(memoryPreferences())
        fresh.initialSetupCompleted = true
        assertEquals("simple", fresh.settingsLevelKey)
        // A stored choice survives a repeated setup flag.
        fresh.settingsLevelKey = "advanced"
        fresh.initialSetupCompleted = true
        assertEquals("advanced", fresh.settingsLevelKey)
    }

    @Test fun simpleShowsWhatPlaybackNeedsAndExpertEverything() {
        assertTrue(SettingsLevel.SIMPLE.shows(SettingsCategory.LANGUAGE.level))
        assertTrue(SettingsLevel.SIMPLE.shows(SettingsCategory.SUBTITLES.level))
        assertFalse(SettingsLevel.SIMPLE.shows(SettingsCategory.PLAYBACK.level))
        assertTrue(SettingsLevel.ADVANCED.shows(SettingsCategory.PLAYBACK.level))
        assertFalse(SettingsLevel.ADVANCED.shows(SettingsCategory.DIAGNOSTICS.level))
        SettingsCategory.entries.forEach { assertTrue(it.name, SettingsLevel.EXPERT.shows(it.level)) }
        assertEquals(SettingsArea.entries.toList(), settingsAreasFor(SettingsLevel.EXPERT))
        // Simple drops areas without any page of its own, never the source areas.
        val simple = settingsAreasFor(SettingsLevel.SIMPLE)
        assertFalse(SettingsArea.NETWORK in simple)
        assertTrue(simple.containsAll(listOf(SettingsArea.START, SettingsArea.SYSTEMS, SettingsArea.TELEVISION, SettingsArea.STREAMING)))
    }

    @Test fun videoPanelAreasFollowTheLevel() {
        assertEquals(SettingsLevel.SIMPLE, videoSectionLevel("scaling"))
        assertEquals(SettingsLevel.ADVANCED, videoSectionLevel("filters"))
        assertEquals(SettingsLevel.EXPERT, videoSectionLevel("output"))
        assertEquals(SettingsLevel.EXPERT, SettingsLevel.fromKey("unknown"))
    }
}
