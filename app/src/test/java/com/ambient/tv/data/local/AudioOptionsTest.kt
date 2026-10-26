package com.ambient.tv.data.local

import org.junit.Assert.*
import org.junit.Test

class AudioOptionsTest {
    @Test fun freshInstallDefaultsToAutomaticPassthrough() {
        val memory = memoryPreferences()
        val preferences = AppPreferences(memory)
        assertEquals("auto", preferences.audioSettings.selectedProfile)
        // Unset = "Automatisch": bitstream only for formats the output confirms (planner gate).
        assertTrue(preferences.mediaAudioPassthroughEnabled)
        assertTrue(preferences.audioSettings.currentForRoute("avr").permitsBitstream(preferences.mediaAudioPassthroughEnabled))
        // Reading the default must not persist it; a later default change still reaches this install.
        assertFalse(memory.contains(AppPreferences.KEY_MEDIA_AUDIO_PASSTHROUGH))
    }

    @Test fun explicitPassthroughOffStaysOff() {
        val memory = memoryPreferences()
        memory.edit().putBoolean(AppPreferences.KEY_MEDIA_AUDIO_PASSTHROUGH, false).commit()
        val preferences = AppPreferences(memory)
        assertFalse(preferences.mediaAudioPassthroughEnabled)
        assertFalse(preferences.audioSettings.currentForRoute("avr").permitsBitstream(preferences.mediaAudioPassthroughEnabled))
    }

    @Test fun legacyPassthroughFlagsBothOffStayOff() {
        val memory = memoryPreferences()
        memory.edit().putBoolean(AppPreferences.KEY_PLEX_AUDIO_PASSTHROUGH, false).commit()
        assertFalse(AppPreferences(memory).mediaAudioPassthroughEnabled)
        assertTrue(memory.contains(AppPreferences.KEY_MEDIA_AUDIO_PASSTHROUGH))
    }

    @Test fun forceChannelsTravelsWithTheBackupFromSchema70() {
        val source = AppPreferences(memoryPreferences())
        source.audioSettings = AudioSettings().update(AudioOptions(forceChannels = 8))
        val json = SettingsBackupManager(source, TokenStore(memoryPreferences(), source)).exportJson()
        val target = AppPreferences(memoryPreferences())
        SettingsBackupManager(target, TokenStore(memoryPreferences(), target)).importJson(json)
        assertEquals(8, target.audioSettings.current.forceChannels)

        // A pre-70 file cannot carry the setting; a hand-edited one must not force channels.
        val old = json.replace(Regex("\"schemaVersion\"\\s*:\\s*\\d+"), "\"schemaVersion\": 69")
        val oldTarget = AppPreferences(memoryPreferences())
        SettingsBackupManager(oldTarget, TokenStore(memoryPreferences(), oldTarget)).importJson(old)
        assertEquals(0, oldTarget.audioSettings.current.forceChannels)
    }

    @Test fun forceChannelsIsBoundedAndRaisesOnlyTheRoute() {
        assertEquals(0, AudioOptions(forceChannels = 4).normalized().forceChannels)
        assertEquals(8, AudioOptions(forceChannels = 8).normalized().forceChannels)
        assertEquals(6, AudioOptions(forceChannels = 6).pcmChannels(2))
        assertEquals(8, AudioOptions(forceChannels = 6).pcmChannels(8))
        // The PCM limit still wins over the forced width.
        assertEquals(2, AudioOptions(forceChannels = 8, pcmChannelLimit = 2).pcmChannels(2))
        assertEquals(2, AudioOptions().pcmChannels(2))
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
        val original = AudioOptions(output = "bitstream", night = true, dialogue = 4, normalize = true, eqEnabled = true, replayGain = "track", channels = "auto", delayMs = 125)
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

    @Test fun automaticOutputKeepsPassthroughWithSavedDsp() {
        assertTrue(AudioOptions(output = "auto").permitsBitstream(false))
        assertTrue(AudioOptions(output = "auto", eqEnabled = true).permitsBitstream(false))
        assertTrue(AudioOptions(output = "auto", night = true).permitsBitstream(false))
        assertTrue(AudioOptions(output = "bitstream", eqEnabled = true).permitsBitstream(false))
    }

    @Test fun profilesAndTrackPreferencesSurviveBackup() {
        val preferences = AppPreferences(memoryPreferences())
        val settings = AudioSettings(selectedProfile = "avr", tracks = AudioTrackPreference("description", "surround"))
            .update(AudioOptions(output = "bitstream", localAc3 = true, pcmChannelLimit = 2, delayMs = 75, night = true, eqEnabled = true, eqDb = List(10) { it - 5 }))
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
