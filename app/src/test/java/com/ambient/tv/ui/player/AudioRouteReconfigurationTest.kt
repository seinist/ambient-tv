package com.ambient.tv.ui.player

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.*
import org.junit.Test

class AudioRouteReconfigurationTest {
    private val hdmi = AudioRouteDevice(AudioDeviceInfo.TYPE_HDMI, setOf(AudioFormat.ENCODING_AC3), 8)
    private val headphones = AudioRouteDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, emptySet(), 2)

    @Test fun legacyHeadphonesNeverInheritConnectedHdmiCapabilities() {
        val selected = selectAudioRouteDevices(listOf(hdmi, headphones), false)
        assertEquals(listOf(headphones), selected)
        assertFalse(routeAcceptsEncoding(selected, AudioFormat.ENCODING_AC3))
        assertEquals(listOf(hdmi), selectAudioRouteDevices(listOf(hdmi), true))
        assertFalse(routeAcceptsEncoding(listOf(hdmi, headphones), AudioFormat.ENCODING_AC3))
    }
    @Test fun opticalOutputParticipatesInAc3Negotiation() {
        val optical = hdmi.copy(type = AudioDeviceInfo.TYPE_LINE_DIGITAL, pcmChannels = 2)
        assertTrue(routeAcceptsEncoding(selectAudioRouteDevices(listOf(optical), false), AudioFormat.ENCODING_AC3))
    }
    @Test fun pcmWidthIsTheWidestRelevantDeviceNotTheMinimum() {
        val speaker = headphones.copy(type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val ambiguous = selectAudioRouteDevices(listOf(hdmi, speaker), false)
        assertEquals(8, routedPcmChannels(ambiguous))
        assertEquals(2, routedPcmChannels(selectAudioRouteDevices(listOf(hdmi, headphones), false)))
        assertEquals(8, routedPcmChannels(selectAudioRouteDevices(listOf(hdmi), true)))
        assertEquals(2, routedPcmChannels(emptyList()))
        assertEquals(2, routedPcmChannels(listOf(speaker.copy(pcmChannels = 0))))
    }
    @Test fun legacySpeakerAndHdmiAreAmbiguousUntilTheRouteIsKnown() {
        val speaker = headphones.copy(type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertFalse(routeAcceptsEncoding(selectAudioRouteDevices(listOf(hdmi, speaker), false), AudioFormat.ENCODING_AC3))
        assertTrue(routeAcceptsEncoding(selectAudioRouteDevices(listOf(hdmi), true), AudioFormat.ENCODING_AC3))
    }
    @Test fun automaticProfileTracksOnlyConfirmedOutputAndKeepsManualSelection() {
        val speaker = headphones.copy(type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val settings = com.ambient.tv.data.local.AudioSettings()
            .updateForRoute(AudioOptions(night = true), "avr")
        assertEquals("avr", routedAudioProfile(selectAudioRouteDevices(listOf(hdmi), true)))
        assertEquals("headphones", routedAudioProfile(selectAudioRouteDevices(listOf(hdmi, headphones), false)))
        assertEquals("tv", routedAudioProfile(selectAudioRouteDevices(listOf(hdmi, speaker), false)))
        assertTrue(settings.currentForRoute("avr").night)
        assertFalse(settings.currentForRoute("headphones").night)
        assertTrue(settings.copy(selectedProfile = "avr").currentForRoute("headphones").night)
    }
    @Test fun routeProfileChangeAppliesSavedDspWithoutChangingOutputMode() {
        val preferences = AppPreferences(memoryPreferences())
        preferences.audioSettings = com.ambient.tv.data.local.AudioSettings()
            .updateForRoute(AudioOptions(night = true), "avr")
        val session = AudioSession(preferences, "media3", false)
        var applied = false
        session.apply = { before, after -> applied = !before.night && after.night }
        session.updateRoute(AudioRouteCapabilities(profile = "avr"))
        assertTrue(applied)
        assertTrue(session.options.night)
    }
    @Test fun dspTransitionsRebuildEncodedMedia3AndChannelChangingUpmix() {
        val before = AudioOptions(output = "auto")
        for (after in listOf(before.copy(night = true), before.copy(eqEnabled = true), before.copy(dialogue = 2), before.copy(normalize = true))) {
            assertTrue(Media3AudioProcessing.requiresAudioReconfiguration(before, after, true))
            assertTrue(Media3AudioProcessing.requiresAudioReconfiguration(after, before, true))
        }
        assertFalse(Media3AudioProcessing.requiresAudioReconfiguration(before.copy(output = "pcm"), before.copy(output = "pcm", night = true), true))
        assertTrue(Media3AudioProcessing.requiresAudioReconfiguration(before.copy(channels = "5.1"), before.copy(channels = "5.1", upmix = true), true))
    }
    @Test fun hotplugReconfiguresSessionWithoutAnAudioPanel() {
        val session = AudioSession(AppPreferences(memoryPreferences()), "mpv", true)
        session.sourceCodec = "ac3"; session.sourceChannels = 6
        session.route = AudioRouteCapabilities(setOf("ac3"), 8, true)
        var change: Pair<AudioOutputMode, AudioOutputMode>? = null
        session.routeChanged = { before, after -> change = before.mode to after.mode }
        session.updateRoute(AudioRouteCapabilities(pcmChannels = 2, mpvAvailable = true))
        assertEquals(AudioOutputMode.PASSTHROUGH to AudioOutputMode.PCM, change)
        session.update(AudioOptions(output = "pcm", channels = "7.1"))
        assertEquals("stereo", session.optionsForPcm().channels)
    }
    @Test fun aFreshPlaybackCanRedirectAgainButOnePlaybackCannotLoop() {
        AudioEngineRedirectGuard.reset()
        assertTrue(AudioEngineRedirectGuard.once("same-channel"))
        assertFalse(AudioEngineRedirectGuard.once("same-channel"))
        AudioEngineRedirectGuard.reset()
        assertTrue(AudioEngineRedirectGuard.once("same-channel"))
        AudioEngineRedirectGuard.reset()
    }
}
