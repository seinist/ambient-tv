package com.ambient.tv.ui.gamepad.retro

import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
/** Device identity is checked before a phone controller intercepts Android navigation keys. */
internal fun isPhysicalGamepadEvent(event: InputEvent): Boolean {
    val device = event.device ?: return false
    return !device.isVirtual && (device.supportsSource(InputDevice.SOURCE_GAMEPAD) ||
        device.supportsSource(InputDevice.SOURCE_JOYSTICK))
}

/** The engine has already applied this device's axis layout, mapping, deadzone and disabled port. */
internal fun isPhysicalGamepadActivity(event: InputEvent, input: RetroPadInput): Boolean {
    if (!isPhysicalGamepadEvent(event) || !input.hasActiveGamepad(event.deviceId)) return false
    return when (event) {
        is KeyEvent -> event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        is MotionEvent -> event.actionMasked == MotionEvent.ACTION_MOVE
        else -> false
    }
}
