package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlayerZoomOptionsTest {
    @Test fun aPinchPercentageIsSelectedAndTheResetRemainsAvailable() {
        val zoom = 3.9506173f
        val choices = playerZoomChoices(zoom)
        assertEquals("395%", choices.value)
        assertEquals(zoom, choices.selectedKey.toFloat(), 0f)
        assertEquals(choices.value, choices.options.single { it.first == choices.selectedKey }.second)
        assertTrue("1.0" to "100%" in choices.options)
    }

    @Test fun presetValuesKeepOneSelectedOption() {
        val choices = playerZoomChoices(1.5000001f)
        assertEquals("1.5", choices.selectedKey)
        assertEquals(4, choices.options.size)
        assertEquals("150%", choices.value)
    }

    @Test fun invalidAndOutOfRangeValuesMatchTheRendererLimits() {
        assertEquals("1.0", playerZoomChoices(Float.NaN).selectedKey)
        assertEquals(VIDEO_MIN_ZOOM, playerZoomChoices(-1f).selectedKey.toFloat(), 0f)
        assertEquals(VIDEO_MAX_ZOOM, playerZoomChoices(100f).selectedKey.toFloat(), 0f)
    }
}
