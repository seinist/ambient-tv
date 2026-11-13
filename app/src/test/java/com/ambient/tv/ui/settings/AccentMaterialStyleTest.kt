package com.ambient.tv.ui.settings

import com.ambient.tv.companion.CompanionBackupPolicy
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.data.local.SettingsBackupManager
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.ui.theme.AccentStyle
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AccentMaterialStyleTest {
    @Test fun allFiveStylesHaveStableDistinctKeysAndUnknownFallsBackToSatin() {
        assertEquals(listOf("satin", "glass", "glow", "pearl", "light"), AccentStyle.entries.map { it.key })
        assertEquals(AccentStyle.SATIN, AccentStyle.fromKey(null))
        assertEquals(AccentStyle.SATIN, AccentStyle.fromKey("invalid"))
        AccentStyle.entries.forEach { assertEquals(it, AccentStyle.fromKey(it.key)) }
    }

    @Test fun preferencePersistsAndRejectsUnknownKeys() {
        val storage = memoryPreferences()
        val p = AppPreferences(storage)
        assertEquals("satin", p.accentStyleKey)
        p.accentStyleKey = "light"
        assertEquals("light", AppPreferences(storage).accentStyleKey)
        p.accentStyleKey = "unknown"
        assertEquals("satin", AppPreferences(storage).accentStyleKey)
    }

    @Test fun backupRestoresStyleAndLegacyBackupsGetNewDefault() {
        val storage = memoryPreferences()
        val p = AppPreferences(storage)
        val manager = SettingsBackupManager(p, TokenStore(memoryPreferences(), p))
        p.accentStyleKey = "pearl"
        val exported = manager.exportJson(false)
        p.accentStyleKey = "glass"
        manager.importJson(exported)
        assertEquals("pearl", AppPreferences(storage).accentStyleKey)
        manager.importJson("""{"schemaVersion":51,"exportedAtEpochMs":0,"accentColorKey":"blue"}""")
        assertEquals("satin", p.accentStyleKey)
    }

    @Test fun styleIsDirtyAndUnrelatedStoredRefreshPreservesEditedDraft() {
        val baseline = SettingsUiState()
        val draft = baseline.withAccentStyleKey("glow")
        val stored = baseline.copy(accentColorKey = "purple")
        assertNotEquals(baseline.editableValues(), draft.editableValues())
        val merged = mergeStoredSettings(draft, baseline, stored)
        assertEquals("glow", merged.accentStyleKey)
        assertEquals("purple", merged.accentColorKey)
        assertEquals("dark", merged.displayThemeModeKey)
        assertEquals("light", mergeStoredSettings(baseline, baseline, baseline.withAccentStyleKey("light")).accentStyleKey)
    }

    @Test fun companionSettingsIncludeStyleButAccountsOnlyDoNotOverrideIt() {
        val source = SettingsBackup(exportedAtEpochMs = 1, accentStyleKey = "glass")
        val target = SettingsBackup(exportedAtEpochMs = 2, accentStyleKey = "light")
        val json = Json { ignoreUnknownKeys = true }
        val data = json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.prepare(source, false))
        assertEquals("glass", data.accentStyleKey)
        val settings = json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(source, target, "settings", true, false))
        assertEquals("glass", settings.accentStyleKey)
        val accounts = json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(source, target, "accounts", false, false))
        assertEquals("light", accounts.accentStyleKey)
    }
}
