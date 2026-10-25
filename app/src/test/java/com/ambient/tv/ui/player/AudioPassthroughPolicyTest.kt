package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPassthroughPolicyTest {
    @Test fun bitstreamCodecsAreRecognized() {
        listOf("ac3", "eac3", "eac3-joc", "atmos", "dts", "dts-hd", "dts-hra", "dca-ma", "truehd", "truehd-atmos").forEach {
            assertTrue(it, AudioPassthroughPolicy.isBitstreamCodec(it))
        }
    }

    @Test fun pcmDecodedFormatsRemainVisualizable() {
        listOf("aac", "flac", "mp3", "opus", "pcm_s16le").forEach {
            assertFalse(it, AudioPassthroughPolicy.isBitstreamActive(true, it))
        }
    }

    @Test fun disabledSettingNeverMarksBitstreamActive() {
        assertFalse(AudioPassthroughPolicy.isBitstreamActive(false, "ac3"))
    }

    @Test fun reportedAudioProfilesOverrideTheLegacyChannelUnion() {
        // API 31+ PCM profiles with masks: a 7.1 legacy mask (e.g. of a compressed format) does not count.
        org.junit.Assert.assertEquals(2, routePcmChannels(profiled = 2, legacy = 8))
        org.junit.Assert.assertEquals(6, routePcmChannels(profiled = 6, legacy = 8))
        // No PCM profile with masks (only compressed profiles, or masks missing): legacy fields count.
        org.junit.Assert.assertEquals(8, routePcmChannels(profiled = null, legacy = 8))
        // Before API 31 or with empty audioProfiles the legacy fields are all there is.
        org.junit.Assert.assertEquals(8, routePcmChannels(profiled = null, legacy = 8))
        org.junit.Assert.assertEquals(2, routePcmChannels(profiled = null, legacy = null))
        org.junit.Assert.assertEquals(2, routePcmChannels(profiled = null, legacy = 0))
    }

    private val mask51 = android.media.AudioFormat.CHANNEL_OUT_5POINT1
    private val mask71 = android.media.AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
    private val stereo = android.media.AudioFormat.CHANNEL_OUT_STEREO

    @Test fun onlyCompressedProfilesFallBackToLegacyChannels() {
        val profiles = listOf(android.media.AudioFormat.ENCODING_E_AC3 to listOf(mask71), android.media.AudioFormat.ENCODING_AC3 to listOf(mask51))
        val profiled = profiledPcmChannels(profiles)
        org.junit.Assert.assertNull(profiled)
        org.junit.Assert.assertEquals(8, routePcmChannels(profiled, legacy = 8))
    }

    @Test fun pcmProfileWithoutMasksFallsBackToLegacyChannels() {
        val profiled = profiledPcmChannels(listOf(android.media.AudioFormat.ENCODING_PCM_16BIT to emptyList()))
        org.junit.Assert.assertEquals(6, routePcmChannels(profiled, legacy = 6))
    }

    @Test fun pcmProfileMaskStaysAuthoritativeOverLegacy() {
        val profiles = listOf(
            android.media.AudioFormat.ENCODING_PCM_16BIT to listOf(stereo),
            android.media.AudioFormat.ENCODING_PCM_FLOAT to listOf(mask51),
            android.media.AudioFormat.ENCODING_E_AC3 to listOf(mask71),
        )
        org.junit.Assert.assertEquals(6, routePcmChannels(profiledPcmChannels(profiles), legacy = 8))
        org.junit.Assert.assertEquals(2, routePcmChannels(profiledPcmChannels(listOf(android.media.AudioFormat.ENCODING_PCM_16BIT to listOf(stereo))), legacy = 8))
    }

    @Test fun legacyOnlyBeforeApi31IsUnchanged() {
        // API < 31 never reads audioProfiles: profiled is empty, the legacy maximum decides.
        val profiled = profiledPcmChannels(emptyList())
        org.junit.Assert.assertEquals(8, routePcmChannels(profiled, legacy = 8))
        org.junit.Assert.assertEquals(6, routePcmChannels(profiled, legacy = 6))
        org.junit.Assert.assertEquals(2, routePcmChannels(profiled, legacy = 2))
    }

    @Test fun routeLogLineNamesRouteAndDevices() {
        val line = audioRouteLogLine(
            AudioRouteCapabilities(passthrough = setOf("eac3", "ac3"), pcmChannels = 8, profile = "avr"),
            listOf(AudioRouteDevice(android.media.AudioDeviceInfo.TYPE_HDMI, emptySet(), 8, "AVR", "profiles=3")),
        )
        org.junit.Assert.assertEquals(
            "Audio route pcmChannels=8 profile=avr passthrough=ac3,eac3 devices=1 | type=9 name=AVR pcmChannels=8 profiles=3", line)
    }
}
