package com.ambient.tv.data.local

import com.ambient.tv.ui.settings.editableValues
import org.junit.Assert.*
import org.junit.Test

class SubtitlePreferencesTest {
    private val tracks = listOf(SubtitleCandidate("de-full", "deu", false), SubtitleCandidate("en-forced", "eng", true), SubtitleCandidate("de-forced", "ger", true))
    private fun choose(mode: SubtitleMode, language: String = "de", fallback: String = "none", audio: String = "en", candidates: List<SubtitleCandidate<String>> = tracks) =
        SubtitleSelectionPolicy.choose(candidates, SubtitlePreferences(mode, language, fallback), audio, "de-DE")?.id

    @Test fun autoSubtitlesFollowUiOrManualAudioAcrossModes() {
        for (mode in listOf(SubtitleMode.FORCED, SubtitleMode.FULL)) {
            val id = if (mode == SubtitleMode.FORCED) "de-forced" else "de-full"
            for (prefs in listOf(SubtitlePreferences(mode, uiLanguage = "de"),
                SubtitlePreferences(mode, preferredAudioLanguage = "de", uiLanguage = "en"))) {
                assertEquals(id, SubtitleSelectionPolicy.choose(tracks, prefs, "en", "en-US")?.id)
            }
        }
        val foreign = SubtitlePreferences(SubtitleMode.OFF, fullOnForeignAudio = true, uiLanguage = "de")
        assertEquals("de-full", SubtitleSelectionPolicy.choose(tracks, foreign, "en", "en-US")?.id)
        assertNull(SubtitleSelectionPolicy.choose(tracks, foreign, "de", "en-US"))
    }

