package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOnPolicyTest {
    private fun awake(
        handheld: Boolean = false,
        appScreensaverEnabled: Boolean = false,
        screensaverOverlayPresent: Boolean = false,
        photoViewer: Boolean = false,
        playerPageVisible: Boolean = false,
        pipVisible: Boolean = false,
        sessionActive: Boolean = false,
        isPlaying: Boolean = false,
        wantsToPlay: Boolean = false,
        spotifyPlaying: Boolean = false,
        standbyRequested: Boolean = false,
    ) = shouldKeepScreenOn(
        handheld, appScreensaverEnabled, screensaverOverlayPresent, photoViewer, playerPageVisible,
        pipVisible, sessionActive, isPlaying, wantsToPlay, spotifyPlaying, standbyRequested,
    )

    @Test fun tvKeepsScreenAwakeForVideoAudioAndBufferingWithoutAppScreensaver() {
        assertTrue(awake(sessionActive = true, isPlaying = true))
        assertTrue(awake(sessionActive = true, wantsToPlay = true))
        assertTrue(awake(sessionActive = true, isPlaying = true, playerPageVisible = false))
        // Spotify has a separate engine, and Radio can continue while browsing the app.
        assertTrue(awake(spotifyPlaying = true))
        assertFalse(awake(isPlaying = true, wantsToPlay = true, sessionActive = false))
    }

    @Test fun tvAllowsSystemIdleOnPauseOrStopWhenAppScreensaverIsDisabled() {
        assertFalse(awake())
        assertFalse(awake(sessionActive = true))
        assertFalse(awake(sessionActive = true, playerPageVisible = true))
        assertFalse(awake(sessionActive = true, isPlaying = false, wantsToPlay = false))
    }

    @Test fun ownTvScreensaverAndPhotoViewerStillKeepDisplayOn() {
        assertTrue(awake(appScreensaverEnabled = true))
        assertTrue(awake(screensaverOverlayPresent = true))
        assertTrue(awake(photoViewer = true))
        assertTrue(awake(handheld = true, photoViewer = true))
    }

    @Test fun phoneKeepsScreenOnOnlyOnVisiblePlayerWithoutPip() {
        assertTrue(awake(handheld = true, playerPageVisible = true, sessionActive = true, isPlaying = true))
        assertTrue(awake(handheld = true, playerPageVisible = true, sessionActive = true, wantsToPlay = true))
        assertTrue(awake(handheld = true, playerPageVisible = true, spotifyPlaying = true))
        assertFalse(awake(handheld = true, sessionActive = true, isPlaying = true))
        assertFalse(awake(handheld = true, playerPageVisible = true, pipVisible = true,
            sessionActive = true, isPlaying = true))
        assertFalse(awake(handheld = true, playerPageVisible = true))
    }

    @Test fun sleepTimerStandbyOverridesEveryOtherReasonOnTv() {
        assertFalse(awake(appScreensaverEnabled = true, sessionActive = true, isPlaying = true,
            screensaverOverlayPresent = true, spotifyPlaying = true, standbyRequested = true))
        assertFalse(awake(photoViewer = true, standbyRequested = true))
    }
}
