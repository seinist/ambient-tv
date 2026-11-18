package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameHoldPolicyTest {
    private fun released(elapsed: Long, status: PlaybackStatus, start: Long = 10_000L, wantsToPlay: Boolean = true) =
        VideoFrameHoldPolicy.released(elapsed, status, start, wantsToPlay)

    @Test fun holdsWhileTheNewRendererIsStillLoading() {
        assertFalse(released(800L, PlaybackStatus(positionMs = 10_000L, playing = false)))
        // "Playing" alone is not enough: MPV can report it before its first frame.
        assertFalse(released(900L, PlaybackStatus(positionMs = 10_100L, playing = true)))
    }

    @Test fun releasesOnceFramesVisiblyProgress() {
        assertTrue(released(900L, PlaybackStatus(positionMs = 10_350L, playing = true)))
    }

    @Test fun aPausedHandOffShowsItsNewStillFrameShortly() {
        assertFalse(released(1_000L, PlaybackStatus(positionMs = 10_000L), wantsToPlay = false))
        assertTrue(released(1_600L, PlaybackStatus(positionMs = 10_000L), wantsToPlay = false))
    }

    @Test fun errorsEndAndTheSafetyLimitAlwaysRelease() {
        assertTrue(released(200L, PlaybackStatus(error = "failed")))
        assertTrue(released(200L, PlaybackStatus(ended = true)))
        assertTrue(released(15_000L, PlaybackStatus(positionMs = 10_000L, playing = false)))
    }
}
