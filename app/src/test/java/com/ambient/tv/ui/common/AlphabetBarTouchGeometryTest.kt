package com.ambient.tv.ui.common

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphabetBarTouchGeometryTest {
    @Test fun scrubbingCanReachEveryLetterInAShortRailAndClampsBeyondItsEnds() {
        for (index in 0 until 26) {
            assertEquals(index, alphabetTouchIndex((index + .5f) * 8f, 208, 26))
        }
        assertEquals(0, alphabetTouchIndex(-100f, 208, 26))
        assertEquals(25, alphabetTouchIndex(208f, 208, 26))
        assertEquals(25, alphabetTouchIndex(500f, 208, 26))
        assertEquals(0, alphabetTouchIndex(15f, 20, 1))
    }

    @Test fun emptyOrUnmeasuredRailsDoNotInventASelection() {
        assertNull(alphabetTouchIndex(10f, 100, 0))
        assertNull(alphabetTouchIndex(10f, 0, 26))
        assertNull(alphabetTouchIndex(Float.NaN, 100, 26))
        assertNull(alphabetTouchIndex(Float.POSITIVE_INFINITY, 100, 26))
    }

    @Test fun compactLabelsLeaveSpaceAroundTheEnlargedActiveMarker() {
        assertTrue(alphabetRailLabelVisible(0, 26, 3, 12))
        assertTrue(alphabetRailLabelVisible(25, 26, 3, 12))
        assertTrue(alphabetRailLabelVisible(9, 26, 3, 12))
        assertFalse(alphabetRailLabelVisible(11, 26, 3, 12))
        assertFalse(alphabetRailLabelVisible(12, 26, 3, 12))
        assertFalse(alphabetRailLabelVisible(13, 26, 3, 12))
        assertFalse(alphabetRailLabelVisible(24, 26, 3, 12))
        assertTrue(alphabetRailLabelVisible(12, 26, 1, 12))
    }

    @Test fun previewSitsBesideTheFingerOnTheRightRail() {
        val actual = AlphabetPreviewPositionProvider(.5f, 16, 8).calculatePosition(
            IntRect(360, 100, 404, 620), IntSize(412, 732), LayoutDirection.Ltr, IntSize(64, 64),
        )
        assertEquals(IntOffset(280, 328), actual)
        assertTrue(actual.x + 64 < 360)
    }

    @Test fun previewStaysWithinAShortWindowAtBothEndsAndInRtl() {
        val anchor = IntRect(684, 0, 728, 280)
        val window = IntSize(732, 280)
        assertEquals(IntOffset(604, 8), AlphabetPreviewPositionProvider(0f, 16, 8)
            .calculatePosition(anchor, window, LayoutDirection.Ltr, IntSize(64, 64)))
        assertEquals(IntOffset(604, 208), AlphabetPreviewPositionProvider(1f, 16, 8)
            .calculatePosition(anchor, window, LayoutDirection.Ltr, IntSize(64, 64)))
        assertEquals(IntOffset(64, 108), AlphabetPreviewPositionProvider(.5f, 16, 8)
            .calculatePosition(IntRect(4, 0, 48, 280), window, LayoutDirection.Rtl, IntSize(64, 64)))
        // An RTL interface can still provide a physically right-hand rail: use its free side.
        assertEquals(IntOffset(604, 108), AlphabetPreviewPositionProvider(.5f, 16, 8)
            .calculatePosition(anchor, window, LayoutDirection.Rtl, IntSize(64, 64)))
    }

    @Test fun largeFontPreviewAndTinyWindowsNeverProduceNegativeCoordinates() {
        val provider = AlphabetPreviewPositionProvider(1f, 16, 8)
        val enlarged = provider.calculatePosition(IntRect(360, 0, 404, 300), IntSize(412, 300),
            LayoutDirection.Ltr, IntSize(112, 112))
        assertEquals(IntOffset(232, 180), enlarged)
        assertEquals(IntOffset(0, 0), provider.calculatePosition(IntRect(4, 0, 20, 20),
            IntSize(32, 32), LayoutDirection.Ltr, IntSize(64, 64)))
    }
}
