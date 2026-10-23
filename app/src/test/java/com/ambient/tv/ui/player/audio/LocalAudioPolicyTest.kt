package com.ambient.tv.ui.player.audio

import com.ambient.tv.data.local.AudioTrackPreference
import com.ambient.tv.ui.player.AudioTrackMetadata
import com.ambient.tv.ui.player.choosePreferredAudioTrack
import org.junit.Assert.*
import org.junit.Test

class LocalAudioPolicyTest {
    private val ac3 = com.ambient.tv.data.local.AudioOptions(output = "auto", localAc3 = true)
    @Test fun unsupportedSurroundUsesLocalAc3OnlyOnAnAc3CapableOutput() {
        assertTrue(ac3.transcodesAc3(false, 8, 2, true))
        assertTrue(ac3.copy(output = "bitstream", pcmChannelLimit = 2).transcodesAc3(false, 6, 8, true))
        assertFalse(ac3.transcodesAc3(false, 8, 2, false))
        assertFalse(ac3.transcodesAc3(false, 2, 2, true))
        assertFalse(ac3.transcodesAc3(false, 8, 8, true))
        assertFalse(ac3.copy(localAc3 = false).transcodesAc3(false, 8, 2, true))
    }
    @Test fun explicitPcmStereoAndDisabledRecoveryAreRespected() {
        assertFalse(ac3.copy(localFallback = "off").transcodesAc3(true, 8, 2, true))
        assertFalse(ac3.copy(localFallback = "pcm").transcodesAc3(true, 8, 2, true))
        assertFalse(ac3.copy(output = "pcm").transcodesAc3(true, 8, 2, true))
        assertFalse(ac3.copy(channels = "stereo").transcodesAc3(true, 8, 2, true))
        assertFalse(ac3.copy(output = "source").transcodesAc3(false, 8, 2, true))
    }
    @Test fun mainAudioWinsEvenWhenCommentaryIsListedFirst() {
        val tracks = listOf(AudioTrackMetadata("de", "Kommentar", 2),
            AudioTrackMetadata("de", "Hauptton EAC3", 8), AudioTrackMetadata("de", "Audiokommentar 2", 2))
        assertEquals(tracks[1], choosePreferredAudioTrack(tracks, "de", AudioTrackPreference("main")) { it })
        assertEquals(tracks[0], choosePreferredAudioTrack(tracks, "de", AudioTrackPreference("commentary")) { it })
    }
}
