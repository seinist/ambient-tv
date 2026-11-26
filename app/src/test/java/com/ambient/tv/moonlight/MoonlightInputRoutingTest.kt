package com.ambient.tv.moonlight

import android.app.Application
import android.view.KeyEvent
import android.view.InputDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Remote button-up must survive the OSD handoff and phone/TV focus loss. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MoonlightInputRoutingTest {
    @Test fun dpadReleaseAndOsdHandoffClearRemoteButtonStates() {
        val frames = mutableListOf<Pair<Int, Int>>()
        val router = MoonlightControllerRouter(output = { _, mask, buttons, _, _, _, _, _, _ ->
            frames += mask.toInt() to buttons
        })
        assertTrue(router.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)))
        assertEquals(1, frames.last().first)
        assertNotEquals(0, frames.last().second)
        router.releaseAll()
        assertEquals(0, frames.last().second)
        assertEquals(1, frames.last().first) // OSD release keeps player assignment
        assertEquals(0, frames.size - 2)
    }

    @Test fun gameStreamStickAxesAndButtonPositions() {
        assertEquals(32767, moonlightStick(1f).toInt())
        assertEquals(-32767, moonlightStick(-1f).toInt())
        assertEquals(0, moonlightStick(Float.NaN).toInt())
        assertEquals(0x1000, moonlightRetroButtonFlags(com.ambient.tv.ui.gamepad.retro.RetroPadButton.B.mask))
    }

    @Test fun keyboardKeysAndReleaseAllUseWindowsVirtualKeyCodes() {
        val events = mutableListOf<Triple<Int, Boolean, Int>>()
        val keyboard = MoonlightKeyboardRouter { code, pressed, modifiers ->
            events += Triple(code.toInt() and 0xffff, pressed, modifiers.toInt())
        }
        assertTrue(keyboard.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A).apply {
            source = InputDevice.SOURCE_KEYBOARD
        }))
        assertEquals(Triple(0x8041, true, 0), events.last())
        keyboard.releaseAll()
        assertEquals(Triple(0x8041, false, 0), events.last())
        assertFalse(keyboard.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
        assertTrue(keyboard.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT).apply {
            source = InputDevice.SOURCE_KEYBOARD
        }))
        assertEquals(0x80A1, events.last().first)
        keyboard.releaseAll()
        assertEquals(Triple(0x80A1, false, 0), events.last())
    }
}
