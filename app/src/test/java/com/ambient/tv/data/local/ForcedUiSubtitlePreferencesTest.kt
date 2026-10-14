package com.ambient.tv.data.local

import com.ambient.tv.companion.CompanionBackupPolicy
import com.ambient.tv.ui.settings.SettingsUiState
import com.ambient.tv.ui.settings.editableValues
import com.ambient.tv.ui.settings.mergeStoredSettings
import org.junit.Assert.*
import org.junit.Test

class ForcedUiSubtitlePreferencesTest {
    private val tracks = listOf(
        SubtitleCandidate("de-full", "deu", false),
        SubtitleCandidate("en-full", "eng", false),
        SubtitleCandidate("de-forced", "ger", true),
        SubtitleCandidate("en-forced", "en", true),
    )
    private val preferences = SubtitlePreferences(forcedInUiLanguage = true, uiLanguage = "de")
    private fun choose(p: SubtitlePreferences = preferences, audio: String? = "en", system: String = "en-US") =
        SubtitleSelectionPolicy.choose(tracks, p, audio, system)?.id

    @Test fun forcedUiWorksFromOffForEveryAudioLanguage() {
        for (audio in listOf("de", "en", "fr", null, "und")) assertEquals("de-forced", choose(audio = audio))
    }
    @Test fun uiLanguageOverridesSubtitleLanguageAndDeviceLanguage() {
        assertEquals("de-forced", choose(preferences.copy(mode = SubtitleMode.FORCED, language = "en")))
        assertEquals("en-forced", choose(preferences.copy(uiLanguage = "en"), system = "de-DE"))
    }
    @Test fun autoUiUsesDeviceLanguage() {
        assertEquals("de-forced", choose(preferences.copy(uiLanguage = "auto"), system = "de-DE"))
    }
    @Test fun missingUiForcedNeverSelectsFullOrFallbackLanguage() {
        val p = preferences.copy(uiLanguage = "fr", fallbackLanguage = "en")
        assertNull(choose(p))
        assertNull(SubtitleSelectionPolicy.choose(tracks.filterNot { it.forced }, preferences, "en", "de"))
    }
    @Test fun fullModeRetainsItsOwnLanguage() {
        assertEquals("en-full", choose(preferences.copy(mode = SubtitleMode.FULL, language = "en")))
    }
    @Test fun foreignAudioFullTakesPriorityAndSwitchingBackRestoresUiForced() {
        val p = preferences.copy(fullOnForeignAudio = true, preferredAudioLanguage = "de", language = "en")
        assertEquals("en-full", choose(p, audio = "fr"))
        assertEquals("de-forced", choose(p, audio = "deu"))
    }
    @Test fun disabledOptionPreservesOriginalMode() {
        assertNull(choose(preferences.copy(forcedInUiLanguage = false)))
        assertEquals("de-forced", choose(preferences.copy(forcedInUiLanguage = false, mode = SubtitleMode.FORCED)))
    }
    @Test fun preferenceAndBackupRoundTripAndOldBackupDefaultsOff() {
        val p = AppPreferences(memoryPreferences())
        assertFalse(p.forcedSubtitlesInUiLanguage)
        p.forcedSubtitlesInUiLanguage = true
        p.uiLanguageKey = "de"
        assertEquals(preferences, p.subtitlePreferences)
        val manager = SettingsBackupManager(p, TokenStore(memoryPreferences(), p))
        val backup = manager.exportJson()
        p.forcedSubtitlesInUiLanguage = false
        p.uiLanguageKey = "en"
        manager.importJson(backup)
        assertEquals(preferences, p.subtitlePreferences)
        manager.importJson("""{"schemaVersion":53,"exportedAtEpochMs":0}""")
        assertFalse(p.forcedSubtitlesInUiLanguage)
    }
    @Test fun settingsMergeRetainsDraftAndImportsStoredValue() {
        val baseline = SettingsUiState()
        val draft = baseline.copy(forcedSubtitlesInUiLanguage = true)
        assertNotEquals(baseline.editableValues(), draft.editableValues())
        assertTrue(mergeStoredSettings(draft, baseline, baseline).forcedSubtitlesInUiLanguage)
        assertTrue(mergeStoredSettings(baseline, baseline, draft).forcedSubtitlesInUiLanguage)
    }
    @Test fun companionTransfersOptionWithUiLanguage() {
        val source = SettingsBackup(exportedAtEpochMs = 0, forcedSubtitlesInUiLanguage = true, uiLanguageKey = "de")
        val wire = CompanionBackupPolicy.prepare(source, false, "settings")
        val incoming = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(wire)
        val result = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(
            incoming, SettingsBackup(exportedAtEpochMs = 0), "settings", true, false))
        assertTrue(result.forcedSubtitlesInUiLanguage)
        assertEquals("de", result.uiLanguageKey)
    }
}
