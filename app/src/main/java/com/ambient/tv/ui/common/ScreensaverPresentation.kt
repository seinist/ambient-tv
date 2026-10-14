package com.ambient.tv.ui.common

import kotlin.math.min

internal object ScreensaverPresentation {
    const val FADE_IN_MS = 900
    const val FADE_OUT_MS = 360
    const val DRIFT_MS = 18000
    private val x = listOf(-0.70f, 0.55f, -0.35f, 0.75f, 0.15f, -0.80f, 0.60f, -0.10f)
    private val y = listOf(-0.55f, -0.75f, 0.65f, 0.35f, -0.20f, 0.75f, -0.40f, 0.10f)

    fun offset(index: Int, width: Float, height: Float, clockWidth: Float, clockHeight: Float, margin: Float, oled: Boolean): Pair<Float, Float> {
        val safeX = ((width - clockWidth) / 2f - margin).coerceAtLeast(0f)
        val safeY = ((height - clockHeight) / 2f - margin).coerceAtLeast(0f)
        val rangeX = if (oled) safeX else min(safeX, width * 0.14f)
        val rangeY = if (oled) safeY else min(safeY, height * 0.14f)
        val i = Math.floorMod(index, x.size)
        return x[i] * rangeX to y[i] * rangeY
    }

    fun shouldShow(active: Boolean, preview: Boolean, playback: Boolean): Boolean = active && (preview || !playback)
}
