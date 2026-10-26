package com.ambient.tv.ui.common

import org.junit.Assert.*
import org.junit.Test

class DialogFocusReturnTest {
    @Test fun parkingKeepsTheRememberedRow() {
        val nav = PlayerPanelNavigation()
        nav.open(UnifiedPlayerPanel.VIDEO)
        nav.noteFocused("renderer:backend")
        // The rescue holds focus on the first row while the renderer row is rebuilt.
        nav.parked { nav.noteFocused("video:first"); true }
        assertEquals("renderer:backend", nav.focusedKey)
        // The user's own move still counts: the key press ends parking first.
        nav.endParking()
        nav.noteFocused("video:first")
        assertEquals("video:first", nav.focusedKey)
    }

    @Test fun parkingWithoutRememberedRowTakesTheStandIn() {
        assertEquals("first", rememberedPanelFocusKey(null, "first", parking = true))
        assertEquals("old", rememberedPanelFocusKey("old", "first", parking = true))
        assertEquals("first", rememberedPanelFocusKey("old", "first", parking = false))
    }

    @Test fun rescuePausesWhileADialogHasTheWindow() {
        assertEquals(PanelRescueStep.PAUSE, panelRescueStep(windowFocused = false, userMoved = false))
        assertEquals(PanelRescueStep.STOP, panelRescueStep(windowFocused = false, userMoved = true))
        assertEquals(PanelRescueStep.CONTINUE, panelRescueStep(windowFocused = true, userMoved = false))
    }

    @Test fun pausedRescueResumesEvenWhileAStandInRowHasFocus() {
        assertTrue(panelRescueShouldStart(true, false, true, panelHasFocus = true, windowFocused = true, pausedTarget = "row"))
        assertFalse(panelRescueShouldStart(true, false, true, panelHasFocus = true, windowFocused = true, pausedTarget = null))
        assertTrue(panelRescueShouldStart(true, false, true, panelHasFocus = false, windowFocused = true, pausedTarget = null))
        assertFalse(panelRescueShouldStart(true, false, true, panelHasFocus = false, windowFocused = false, pausedTarget = "row"))
        assertFalse(panelRescueShouldStart(true, true, true, panelHasFocus = false, windowFocused = true, pausedTarget = "row"))
    }

    @Test fun returnsToTheRowOfTheSamePanelOnly() {
        assertEquals("renderer:backend",
            dialogFocusReturnTarget(UnifiedPlayerPanel.VIDEO, "renderer:backend", UnifiedPlayerPanel.VIDEO))
        // "Anpassen" opened another level: that wins.
        assertNull(dialogFocusReturnTarget(UnifiedPlayerPanel.RENDERER, "renderer:backend", UnifiedPlayerPanel.VIDEO))
        assertNull(dialogFocusReturnTarget(UnifiedPlayerPanel.NONE, "x", UnifiedPlayerPanel.NONE))
        assertNull(dialogFocusReturnTarget(UnifiedPlayerPanel.VIDEO, null, UnifiedPlayerPanel.VIDEO))
    }

    @Test fun capturesOnceAndRestoresAfterTheDialog() {
        val nav = PlayerPanelNavigation()
        nav.open(UnifiedPlayerPanel.VIDEO)
        nav.noteFocused("renderer:backend")
        val focus = DialogFocusReturn()
        focus.capture(nav)
        nav.noteFocused("other") // later frames while the dialog is open do not replace it
        focus.capture(nav)
        focus.restore()
        assertEquals(UnifiedPlayerPanel.VIDEO to "renderer:backend", focus.pending)
        assertEquals(1, focus.generation)
        focus.restore() // nothing captured any more
        assertEquals(1, focus.generation)
    }

    @Test fun stopForgetsTheRow() {
        val nav = PlayerPanelNavigation()
        nav.open(UnifiedPlayerPanel.VIDEO)
        nav.noteFocused("renderer:backend")
        val focus = DialogFocusReturn()
        focus.capture(nav)
        focus.forget()
        focus.restore()
        assertNull(focus.pending)
    }
}
