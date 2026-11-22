package com.ambient.tv.ui.games

import android.graphics.Bitmap
import com.ambient.tv.games.GameHdrToneMap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * CPU approximation of the GLES HDR->SDR conversion for small save-state previews.
 * Never applied to ordinary SDR screenshots. This works on a *downscaled* 8-bit
 * raw frame only after the core's HDR10 pixel-format handshake was accepted.
 * It is not a replacement for GPU color conversion or source mastering data.
 */
internal object GameHdrPreviewToneMap {
    fun transformSmallBitmap(bitmap: Bitmap, input: GameHdrToneMap): Bitmap {
        if (input == GameHdrToneMap.OFF) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0 || width.toLong() * height > 480L * 480L) return bitmap
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            val argb = pixels[index]
            pixels[index] = toneMapCode8(
                (argb ushr 16) and 255,
                (argb ushr 8) and 255,
                argb and 255,
                input,
                argb ushr 24,
            )
        }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        copy.setPixels(pixels, 0, width, 0, 0, width, height)
        return copy
    }

    /** Pure math so transfer/gamut behavior is covered by ordinary JVM tests. */
    fun toneMapCode8(red: Int, green: Int, blue: Int, mode: GameHdrToneMap, alpha: Int = 255): Int {
        fun nits(value: Int): Double {
            val encoded = value.coerceIn(0, 255) / 255.0
            return if (mode == GameHdrToneMap.HLG) {
                val scene = if (encoded <= 0.5) encoded * encoded / 3.0
                    else (exp((encoded - 0.55991073) / 0.17883277) + 0.28466892) / 12.0
                scene.coerceAtLeast(0.0)
            } else {
                val p = encoded.pow(1.0 / 78.84375)
                val base = (max(p - 0.8359375, 0.0) /
                    max(18.8515625 - 18.6875 * p, 0.00001)).coerceAtLeast(0.0)
                10000.0 * base.pow(1.0 / 0.1593017578125)
            }
        }
        if (mode == GameHdrToneMap.OFF) {
            return (alpha.coerceIn(0, 255) shl 24) or
                (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
        }
        var r = nits(red)
        var g = nits(green)
        var b = nits(blue)
        if (mode == GameHdrToneMap.HLG) {
            val l = max(r * 0.2627 + g * 0.6780 + b * 0.0593, 0.00001)
            val ootf = l.pow(0.2) * 1000.0
            r *= ootf; g *= ootf; b *= ootf
        }
        val luminance = r * 0.2627 + g * 0.6780 + b * 0.0593
        val mappedLuma = (luminance / (luminance + 203.0)) /
            (1000.0 / (1000.0 + 203.0))
        val scale = mappedLuma / max(luminance, 0.00001)
        r *= scale; g *= scale; b *= scale
        fun srgb(x: Double): Int {
            val clamped = x.coerceIn(0.0, 1.0)
            val value = if (clamped < 0.0031308) 12.92 * clamped
                else 1.055 * clamped.pow(1.0 / 2.4) - 0.055
            return (value * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }
        val rr = srgb(1.6605 * r - 0.5876 * g - 0.0728 * b)
        val gg = srgb(-0.1246 * r + 1.1329 * g - 0.0083 * b)
        val bb = srgb(-0.0182 * r - 0.1006 * g + 1.1187 * b)
        return (alpha.coerceIn(0, 255) shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
}
