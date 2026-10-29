package com.ambient.tv.ui.player

import android.media.session.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceRadioActionsTest {
    @Test fun radioPublishesSkipButNeverSeek() {
        val actions = platformPlaybackActions("radio", hasPrevious = true, hasNext = true)

        assertTrue(actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertTrue(actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        assertFalse(actions and PlaybackState.ACTION_SEEK_TO != 0L)
    }

    @Test fun radioOmitsUnavailableQueueDirections() {
        val actions = platformPlaybackActions("radio", hasPrevious = false, hasNext = true)

        assertFalse(actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertTrue(actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
    }

    @Test fun ordinaryPlaybackKeepsSeekAndNoRadioSkipActions() {
        val actions = platformPlaybackActions("plex", hasPrevious = true, hasNext = true)

        assertTrue(actions and PlaybackState.ACTION_SEEK_TO != 0L)
        assertFalse(actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertFalse(actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
    }
}
