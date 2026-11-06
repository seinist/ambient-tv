package com.ambient.tv.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSkipOverlayPlacementTest {
    @Test fun floatingActionUsesActualViewportAndButtonSize() {
        assertEquals(
            IntOffset(848, 638),
            skipOverlayPosition(IntSize(1280, 720), IntSize(400, 50), Offset.Zero,
                null, contextVisible = false, marginPx = 32),
        )
        // Expanding a translated OSD should never change a closed prompt's position.
        assertEquals(
            IntOffset(848, 638),
            skipOverlayPosition(IntSize(1280, 720), IntSize(400, 50), Offset.Zero,
                Rect(700f, 360f, 1200f, 430f), contextVisible = false, marginPx = 32),
        )
    }

    @Test fun openOsdMovesSameActionIntoMeasuredContextRow() {
        val viewport = IntSize(1280, 720)
        val action = IntSize(400, 50)
        val slot = Rect(700f, 360f, 1200f, 430f)
        assertEquals(
            IntOffset(800, 370),
            skipOverlayPosition(viewport, action, Offset.Zero, slot, true, 32),
        )
        assertEquals(
            IntOffset(848, 638),
            skipOverlayPosition(viewport, action, Offset.Zero, slot, false, 32),
        )
    }

    @Test fun usesRootCoordinatesRatherThanAssumingFullWindowAtZero() {
        assertEquals(
            IntOffset(1620, 672),
            skipOverlayPosition(IntSize(1920, 1080), IntSize(240, 56),
                Offset(40f, 80f), Rect(1600f, 740f, 1900f, 820f), true, 32),
        )
    }

    @Test fun shortScreensAndWideActionGroupsStayInsideSafeBounds() {
        val viewport = IntSize(360, 230)
        val action = IntSize(350, 100)
        assertEquals(
            IntOffset(0, 118),
            skipOverlayPosition(viewport, action, Offset.Zero,
                Rect(10f, 180f, 340f, 220f), true, 12),
        )
        assertEquals(
            IntOffset(10, 118),
            skipOverlayPosition(viewport, action, Offset.Zero, null, false, 12),
        )
        assertEquals(
            IntOffset(0, 0),
            skipOverlayPosition(IntSize(200, 80), IntSize(300, 120), Offset.Zero,
                Rect(0f, 0f, 200f, 80f), true, 12),
        )
    }

    @Test fun missingOrZeroSizeAnchorFallsBackToFloating() {
        assertEquals(
            IntOffset(548, 278),
            skipOverlayPosition(IntSize(800, 400), IntSize(220, 90), Offset.Zero,
                Rect(0f, 0f, 0f, 0f), true, 32),
        )
    }
}
