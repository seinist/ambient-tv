package com.ambient.tv.companion

import com.ambient.tv.util.QrCode
import org.junit.Assert.*
import org.junit.Test

/**
 * Renders codes with the app's own encoder (the one the TV shows) and reads them back the way the
 * phone's camera delivers frames: a Y plane with a row stride, quiet zone, scale and noise.
 */
class CompanionQrDecodeTest {
    private val link = "ambienttv://pair?host=192.168.178.42&port=49152&key=" + "a1b2c3d4".repeat(8) +
        "&token=" + "Ab3_".repeat(10) + "cde"

    /** The code centred in a frame, like a phone pointed at the TV. */
    private fun frame(
        text: String,
        scale: Int = 6,
        quiet: Int = 4,
        rowStridePadding: Int = 0,
        dark: Byte = 0x12,
        light: Byte = 0xE8.toByte(),
    ): Triple<ByteArray, Int, Int> {
        val code = QrCode.encode(text)
        val side = (code.size + quiet * 2) * scale
        val width = side
        val height = side
        val stride = width + rowStridePadding
        val pixels = ByteArray(stride * height) { light }
        for (y in 0 until code.size) for (x in 0 until code.size) {
            if (!code[x, y]) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                val py = (y + quiet) * scale + dy
                val px = (x + quiet) * scale + dx
                pixels[py * stride + px] = dark
            }
        }
        return Triple(pixels, width, height)
    }

    @Test fun readsTheInvitationBackFromACameraFrame() {
        val (pixels, width, height) = frame(link)
        assertEquals(link, CompanionQrDecoder().decode(pixels, width, height))
    }

    @Test fun readsFramesWithARowStrideLargerThanTheWidth() {
        val (pixels, width, height) = frame(link, rowStridePadding = 48)
        assertEquals(link, CompanionQrDecoder().decode(pixels, width, height, rowStride = width + 48))
    }

    @Test fun readsLowContrastAndSmallCodes() {
        val (dim, w1, h1) = frame(link, dark = 0x40, light = 0x9A.toByte())
        assertEquals(link, CompanionQrDecoder().decode(dim, w1, h1))
        val (small, w2, h2) = frame(link, scale = 3, quiet = 3)
        assertEquals(link, CompanionQrDecoder().decode(small, w2, h2))
    }

    @Test fun oneDecoderInstanceReadsManyFramesInARow() {
        val decoder = CompanionQrDecoder()
        val (empty, ew, eh) = frame(link).let { (p, w, h) -> Triple(ByteArray(p.size) { 0x80.toByte() }, w, h) }
        repeat(3) {
            assertNull(decoder.decode(empty, ew, eh))
            val (pixels, width, height) = frame(link)
            assertEquals(link, decoder.decode(pixels, width, height))
        }
    }

    @Test fun rejectsFramesThatDoNotHoldTheirPixels() {
        val decoder = CompanionQrDecoder()
        assertNull(decoder.decode(ByteArray(10), 100, 100))
        assertNull(decoder.decode(ByteArray(0), 0, 0))
        assertNull(decoder.decode(ByteArray(100), 10, 10, rowStride = 4))
    }

    @Test fun aScannedCodeStillHasToBeAValidInvitation() {
        val (pixels, width, height) = frame("https://example.com/not-a-pairing-link")
        val text = CompanionQrDecoder().decode(pixels, width, height)
        assertEquals("https://example.com/not-a-pairing-link", text)
        assertThrows(Exception::class.java) { CompanionInvitation.parse(text!!) }
    }
}
