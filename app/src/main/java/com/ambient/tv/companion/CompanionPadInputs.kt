package com.ambient.tv.companion

import kotlin.math.abs

/** Main-thread input union: releasing a touch must never release a physical controller hold. */
internal class CompanionPadInputs {
    var touchButtons: Int = 0
    var hardwareButtons: Int = 0
    private val touchAxes = FloatArray(4)
    private val hardwareAxes = FloatArray(4)
    val buttons: Int get() = touchButtons or hardwareButtons

    fun touchStick(index: Int, x: Float, y: Float) {
        if (index !in 0..1) return
        touchAxes[index * 2] = x.coerceIn(-1f, 1f)
        touchAxes[index * 2 + 1] = y.coerceIn(-1f, 1f)
    }

    fun hardwareAxis(index: Int, value: Float) {
        if (index in 0..3) hardwareAxes[index] = value.coerceIn(-1f, 1f)
    }

    fun axis(index: Int): Float = if (abs(touchAxes[index]) >= abs(hardwareAxes[index]))
        touchAxes[index] else hardwareAxes[index]

    fun clearTouch() { touchButtons = 0; touchAxes.fill(0f) }
    fun clearHardware() { hardwareButtons = 0; hardwareAxes.fill(0f) }
    fun clear() { clearTouch(); clearHardware() }
}
