package com.ambient.tv.data.local

import org.junit.Assert.*
import org.junit.Test

class UiRenderResolutionPolicyTest {
    @Test fun unknownChoicesAndUnsupportedDevicesUseTheSystemResolution() {
        for (key in listOf(null, "", "4k", "1080", "PANEL")) {
            assertEquals("system", UiRenderResolutionPolicy.normalize(key))
        }
        for (key in listOf("system", "1080p", "panel")) {
            assertEquals(key, UiRenderResolutionPolicy.normalize(key))
            assertEquals(key, UiRenderResolutionPolicy.effectiveKey(key, isTelevision = true, sdkInt = 30))
            assertEquals("system", UiRenderResolutionPolicy.effectiveKey(key, isTelevision = true, sdkInt = 29))
            assertEquals("system", UiRenderResolutionPolicy.effectiveKey(key, isTelevision = false, sdkInt = 35))
        }
    }

    @Test fun uiChoiceCanDifferFromTheSystemWindowWithoutChangingThePhysicalLimit() {
        assertEquals(1920 to 1080, UiRenderResolutionPolicy.targetSize("system", 1920, 1080, 3840, 2160))
        assertEquals(1920 to 1080, UiRenderResolutionPolicy.targetSize("1080p", 1920, 1080, 3840, 2160))
        assertEquals(3840 to 2160, UiRenderResolutionPolicy.targetSize("panel", 1920, 1080, 3840, 2160))
        assertEquals(1280 to 720, UiRenderResolutionPolicy.targetSize("panel", 1920, 1080, 1280, 720))
    }

    @Test fun fullHdPreservesTheWindowAspectRatioAndDoesNotUpscale() {
        for ((physical, expected) in listOf(
            (3840 to 2160) to (1920 to 1080),
            (2160 to 3840) to (1080 to 1920),
            (1920 to 1440) to (1440 to 1080),
            (3840 to 1600) to (1920 to 800),
            (1280 to 720) to (1280 to 720),
        )) {
            assertEquals(expected, UiRenderResolutionPolicy.targetSize("1080p", 1920, 1080, physical.first, physical.second))
        }
    }

    @Test fun missingPhysicalMetricsFallBackToTheWindowButNoSizeIsInvented() {
        for (key in listOf("system", "1080p", "panel", "unknown")) {
            assertEquals(1280 to 720, UiRenderResolutionPolicy.targetSize(key, 1280, 720, 0, -1))
            assertNull(UiRenderResolutionPolicy.targetSize(key, 0, 0, 0, 0))
        }
        assertNull(UiRenderResolutionPolicy.targetSize("system", 0, 0, 3840, 2160))
        assertEquals(3840 to 2160, UiRenderResolutionPolicy.targetSize("panel", 0, 0, 3840, 2160))
    }
}
