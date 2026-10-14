package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvEndReasonTest {
    @Test fun internalStopsAndRedirectsNeverCompleteAnEpisode() {
        for (reason in listOf(2, 3, 5)) {
            assertFalse(mpvTerminalEnd(reason, 0, false))
            assertFalse(mpvTerminalEnd(reason, 0, true)) // stale EOF during loadfile
        }
    }

    @Test fun naturalEndAndErrorsRemainTerminal() {
        assertTrue(mpvTerminalEnd(0, 0, false))
        assertTrue(mpvTerminalEnd(4, -14, false))
    }

    @Test fun olderNativeBridgesRequireExplicitEndOrErrorEvidence() {
        assertFalse(mpvTerminalEnd(-1, 0, false))
        assertTrue(mpvTerminalEnd(-1, 0, true))
        assertTrue(mpvTerminalEnd(-1, -14, false))
    }
}
