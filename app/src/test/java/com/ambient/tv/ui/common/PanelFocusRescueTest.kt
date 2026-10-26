package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelFocusRescueTest {
    @Test fun retryRunsForTheTimeWindowNotAFrameCount() {
        val start = 1_000_000_000L
        assertTrue(focusRetryWithin(start, start + 599_000_000L))
        assertFalse(focusRetryWithin(start, start + 600_000_000L))
        assertEquals(600L, PANEL_FOCUS_RESTORE_MS)
    }

    @Test fun onlyFreshLevelsMayFallBackToFooter() {
        assertEquals(PanelFocusFallback.FOOTER, panelFocusFallback(levelFresh = true))
        assertEquals(PanelFocusFallback.FIRST_ROW, panelFocusFallback(levelFresh = false))
    }

    @Test fun pendingHandBackWinsOverLastFocusedRow() {
        assertEquals("entry:Video", panelFocusRescueTarget("entry:Video", "selector:filters:Deband"))
        assertEquals("selector:filters:Deband", panelFocusRescueTarget(null, "selector:filters:Deband"))
        assertNull(panelFocusRescueTarget(null, null))
    }

    @Test fun levelRemembersItsLastFocusedRow() {
        val navigation = PlayerPanelNavigation()
        navigation.open(UnifiedPlayerPanel.SETTINGS)
        assertTrue(navigation.levelFresh)
        navigation.noteFocused("entry:Quality")
        assertFalse(navigation.levelFresh)
        assertEquals("entry:Quality", navigation.focusedKey)
        navigation.fromOrigin("entry:Video", root = false) { navigation.open(UnifiedPlayerPanel.VIDEO) }
        assertTrue(navigation.levelFresh)
        assertNull(navigation.focusedKey)
        navigation.back()
        assertEquals("entry:Quality", navigation.focusedKey)
        assertEquals("entry:Video", navigation.restoreKey)
    }

    @Test fun adjustInsideVideoSettingsDoesNotStackAnotherLevel() {
        val navigation = PlayerPanelNavigation()
        navigation.openVideoSection("filters", "entry:Filter")
        navigation.openVideoSettings()
        assertEquals(UnifiedPlayerPanel.VIDEO_SECTION, navigation.panel)
        navigation.back()
        assertEquals(UnifiedPlayerPanel.VIDEO, navigation.panel)
        navigation.back()
        assertEquals(UnifiedPlayerPanel.NONE, navigation.panel)
    }

    @Test fun adjustFromAnotherPanelStartsAFreshVideoPath() {
        val navigation = PlayerPanelNavigation()
        navigation.open(UnifiedPlayerPanel.SETTINGS)
        navigation.openVideoSettings()
        assertEquals(UnifiedPlayerPanel.VIDEO, navigation.panel)
        navigation.back()
        assertEquals(UnifiedPlayerPanel.NONE, navigation.panel)
    }

    @Test fun adjustOpensPanelOnlyOutsideVideoLevels() {
        assertTrue(videoAdjustOpensPanel(UnifiedPlayerPanel.NONE))
        assertTrue(videoAdjustOpensPanel(UnifiedPlayerPanel.SETTINGS))
        assertFalse(videoAdjustOpensPanel(UnifiedPlayerPanel.VIDEO))
        assertFalse(videoAdjustOpensPanel(UnifiedPlayerPanel.VIDEO_SVP))
        assertFalse(videoAdjustOpensPanel(UnifiedPlayerPanel.RENDERER))
    }
}
