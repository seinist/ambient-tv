package com.ambient.tv.ui.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroBrandMarkMotionTest {
    @Test fun aStaysRigidWithTheTvUntilTheFrameIsAlmostGone() {
        assertEquals(0f, introBloomPeakMorph(2.29f), 0.0001f)
        assertEquals(0f, introBloomPeakMorph(2.3f), 0.0001f)
    }

    @Test fun aFinishesItsWordmarkShapeOnlyAtTheEndOfTheTvFade() {
        val duringFade = introBloomPeakMorph(2.35f)
        assertTrue(duringFade > 0f && duringFade < 1f)
        assertEquals(1f, introBloomPeakMorph(2.4f), 0.0001f)
    }
}
