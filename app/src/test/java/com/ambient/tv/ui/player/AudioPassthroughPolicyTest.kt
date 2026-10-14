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
}
