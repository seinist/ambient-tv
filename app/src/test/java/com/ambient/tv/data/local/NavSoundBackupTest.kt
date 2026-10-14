package com.ambient.tv.data.local

import com.ambient.tv.ui.settings.SettingsUiState
import com.ambient.tv.ui.settings.mergeStoredSettings
import com.ambient.tv.ui.sound.NavSoundBank
import com.ambient.tv.ui.sound.NavSoundDesign
import com.ambient.tv.ui.sound.UiSound
import org.junit.Assert.*
import org.junit.Test

class NavSoundBackupTest {
    @Test fun soundDesignsSurviveSettingsBackupAndOldBackupsPreserveExistingDesigns() {
        val source = AppPreferences(memoryPreferences())
        val bank = NavSoundBank().with(UiSound.BACK, NavSoundDesign.preset("deep", UiSound.BACK)).encode()
        source.navSoundBank = bank
        val exported = SettingsBackupManager(source, TokenStore(memoryPreferences(), source)).exportJson()
        val target = AppPreferences(memoryPreferences())
        val manager = SettingsBackupManager(target, TokenStore(memoryPreferences(), target))
        manager.importJson(exported)
        assertEquals(bank, target.navSoundBank)
        manager.importJson("""{"schemaVersion":49,"exportedAtEpochMs":0}""")
        assertEquals(bank, target.navSoundBank)
    }

    @Test fun externalSettingsRefreshDoesNotDiscardAnEditedSoundDraft() {
        val baseline = SettingsUiState()
        val draft = baseline.copy(navSoundBank = NavSoundBank().encode())
        val stored = baseline.copy(navSoundsVolume = .6f)
        val merged = mergeStoredSettings(draft, baseline, stored)
        assertEquals(draft.navSoundBank, merged.navSoundBank)
        assertEquals(.6f, merged.navSoundsVolume)
        assertEquals(draft.navSoundBank, mergeStoredSettings(baseline, baseline, draft).navSoundBank)
    }
}
