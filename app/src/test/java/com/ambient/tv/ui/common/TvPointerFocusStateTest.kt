package com.ambient.tv.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPointerFocusStateTest {
    @Test fun stationaryPointerCannotUndoKeysEvenWhenAnotherControlEntersItsPosition() {
        val state = TvPointerFocusState()
        assertTrue(state.onPointer(200f, 400f, entered = true))
        state.onKeyInput()
        repeat(20) {
            assertFalse(state.onPointer(200f, 400f, entered = true))
            assertFalse(state.onPointer(200.2f, 399.8f, entered = false))
        }
        assertTrue(state.onPointer(204f, 400f, entered = false))
        assertTrue(state.onPointer(204f, 400f, entered = true))
        state.onKeyInput()
        assertFalse(state.onPointer(204f, 400f, entered = true))
        assertTrue(state.onPointer(204f, 400f, entered = false, pressed = true))
    }
}
