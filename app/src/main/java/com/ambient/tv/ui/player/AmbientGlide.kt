package com.ambient.tv.ui.player

import kotlin.math.roundToInt

/** Time constant of the ambient colour glide. */
internal const val AMBIENT_GLIDE_TAU_MS = 700f

/** The shown colours ease exponentially towards the latest capture without a mid-fade dip. */
internal class AmbientGlide {
    var width = 0
        private set
    var height = 0
        private set
    private var current = FloatArray(0)
    private var target = FloatArray(0)
    private var output = IntArray(0)

    /** Returns true only when the target or its dimensions change. Never borrows [pixels]. */
    fun setTarget(pixels: IntArray, width: Int, height: Int): Boolean {
        val resized = width != this.width || height != this.height || current.size != pixels.size * 3
        if (resized) {
            this.width = width
            this.height = height
            current = FloatArray(pixels.size * 3)
            target = FloatArray(current.size)
            output = IntArray(pixels.size)
        }
        var changed = resized
        for (i in pixels.indices) {
            val p = pixels[i]
            val j = i * 3
            val r = (p shr 16 and 0xFF).toFloat()
            val g = (p shr 8 and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            if (target[j] != r || target[j + 1] != g || target[j + 2] != b) changed = true
            target[j] = r
            target[j + 1] = g
            target[j + 2] = b
        }
        return changed
    }

    /** Moves [elapsedMs] closer to the target; false once all channels are settled. */
    fun step(elapsedMs: Float): Boolean {
        if (current.isEmpty()) return false
        val k = 1f - kotlin.math.exp(-elapsedMs.coerceAtLeast(0f) / AMBIENT_GLIDE_TAU_MS)
        var moving = false
        for (i in current.indices) {
            val delta = target[i] - current[i]
            if (delta == 0f) continue
            moving = true
            // Finish the subpixel tail explicitly so an idle animation can really stop.
            current[i] = if (kotlin.math.abs(delta) <= 0.5f) target[i] else current[i] + delta * k
        }
        return moving
    }

    /** Borrowed UI-dispatcher scratch; consume synchronously before the next call. */
    fun pixels(): IntArray {
        for (i in output.indices) {
            val r = current[i * 3].roundToInt().coerceIn(0, 255)
            val g = current[i * 3 + 1].roundToInt().coerceIn(0, 255)
            val b = current[i * 3 + 2].roundToInt().coerceIn(0, 255)
            output[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return output
    }
}
