package com.ambient.tv.companion

import com.ambient.tv.ui.gamepad.retro.RetroPadButton

/**
 * The digital edge gate may retain a short L2/R2 tap for one polling interval. Keep its
 * pressure in that interval too, otherwise libretro's analog-zero fallback turns it into
 * a full-strength digital press. A queued re-press stays neutral through the gate's UP gap.
 */
internal class CompanionTriggerEdges {
    private val lastNonzero = FloatArray(2)

    fun pressure(index: Int, rawButtons: Int, visibleButtons: Int, value: Float?): Float? {
        if (value == null) { lastNonzero[index] = 0f; return null }
        if (value > 0f) lastNonzero[index] = value
        val mask = if (index == 0) RetroPadButton.L2.mask else RetroPadButton.R2.mask
        val raw = rawButtons and mask != 0
        val visible = visibleButtons and mask != 0
        return when {
            raw && !visible -> 0f
            visible && value == 0f -> lastNonzero[index]
            else -> value
        }
    }
}
