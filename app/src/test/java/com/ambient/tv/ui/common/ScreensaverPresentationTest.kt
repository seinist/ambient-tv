package com.ambient.tv.ui.common

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class ScreensaverPresentationTest {
    @Test fun explicitPreviewIsVisibleDuringBackgroundPlayback() {
        assertTrue(ScreensaverPresentation.shouldShow(active = true, preview = true, playback = true))
        assertFalse(ScreensaverPresentation.shouldShow(active = true, preview = false, playback = true))
        assertFalse(ScreensaverPresentation.shouldShow(active = false, preview = true, playback = false))
        assertTrue(ScreensaverPresentation.shouldShow(active = true, preview = false, playback = false))
    }

    @Test fun everyClockPositionStaysInsideMeasuredScreenMargins() {
        for ((w, h) in listOf(1920f to 1080f, 960f to 540f, 360f to 640f)) {
            val cw = w * 0.65f; val ch = h * 0.30f; val margin = 28f
            for (oled in listOf(false, true)) for (index in -1..16) {
                val (x, y) = ScreensaverPresentation.offset(index, w, h, cw, ch, margin, oled)
                assertTrue(abs(x) + cw / 2 + margin <= w / 2)
                assertTrue(abs(y) + ch / 2 + margin <= h / 2)
            }
        }
    }

    @Test fun oversizedClockStaysCenteredInsteadOfBeingPushedOffscreen() {
        val (x, y) = ScreensaverPresentation.offset(2, 320f, 240f, 400f, 300f, 28f, true)
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
    }

    @Test fun oledUsesWiderButStillBoundedMovement() {
        val normal = ScreensaverPresentation.offset(0, 1920f, 1080f, 400f, 180f, 28f, false)
        val oled = ScreensaverPresentation.offset(0, 1920f, 1080f, 400f, 180f, 28f, true)
        assertTrue(abs(oled.first) > abs(normal.first))
        assertTrue(abs(oled.second) > abs(normal.second))
    }
}
