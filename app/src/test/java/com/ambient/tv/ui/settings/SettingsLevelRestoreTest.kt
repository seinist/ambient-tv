package com.ambient.tv.ui.settings

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.ui.common.SettingsLevel
import com.ambient.tv.ui.common.SettingsLevels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLevelRestoreTest {
    @After fun clearLiveChoice() {
        SettingsLevels.refresh(AppPreferences(memoryPreferences()))
    }

    @Test fun importedLevelReplacesAPreviouslySelectedLiveLevel() {
        val preferences = AppPreferences(memoryPreferences())
        SettingsLevels.set(preferences, SettingsLevel.ADVANCED)
        preferences.settingsLevelKey = "simple"

        SettingsLevels.refresh(preferences)

        assertEquals(SettingsLevel.SIMPLE, SettingsLevels.current(preferences))
        assertEquals("simple", preferences.settingsLevelKey)
    }

    @Test fun refreshingAnUnknownStoredLevelUsesTheExistingExpertFallback() {
        val preferences = AppPreferences(memoryPreferences())
        SettingsLevels.set(preferences, SettingsLevel.SIMPLE)
        preferences.settingsLevelKey = "future-level"

        SettingsLevels.refresh(preferences)

        assertEquals(SettingsLevel.EXPERT, SettingsLevels.current(preferences))
    }
}
