package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** G3 of the 26.10. audit: dialog focus return and panel rescue never compete. */
class PanelFocusSingleOwnerTest {
    @Test fun dialogOwnerStopsAndBlocksTheRescue() {
        assertEquals(PanelRescueStep.STOP, panelRescueStep(windowFocused = true, userMoved = false, dialogOwnsFocus = true))
        assertEquals(PanelRescueStep.STOP, panelRescueStep(windowFocused = false, userMoved = false, dialogOwnsFocus = true))
        assertFalse(panelRescueShouldStart(true, false, true, panelHasFocus = false, windowFocused = true, pausedTarget = null, dialogOwnsFocus = true))
        assertTrue(panelRescueShouldStart(true, false, true, panelHasFocus = false, windowFocused = true, pausedTarget = null, dialogOwnsFocus = false))
    }

    @Test fun parkingLastsUntilEndedSoLateFocusEventsDoNotOverwrite() {
        val nav = PlayerPanelNavigation()
        nav.open(UnifiedPlayerPanel.VIDEO)
        nav.noteFocused("row:a")
        nav.parked { nav.noteFocused("row:standin") }
        nav.noteFocused("row:late") // Compose delivers focus after the request returned
        assertEquals("row:a", nav.focusedKey)
        nav.endParking()
        nav.noteFocused("row:b")
        assertEquals("row:b", nav.focusedKey)
    }
}
