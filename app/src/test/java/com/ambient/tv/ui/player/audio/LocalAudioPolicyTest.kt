package com.ambient.tv.ui.player.audio

import com.ambient.tv.data.local.AudioTrackPreference
import com.ambient.tv.ui.player.AudioTrackMetadata
import com.ambient.tv.ui.player.choosePreferredAudioTrack
import org.junit.Assert.*
import org.junit.Test

class LocalAudioPolicyTest {
    @Test fun unsupportedSurroundUsesLocalAc3OnlyOnAnAc3CapableOutput() {
        assertTrue(localAc3Eligible("auto", "auto", "auto", 8, true))
        assertTrue(localAc3Eligible("auto", "bitstream", "5.1", 6, true))
        assertFalse(localAc3Eligible("auto", "auto", "auto", 8, false))
        assertFalse(localAc3Eligible("auto", "auto", "auto", 2, true))
    }
    @Test fun explicitPcmStereoAndDisabledRecoveryAreRespected() {
        assertFalse(localAc3Eligible("off", "auto", "auto", 8, true))
        assertFalse(localAc3Eligible("pcm", "auto", "auto", 8, true))
        assertFalse(localAc3Eligible("auto", "pcm", "auto", 8, true))
        assertFalse(localAc3Eligible("auto", "auto", "stereo", 8, true))
    }
    @Test fun mainAudioWinsEvenWhenCommentaryIsListedFirst() {
        val tracks = listOf(AudioTrackMetadata("de", "Kommentar", 2),
            AudioTrackMetadata("de", "Hauptton EAC3", 8), AudioTrackMetadata("de", "Audiokommentar 2", 2))
        assertEquals(tracks[1], choosePreferredAudioTrack(tracks, "de", AudioTrackPreference("main")) { it })
        assertEquals(tracks[0], choosePreferredAudioTrack(tracks, "de", AudioTrackPreference("commentary")) { it })
    }
}
