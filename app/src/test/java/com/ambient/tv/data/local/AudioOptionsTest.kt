package com.ambient.tv.data.local

import org.junit.Assert.*
import org.junit.Test

class AudioOptionsTest {
    @Test fun automaticProfileDoesNotEnableGlobalPassthrough() {
        val preferences = AppPreferences(memoryPreferences())
        assertEquals("auto", preferences.audioSettings.selectedProfile)
        assertFalse(preferences.mediaAudioPassthroughEnabled)
        assertFalse(preferences.audioSettings.currentForRoute("avr").permitsBitstream(preferences.mediaAudioPassthroughEnabled))
    }
    @Test fun legacyAudioProfileDefaultsToAutomaticLocalRecovery() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val options = json.decodeFromString<AudioOptions>("""{"localAc3":false,"output":"source"}""")
        assertEquals("auto", options.localFallback)
        assertEquals("auto", options.copy(localFallback = "invalid").normalized().localFallback)
    }
    @Test fun importedValuesAreBoundedAndUnknownCodecsRemoved() {
        val o = AudioOptions(output = "invalid", delayMs = 9000, channels = "oops", allowedCodecs = listOf("dts", "dts", "injection"), eqDb = listOf(-99, 99), preampDb = 99).normalized()
        assertEquals("source", o.output)
        assertEquals(2000, o.delayMs)
        assertEquals("auto", o.channels)
        assertEquals(listOf("dts"), o.allowedCodecs)
        assertEquals(listOf(-12, 12) + List(8) { 0 }, o.eqDb)
        assertEquals(0, o.preampDb)
        assertTrue(AudioOptions(allowedCodecs = listOf("dts-hd")).normalized().allowedCodecs.isEmpty())
    }

    @Test fun profilesStayIndependent() {
        val settings = AudioSettings().update(AudioOptions(delayMs = 125, night = true))
            .copy(selectedProfile = "headphones").update(AudioOptions(delayMs = -250, channels = "stereo"))
        assertEquals(125, settings.profiles.getValue("tv").delayMs)
        assertTrue(settings.profiles.getValue("tv").night)
        assertEquals(-250, settings.current.delayMs)
        assertFalse(settings.current.night)
    }

    @Test fun passthroughSuspendsAllDspWithoutDiscardingSettings() {
        val original = AudioOptions(output = "bitstream", night = true, dialogue = 4, normalize = true, eqEnabled = true, replayGain = "track", channels = "mono", delayMs = 125)
        val applied = original.pcmOptions(false)
        assertFalse(applied.night || applied.normalize || applied.eqEnabled)
        assertEquals(0, applied.dialogue)
        assertEquals("off", applied.replayGain)
        assertEquals("auto", applied.channels)
        assertEquals(125, applied.delayMs)
        assertTrue(original.night)
    }

    @Test fun explicitPcmOverridesLegacyPassthrough() {
        assertFalse(AudioOptions(output = "pcm").permitsBitstream(true))
        assertTrue(AudioOptions(output = "source").permitsBitstream(true))
        assertFalse(AudioOptions(output = "source").permitsBitstream(false))
        assertTrue(AudioOptions(output = "auto").permitsBitstream(false))
    }

    @Test fun automaticOutputUsesPcmWhenProfileRequestsDsp() {
        assertTrue(AudioOptions(output = "auto").permitsBitstream(false))
        assertFalse(AudioOptions(output = "auto", eqEnabled = true).permitsBitstream(false))
        assertFalse(AudioOptions(output = "auto", night = true).permitsBitstream(false))
        assertTrue(AudioOptions(output = "bitstream", eqEnabled = true).permitsBitstream(false))
    }

    @Test fun profilesAndTrackPreferencesSurviveBackup() {
        val preferences = AppPreferences(memoryPreferences())
        val settings = AudioSettings(selectedProfile = "avr", tracks = AudioTrackPreference("description", "surround"))
            .update(AudioOptions(output = "pcm", localFallback = "pcm", delayMs = 75, night = true, eqEnabled = true, eqDb = List(10) { it - 5 }))
        preferences.audioSettings = settings
        val backup = SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences)).exportJson()
        val target = AppPreferences(memoryPreferences())
        SettingsBackupManager(target, TokenStore(memoryPreferences(), target)).importJson(backup)
        assertEquals(settings, target.audioSettings)
    }

    @Test fun oldBackupClearsStaleProfilesAndKeepsSourcePolicy() {
        val preferences = AppPreferences(memoryPreferences())
        preferences.audioSettings = AudioSettings().update(AudioOptions(output = "bitstream", night = true))
        SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences)).importJson("""{"schemaVersion":30,"exportedAtEpochMs":0,"plexAudioPassthroughEnabled":true}""")
        assertEquals(AudioSettings(), preferences.audioSettings)
        assertTrue(preferences.mediaAudioPassthroughEnabled)
    }

    @Test fun legacyLocalPassthroughMigratesWithOr() {
        val memory = memoryPreferences()
        memory.edit()
            .putBoolean(AppPreferences.KEY_PLEX_AUDIO_PASSTHROUGH, false)
            .putBoolean(AppPreferences.KEY_TVH_AUDIO_PASSTHROUGH, true)
            .commit()
        val preferences = AppPreferences(memory)
        assertTrue(preferences.mediaAudioPassthroughEnabled)
        assertTrue(memory.contains(AppPreferences.KEY_MEDIA_AUDIO_PASSTHROUGH))
        assertFalse(memory.contains(AppPreferences.KEY_PLEX_AUDIO_PASSTHROUGH))
        assertFalse(memory.contains(AppPreferences.KEY_TVH_AUDIO_PASSTHROUGH))
    }
}
