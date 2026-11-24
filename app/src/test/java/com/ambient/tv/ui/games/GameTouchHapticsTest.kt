package com.ambient.tv.ui.games

import androidx.compose.ui.geometry.Offset
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTouchHapticsTest {
    private val directions = setOf(RetroPadButton.UP, RetroPadButton.DOWN, RetroPadButton.LEFT, RetroPadButton.RIGHT)

    @Test fun onlyEnabledNondirectionalButtonPressEdgesRequestFeedback() {
        for (button in RetroPadButton.entries) {
            val pressed = setOf(button)
            assertFalse(gameTouchButtonFeedback(false, emptySet(), pressed))
            assertEquals(button !in directions, gameTouchButtonFeedback(true, emptySet(), pressed))
            assertFalse(gameTouchButtonFeedback(true, pressed, pressed))
            assertFalse(gameTouchButtonFeedback(true, pressed, emptySet()))
        }
        assertFalse(gameTouchButtonFeedback(true, setOf(RetroPadButton.A), setOf(RetroPadButton.A, RetroPadButton.LEFT)))
        assertTrue(gameTouchButtonFeedback(true, setOf(RetroPadButton.A), setOf(RetroPadButton.A, RetroPadButton.B)))
    }

    @Test fun actualDpadAndStickGesturesRemainSilentIncludingDiagonalsAndNeutral() {
        val mode = GameTouchMode.HYBRID
        val layout = gameTouchLayout(mode, 360f, gameTouchDeckHeight(mode, 360f), portrait = true)
        val input = GameTouchInput(layout)
        var pulses = 0
        fun change(action: () -> Unit) {
            val before = input.state.buttons
            action()
            if (gameTouchButtonFeedback(true, before, input.state.buttons)) pulses++
        }
        val dpad = requireNotNull(layout.dpad)
        change { input.press(1, Offset(dpad.left + 8f, dpad.center.y)) }
        change { input.move(1, dpad.topLeft + Offset(8f, 8f)) }
        change { input.move(1, dpad.center) }
        change { input.release(1) }
        val stick = requireNotNull(layout.leftStick)
        change { input.press(2, stick.center) }
        change { input.move(2, stick.topLeft) }
        change { input.move(2, stick.bottomRight) }
        change { input.release(2) }
        assertEquals(0, pulses)
    }

    @Test fun heldMovementAndAdditionalFingersDoNotRepeatButANewButtonPressDoes() {
        val mode = GameTouchMode.DPAD
        val layout = gameTouchLayout(mode, 360f, gameTouchDeckHeight(mode, 360f), portrait = true)
        val input = GameTouchInput(layout)
        var pulses = 0
        fun change(action: () -> Unit) {
            val before = input.state.buttons
            action()
            if (gameTouchButtonFeedback(true, before, input.state.buttons)) pulses++
        }
        val a = layout.buttons.getValue(RetroPadButton.A).center
        val b = layout.buttons.getValue(RetroPadButton.B).center
        change { input.press(1, a) }
        repeat(5) { change { input.move(1, a + Offset(it.toFloat(), 0f)) } }
        change { input.press(2, a) }
        change { input.release(1) }
        assertEquals(1, pulses)
        change { input.move(2, (a + b) / 2f) }
        assertEquals(2, pulses) // B gained a press edge while A remains held.
        change { input.move(2, b) }
        change { input.release(2) }
        assertEquals(2, pulses)
        change { input.press(3, a) }
        change { input.clear() }
        assertEquals(3, pulses)
    }
}
