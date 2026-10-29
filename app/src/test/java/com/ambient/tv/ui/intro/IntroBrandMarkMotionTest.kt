package com.ambient.tv.ui.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroBrandMarkMotionTest {
    @Test fun rigidMonitorAUsesTheSameFinalScaleAsTheOriginalWordmarkAWidth() {
        assertEquals(16f / 18f, introBloomIconScaleInWordmarkUnits(), 0.0001f)
    }

    @Test fun mbientStartsAfterTheRetainedMonitorFrame() {
        val scale = introBloomIconScaleInWordmarkUnits()
        val frameRight = 9.914f + (42f - 24f) * scale
        val firstLetterInkLeft = 19.828f + 82f * 0.022f + introBloomWordShift()
        assertEquals(2.5f, firstLetterInkLeft - frameRight, 0.0001f)
        assertTrue(introBloomWordShift() > 0f)
    }

    @Test fun finalMonitorFrameExpandsTheBloomBoundsToTheLeft() {
        assertTrue(introBloomWordmarkLeft() < 0.5f)
    }
}
