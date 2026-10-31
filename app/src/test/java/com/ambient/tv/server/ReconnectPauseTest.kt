package com.ambient.tv.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPauseTest {
    @Test fun passiveChecksRunForOfflineServicesEvenWhenNotPaused() {
        assertTrue(shouldProbeInBackground(true, false, false, false))
        assertTrue(shouldProbeInBackground(true, false, false, true))
        assertTrue(shouldProbeInBackground(true, false, true, false))
        assertFalse(shouldProbeInBackground(true, false, true, true))
        assertTrue(shouldProbeInBackground(true, true, true, true))
        assertFalse(shouldProbeInBackground(false, true, false, false))
    }

    @Test fun pauseHoldsForTheSameEndpointOnly() {
        val pause = ReconnectPause()
        pause.pause("a")
        assertTrue(pause.isPaused("a"))
        assertFalse(pause.isPaused("b"))
    }

    @Test fun serverBackOnlineEndsThePause() {
        // M4: after the passive check found the server again, a later outage reconnects automatically.
        val pause = ReconnectPause()
        pause.pause("a")
        pause.onlineAgain()
        assertFalse(pause.isPaused("a"))
    }

    @Test fun manualReconnectClears() {
        val pause = ReconnectPause()
        pause.pause("a")
        pause.clear()
        assertFalse(pause.isPaused("a"))
    }
}
