package com.ambient.tv.ui.settings

import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.SettingsBackupManager
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.ui.display.SdrToHdrStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class UiHdrSettingsTest {
    @Test fun choiceRoundTripsThroughPreferencesAndBackup() {
        val preferences = AppPreferences(memoryPreferences())
        val backup = SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))
        assertEquals("auto", preferences.uiHdrModeKey)
        preferences.uiHdrModeKey = "sdr"
        val exported = backup.exportJson(false)
        preferences.uiHdrModeKey = "auto"
        backup.importJson(exported)
        assertEquals("sdr", preferences.uiHdrModeKey)
        preferences.uiHdrModeKey = "force"
        assertEquals("force", preferences.uiHdrModeKey)
        val forced = backup.exportJson(false)
        preferences.uiHdrModeKey = "auto"
        backup.importJson(forced)
        assertEquals("force", preferences.uiHdrModeKey)
        backup.importJson("""{"schemaVersion":48,"exportedAtEpochMs":0,"uiHdrModeKey":"dolby-vision"}""")
        assertEquals("sdr", preferences.uiHdrModeKey)
        backup.importJson("""{"schemaVersion":48,"exportedAtEpochMs":0}""")
        assertEquals("auto", preferences.uiHdrModeKey)
    }

    @Test fun draftChoiceIsDirtyAndSurvivesUnrelatedStoredRefresh() {
        val baseline = SettingsUiState()
        val draft = baseline.copy(uiHdrModeKey = "sdr")
        assertNotEquals(baseline.editableValues(), draft.editableValues())
        val merged = mergeStoredSettings(draft, baseline, baseline.copy(uiScale = .9f))
        assertEquals("sdr", merged.uiHdrModeKey)
        assertEquals(.9f, merged.uiScale, 0f)
        val imported = mergeStoredSettings(baseline, baseline, draft)
        assertEquals("sdr", imported.uiHdrModeKey)
    }

    @Test fun sdrToHdrDefaultsToDisabled() {
        assertFalse(AppPreferences(memoryPreferences()).sdrToHdrEnabled)
        assertFalse(SettingsUiState().sdrToHdrEnabled)
    }

    @Test fun sdrToHdrRoundTripsThroughPreferencesAndBackupInEitherMasterMode() {
        for (mode in listOf("auto", "sdr")) {
            for (enabled in listOf(false, true)) {
                val storage = memoryPreferences()
                val preferences = AppPreferences(storage)
                preferences.uiHdrModeKey = mode
                preferences.sdrToHdrEnabled = enabled
                assertEquals(enabled, AppPreferences(storage).sdrToHdrEnabled)

                val backup = SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))
                val exported = backup.exportJson(false)
                assertEquals(enabled, Json.parseToJsonElement(exported).jsonObject.getValue("sdrToHdrEnabled").jsonPrimitive.boolean)
                preferences.sdrToHdrEnabled = !enabled
                preferences.uiHdrModeKey = if (mode == "auto") "sdr" else "auto"
                backup.importJson(exported)
                assertEquals(enabled, preferences.sdrToHdrEnabled)
                assertEquals(mode, preferences.uiHdrModeKey)
            }
        }
    }

    @Test fun oldBackupDisablesSdrToHdrEvenWhenPreviouslyEnabled() {
        val preferences = AppPreferences(memoryPreferences())
        val backup = SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))
        for (version in listOf(47, 48)) {
            preferences.sdrToHdrEnabled = true
            backup.importJson("""{"schemaVersion":$version,"exportedAtEpochMs":0}""")
            assertFalse(preferences.sdrToHdrEnabled)
        }
    }

    @Test fun sdrToHdrDraftIsDirtyAndSurvivesStoredRefreshInEitherDirection() {
        for (enabled in listOf(false, true)) {
            val baseline = SettingsUiState(sdrToHdrEnabled = enabled)
            val draft = baseline.copy(sdrToHdrEnabled = !enabled)
            assertNotEquals(baseline.editableValues(), draft.editableValues())

            val merged = mergeStoredSettings(draft, baseline, baseline.copy(uiScale = .9f, uiHdrModeKey = "sdr"))
            assertEquals(!enabled, merged.sdrToHdrEnabled)
            assertEquals(.9f, merged.uiScale, 0f)
            assertEquals("sdr", merged.uiHdrModeKey)
        }
    }

    @Test fun untouchedSdrToHdrDraftAcceptsStoredChangesInEitherDirection() {
        for (enabled in listOf(false, true)) {
            val baseline = SettingsUiState(sdrToHdrEnabled = enabled)
            val draft = baseline.copy(uiScale = .9f)
            val merged = mergeStoredSettings(draft, baseline, baseline.copy(sdrToHdrEnabled = !enabled))
            assertEquals(!enabled, merged.sdrToHdrEnabled)
            assertEquals(.9f, merged.uiScale, 0f)
        }
    }

    @Test fun changingStoredHdrModePreservesSdrToHdrPreference() {
        val preferences = AppPreferences(memoryPreferences())
        preferences.sdrToHdrEnabled = true
        preferences.uiHdrModeKey = "sdr"
        assertTrue(preferences.sdrToHdrEnabled)
        preferences.uiHdrModeKey = "auto"
        assertTrue(preferences.sdrToHdrEnabled)
    }

    @Test fun conversionStatusDistinguishesRequestedAndRendererConfirmedOutput() {
        assertEquals(R.string.settings_sdr_to_hdr_waiting, sdrToHdrStatusTextRes("auto", true, SdrToHdrStatus.WAITING))
        assertEquals(R.string.settings_sdr_to_hdr_active, sdrToHdrStatusTextRes("auto", true, SdrToHdrStatus.ACTIVE))
        assertEquals(R.string.settings_sdr_to_hdr_unavailable, sdrToHdrStatusTextRes("auto", true, null))
        assertEquals(R.string.settings_sdr_to_hdr_unavailable, sdrToHdrStatusTextRes("auto", true, SdrToHdrStatus.UNAVAILABLE))
        assertEquals(R.string.settings_sdr_to_hdr_failed, sdrToHdrStatusTextRes("auto", true, SdrToHdrStatus.FAILED))
        assertEquals(R.string.settings_sdr_to_hdr_off, sdrToHdrStatusTextRes("auto", true, SdrToHdrStatus.OFF))
    }

    @Test fun disabledConversionShowsOffDespiteStaleRendererStatus() {
        for (status in SdrToHdrStatus.entries) {
            assertEquals(R.string.settings_sdr_to_hdr_off, sdrToHdrStatusTextRes("sdr", true, status))
            assertEquals(R.string.settings_sdr_to_hdr_off, sdrToHdrStatusTextRes("auto", false, status))
        }
    }
}
