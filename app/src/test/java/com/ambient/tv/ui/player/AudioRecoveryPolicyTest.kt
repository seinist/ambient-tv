package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AudioOptions
import org.junit.Assert.*
import org.junit.Test

class AudioRecoveryPolicyTest {
    private val arc = AudioRouteCapabilities(passthrough = setOf("ac3"), pcmChannels = 2)
    @Test fun localIsTheDefaultEvenWhenTheVideoMustStayOnMedia3() {
        assertEquals("media3", RendererCapabilityPolicy.effectiveBackend("mpv", "hevc", "Dolby Vision", null))
        assertFalse(shouldConvertAudioOnServer(AudioOptions(), "eac3", 8, arc, false))
    }
    @Test fun explicitServerChoiceCoversTrueHdAndEac3WithoutVideoRestrictions() {
        for (codec in listOf("truehd", "eac3", "dts-hd")) {
            assertTrue(shouldConvertAudioOnServer(AudioOptions(conversionLocation = "server"), codec, 8, arc, false))
        }
        assertFalse(shouldConvertAudioOnServer(AudioOptions(conversionLocation = "server", localFallback = "off"), "eac3", 8, arc, false))
    }
}
