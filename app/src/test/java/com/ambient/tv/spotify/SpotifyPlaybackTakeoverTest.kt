package com.ambient.tv.spotify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaybackTakeoverTest {
    @Test fun foregroundConnectPromptsBeforeReplacingCurrentPlayback() {
        assertTrue(shouldPromptSpotifyTakeover(true, true, true, connectStarted = true, spotifyPlaybackStarted = false))
        assertTrue(shouldPromptSpotifyTakeover(true, true, true, connectStarted = false, spotifyPlaybackStarted = true))
    }

    @Test fun pausedOrMissingPlaybackDoesNotPrompt() {
        assertFalse(shouldPromptSpotifyTakeover(true, false, true, true, false))
        assertFalse(shouldPromptSpotifyTakeover(false, true, true, true, false))
    }

    @Test fun backgroundSpotifyPlayTakesOverWithoutInvisibleDialog() {
        assertTrue(shouldAutoTakeOverInBackground(true, true, false, spotifyPlaybackStarted = true))
        assertFalse(shouldAutoTakeOverInBackground(true, true, true, spotifyPlaybackStarted = true))
        assertFalse(shouldAutoTakeOverInBackground(true, true, false, spotifyPlaybackStarted = false))
    }

    @Test fun foregroundPresenceSurvivesStartedActivityHandOffs() {
        SpotifyPlaybackTakeoverUiPresence.resetForTest()
        assertFalse(SpotifyPlaybackTakeoverUiPresence.foreground)

        SpotifyPlaybackTakeoverUiPresence.onActivityStarted()
        SpotifyPlaybackTakeoverUiPresence.onActivityStarted()
        assertTrue(SpotifyPlaybackTakeoverUiPresence.foreground)

        SpotifyPlaybackTakeoverUiPresence.onActivityStopped()
        assertTrue(SpotifyPlaybackTakeoverUiPresence.foreground)

        SpotifyPlaybackTakeoverUiPresence.onActivityStopped()
        assertFalse(SpotifyPlaybackTakeoverUiPresence.foreground)
        // Defensive extra stop must never underflow into a permanent foreground state.
        SpotifyPlaybackTakeoverUiPresence.onActivityStopped()
        assertFalse(SpotifyPlaybackTakeoverUiPresence.foreground)
    }
}
