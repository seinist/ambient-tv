package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOsdContinuityTest {
    @Test fun aVisibleOsdDoesNotFadeInAgainWhenItsContentIsReplaced() {
        assertTrue(playerOsdResumesOnScreen(wasOnScreen = true, show = true))
    }

    @Test fun aRealOpeningStillFadesIn() {
        assertFalse(playerOsdResumesOnScreen(wasOnScreen = false, show = true))
        assertFalse(playerOsdResumesOnScreen(wasOnScreen = true, show = false))
    }

    @Test fun aResumedOsdKeepsItsActionRow() {
        assertFalse(playerOsdStowStartsCollapsed(resumed = true, handheld = true, hasController = true, expanded = true))
        assertTrue(playerOsdStowStartsCollapsed(resumed = true, handheld = true, hasController = true, expanded = false))
        // Opening on a phone still slides the row in.
        assertTrue(playerOsdStowStartsCollapsed(resumed = false, handheld = true, hasController = true, expanded = true))
        assertFalse(playerOsdStowStartsCollapsed(resumed = false, handheld = false, hasController = true, expanded = true))
    }

    @Test fun channelInfoKeepsAVisibleOsdAndRestartsItsTimer() {
        val osd = PlayerOsdController()
        osd.showChannelInfo()
        val version = osd.interactionVersion
        assertTrue(osd.visible.value)
        osd.showChannelInfo()
        assertTrue(osd.visible.value)
        assertFalse(osd.closing)
        assertTrue(osd.interactionVersion > version)
    }

    @Test fun expandedControlsStayExpandedOnZap() {
        val osd = PlayerOsdController()
        osd.expand()
        osd.showChannelInfo()
        assertTrue(osd.expanded)
        assertTrue(osd.visible.value)
    }
}
