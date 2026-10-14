package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class LocalAudioFallbackPolicyTest {
    @Test fun explicitMedia3CanRecoverLocallyAndExhaustionTerminates() {
        val tried = mutableSetOf("media3")
        assertEquals("mpv", LocalAudioFallbackPolicy.nextBackend("media3", tried, true))
        tried.add("mpv")
        assertEquals("vlc", LocalAudioFallbackPolicy.nextBackend("mpv", tried, true))
        tried.add("vlc")
        assertNull(LocalAudioFallbackPolicy.nextBackend("vlc", tried, true))
    }

    @Test fun missingMpvUsesVlcAndNeverRevisitsIt() {
        assertEquals("vlc", LocalAudioFallbackPolicy.nextBackend("auto", emptySet(), false))
        assertNull(LocalAudioFallbackPolicy.nextBackend("vlc", setOf("media3", "vlc"), false))
    }

    @Test fun disabledRecoveryOrDolbyVisionStopsWithoutAnUnsafeRenderer() {
        assertNull(LocalAudioFallbackPolicy.nextBackend("media3", emptySet(), true, enabled = false))
        assertNull(LocalAudioFallbackPolicy.nextBackend("media3", emptySet(), true, videoCodec = "hevc", dynamicRange = "Dolby Vision"))
        assertEquals("mpv", LocalAudioFallbackPolicy.nextBackend("media3", emptySet(), true, videoCodec = "hevc", dynamicRange = "HDR10"))
    }
}
