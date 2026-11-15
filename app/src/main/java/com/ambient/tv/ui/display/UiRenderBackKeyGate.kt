package com.ambient.tv.ui.display

import android.view.KeyEvent

/** Only the fallback that accepted a Back/Escape down may act on its matching release. */
internal class UiRenderBackKeyGate {
    private data class Key(val code: Int, val device: Int)
    private val pending = mutableMapOf<Key, Long>()

    fun onKeyEvent(event: KeyEvent, consumedByContent: Boolean): Boolean = onKeyEvent(
        event.keyCode, event.action, event.deviceId, event.downTime,
        event.repeatCount, event.isCanceled, consumedByContent,
    )

    internal fun onKeyEvent(
        keyCode: Int,
        action: Int,
        deviceId: Int,
        downTime: Long,
        repeatCount: Int = 0,
        canceled: Boolean = false,
        consumedByContent: Boolean = false,
    ): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ESCAPE) return false
        val key = Key(keyCode, deviceId)
        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (consumedByContent || canceled) pending.remove(key)
                else if (repeatCount == 0) pending[key] = downTime
                false
            }
            KeyEvent.ACTION_UP -> {
                val pressedAt = pending.remove(key)
                pressedAt == downTime && !canceled && !consumedByContent
            }
            else -> false
        }
    }

    fun reset() { pending.clear() }
}
