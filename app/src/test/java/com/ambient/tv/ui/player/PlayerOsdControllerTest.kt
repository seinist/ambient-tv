package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlayerOsdControllerTest {
    @Test fun engineVisibilityBindingConsumesTwoBackPressesBeforeHiddenPlayback() {
        val osd = PlayerOsdController()
        osd.expand()
        osd.visible.value = false
        assertTrue(osd.visible.value)
        assertTrue(osd.closing)
        osd.visible.value = false
        assertFalse(osd.visible.value)
        assertFalse(osd.closing)
    }

    @Test fun repeatedEngineRevealRefreshesTimeoutAndKeepsExpandedControls() {
        val osd = PlayerOsdController()
        osd.expand()
        val version = osd.interactionVersion
        osd.visible.value = true
        assertTrue(osd.expanded)
        assertTrue(osd.interactionVersion > version)
    }

    @Test fun interruptedExitCannotFinishASubsequentExit() {
        val osd = PlayerOsdController()
        osd.expand()
        osd.close()
        val staleExit = osd.transitionVersion
        osd.reveal()
        osd.close()
        val currentExit = osd.transitionVersion
        osd.finishClose(staleExit)
        assertTrue(osd.visible.value)
        assertTrue(osd.closing)
        osd.finishClose(currentExit)
        assertFalse(osd.visible.value)
    }

    @Test fun claimingAutomaticLiveInformationClearsZapModeWithoutOpeningButtons() {
        val osd = PlayerOsdController(initialZapOsd = true)
        assertTrue(osd.shownByZap.value)
        osd.reveal()
        assertFalse(osd.shownByZap.value)
        assertTrue(osd.show)
        assertFalse(osd.expanded)
    }

    @Test fun anotherJumpRefreshesFeedbackTimeoutWithoutChangingPresentation() {
        val osd = PlayerOsdController()
        osd.showSeekFeedback()
        val version = osd.interactionVersion
        osd.showSeekFeedback()
        assertTrue(osd.interactionVersion > version)
        assertTrue(osd.seekFeedback)
        assertEquals(PlayerOsdMode.COMPACT, osd.mode)
        osd.reveal()
        assertFalse(osd.seekFeedback)
        assertTrue(osd.visible.value)
    }

    @Test fun panelTimeoutHidesImmediatelyAndInvalidatesAnOlderFade() {
        val chrome = PlayerChromeState(initialZapOsd = true)
        chrome.osd.expand()
        chrome.osd.close()
        val fade = chrome.osd.transitionVersion
        chrome.panels.fromOrigin("bar:settings", true) {
            chrome.panels.open(com.ambient.tv.ui.common.UnifiedPlayerPanel.SETTINGS)
        }
        assertTrue(chrome.closePanelsForInactivity(chrome.panels.activity.version))
        assertEquals(PlayerOsdMode.HIDDEN, chrome.osd.mode)
        assertFalse(chrome.osd.closing)
        assertFalse(chrome.osd.shownByZap.value)
        assertNull(chrome.panels.restoreKey)
        chrome.osd.finishClose(fade)
        assertEquals(PlayerOsdMode.HIDDEN, chrome.osd.mode)
        chrome.osd.reveal()
        chrome.osd.finishClose(fade)
        assertEquals(PlayerOsdMode.COMPACT, chrome.osd.mode)
    }
}
