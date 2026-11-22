package com.ambient.tv.ui.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTouchInputTest {
    private fun layout(mode: GameTouchMode = GameTouchMode.DPAD): GameTouchLayout =
        gameTouchLayout(mode, 360f, gameTouchDeckHeight(mode, 360f), portrait = true)

    @Test fun digitalAreasCaptureNeutralCentersWhileControlGapsStayAvailable() {
        val layout = layout()
        val input = GameTouchInput(layout)
        assertFalse(input.press(1, Offset(-10f, -10f)))
        assertFalse(input.move(1, layout.buttons.getValue(RetroPadButton.A).center))
        assertTrue(input.press(2, layout.dpad!!.center))
        assertTrue(input.press(3, layout.faceButtonsBounds!!.center))
        assertTrue(input.state.buttons.isEmpty())
        assertTrue(input.release(2))
        assertTrue(input.release(3))
        assertFalse(input.release(1))
    }

    @Test fun thumbCanRollFromAToABToBWithoutLiftingAndCanLeaveAndReenter() {
        val layout = layout()
        val input = GameTouchInput(layout)
        val a = layout.buttons.getValue(RetroPadButton.A).center
        val b = layout.buttons.getValue(RetroPadButton.B).center
        input.press(1, a)
        assertEquals(setOf(RetroPadButton.A), input.state.buttons)
        input.move(1, (a + b) / 2f)
        assertEquals(setOf(RetroPadButton.A, RetroPadButton.B), input.state.buttons)
        input.move(1, b)
        assertEquals(setOf(RetroPadButton.B), input.state.buttons)
        input.move(1, Offset(-10f, -10f))
        assertTrue(input.state.buttons.isEmpty())
        input.move(1, layout.buttons.getValue(RetroPadButton.X).center)
        assertEquals(setOf(RetroPadButton.X), input.state.buttons)
        input.release(1)
        assertTrue(input.state.buttons.isEmpty())
    }

    @Test fun everyAdjacentFacePairHasACombinedTouchSector() {
        val layout = layout()
        val input = GameTouchInput(layout)
        val around = listOf(RetroPadButton.A, RetroPadButton.B, RetroPadButton.Y, RetroPadButton.X)
        around.forEachIndexed { index, first ->
            val second = around[(index + 1) % around.size]
            val position = (layout.buttons.getValue(first).center + layout.buttons.getValue(second).center) / 2f
            input.press(1, position)
            assertEquals(setOf(first, second), input.state.buttons)
            input.release(1)
        }
    }

    @Test fun releasingOneOfTwoFingersOnTheSameButtonKeepsItHeld() {
        val layout = layout()
        val input = GameTouchInput(layout)
        val a = layout.buttons.getValue(RetroPadButton.A).center
        input.press(1, a)
        input.press(2, a)
        input.release(1)
        assertEquals(setOf(RetroPadButton.A), input.state.buttons)
        input.release(2)
        assertTrue(input.state.buttons.isEmpty())
    }

    @Test fun repeatedFacePressesAndRollingDoNotReleaseTheOtherThumbDirection() {
        val layout = layout()
        val input = GameTouchInput(layout)
        val dpad = layout.dpad!!
        input.press(1, Offset(dpad.left + 8f, dpad.center.y))
        repeat(2) {
            input.press(2, layout.buttons.getValue(RetroPadButton.A).center)
            assertEquals(setOf(RetroPadButton.LEFT, RetroPadButton.A), input.state.buttons)
            input.release(2)
            assertEquals(setOf(RetroPadButton.LEFT), input.state.buttons)
        }
        input.press(2, layout.buttons.getValue(RetroPadButton.B).center)
        input.move(2, layout.buttons.getValue(RetroPadButton.X).center)
        assertEquals(setOf(RetroPadButton.LEFT, RetroPadButton.X), input.state.buttons)
        input.release(1)
        assertEquals(setOf(RetroPadButton.X), input.state.buttons)
    }

    @Test fun shoulderGestureReleasesAndCanSlideIntoTheNeighboringShoulder() {
        val layout = layout()
        val input = GameTouchInput(layout)
        input.press(1, layout.buttons.getValue(RetroPadButton.L2).center)
        assertEquals(setOf(RetroPadButton.L2), input.state.buttons)
        input.move(1, Offset(-10f, -10f))
        assertTrue(input.state.buttons.isEmpty())
        input.move(1, layout.buttons.getValue(RetroPadButton.L).center)
        assertEquals(setOf(RetroPadButton.L), input.state.buttons)
        input.move(1, layout.dpad!!.center)
        assertTrue(input.state.buttons.isEmpty())
    }

    @Test fun dpadGestureStaysWithTheDpadWhenDraggedOverOtherControls() {
        val layout = layout()
        val input = GameTouchInput(layout)
        val dpad = layout.dpad!!
        input.press(1, Offset(dpad.left + 8f, dpad.top + 8f))
        assertEquals(setOf(RetroPadButton.LEFT, RetroPadButton.UP), input.state.buttons)
        input.move(1, layout.buttons.getValue(RetroPadButton.A).center)
        assertFalse(RetroPadButton.A in input.state.buttons)
        assertTrue(RetroPadButton.RIGHT in input.state.buttons)
        input.move(1, dpad.center)
        assertTrue(input.state.buttons.isEmpty())
    }

    @Test fun angularDpadSectorsDoNotChangeWithThumbDistance() {
        val size = IntSize(100, 100)
        // The same diagonal near the center and at the edge must produce the same input.
        assertEquals(setOf(RetroPadButton.RIGHT, RetroPadButton.UP),
            touchDpadDirections(Offset(60f, 40f), size))
        assertEquals(setOf(RetroPadButton.RIGHT, RetroPadButton.UP),
            touchDpadDirections(Offset(100f, 0f), size))
        assertEquals(setOf(RetroPadButton.UP), touchDpadDirections(Offset(60f, 0f), size))
        assertEquals(emptySet<RetroPadButton>(), touchDpadDirections(Offset(55f, 55f), size))
    }

    @Test fun stickHasARadialDeadzoneAndClampsDiagonalsToUnitLength() {
        val size = IntSize(100, 100)
        assertEquals(Offset.Zero, touchStickAxes(Offset(53f, 53f), size))
        val partial = touchStickAxes(Offset(71f, 50f), size)
        assertTrue(partial.x in .4f.. .5f)
        assertEquals(0f, partial.y, .0001f)
        val diagonal = touchStickAxes(Offset(200f, -100f), size)
        assertTrue(diagonal.x > 0f && diagonal.y < 0f)
        assertEquals(1f, diagonal.getDistance(), .0001f)
    }

    @Test fun occupiedStickIgnoresSecondFingerAndNeverTransfersOwnershipOnRelease() {
        val layout = layout(GameTouchMode.DUAL)
        val input = GameTouchInput(layout)
        val stick = layout.leftStick!!
        input.press(1, Offset(stick.right - 1f, stick.center.y))
        val right = input.state.leftStick
        input.press(2, Offset(stick.left + 1f, stick.center.y))
        assertEquals(right, input.state.leftStick)
        input.release(2)
        assertEquals(right, input.state.leftStick)
        input.press(3, Offset(stick.left + 1f, stick.center.y))
        input.release(1)
        assertEquals(Offset.Zero, input.state.leftStick)
        assertFalse(input.state.leftStickHeld)
        input.move(3, stick.center)
        assertEquals(Offset.Zero, input.state.leftStick)
        assertFalse(input.state.leftStickHeld)
    }

    @Test fun twoSticksRemainIndependentAndCancelReleasesEveryInput() {
        val layout = layout(GameTouchMode.DUAL)
        val input = GameTouchInput(layout)
        val left = layout.leftStick!!
        val right = layout.rightStick!!
        input.press(1, Offset(left.right - 1f, left.center.y))
        input.press(2, Offset(right.center.x, right.top + 1f))
        input.press(3, layout.buttons.getValue(RetroPadButton.A).center)
        assertTrue(input.state.leftStick.x > 0f)
        assertTrue(input.state.rightStick.y < 0f)
        input.release(1)
        assertEquals(Offset.Zero, input.state.leftStick)
        assertTrue(input.state.rightStickHeld)
        assertTrue(RetroPadButton.A in input.state.buttons)
        input.clear()
        assertEquals(GameTouchInputState(), input.state)
        assertFalse(input.move(2, right.center))
        assertFalse(input.release(3))
    }
}
