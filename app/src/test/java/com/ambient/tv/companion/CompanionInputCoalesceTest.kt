package com.ambient.tv.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CompanionInputCoalesceTest {
    private fun move(x: Float, y: Float) = CompanionFrame(type = "input", input = CompanionInput("move", x = x, y = y))

    @Test
    fun `consecutive moves are summed`() {
        val merged = coalesceCompanionInput(move(0.1f, 0.2f), move(0.3f, -0.1f))!!
        assertEquals(0.4f, merged.input!!.x, 1e-6f)
        assertEquals(0.1f, merged.input.y, 1e-6f)
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
        assertEquals(-0.05f, merged.gamepadState.pointer.dy, 1e-6f)
        val click = second.copy(gamepadState = second.gamepadState!!.copy(
            pointer = second.gamepadState.pointer!!.copy(buttons = 1)))
        assertNull(coalesceCompanionInput(first, click))
    }

    @Test
    fun `absolute samples reuse state while preserving oldest queued timestamp`() {
        val first = CompanionFrame(type = "gamepad_input", gamepadState = CompanionGamepadState(leftX = 0.1f),
            gamepadQueuedAtMs = 10L)
        val next = first.copy(gamepadState = CompanionGamepadState(leftX = 0.9f), gamepadQueuedAtMs = 20L)
        val merged = coalesceCompanionInput(first, next)!!
        assertSame(next.gamepadState, merged.gamepadState)
        assertEquals(10L, merged.gamepadQueuedAtMs)
        assertSame(next, coalesceCompanionInput(first.copy(gamepadQueuedAtMs = 0L), next))
    }

    @Test
    fun `trigger edges and ordered contacts use the same predicate as the sender`() {
        fun frame(state: CompanionGamepadState) = CompanionFrame(type = "gamepad_input", gamepadState = state)
        val neutral = CompanionGamepadState()
        val pressed = neutral.copy(leftTrigger = 0.2f)
        assertNull(coalesceCompanionInput(frame(neutral), frame(pressed)))
        assertNull(coalesceCompanionInput(frame(pressed), frame(neutral)))
        val pressure = pressed.copy(leftTrigger = 0.8f)
        assertSame(frame(pressure).gamepadState, coalesceCompanionInput(frame(pressed), frame(pressure))!!.gamepadState)

        val a = CompanionPointerContact(0, 0.1f, 0.2f)
        val b = CompanionPointerContact(1, 0.3f, 0.4f)
        val off = CompanionPointerContact(2, 0f, 0f, pressed = false)
        fun state(vararg contacts: CompanionPointerContact) =
            neutral.copy(pointer = CompanionPointerState(contacts = contacts.toList()))
        val original = state(a, b)
        val moved = state(off, a.copy(x = 0.8f), b)
        assertFalse(companionGamepadCriticalEdge(original, moved))
        assertSame(moved, coalesceCompanionInput(frame(original), frame(moved))!!.gamepadState)
        assertTrue(companionGamepadCriticalEdge(original, state(b, a)))
        assertNull(coalesceCompanionInput(frame(original), frame(state(b, a))))
        assertNull(coalesceCompanionInput(frame(original), frame(state(a))))
    }

    @Test
    fun `validation retains axis bounds and rejects duplicate or invalid contacts`() {
        val good = CompanionGamepadState(leftX = -1f, rightY = 1f,
            motion = CompanionMotionState(mask = 3, ax = -16f, gz = 100f),
            pointer = CompanionPointerState(contacts = (0..3).map { CompanionPointerContact(it, 0f, 1f) }))
        good.validate()
        fun invalid(state: CompanionGamepadState) {
            assertTrue(runCatching { state.validate() }.exceptionOrNull() is IllegalArgumentException)
        }
        invalid(good.copy(leftX = Float.NaN))
        invalid(good.copy(rightY = Float.POSITIVE_INFINITY))
        invalid(good.copy(motion = CompanionMotionState(ax = 16.01f)))
        invalid(good.copy(motion = CompanionMotionState(gz = -100.01f)))
        invalid(good.copy(pointer = CompanionPointerState(contacts = listOf(
            CompanionPointerContact(0, 0f, 0f), CompanionPointerContact(0, 1f, 1f)))))
        invalid(good.copy(pointer = CompanionPointerState(contacts = listOf(CompanionPointerContact(32, 0f, 0f)))))
    }

    @Test
    fun `other frames and out of range sums are kept separate`() {
        assertNull(coalesceCompanionInput(move(0.9f, 0f), move(0.2f, 0f)))
        assertNull(coalesceCompanionInput(move(0.1f, 0f), CompanionFrame(type = "input", input = CompanionInput("click"))))
        assertNull(coalesceCompanionInput(move(0.1f, 0f), CompanionFrame(type = "status")))
        assertNull(coalesceCompanionInput(move(0.1f, 0f), CompanionFrame(type = "input", input = CompanionInput("scroll", y = 0.1f))))
    }
}
