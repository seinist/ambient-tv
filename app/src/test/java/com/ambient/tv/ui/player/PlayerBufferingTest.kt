package com.ambient.tv.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBufferingTest {
    @Test fun engineShowsSpinnerOnlyWithPlayIntentAndNoPicture() {
        assertTrue(waitingForPlaybackPicture(wantsToPlay = true, isPlaying = false))
        assertFalse(waitingForPlaybackPicture(wantsToPlay = true, isPlaying = true))
        assertFalse(waitingForPlaybackPicture(wantsToPlay = false, isPlaying = false))
        assertFalse(waitingForPlaybackPicture(wantsToPlay = false, isPlaying = true))
    }

    @Test fun media3ShowsSpinnerWhileIdleOrBufferingWithPlayIntent() {
        assertTrue(media3WaitingForPlaybackPicture(Player.STATE_IDLE, playWhenReady = true))
        assertTrue(media3WaitingForPlaybackPicture(Player.STATE_BUFFERING, playWhenReady = true))
        assertFalse(media3WaitingForPlaybackPicture(Player.STATE_READY, playWhenReady = true))
        assertFalse(media3WaitingForPlaybackPicture(Player.STATE_ENDED, playWhenReady = true))
        assertFalse(media3WaitingForPlaybackPicture(Player.STATE_BUFFERING, playWhenReady = false))
        assertFalse(media3WaitingForPlaybackPicture(Player.STATE_IDLE, playWhenReady = false))
    }
}
