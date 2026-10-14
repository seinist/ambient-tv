package com.ambient.tv.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class MaxDisplayPixelsTest {
    @Test fun physicalFourKModeWinsOverFullHdUi() {
        assertEquals(3840L * 2160L, maxDisplayPixels(3840, 2160, 1920, 1080))
    }

    @Test fun fullHdOutputStaysFullHd() {
        assertEquals(1920L * 1080L, maxDisplayPixels(1920, 1080, 1920, 1080))
    }

    @Test fun unknownDisplayAllowsAnything() {
        assertEquals(Long.MAX_VALUE, maxDisplayPixels(0, 0, 0, 0))
        assertEquals(1920L * 1080L, maxDisplayPixels(0, 0, 1920, 1080))
    }
}
