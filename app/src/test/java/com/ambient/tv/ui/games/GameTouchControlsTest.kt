package com.ambient.tv.ui.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTouchControlsTest {
    @Test fun dpadSupportsDiagonalsAndReturnsNeutralAtCenter() {
        val size = IntSize(100, 100)
        assertEquals(emptySet<RetroPadButton>(), touchDpadDirections(Offset(50f, 50f), size))
        assertEquals(setOf(RetroPadButton.LEFT, RetroPadButton.UP), touchDpadDirections(Offset(0f, 0f), size))
        assertEquals(setOf(RetroPadButton.RIGHT, RetroPadButton.DOWN), touchDpadDirections(Offset(99f, 99f), size))
        assertEquals(setOf(RetroPadButton.UP), touchDpadDirections(Offset(50f, 0f), size))
    }

    @Test fun touchSticksClampAndReturnToCenter() {
        val size = IntSize(100, 100)
        assertEquals(Offset.Zero, touchStickAxes(Offset(50f, 50f), size))
        val topLeft = touchStickAxes(Offset(-200f, -200f), size)
        assertTrue(topLeft.x in -1f..0f)
        assertTrue(topLeft.y in -1f..0f)
        assertTrue(topLeft.getDistance() <= 1.01f)
        assertEquals(Offset.Zero, touchStickAxes(Offset.Zero, IntSize.Zero))
    }
}
