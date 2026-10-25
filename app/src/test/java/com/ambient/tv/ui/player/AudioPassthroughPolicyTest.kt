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
        // API 31+ with profiles: a 7.1 legacy mask (e.g. of a compressed format) does not count.
        org.junit.Assert.assertEquals(2, routePcmChannels(profilesReported = true, profiled = 2, legacy = 8))
        org.junit.Assert.assertEquals(6, routePcmChannels(profilesReported = true, profiled = 6, legacy = 8))
        // Profiles without any PCM entry: stereo, not the legacy maximum.
        org.junit.Assert.assertEquals(2, routePcmChannels(profilesReported = true, profiled = null, legacy = 8))
        // Before API 31 or with empty audioProfiles the legacy fields are all there is.
        org.junit.Assert.assertEquals(8, routePcmChannels(profilesReported = false, profiled = null, legacy = 8))
        org.junit.Assert.assertEquals(2, routePcmChannels(profilesReported = false, profiled = null, legacy = null))
        org.junit.Assert.assertEquals(2, routePcmChannels(profilesReported = false, profiled = null, legacy = 0))
    }
}
