package com.ambient.tv.ui.player

import com.ambient.tv.domain.playback.PlaybackMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GaplessEligibilityTest {
    @Test fun onlyDirectlyPlayedSongsOnMedia3() {
        assertTrue(gaplessEligible("track", PlaybackMode.DIRECT_PLAY, onMedia3 = true, serverAudioConversion = null))
        assertTrue(gaplessEligible("Audio", PlaybackMode.DIRECT_PLAY, onMedia3 = true, serverAudioConversion = null))
        assertFalse(gaplessEligible("episode", PlaybackMode.DIRECT_PLAY, onMedia3 = true, serverAudioConversion = null))
        assertFalse(gaplessEligible("track", PlaybackMode.TRANSCODE, onMedia3 = true, serverAudioConversion = null))
        assertFalse(gaplessEligible("track", PlaybackMode.DIRECT_PLAY, onMedia3 = false, serverAudioConversion = null))
        assertFalse(gaplessEligible("track", PlaybackMode.DIRECT_PLAY, onMedia3 = true, serverAudioConversion = "aac"))
    }
}
