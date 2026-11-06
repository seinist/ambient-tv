package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: low UI profiles must not silently disable row/grid focus motion. */
class FocusMotionPolicyTest {
    @Test fun fullProfileRetainsCardAndRowScale() {
        assertEquals(1.04f, mediaFocusTargetScale(1.04f, reducedEffects = false), 0.0001f)
        assertEquals(1.012f, mediaFocusTargetScale(1.012f, reducedEffects = false), 0.0001f)
    }

    @Test fun reducedProfileKeepsSmallerButVisibleCardAndRowLift() {
        val card = mediaFocusTargetScale(1.04f, reducedEffects = true)
        val row = mediaFocusTargetScale(1.012f, reducedEffects = true)
        assertEquals(1.02f, card, 0.0001f)
        assertEquals(1.006f, row, 0.0001f)
        assertTrue(card > 1f && row > 1f)
    }

    @Test fun normalScaleStaysUnchangedAndReducedFocusTweenIsShort() {
        assertEquals(1f, mediaFocusTargetScale(1f, reducedEffects = true), 0f)
        assertEquals(150, mediaFocusReducedDurationMs(focused = true))
        assertEquals(115, mediaFocusReducedDurationMs(focused = false))
    }
}
