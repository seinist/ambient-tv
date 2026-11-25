package com.ambient.tv.moonlight

import android.view.InputDevice
import android.view.KeyEvent

/**
 * Basic Android keyboard -> Windows virtual-key translation for GameStream.
 * Never captures Android HOME, system Back, volume or power. The caller switches off routing
 * while the Ambient TV OSD has focus, and always releases held keys at that boundary.
 */
internal class MoonlightKeyboardRouter(
    private val output: (virtualKey: Short, pressed: Boolean, modifiers: Byte) -> Unit,
) {
    private val pressed = LinkedHashSet<Short>()

    fun onKey(event: KeyEvent): Boolean {
        if (event.action !in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        if ((event.source and InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD ||
            event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC) return false
        if (event.keyCode in SYSTEM_KEYS) return false
        val key = virtualKey(event.keyCode) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount > 0) return true
        if (down) pressed += key else pressed.remove(key)
        val meta = (if (event.isShiftPressed) 0x01 else 0) or
            (if (event.isCtrlPressed) 0x02 else 0) or
            (if (event.isAltPressed) 0x04 else 0) or
            (if (event.isMetaPressed) 0x08 else 0)
        output(key, down, meta.toByte())
        return true
    }

    fun releaseAll() {
        pressed.forEach { output(it, false, 0) }
        pressed.clear()
    }

    private fun virtualKey(keyCode: Int): Short? = (when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 65 + keyCode - KeyEvent.KEYCODE_A
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 48 + keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 112 + keyCode - KeyEvent.KEYCODE_F1
        KeyEvent.KEYCODE_SPACE -> 32
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 13
        KeyEvent.KEYCODE_DEL -> 8
        KeyEvent.KEYCODE_FORWARD_DEL -> 46
        KeyEvent.KEYCODE_TAB -> 9
        KeyEvent.KEYCODE_ESCAPE -> 27
        KeyEvent.KEYCODE_DPAD_UP -> 38
        KeyEvent.KEYCODE_DPAD_DOWN -> 40
        KeyEvent.KEYCODE_DPAD_LEFT -> 37
        KeyEvent.KEYCODE_DPAD_RIGHT -> 39
        KeyEvent.KEYCODE_MOVE_HOME -> 36
        KeyEvent.KEYCODE_MOVE_END -> 35
        KeyEvent.KEYCODE_PAGE_UP -> 33
        KeyEvent.KEYCODE_PAGE_DOWN -> 34
        else -> null
    })?.toShort()

    companion object {
        private val SYSTEM_KEYS = setOf(
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_APP_SWITCH)
    }
}
