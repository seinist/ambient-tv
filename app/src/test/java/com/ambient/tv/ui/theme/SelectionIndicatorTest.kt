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

    @Test fun selectedChipsKeepAnInsetUnderlineWithEveryAccentMaterial() {
        // The geometry remains stable for all eight materials and focus transitions;
        // the brush changes, not the indicator's meaning, size or location.
        for (style in AccentStyle.entries) {
            val marker = selectionIndicatorRect(true, size, SelectionIndicator.UNDERLINE, 1f)
            assertNotNull(style.toString(), marker)
            assertEquals(style.toString(), 34f, marker!!.left, .001f)
            assertEquals(style.toString(), 66f, marker.right, .001f)
            assertEquals(style.toString(), 44f, marker.bottom, .001f)
            assertEquals(style.toString(), 41f, marker.top, .001f)
        }
    }

    @Test fun sidebarAndOptionsMarkersFollowReadingDirection() {
        val ltr = selectionIndicatorRect(true, size, SelectionIndicator.LEADING_BAR, 1f, rtl = false)!!
        val rtl = selectionIndicatorRect(true, size, SelectionIndicator.LEADING_BAR, 1f, rtl = true)!!
        assertEquals(4f, ltr.left, .001f)
        assertEquals(96f, rtl.right, .001f)
        assertEquals(ltr.width, rtl.width, .001f)
        assertEquals(ltr.height, rtl.height, .001f)
    }

    @Test fun activeOsdButtonsUseACompactAccentDot() {
        val ltr = selectionIndicatorRect(true, size, SelectionIndicator.DOT, 1f, rtl = false)!!
        val rtl = selectionIndicatorRect(true, size, SelectionIndicator.DOT, 1f, rtl = true)!!
        assertEquals(6f, ltr.width, .001f)
        assertEquals(6f, ltr.height, .001f)
        assertEquals(90f, ltr.left, .001f)
        assertEquals(4f, rtl.left, .001f)
        assertEquals(4f, ltr.top, .001f)
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
        assertEquals(3f, half.width, .001f)
        assertEquals(3f, half.height, .001f)
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
        val compact = selectionIndicatorRect(true, Size(12f, 12f), SelectionIndicator.DOT, 1f)
        assertNotNull(compact)
        assertTrue(compact!!.left >= 0f && compact.top >= 0f)
        assertTrue(compact.right <= 12f && compact.bottom <= 12f)
        assertFalse(compact.isEmpty)
    }
}
