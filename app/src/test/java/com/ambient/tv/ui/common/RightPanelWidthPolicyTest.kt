package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Video Playback Settings drawer is the width reference for every right-side panel.
 * Panels must not jump between a fixed 500dp and a 38%-screen drawer on television.
 */
class RightPanelWidthPolicyTest {
    @Test fun tvAlwaysUsesTheVideoPlaybackSettingsScreenFraction() {
        for (screenWidth in listOf(720, 960, 1280, 1920)) {
            val expected = screenWidth * RIGHT_PANEL_SCREEN_FRACTION
            assertEquals(expected, referenceRightPanelWidthDp(screenWidth, handheld = false, landscape = true), .001f)
            assertEquals(expected, referenceRightPanelWidthDp(screenWidth, handheld = false, landscape = false), .001f)
        }
    }

    @Test fun handheldPortraitUsesTheVideoSettingsWidthAndNeverOverflowsTheDisplay() {
        assertEquals(360f, referenceRightPanelWidthDp(360, handheld = true, landscape = false), .001f)
        assertEquals(393f, referenceRightPanelWidthDp(393, handheld = true, landscape = false), .001f)
        assertEquals(420f, referenceRightPanelWidthDp(480, handheld = true, landscape = false), .001f)
        assertEquals(420f, referenceRightPanelWidthDp(800, handheld = true, landscape = false), .001f)
        assertEquals(420f, RIGHT_PANEL_HANDHELD_PORTRAIT_DP, .001f)
    }

    @Test fun handheldLandscapeMatchesPlaybackSettingsAndTvFraction() {
        for (screenWidth in listOf(640, 900, 1200)) {
            val expected = screenWidth * .38f
            assertEquals(expected, referenceRightPanelWidthDp(screenWidth, handheld = true, landscape = true), .001f)
        }
    }

    @Test fun aNarrowPhoneStillHasAValidResponsivePanel() {
        for (screenWidth in 280..520 step 20) {
            val width = referenceRightPanelWidthDp(screenWidth, handheld = true, landscape = false)
            assertTrue(width > 0f)
            assertTrue(width <= screenWidth)
            assertTrue(width <= RIGHT_PANEL_HANDHELD_PORTRAIT_DP)
        }
    }
}
