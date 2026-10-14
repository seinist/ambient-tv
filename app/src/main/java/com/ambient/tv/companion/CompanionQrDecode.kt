package com.ambient.tv.companion

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.HybridBinarizer

/**
 * Decodes pairing QR codes from camera frames. Free of Android types so it can be exercised with
 * rendered codes in unit tests.
 *
 * Camera2 hands out YUV_420_888 frames whose Y plane alone is the grey image a QR decoder needs,
 * so nothing is converted. One reader instance is reused per scan (it is not thread safe, and the
 * scanner decodes on a single analysis thread).
 */
internal class CompanionQrDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            // The code fills a fair part of the frame and is held still: trade a little speed for
            // the harder cases (slight blur, an angle, poor light).
            DecodeHintType.TRY_HARDER to true,
        ))
    }

    /**
     * [luminance] is the Y plane as the camera delivered it, [rowStride] its row length in bytes
     * (often larger than [width]). Returns the decoded text, or null when this frame holds none.
     */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        if (luminance.size < rowStride * (height - 1) + width) return null
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
            // Not a readable code in this frame (the common case while aiming).
            null
        } finally {
            reader.reset()
        }
    }
}
