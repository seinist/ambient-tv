package com.ambient.tv.ui.settings

import com.ambient.tv.companion.CompanionBackupPolicy
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.data.local.SettingsBackupManager
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.ui.theme.AccentMotionMode
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AccentMotionSettingsTest {
    @Test fun preferenceDefaultsToOffAndNormalizesUnknownValues() {
        val storage = memoryPreferences()
        val preferences = AppPreferences(storage)
        assertEquals("off", preferences.accentMotionKey)
        for (mode in AccentMotionMode.entries) {
            preferences.accentMotionKey = mode.key
            assertEquals(mode.key, AppPreferences(storage).accentMotionKey)
        }
        preferences.accentMotionKey = "unknown-future-mode"
        assertEquals("off", AppPreferences(storage).accentMotionKey)
        assertEquals(AccentMotionMode.OFF, AccentMotionMode.fromKey(null))
    }

    @Test fun switchingPerformanceProfilesDoesNotEraseTheSavedMotionMode() {
        val storage = memoryPreferences()
        val preferences = AppPreferences(storage)
        preferences.accentMotionKey = "ambient"
        for (performance in listOf("low", "balanced", "auto", "maximum")) {
            preferences.uiPerformanceModeKey = performance
            preferences.playbackPerformanceModeKey = performance
            assertEquals("ambient", AppPreferences(storage).accentMotionKey)
        }
    }

    @Test fun legacyDraftsAndIndependentAppearanceEditsKeepAllKeys() {
        val legacy = SettingsUiState(themeModeKey = "oled~chrome")
        assertEquals("off", legacy.accentMotionKey)
        assertEquals("chrome", legacy.accentStyleKey)
        val motion = legacy.withAccentMotionKey("ambient")
        val edited = motion.withAccentStyleKey("pearl").withThemeModeKey("dim")
        assertEquals("ambient", edited.accentMotionKey)
        assertEquals("pearl", edited.accentStyleKey)
        assertEquals("dim", edited.displayThemeModeKey)
        assertEquals("off", edited.withAccentMotionKey("unknown").accentMotionKey)
        assertEquals("off", SettingsUiState().accentMotionKey)
    }

    @Test fun storedRefreshMergesUnrelatedAppearanceChangesWithoutLosingMotionDraft() {
        val baseline = SettingsUiState()
        val draft = baseline.withAccentMotionKey("focus")
        assertNotEquals(baseline.editableValues(), draft.editableValues())
        val stored = baseline.withAccentStyleKey("chrome").withThemeModeKey("oled")
        val merged = mergeStoredSettings(draft, baseline, stored)
        assertEquals("focus", merged.accentMotionKey)
        assertEquals("chrome", merged.accentStyleKey)
        assertEquals("oled", merged.displayThemeModeKey)
        assertEquals(baseline, mergeStoredSettings(baseline, baseline, baseline))
    }

    @Test fun backupRoundTripPreservesMotionAndLegacyImportsUseStaticDefault() {
        val storage = memoryPreferences()
        val preferences = AppPreferences(storage)
        val manager = SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))
        for (mode in listOf("focus", "ambient")) {
            preferences.accentMotionKey = mode
            val exported = manager.exportJson(false)
            preferences.accentMotionKey = "off"
            manager.importJson(exported)
            assertEquals(mode, AppPreferences(storage).accentMotionKey)
        }
        manager.importJson("""{"schemaVersion":77,"exportedAtEpochMs":0,"accentStyleKey":"chrome"}""")
        assertEquals("off", preferences.accentMotionKey)
        assertEquals("chrome", preferences.accentStyleKey)
        manager.importJson("""{"schemaVersion":78,"exportedAtEpochMs":0,"accentMotionKey":"unknown"}""")
        assertEquals("off", preferences.accentMotionKey)
    }

    @Test fun companionPreferencesTransferMotionButAccountsAndOlderSendersDoNotOverrideIt() {
        val source = SettingsBackup(schemaVersion = 78, exportedAtEpochMs = 1, accentMotionKey = "ambient")
        val target = SettingsBackup(schemaVersion = 78, exportedAtEpochMs = 2, accentMotionKey = "focus")
        val json = Json { ignoreUnknownKeys = true }
        val prepared = json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.prepare(source, false))
        assertEquals("ambient", prepared.accentMotionKey)
        val settings = json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(source, target, "settings", true, false))
        assertEquals("ambient", settings.accentMotionKey)
        val accounts = json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(source, target, "accounts", false, false))
        assertEquals("focus", accounts.accentMotionKey)
        val oldSource = SettingsBackup(schemaVersion = 77, exportedAtEpochMs = 1)
        val legacy = json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(oldSource, target, "settings", true, false))
        assertEquals("focus", legacy.accentMotionKey)
    }
}