    @Test fun explicitForcedLanguageIsIndependentOfAudio() { assertEquals("de-forced", choose(SubtitleMode.FORCED)) }
    @Test fun fullModeSkipsForcedEvenWhenForcedIsFirst() { assertEquals("de-full", choose(SubtitleMode.FULL, candidates = tracks.reversed())) }
    @Test fun missingForcedDoesNotEnableFullOrWrongLanguage() { assertNull(choose(SubtitleMode.FORCED, candidates = tracks.take(2))) }
    @Test fun optionalFallbackStillRequiresForced() {
        assertEquals("en-forced", choose(SubtitleMode.FORCED, fallback = "en", candidates = tracks.take(2)))
        assertNull(choose(SubtitleMode.FORCED, fallback = "en", candidates = listOf(SubtitleCandidate("english-full", "en", false))))
    }
    @Test fun autoSubtitlesFollowRequestedLanguageEvenWhenAudioFallsBack() {
        assertEquals("de-forced", choose(SubtitleMode.FORCED, "auto"))
        assertEquals("de-full", choose(SubtitleMode.FULL, "auto"))
    }
    @Test fun offDisablesEvenSelectedForced() { assertNull(choose(SubtitleMode.OFF, candidates = tracks.map { it.copy(selected = true) })) }
    @Test fun missingLanguageIsNeverGuessedAsGerman() { assertNull(choose(SubtitleMode.FORCED, candidates = listOf(SubtitleCandidate("unknown", null, true)))) }
    @Test fun isoAndRegionalLanguagesMatch() {
        assertTrue(LanguagePreferences.matches("fra", "fr-FR")); assertTrue(LanguagePreferences.matches("fre", "fr"))
        assertTrue(LanguagePreferences.matches("jpn", "ja")); assertTrue(LanguagePreferences.matches("dut", "nl-NL"))
    }
    @Test fun legacyForcedKeepsAutomaticLanguageKey() {
        val p = AppPreferences(memoryPreferences()); p.forcedSubtitlesEnabled = true; p.preferredSubtitleLanguage = "en"
        assertEquals("forced", p.subtitleModeKey); assertEquals("auto", p.subtitleLanguageKey)
        p.subtitleLanguageKey = "de"; assertEquals("de", p.subtitlePreferences.language)
    }
    @Test fun legacyFullKeepsItsRememberedLanguage() {
        val p = AppPreferences(memoryPreferences()); p.subtitlesEnabled = true; p.preferredSubtitleLanguage = "fr"
        assertEquals(SubtitleMode.FULL, p.subtitlePreferences.mode); assertEquals("fr", p.subtitleLanguageKey)
    }
    @Test fun modeWritesCompatibleFlagsAndNormalizesInvalidValues() {
        val p = AppPreferences(memoryPreferences()); p.subtitleModeKey = "forced"
        assertTrue(p.forcedSubtitlesEnabled); assertFalse(p.subtitlesEnabled)
        p.subtitleModeKey = "full"; assertFalse(p.forcedSubtitlesEnabled); assertTrue(p.subtitlesEnabled)
        p.subtitleModeKey = "unexpected"; assertEquals("off", p.subtitleModeKey)
    }
    @Test fun policyBackupRoundTripsModeLanguageAndFallback() {
        val p = AppPreferences(memoryPreferences()); p.subtitleModeKey = "forced"; p.subtitleLanguageKey = "de"; p.subtitleFallbackLanguageKey = "en"
        val manager = SettingsBackupManager(p, TokenStore(memoryPreferences(), p)); val backup = manager.exportJson()
        p.subtitleModeKey = "off"; p.subtitleLanguageKey = "ja"; manager.importJson(backup)
        assertEquals(SubtitlePreferences(SubtitleMode.FORCED, "de", "en"), p.subtitlePreferences)
    }
    @Test fun legacyBackupReplacesNewPolicyWithoutRetainingFallback() {
        val p = AppPreferences(memoryPreferences()); p.subtitleModeKey = "full"; p.subtitleLanguageKey = "ja"; p.subtitleFallbackLanguageKey = "en"
        SettingsBackupManager(p, TokenStore(memoryPreferences(), p)).importJson("""{"schemaVersion":33,"exportedAtEpochMs":0,"forcedSubtitlesEnabled":true,"preferredSubtitleLanguage":"fr"}""")
        assertEquals(SubtitlePreferences(SubtitleMode.FORCED, "auto", "none"), p.subtitlePreferences)
    }
    @Test fun foreignAudioEnablesFullFromOffAndForced() {
        for (mode in listOf(SubtitleMode.OFF, SubtitleMode.FORCED)) {
            val prefs = SubtitlePreferences(mode, fullOnForeignAudio = true)
            assertEquals("de-full", SubtitleSelectionPolicy.choose(tracks, prefs, "eng", "de-DE")?.id)
        }
    }
    @Test fun returningToPreferredAudioRestoresNormalMode() {
        val prefs = SubtitlePreferences(SubtitleMode.FORCED, fullOnForeignAudio = true)
        assertEquals("de-full", SubtitleSelectionPolicy.choose(tracks, prefs, "en", "de")?.id)
        assertEquals("de-forced", SubtitleSelectionPolicy.choose(tracks, prefs, "ger", "de-DE")?.id)
        assertNull(SubtitleSelectionPolicy.choose(tracks, prefs.copy(mode = SubtitleMode.OFF), "de", "de"))
    }
    @Test fun foreignAudioUsesExplicitAudioPreferenceAndSubtitleLanguage() {
        val prefs = SubtitlePreferences(SubtitleMode.OFF, "de", fullOnForeignAudio = true, preferredAudioLanguage = "fr")
        assertEquals("de-full", SubtitleSelectionPolicy.choose(tracks, prefs, "en", "en-US")?.id)
        assertNull(SubtitleSelectionPolicy.choose(tracks, prefs, "fra", "en-US"))
    }
    @Test fun unknownAudioDoesNotActivateFullSubtitles() {
        val prefs = SubtitlePreferences(SubtitleMode.OFF, fullOnForeignAudio = true)
        for (audio in listOf(null, "", "und", "zxx", "mul")) {
            assertNull(SubtitleSelectionPolicy.choose(tracks, prefs, audio, "de"))
        }
    }
    @Test fun foreignAudioUsesFullFallbackButNeverForcedSubstitute() {
        val prefs = SubtitlePreferences(SubtitleMode.FORCED, "fr", "de", fullOnForeignAudio = true)
        assertEquals("de-full", SubtitleSelectionPolicy.choose(tracks, prefs, "en", "de")?.id)
        assertNull(SubtitleSelectionPolicy.choose(tracks.filter { it.forced }, prefs, "en", "de"))
    }
    @Test fun foreignAudioOptionPersistsAndBackupRestoresIt() {
        val p = AppPreferences(memoryPreferences())
        assertFalse(p.fullSubtitlesOnForeignAudio)
        p.fullSubtitlesOnForeignAudio = true
        p.audioLanguageKey = "fr"
        val manager = SettingsBackupManager(p, TokenStore(memoryPreferences(), p))
        val json = manager.exportJson()
        p.fullSubtitlesOnForeignAudio = false
        manager.importJson(json)
        assertTrue(p.subtitlePreferences.fullOnForeignAudio)
        assertEquals("fr", p.subtitlePreferences.preferredAudioLanguage)
        manager.importJson("""{"schemaVersion":52,"exportedAtEpochMs":0}""")
        assertFalse(p.fullSubtitlesOnForeignAudio)
    }
    @Test fun foreignAudioOptionSurvivesSettingsMergeAndCompanionTransfer() {
        val baseline = com.ambient.tv.ui.settings.SettingsUiState()
        val draft = baseline.copy(fullSubtitlesOnForeignAudio = true)
        assertNotEquals(baseline.editableValues(), draft.editableValues())
        assertTrue(com.ambient.tv.ui.settings.mergeStoredSettings(draft, baseline, baseline).fullSubtitlesOnForeignAudio)
        assertTrue(com.ambient.tv.ui.settings.mergeStoredSettings(baseline, baseline, draft).fullSubtitlesOnForeignAudio)
        val incoming = SettingsBackup(exportedAtEpochMs = 0, fullSubtitlesOnForeignAudio = true)
        val policy = com.ambient.tv.companion.CompanionBackupPolicy
        val wire = policy.prepare(incoming, false, "settings")
        val exported = policy.json.decodeFromString<SettingsBackup>(wire)
        val result = policy.json.decodeFromString<SettingsBackup>(policy.merge(
            exported, SettingsBackup(exportedAtEpochMs = 0), "settings", true, false))
        assertTrue(result.fullSubtitlesOnForeignAudio)
    }
}
