package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameDropDurationTest {
    @Test fun autoRetainsAdaptiveWindows() {
        assertEquals(2.0, mpvRenderSampleSeconds(true, 0), 0.0)
        assertEquals(6.0, mpvRenderSampleSeconds(false, 0), 0.0)
    }

    @Test fun explicitWindowOverridesBothProcessingPaths() {
        for (seconds in listOf(2, 5, 6, 10, 15, 30, 60)) {
            assertEquals(seconds.toDouble(), mpvRenderSampleSeconds(true, seconds), 0.0)
            assertEquals(seconds.toDouble(), mpvRenderSampleSeconds(false, seconds), 0.0)
        }
    }

    @Test fun invalidWindowFallsBackToAuto() {
        for (seconds in listOf(-1, 1, 61, Int.MAX_VALUE)) {
            assertEquals(2.0, mpvRenderSampleSeconds(true, seconds), 0.0)
            assertEquals(6.0, mpvRenderSampleSeconds(false, seconds), 0.0)
        }
    }
}
