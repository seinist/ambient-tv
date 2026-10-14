package com.ambient.tv.ui.common

import kotlin.math.abs

/** Screen coordinates remain stable when controls scroll or a different window opens. */
internal class TvPointerFocusState {
    private var x: Float? = null
    private var y: Float? = null
    private var pointerActive = true

    fun onKeyInput() { pointerActive = false }

    fun onPointer(screenX: Float, screenY: Float, entered: Boolean, pressed: Boolean = false): Boolean {
        val moved = x == null || y == null || abs(screenX - x!!) > 1f || abs(screenY - y!!) > 1f
        if (moved || pressed) {
            x = screenX
            y = screenY
            pointerActive = true
        }
        return moved || pressed || (entered && pointerActive)
    }
}
