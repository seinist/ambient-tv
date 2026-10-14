package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFocusPolicyTest {
    private fun action(
        active: Boolean = true,
        backend: String = "mpv",
        playing: Boolean = true,
        waitingForFocus: Boolean = false,
        held: Boolean = false,
    ) = audioFocusAction(active, backend, playing, waitingForFocus, held)

    @Test fun playingNativeBackendAsksForFocusOnceAndThenKeepsIt() {
        assertEquals(AudioFocusAction.REQUEST, action())
        assertEquals(AudioFocusAction.KEEP, action(held = true))
        assertEquals(AudioFocusAction.REQUEST, action(backend = "vlc"))
    }

    /** The heart of the fix: a session paused only because focus is pending keeps its request. */
    @Test fun pausedWhileWaitingForFocusKeepsTheRequest() {
        assertEquals(AudioFocusAction.KEEP, action(playing = false, waitingForFocus = true, held = true))
        assertEquals(AudioFocusAction.KEEP, action(playing = false, waitingForFocus = true, held = false))
    }

    @Test fun pausedByTheUserGivesFocusBack() {
        assertEquals(AudioFocusAction.ABANDON, action(playing = false, waitingForFocus = false))
    }

    @Test fun bufferingOrFilterRestartKeepsFocusDespiteNoAdvancingFrames() {
        assertEquals(AudioFocusAction.KEEP, audioFocusAction(active = true, backend = "vlc",
            wantsToPlay = true, waitingForFocus = false, held = true))
        assertEquals(AudioFocusAction.REQUEST, audioFocusAction(active = true, backend = "mpv",
            wantsToPlay = true, waitingForFocus = false, held = false))
    }

    @Test fun media3AndEndedSessionsNeverTakeFocusFromTheService() {
        assertEquals(AudioFocusAction.ABANDON, action(backend = "media3"))
        assertEquals(AudioFocusAction.ABANDON, action(backend = ""))
        assertEquals(AudioFocusAction.ABANDON, action(backend = "media3", waitingForFocus = true, held = true))
        assertEquals(AudioFocusAction.ABANDON, action(active = false))
        assertEquals(AudioFocusAction.ABANDON, action(active = false, waitingForFocus = true, held = true))
    }
}
