package com.ambient.tv.ui.display

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiRenderBackKeyGateTest {
    @Test fun contentThatClosesOnDownDoesNotCauseASecondBackOnRelease() {
        val gate = UiRenderBackKeyGate()
        assertFalse(gate.key(KeyEvent.ACTION_DOWN, consumed = true))
        assertFalse(gate.key(KeyEvent.ACTION_UP))
        assertFalse(gate.key(KeyEvent.ACTION_DOWN, downTime = 20))
        assertTrue(gate.key(KeyEvent.ACTION_UP, downTime = 20))
        assertFalse(gate.key(KeyEvent.ACTION_UP, downTime = 20))
    }

    @Test fun repeatsCannotArmAnOrphanPressOrDispatchBeforeRelease() {
        val gate = UiRenderBackKeyGate()
        assertFalse(gate.key(KeyEvent.ACTION_DOWN, repeatCount = 1))
        assertFalse(gate.key(KeyEvent.ACTION_UP))
        assertFalse(gate.key(KeyEvent.ACTION_DOWN, downTime = 20))
        assertFalse(gate.key(KeyEvent.ACTION_DOWN, downTime = 20, repeatCount = 1))
        assertTrue(gate.key(KeyEvent.ACTION_UP, downTime = 20))
    }

    @Test fun canceledOrConsumedReleasesAndFocusLossDisarmTheFallback() {
        val gate = UiRenderBackKeyGate()
        gate.key(KeyEvent.ACTION_DOWN)
        assertFalse(gate.key(KeyEvent.ACTION_UP, canceled = true))
        gate.key(KeyEvent.ACTION_DOWN, downTime = 20)
        assertFalse(gate.key(KeyEvent.ACTION_UP, downTime = 20, consumed = true))
        gate.key(KeyEvent.ACTION_DOWN, downTime = 30)
        gate.reset()
        assertFalse(gate.key(KeyEvent.ACTION_UP, downTime = 30))
    }

    @Test fun aReleaseMustMatchItsOwnKeyDeviceAndPressTime() {
        val gate = UiRenderBackKeyGate()
        gate.key(KeyEvent.ACTION_DOWN)
        assertFalse(gate.key(KeyEvent.ACTION_UP, keyCode = KeyEvent.KEYCODE_ESCAPE))
        assertFalse(gate.key(KeyEvent.ACTION_UP, deviceId = 2))
        assertTrue(gate.key(KeyEvent.ACTION_UP))
        gate.key(KeyEvent.ACTION_DOWN, downTime = 20)
        assertFalse(gate.key(KeyEvent.ACTION_UP, downTime = 21))
        assertFalse(gate.key(KeyEvent.ACTION_UP, downTime = 20))
    }

    @Test fun escapeUsesTheSamePairingAndAConsumedRepeatClearsThePress() {
        val gate = UiRenderBackKeyGate()
        gate.key(KeyEvent.ACTION_DOWN, keyCode = KeyEvent.KEYCODE_ESCAPE)
        assertTrue(gate.key(KeyEvent.ACTION_UP, keyCode = KeyEvent.KEYCODE_ESCAPE))
        gate.key(KeyEvent.ACTION_DOWN, downTime = 20)
        gate.key(KeyEvent.ACTION_DOWN, downTime = 20, repeatCount = 1, consumed = true)
        assertFalse(gate.key(KeyEvent.ACTION_UP, downTime = 20))
    }

    private fun UiRenderBackKeyGate.key(
        action: Int,
        keyCode: Int = KeyEvent.KEYCODE_BACK,
        deviceId: Int = 1,
        downTime: Long = 10,
        repeatCount: Int = 0,
        canceled: Boolean = false,
        consumed: Boolean = false,
    ): Boolean = onKeyEvent(keyCode, action, deviceId, downTime, repeatCount, canceled, consumed)
}
