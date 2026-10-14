package com.ambient.tv.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PauseToggleTest {
    @Test fun bufferingWithPlayRequestPauses() {
        assertTrue(media3PauseOnToggle(true, Player.STATE_BUFFERING))
        assertTrue(media3PauseOnToggle(true, Player.STATE_READY))
    }

    @Test fun pausedEndedOrIdlePlays() {
        assertFalse(media3PauseOnToggle(false, Player.STATE_READY))
        assertFalse(media3PauseOnToggle(false, Player.STATE_BUFFERING))
        assertFalse(media3PauseOnToggle(true, Player.STATE_ENDED))
        assertFalse(media3PauseOnToggle(true, Player.STATE_IDLE))
    }
}
