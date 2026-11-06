package com.ambient.tv.ui.telegram

import com.ambient.tv.ui.player.PlaybackStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonogramPlayerBufferingTest {
    private fun spinner(
        activeMediaKey: String? = "post",
        activeRenderer: String = "media3",
        replacingSource: Boolean = false,
        wantsToPlay: Boolean = true,
        status: PlaybackStatus = PlaybackStatus(playing = false),
    ) = monogramNeedsBufferingSpinner(
        requestedMediaKey = "post",
        requestedRenderer = "media3",
        activeMediaKey = activeMediaKey,
        activeRenderer = activeRenderer,
        replacingSource = replacingSource,
        wantsToPlay = wantsToPlay,
        status = status,
    )

    @Test fun waitsForFirstStreamAndAfterRebuffering() {
        assertTrue(spinner())
        assertTrue(spinner(status = PlaybackStatus(positionMs = 20_000, playing = false)))
        assertFalse(spinner(status = PlaybackStatus(playing = true)))
    }

    @Test fun waitsForNewPostOrRendererInsteadOfUsingOldPlaybackState() {
        assertTrue(spinner(activeMediaKey = null, wantsToPlay = false))
        assertTrue(spinner(activeMediaKey = "previous", status = PlaybackStatus(playing = true)))
        assertTrue(spinner(activeRenderer = "vlc", status = PlaybackStatus(playing = true)))
        assertTrue(spinner(replacingSource = true, status = PlaybackStatus(playing = true)))
    }

    @Test fun pausesAndTerminatedPlaybackDoNotShowSpinner() {
        assertFalse(spinner(wantsToPlay = false))
        assertFalse(spinner(wantsToPlay = false, status = PlaybackStatus(playing = true)))
        assertFalse(spinner(status = PlaybackStatus(ended = true)))
        assertFalse(spinner(status = PlaybackStatus(error = "stream failed")))
    }
}
