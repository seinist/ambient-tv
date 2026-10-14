package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvAoRetryTest {
    @Test fun onlyBitstreamCodecsGetAPassthroughRetry() {
        listOf("ac3", "eac3", "truehd", "dts", "DTS").forEach { assertTrue(it, mpvBitstreamCodec(it)) }
        listOf("aac", "mp3", "flac", "opus", "pcm_s16le", null).forEach { assertFalse("$it", mpvBitstreamCodec(it)) }
    }
}
