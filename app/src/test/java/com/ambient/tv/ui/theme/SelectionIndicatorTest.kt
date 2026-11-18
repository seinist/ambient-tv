package com.ambient.tv.ui.theme

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selection stays visible independently of focus and never affects control layout. */
class SelectionIndicatorTest {
    private val size = Size(100f, 48f)

    @Test fun normalControlsHaveNoSelectionMarker() {
        for (indicator in SelectionIndicator.entries) {
            assertNull(selectionIndicatorRect(false, size, indicator, 1f))
        }
        assertNull(selectionIndicatorRect(true, size, SelectionIndicator.NONE, 1f))
    }

    @Test fun compactUnderlineKeepsItsInsetForControlsThatStillUseIt() {
        // OSD controls may retain this compact marker. TV chips intentionally use NONE;
        // changing material style must not change this shared geometry.
        for (style in AccentStyle.entries) {
            val marker = selectionIndicatorRect(true, size, SelectionIndicator.UNDERLINE, 1f)
            assertNotNull(style.toString(), marker)
            assertEquals(style.toString(), 38f, marker!!.left, .001f)
            assertEquals(style.toString(), 62f, marker.right, .001f)
            assertEquals(style.toString(), 41f, marker.bottom, .001f)
            assertEquals(style.toString(), 39.25f, marker.top, .001f)
            assertTrue(style.toString(), marker.height < 2f)
        }
    }

    @Test fun sidebarAndOptionsMarkersFollowReadingDirection() {
        val ltr = selectionIndicatorRect(true, size, SelectionIndicator.LEADING_BAR, 1f, rtl = false)!!
        val rtl = selectionIndicatorRect(true, size, SelectionIndicator.LEADING_BAR, 1f, rtl = true)!!
        assertEquals(7f, ltr.left, .001f)
        assertEquals(93f, rtl.right, .001f)
        assertEquals(ltr.width, rtl.width, .001f)
        assertEquals(ltr.height, rtl.height, .001f)
    }

    @Test fun activeOsdButtonsUseACompactAccentDot() {
        val ltr = selectionIndicatorRect(true, size, SelectionIndicator.DOT, 1f, rtl = false)!!
        val rtl = selectionIndicatorRect(true, size, SelectionIndicator.DOT, 1f, rtl = true)!!
        assertEquals(5f, ltr.width, .001f)
        assertEquals(5f, ltr.height, .001f)
        assertEquals(88f, ltr.left, .001f)
        assertEquals(7f, rtl.left, .001f)
        assertEquals(7f, ltr.top, .001f)
    }

    @Test fun revealKeepsUnderlineCenteredAndItsThicknessFixed() {
        val full = selectionIndicatorRect(true, size, SelectionIndicator.UNDERLINE, 1f)!!
        assertNull(selectionIndicatorRevealRect(full, SelectionIndicator.UNDERLINE, 0f))
        val half = selectionIndicatorRevealRect(full, SelectionIndicator.UNDERLINE, .5f)!!
        assertEquals(full.center.x, half.center.x, .001f)
        assertEquals(full.width / 2f, half.width, .001f)
        assertEquals(full.height, half.height, .001f)
        assertEquals(full.top, half.top, .001f)
        assertEquals(full, selectionIndicatorRevealRect(full, SelectionIndicator.UNDERLINE, 1f))
    }

    @Test fun revealKeepsLeadingBarCenteredInBothLtrAndRtl() {
        for (rtl in listOf(false, true)) {
            val full = selectionIndicatorRect(true, size, SelectionIndicator.LEADING_BAR, 1f, rtl)!!
            val half = selectionIndicatorRevealRect(full, SelectionIndicator.LEADING_BAR, .5f)!!
            assertEquals(full.left, half.left, .001f)
            assertEquals(full.right, half.right, .001f)
            assertEquals(full.center.y, half.center.y, .001f)
            assertEquals(full.height / 2f, half.height, .001f)
        }
    }

    @Test fun dotExpandsFromCenterWithoutMovingItsAnchor() {
        val full = selectionIndicatorRect(true, size, SelectionIndicator.DOT, 1f)!!
        val half = selectionIndicatorRevealRect(full, SelectionIndicator.DOT, .5f)!!
        assertEquals(full.center.x, half.center.x, .001f)
        assertEquals(full.center.y, half.center.y, .001f)
        assertEquals(2.5f, half.width, .001f)
        assertEquals(2.5f, half.height, .001f)
    }

    @Test fun revealRejectsInvalidProgressAndClampsOverflow() {
        val full = selectionIndicatorRect(true, size, SelectionIndicator.UNDERLINE, 1f)!!
        assertNull(selectionIndicatorRevealRect(full, SelectionIndicator.UNDERLINE, Float.NaN))
        assertNull(selectionIndicatorRevealRect(full, SelectionIndicator.UNDERLINE, -1f))
        assertNull(selectionIndicatorRevealRect(full, SelectionIndicator.NONE, .5f))
        assertEquals(full, selectionIndicatorRevealRect(full, SelectionIndicator.UNDERLINE, 2f))
    }

    @Test fun selectionTransitionsAreShortAndAlphabetRailIsFaster() {
        assertEquals(220, selectionIndicatorMotionDurationMs(selecting = true))
        assertEquals(160, selectionIndicatorMotionDurationMs(selecting = false))
        assertEquals(180, selectionIndicatorMotionDurationMs(selecting = true, focusPulse = true))
        assertEquals(140, selectionIndicatorMotionDurationMs(selecting = true, compact = true))
        assertEquals(100, selectionIndicatorMotionDurationMs(selecting = false, compact = true))
        assertEquals(110, selectionIndicatorMotionDurationMs(selecting = true, focusPulse = true, compact = true))
    }

    @Test fun tinyOrNonfiniteControlsDoNotCreateInvalidMarkers() {
        assertNull(selectionIndicatorRect(true, Size(5f, 5f), SelectionIndicator.UNDERLINE, 1f))
        assertNull(selectionIndicatorRect(true, Size(Float.NaN, 48f), SelectionIndicator.DOT, 1f))
        assertNull(selectionIndicatorRect(true, size, SelectionIndicator.DOT, 0f))
        val compact = selectionIndicatorRect(true, Size(18f, 18f), SelectionIndicator.DOT, 1f)
        assertNotNull(compact)
        assertTrue(compact!!.left >= 0f && compact.top >= 0f)
        assertTrue(compact.right <= 18f && compact.bottom <= 18f)
        assertFalse(compact.isEmpty)
    }
}
