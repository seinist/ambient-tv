package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayHandoffPolicyTest {
    private fun mode(w: Int, h: Int, hz: Float) = SupportedDisplayMode(DisplayRefreshRateMatcher.modeKey(w, h, hz), 0, w, h, hz)

    @Test fun doubledRateThatFitsStays() {
        val current = mode(1920, 1080, 50f)
        assertTrue(handoffKeepsCurrentMode(current, mode(1920, 1080, 25f), 25f, allowDoubleRate = true, highRateDoubling = false))
        assertTrue(handoffKeepsCurrentMode(mode(3840, 2160, 47.952f), mode(3840, 2160, 23.976f), 23.976f, true, false))
        assertTrue(handoffKeepsCurrentMode(current, current, 50f, allowDoubleRate = false, highRateDoubling = false))
    }

    @Test fun otherSizeCadenceOrForbiddenDoublingSwitches() {
        val current = mode(1920, 1080, 50f)
        assertFalse(handoffKeepsCurrentMode(current, mode(3840, 2160, 25f), 25f, true, false))
        assertFalse(handoffKeepsCurrentMode(current, mode(1920, 1080, 23.976f), 23.976f, true, false))
        assertFalse(handoffKeepsCurrentMode(current, mode(1920, 1080, 25f), 25f, allowDoubleRate = false, highRateDoubling = false))
        assertFalse(handoffKeepsCurrentMode(mode(1920, 1080, 100f), mode(1920, 1080, 50f), 50f, true, highRateDoubling = false))
        assertTrue(handoffKeepsCurrentMode(mode(1920, 1080, 100f), mode(1920, 1080, 50f), 50f, true, highRateDoubling = true))
        assertFalse(handoffKeepsCurrentMode(mode(1920, 1080, 60f), mode(1920, 1080, 24f), 24f, true, false))
    }
}
