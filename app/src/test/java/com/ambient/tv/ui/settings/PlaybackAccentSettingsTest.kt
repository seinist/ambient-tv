package com.ambient.tv.ui.settings

import com.ambient.tv.companion.CompanionBackupPolicy
import com.ambient.tv.data.local.*
import com.ambient.tv.ui.theme.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlaybackAccentSettingsTest {
    @Test fun oldCustomPreferenceGetsComplementAndExplicitChoiceStaysIndependent() {
        val storage = memoryPreferences()
        val prefs = AppPreferences(storage)
        prefs.customAccentHex = "#123456"
        assertEquals("#EDCBA9", prefs.customPlaybackAccentHex)
        prefs.customPlaybackAccentHex = " #00abcdef "
        prefs.customAccentHex = "#998877"
        prefs.accentColorKey = "purple"
        assertEquals("#ABCDEF", AppPreferences(storage).customPlaybackAccentHex)
        prefs.accentColorKey = "custom"
        assertEquals("#ABCDEF", prefs.customPlaybackAccentHex)
    }

    @Test fun customPairRoundTripsThroughBackupAndRestart() {
        val storage = memoryPreferences()
        val prefs = AppPreferences(storage)
        val backup = SettingsBackupManager(prefs, TokenStore(memoryPreferences(), prefs))
        prefs.accentColorKey = "custom"
        prefs.customAccentHex = "#123456"
        prefs.customPlaybackAccentHex = "#FEDCBA"
        val exported = backup.exportJson(false)
        prefs.customAccentHex = "#FFFFFF"
        prefs.customPlaybackAccentHex = "#000000"
        backup.importJson(exported)
        val restored = AppPreferences(storage)
        assertEquals("custom", restored.accentColorKey)
        assertEquals("#123456", restored.customAccentHex)
        assertEquals("#FEDCBA", restored.customPlaybackAccentHex)
    }

    @Test fun legacyBackupDerivesSecondColorFromImportedPrimaryNotPreviousDeviceChoice() {
        val prefs = AppPreferences(memoryPreferences())
        val backup = SettingsBackupManager(prefs, TokenStore(memoryPreferences(), prefs))
        prefs.customPlaybackAccentHex = "#FFFFFF"
        backup.importJson("""{"schemaVersion":50,"exportedAtEpochMs":0,"accentColorKey":"custom","customAccentHex":"#123456"}""")
        assertEquals("#EDCBA9", prefs.customPlaybackAccentHex)
        backup.importJson("""{"schemaVersion":51,"exportedAtEpochMs":0,"accentColorKey":"custom","customAccentHex":"#FF8A33","customPlaybackAccentHex":"invalid"}""")
        assertEquals("#0075CC", prefs.customPlaybackAccentHex)
    }

    @Test fun partialHexIsOnlyDraftAndFallbackIsValidOpaqueRgb() {
        val prefs = AppPreferences(memoryPreferences())
        prefs.customAccentHex = "#123456"
        for (input in listOf("", "#", "#12", "#XYZXYZ")) {
            prefs.customPlaybackAccentHex = input
            assertEquals("#EDCBA9", prefs.customPlaybackAccentHex)
        }
        assertEquals("#123456", normalizedCustomPlaybackAccentHex("#00123456"))
    }

    @Test fun secondColorIsDirtyAndMergesWithoutLosingOtherDraftOrImportedValues() {
        val baseline = SettingsUiState(accentColorKey = "custom")
        val draft = baseline.copy(customPlaybackAccentHex = "#123456")
        assertNotEquals(baseline.editableValues(), draft.editableValues())
        val stored = baseline.copy(customAccentHex = "#FEDCBA", uiScale = .9f)
        val merged = mergeStoredSettings(draft, baseline, stored)
        assertEquals("#123456", merged.customPlaybackAccentHex)
        assertEquals("#FEDCBA", merged.customAccentHex)
        assertEquals(.9f, merged.uiScale, 0f)
        assertEquals("#123456", mergeStoredSettings(baseline, baseline, draft).customPlaybackAccentHex)
    }

    @Test fun companionSettingsCarryBothColorsAndAccountsOnlyPreservesTargetPair() {
        val source = SettingsBackup(exportedAtEpochMs = 1, accentColorKey = "custom",
            customAccentHex = "#123456", customPlaybackAccentHex = "#ABCDEF")
        val target = SettingsBackup(exportedAtEpochMs = 2, customAccentHex = "#112233", customPlaybackAccentHex = "#445566")
        val json = Json { ignoreUnknownKeys = true }
        val exported = json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.prepare(source, false))
        assertEquals("#ABCDEF", exported.customPlaybackAccentHex)
        val merged = json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(exported, target, "settings", true, false))
        assertEquals("#123456", merged.customAccentHex)
        assertEquals("#ABCDEF", merged.customPlaybackAccentHex)
        val accountsOnly = json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(source, target, "accounts", false, false))
        assertEquals(target.customAccentHex, accountsOnly.customAccentHex)
        assertEquals(target.customPlaybackAccentHex, accountsOnly.customPlaybackAccentHex)
    }

    @Test fun singleAccentIsDefaultAndDualOptInRoundTripsAndIsDirty() {
        val storage = memoryPreferences()
        val prefs = AppPreferences(storage)
        assertFalse(prefs.dualAccentEnabled)
        assertFalse(SettingsUiState().dualAccentEnabled)
        val backup = SettingsBackupManager(prefs, TokenStore(memoryPreferences(), prefs))
        prefs.dualAccentEnabled = true
        val exported = backup.exportJson(false)
        prefs.dualAccentEnabled = false
        backup.importJson(exported)
        assertTrue(AppPreferences(storage).dualAccentEnabled)
        backup.importJson("""{"schemaVersion":51,"exportedAtEpochMs":0,"customPlaybackAccentHex":"#ABCDEF"}""")
        assertFalse(prefs.dualAccentEnabled)
        val baseline = SettingsUiState()
        assertNotEquals(baseline.editableValues(), baseline.copy(dualAccentEnabled = true).editableValues())
    }
}
