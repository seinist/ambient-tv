package com.ambient.tv.ui.performance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Hidden device checks for the two performance preferences.
 *
 * Nothing is uploaded. Neither check presents a picture:
 * interface work draws into an off-screen bitmap that is never attached to a window,
 * and playback work is a CPU filter on plain arrays. No [android.view.Surface],
 * SurfaceView, TextureView or player is created.
 *
 * The result is one of the existing levels (Maximum, Balanced, Low). Auto is not a probe result.
 */
enum class PerformanceProbeTarget { UI, PLAYBACK }

object PerformanceProbe {
    /** Normalized cost at or above this maps to Low / Sparsam. */
    const val LOW_AT_MS = 420L
    /** Normalized cost at or above this, and below [LOW_AT_MS], maps to Balanced. */
    const val BALANCED_AT_MS = 140L

    private const val TARGET_ROUNDS = 8
    private const val BUDGET_NS = 220_000_000L

    @Volatile
    private var sink: Int = 0

    fun classify(normalizedMs: Long): PerformanceMode = when {
        normalizedMs >= LOW_AT_MS -> PerformanceMode.LOW
        normalizedMs >= BALANCED_AT_MS -> PerformanceMode.BALANCED
        else -> PerformanceMode.MAXIMUM
    }

    /** Interface draw cost: gradients, text and a software blur on a detached bitmap. */
    fun measureUiDrawCost(): Long = normalizedCost {
        val width = 192
        val height = 108
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(0xFF1A1A1A.toInt(), 0xFFE5A00D.toInt(), 0xFF224466.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(RectF(4f, 4f, width - 4f, height - 4f), 16f, 16f, paint)
            paint.shader = null
            paint.color = 0x66FFFFFF
            canvas.drawText("interface", 12f, 28f, paint)
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            for (y in 1 until height - 1) {
                val row = y * width
                for (x in 1 until width - 1) {
                    val i = row + x
                    pixels[i] = (pixels[i - 1] + pixels[i + 1] + pixels[i - width] + pixels[i + width]) ushr 2
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            pixels[width * height / 2]
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Playback filter load: a 4-tap upscale plus a field blend, standing in for heavy
     * upscalers and deinterlace. Arrays only — no picture is produced for the user.
     */
    fun measurePlaybackFilterLoad(): Long = normalizedCost {
        val sw = 128
        val sh = 72
        val dw = sw * 2
        val src = IntArray(sw * sh) { index -> index * 1103515245 }
        val mid = IntArray(dw * sh)
        var checksum = 0
        for (y in 0 until sh) {
            val row = y * sw
            val out = y * dw
            for (x in 0 until dw) {
                val sx = x / 2
                val s0 = src[row + (sx - 1).coerceAtLeast(0)]
                val s1 = src[row + sx]
                val s2 = src[row + (sx + 1).coerceAtMost(sw - 1)]
                val s3 = src[row + (sx + 2).coerceAtMost(sw - 1)]
                mid[out + x] = (s0 + s1 * 3 + s2 * 3 + s3) ushr 3
            }
        }
        for (y in 0 until sh) {
            val y0 = (y - 1).coerceAtLeast(0)
            val y2 = (y + 1).coerceAtMost(sh - 1)
            for (x in 0 until dw) {
                val blended = (mid[y0 * dw + x] + mid[y * dw + x] * 2 + mid[y2 * dw + x]) ushr 2
                checksum = checksum xor blended
            }
        }
        checksum
    }

    private inline fun normalizedCost(work: () -> Int): Long {
        val start = System.nanoTime()
        var rounds = 0
        var local = 0
        while (rounds < 2 || (rounds < TARGET_ROUNDS && System.nanoTime() - start < BUDGET_NS)) {
            local = local xor work()
            rounds++
        }
        sink = local
        val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
        return elapsedMs * TARGET_ROUNDS / rounds
    }
}
