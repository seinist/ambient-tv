package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerValueSliderTest {
    @Test
    fun intAdjustMovesByStepAndClamps() {
        assertEquals(1, adjustPlayerIntValue(0, -20..20, step = 1, deltaSteps = 1))
        assertEquals(-1, adjustPlayerIntValue(0, -20..20, step = 1, deltaSteps = -1))
        assertEquals(20, adjustPlayerIntValue(19, -20..20, step = 1, deltaSteps = 5))
        assertEquals(-20, adjustPlayerIntValue(-18, -20..20, step = 1, deltaSteps = -5))
    }

    @Test
    fun intAdjustSnapsOntoStepGrid() {
        assertEquals(10, adjustPlayerIntValue(3, -20..20, step = 5, deltaSteps = 1))
        assertEquals(0, adjustPlayerIntValue(3, -20..20, step = 5, deltaSteps = -1))
        assertEquals(100, adjustPlayerIntValue(97, 0..100, step = 5, deltaSteps = 1))
    }

    @Test
    fun dialogueFineStepsCoverFullRange() {
        var v = 0
        repeat(6) { v = adjustPlayerIntValue(v, 0..6, step = 1, deltaSteps = 1) }
        assertEquals(6, v)
        v = adjustPlayerIntValue(v, 0..6, step = 1, deltaSteps = 1)
        assertEquals(6, v)
    }

    @Test
    fun floatHalfStepAdjustsAndClamps() {
        assertEquals(0.5f, adjustPlayerFloatValue(0f, -12f, 12f, step = 0.5f, deltaSteps = 1), 0.001f)
        assertEquals(-0.5f, adjustPlayerFloatValue(0f, -12f, 12f, step = 0.5f, deltaSteps = -1), 0.001f)
        assertEquals(12f, adjustPlayerFloatValue(11.5f, -12f, 12f, step = 0.5f, deltaSteps = 4), 0.001f)
    }

    @Test
    fun repeatStepsAccelerateAfterHold() {
        assertEquals(1, playerValueRepeatSteps(0))
        assertEquals(1, playerValueRepeatSteps(3))
        assertEquals(2, playerValueRepeatSteps(8))
        assertEquals(4, playerValueRepeatSteps(20))
    }

    @Test
    fun progressIsUnipolarFraction() {
        assertEquals(0.5f, playerValueProgress(0, -20..20), 0.001f)
        assertEquals(0f, playerValueProgress(-20, -20..20), 0.001f)
        assertEquals(1f, playerValueProgress(20, -20..20), 0.001f)
        assertEquals(0.5f, playerValueProgress(3, 0..6), 0.001f)
    }

    @Test
    fun pointerFractionsSnapAndClamp() {
        assertEquals(-20, playerIntValueAtFraction(-1f, -20..20, step = 5))
        assertEquals(0, playerIntValueAtFraction(0.5f, -20..20, step = 5))
        assertEquals(20, playerIntValueAtFraction(2f, -20..20, step = 5))
        assertEquals(0.5f, playerFloatValueAtFraction(0.52f, -1f, 2f, step = 0.5f), 0.001f)
    }

    @Test
    fun labelsFormatSignedAndDb() {
        assertEquals("+5", formatSignedIntLabel(5))
        assertEquals("0", formatSignedIntLabel(0))
        assertEquals("-3", formatSignedIntLabel(-3))
        assertEquals("+2 dB", formatDbLabel(2))
        assertEquals("0 dB", formatDbLabel(0))
        assertEquals("-4 dB", formatDbLabel(-4, showPlus = false))
        assertEquals("+1.5", formatPlayerFloatLabel(1.5f))
        assertEquals("0", formatPlayerFloatLabel(0f))
    }
}
