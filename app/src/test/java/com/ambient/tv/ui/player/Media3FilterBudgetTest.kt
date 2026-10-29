package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FilterBudgetTest {
    @Test fun displayOnlyUpscalingDoesNotChargeSharpeningForUnprocessed4kPixels() {
        // Bilinear: sharpen/grain process 1080p60, then the display path scales to 4K.
        assertTrue(media3SharpenBudgetAllows(1920, 1080, 3840, 2160, 60f, scalingInChain = false))
        // An actual GPU scaler makes those same passes process 4K60 and keeps the protection.
        assertFalse(media3SharpenBudgetAllows(1920, 1080, 3840, 2160, 60f, scalingInChain = true))
    }

    @Test fun nativeResolutionSharpeningKeepsTheExistingSourceBudget() {
        assertTrue(media3SharpenBudgetAllows(1920, 1080, 1920, 1080, 60f, scalingInChain = false))
        assertTrue(media3SharpenBudgetAllows(2560, 1440, 2560, 1440, 30f, scalingInChain = false))
        assertFalse(media3SharpenBudgetAllows(2560, 1440, 2560, 1440, 60f, scalingInChain = false))
        assertFalse(media3SharpenBudgetAllows(3840, 2160, 3840, 2160, 24f, scalingInChain = false))
    }

    @Test fun displayDownscalingDoesNotUnderstateTheShaderWork() {
        // Display downscaling occurs after the source-sized shader, so a small surface is no bypass.
        assertFalse(media3SharpenBudgetAllows(3840, 2160, 1920, 1080, 30f, scalingInChain = false))
    }

    @Test fun gpuScalingUsesTheOutputRateWithoutChangingTheThreshold() {
        assertTrue(media3SharpenBudgetAllows(1280, 720, 1920, 1088, 60f, scalingInChain = true))
        assertFalse(media3SharpenBudgetAllows(1280, 720, 1920, 1088, 61f, scalingInChain = true))
        assertTrue(media3SharpenBudgetAllows(1280, 720, 3840, 2160, 15f, scalingInChain = true))
        assertFalse(media3SharpenBudgetAllows(1280, 720, 3840, 2160, 16f, scalingInChain = true))
    }

    @Test fun unknownFrameRateStillUsesThirtyFps() {
        for (fps in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(media3SharpenBudgetAllows(1920, 1080, 3840, 2160, fps, scalingInChain = false))
            assertFalse(media3SharpenBudgetAllows(1920, 1080, 3840, 2160, fps, scalingInChain = true))
            assertFalse(media3FilterBudgetAllows(3840, 2160, fps))
        }
    }
}
