package com.ambient.tv.data.local

import com.ambient.tv.domain.model.MediaTrack
import com.ambient.tv.domain.model.MediaTrackType
import com.ambient.tv.ui.player.AudioRouteCapabilities
import com.ambient.tv.ui.player.shouldConvertAudioOnServer
import com.ambient.tv.ui.player.serverAudioTarget
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AudioConversionTest {
    private fun track(id: Long, title: String, channels: Int) = MediaTrack(
        id, MediaTrackType.AUDIO, "eac3", "German", "de", title, channels, false, false, null,
    )
    @Test fun embyAndJellyfinRequestTheChosenTrackAndCodecInsteadOfDirectStreamingIt() {
        val query = com.ambient.tv.data.remote.serverAudioConversionQuery("ac3", false, 7, "session")
        assertEquals("ac3", query["AudioCodec"])
        assertEquals("7", query["AudioStreamIndex"])
        assertEquals("false", query["AllowAudioStreamCopy"])
        assertEquals("true", query["AllowVideoStreamCopy"])
        assertEquals("6", query["TranscodingMaxAudioChannels"])
        assertEquals("session", query["PlaySessionId"])
        val pcm = com.ambient.tv.data.remote.serverAudioConversionQuery("aac", false, 3, null)
        assertEquals("aac", pcm["AudioCodec"])
        assertEquals("2", pcm["TranscodingMaxAudioChannels"])
    }
    @Test fun plexDisplayLabelDoesNotHideCommentaryMetadata() {
        val label = com.ambient.tv.data.remote.dto.plexStreamTitle("German (AAC Stereo)", "Commentary", true)
        val commentary = track(10, requireNotNull(label), 2)
        assertEquals(11L, audioTrackForConversion(listOf(commentary, track(11, "Hauptton", 8)), "de", AudioTrackPreference())?.id)
    }
    @Test fun oldProfilesDefaultToLocalAndServerChoiceRoundTrips() {
        assertEquals("local", Json.decodeFromString<AudioOptions>("{}").conversionLocation)
        val options = AudioOptions(conversionLocation = "server")
        assertEquals(options, Json.decodeFromString<AudioOptions>(Json.encodeToString(options)))
        assertEquals("local", options.copy(conversionLocation = "unknown").normalized().conversionLocation)
    }
    @Test fun conversionChoiceSurvivesSettingsBackup() {
        val prefs = AppPreferences(memoryPreferences())
        val manager = SettingsBackupManager(prefs, TokenStore(memoryPreferences(), prefs))
        prefs.audioSettings = AudioSettings(profiles = mapOf("tv" to AudioOptions(conversionLocation = "server")))
        val backup = manager.exportJson()
        prefs.audioSettings = AudioSettings()
        val exportedVersion = manager.previewJson(backup).schemaVersion
        assertEquals(exportedVersion, manager.importJson(backup).schemaVersion)
        assertEquals("server", prefs.audioSettings.profiles.getValue("tv").conversionLocation)
    }
    @Test fun serverConversionKeepsExplicitTrackAndAvoidsFirstCommentaryByDefault() {
        val tracks = listOf(track(10, "Kommentar", 2), track(11, "Hauptton", 8), track(12, "Kommentar 2", 2))
        assertEquals(11L, audioTrackForConversion(tracks, "de", AudioTrackPreference())?.id)
        assertEquals(12L, audioTrackForConversion(tracks, "de", AudioTrackPreference(), 12)?.id)
        assertThrows(IllegalArgumentException::class.java) { audioTrackForConversion(tracks, "de", AudioTrackPreference(), 99) }
    }
    @Test fun onlyExplicitServerModeRequestsServerAndHonorsTheOutputRoute() {
        val route = AudioRouteCapabilities(passthrough = setOf("ac3"))
        assertFalse(shouldConvertAudioOnServer(AudioOptions(), "eac3", 8, route, false))
        val server = AudioOptions(conversionLocation = "server", output = "auto", localAc3 = true)
        assertTrue(shouldConvertAudioOnServer(server, "eac3", 8, route, true))
        assertFalse(shouldConvertAudioOnServer(server, "ac3", 6, route, true))
        assertFalse(shouldConvertAudioOnServer(server.copy(localFallback = "off"), "eac3", 8, route, false))
        assertEquals("ac3", serverAudioTarget(server, route, 8))
        // The server path needs no separate AC3 switch; a stereo mix keeps AAC.
        assertEquals("ac3", serverAudioTarget(server.copy(localAc3 = false), route, 8))
        assertEquals("aac", serverAudioTarget(server.copy(channels = "stereo"), route, 8))
        assertEquals("aac", serverAudioTarget(server, route.copy(passthrough = emptySet()), 8))
        assertEquals("aac", serverAudioTarget(server.copy(output = "pcm"), route, 8))
    }
}
