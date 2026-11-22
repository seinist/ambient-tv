package com.ambient.tv.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionInputCoalesceTest {
    private fun move(x: Float, y: Float) = CompanionFrame(type = "input", input = CompanionInput("move", x = x, y = y))

    @Test
    fun `consecutive moves are summed`() {
        val merged = coalesceCompanionInput(move(0.1f, 0.2f), move(0.3f, -0.1f))!!
        assertEquals(0.4f, merged.input!!.x, 1e-6f)
        assertEquals(0.1f, merged.input!!.y, 1e-6f)
    }

    @Test
    fun `latest complete gamepad snapshot wins but release and menu stay ordered`() {
        val first = CompanionFrame(type = "gamepad_input",
            gamepadState = CompanionGamepadState(buttons = 1, leftX = -0.6f))
        val last = CompanionFrame(type = "gamepad_input",
            gamepadState = CompanionGamepadState(buttons = 0, leftX = 0f, rightY = 1f))
        assertNull("Pressed and released buttons must remain separate network frames", coalesceCompanionInput(first, last))
        assertEquals(last, coalesceCompanionInput(
            last.copy(gamepadState = last.gamepadState!!.copy(leftX = -0.5f)), last))
        assertNull(coalesceCompanionInput(last, CompanionFrame(type = "gamepad_release")))
        assertNull(coalesceCompanionInput(last, CompanionFrame(type = "gamepad_menu")))
        assertNull(coalesceCompanionInput(CompanionFrame(type = "gamepad_claim"), last))
    }

    @Test
    fun `multi touch press edges are never coalesced away`() {
        val down = CompanionFrame(type = "gamepad_input", gamepadState = CompanionGamepadState(
            pointer = CompanionPointerState(contacts = listOf(CompanionPointerContact(0, 0.2f, 0.4f)))))
        val up = CompanionFrame(type = "gamepad_input", gamepadState = CompanionGamepadState(
            pointer = CompanionPointerState()))
        assertNull(coalesceCompanionInput(down, up))
        val dragged = down.copy(gamepadState = down.gamepadState!!.copy(
            pointer = CompanionPointerState(contacts = listOf(CompanionPointerContact(0, 0.8f, 0.9f)))))
        assertEquals(dragged, coalesceCompanionInput(down, dragged))
    }

    @Test
    fun `mouse deltas add rather than disappear when motion frames are merged`() {
        val first = CompanionFrame(type = "gamepad_input", gamepadState = CompanionGamepadState(
            pointer = CompanionPointerState(kind = "mouse", dx = 0.15f, dy = 0.1f)))
        val second = CompanionFrame(type = "gamepad_input", gamepadState = CompanionGamepadState(
            pointer = CompanionPointerState(kind = "mouse", dx = 0.25f, dy = -0.15f)))
        val merged = coalesceCompanionInput(first, second)!!
        assertEquals(0.4f, merged.gamepadState!!.pointer!!.dx, 1e-6f)
        assertEquals(-0.05f, merged.gamepadState!!.pointer!!.dy, 1e-6f)
        val click = second.copy(gamepadState = second.gamepadState!!.copy(
            pointer = second.gamepadState!!.pointer!!.copy(buttons = 1)))
        assertNull(coalesceCompanionInput(first, click))
    }

    @Test
    fun `other frames and out of range sums are kept separate`() {
        assertNull(coalesceCompanionInput(move(0.9f, 0f), move(0.2f, 0f)))
        assertNull(coalesceCompanionInput(move(0.1f, 0f), CompanionFrame(type = "input", input = CompanionInput("click"))))
        assertNull(coalesceCompanionInput(move(0.1f, 0f), CompanionFrame(type = "status")))
        assertNull(coalesceCompanionInput(move(0.1f, 0f), CompanionFrame(type = "input", input = CompanionInput("scroll", y = 0.1f))))
    }
}
