package com.ambient.tv.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionPlaybackTargetTest {
    private val media = CompanionMedia("library", "episode", "server")
    private val playing = CompanionFrame(type = "state", media = media, active = true, playbackState = "playing")

    @Test fun acceptedCommandAndOldPlaybackDoNotConfirmANewStart() {
        val frame = playing.copy(capabilities = listOf("start_confirmation"), startRequestId = "new", startStatus = "accepted")
        assertFalse(companionStartConfirmed(media, frame, "new"))
        assertFalse(companionStartConfirmed(media, frame.copy(startStatus = "playing", startRequestId = "old"), "new"))
        assertTrue(companionStartConfirmed(media, frame.copy(startStatus = "playing"), "new"))
    }

    @Test fun loadingPausedAndFailedFramesNeverConfirmSuccess() {
        for (phase in listOf("loading", "buffering", "paused", "error", "idle", "ended")) {
            assertFalse(companionStartConfirmed(media, playing.copy(playbackState = phase)))
            assertFalse(companionStartConfirmed(media, playing.copy(playbackState = phase,
                capabilities = listOf("start_confirmation"), startRequestId = "request", startStatus = "playing"), "request"))
        }
    }

    @Test fun legacyReceiverRequiresTheExactMediaSource() {
        assertTrue(companionStartConfirmed(media, playing))
        assertFalse(companionStartConfirmed(media, playing.copy(media = media.copy(source = "other"))))
        assertFalse(companionStartConfirmed(media, playing.copy(media = media.copy(id = "next"))))
        assertFalse(companionStartConfirmed(media, playing.copy(active = false)))
    }

    @Test fun playlistConfirmationUsesTheAcknowledgedRequestInsteadOfTheTrackId() {
        val playlist = CompanionMedia("spotify:playlist", "playlist")
        val track = playing.copy(media = CompanionMedia("spotify:track", "track"),
            capabilities = listOf("start_confirmation"), startRequestId = "request", startStatus = "playing")
        assertTrue(companionStartConfirmed(playlist, track, "request"))
        assertFalse(companionStartConfirmed(playlist, track.copy(startStatus = "accepted"), "request"))
    }
}
