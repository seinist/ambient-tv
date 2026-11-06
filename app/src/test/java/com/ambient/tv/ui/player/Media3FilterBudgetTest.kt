package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FilterBudgetTest {
    @Test fun processingInputMatchesDecoderRotationAndSquarePixelExpansion() {
        assertEquals(1920 to 1080, media3ProcessingInputSize(1440, 1080, 4f / 3f))
        assertEquals(1920 to 1080, media3ProcessingInputSize(1920, 540, 0.5f))
        assertEquals(1080 to 1920, media3ProcessingInputSize(1440, 1080, 4f / 3f, 90))
        assertEquals(1080 to 1920, media3ProcessingInputSize(1440, 1080, 4f / 3f, 270))
        assertEquals(1920 to 1080, media3ProcessingInputSize(1440, 1080, 4f / 3f, 180))
    }

    @Test fun normalizedInputPreventsUnderchargingAnamorphicCleanupAndRotatedUpscaling() {
        val anamorphic = media3ProcessingInputSize(1920, 1080, 2f)
        val cleanup = media3ApplyFilterBudget(listOf(Media3Stage.Deband("light")),
            anamorphic.first, anamorphic.second, 3840, 2160, 60f)
        assertEquals(Media3Stage.Deband("light"), cleanup.excluded.single().stage)
        assertEquals(3840, cleanup.excluded.single().width)

        // A portrait-encoded track rotated to landscape fills UHD; the raw dimensions would
        // incorrectly charge Adaptive only for a 1215x2160 fitted portrait image.
        val rotated = media3ProcessingInputSize(1080, 1920, rotationDegrees = 90)
        val plan = listOf(Media3Stage.Scale("catmull_rom"), Media3Stage.Sharpen("adaptive"))
        val upscale = media3ApplyFilterBudget(plan, rotated.first, rotated.second, 3840, 2160, 60f)
        assertEquals(3840 to 2160, upscale.processingSize)
        assertEquals(Media3Stage.Sharpen("adaptive"), upscale.excluded.single().stage)
    }

    @Test fun invalidPixelRatioFallsBackAndExtremeExpansionCannotWrapIntoAnAllowedSize() {
        for (ratio in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1920 to 1080, media3ProcessingInputSize(1920, 1080, ratio))
        }
        val hugeWidth = media3ProcessingInputSize(1920, 1080, Float.MAX_VALUE)
        val hugeHeight = media3ProcessingInputSize(1920, 1080, Float.MIN_VALUE)
        val rotated = media3ProcessingInputSize(1920, 1080, Float.MIN_VALUE, 90)
        assertEquals(Int.MAX_VALUE to 1080, hugeWidth)
        assertEquals(1920 to Int.MAX_VALUE, hugeHeight)
        assertEquals(Int.MAX_VALUE to 1920, rotated)
        for (size in listOf(hugeWidth, hugeHeight, rotated)) {
            assertFalse(media3FilterBudgetAllows(size.first, size.second, 30f))
        }
    }

    @Test fun lightSharpeningAllowsUhd24ThirtyAndSixtyButStillHasAnUpperBound() {
        for (kind in listOf("light", "medium", "strong", "cas", "rcas")) {
            for (fps in listOf(23.976f, 24f, 29.97f, 30f, 59.94f, 60f)) {
                assertTrue("$kind at $fps", media3SharpenBudgetAllows(
                    3840, 2160, 3840, 2160, fps, scalingInChain = false, kind = kind))
            }
            assertFalse("$kind above UHD60", media3SharpenBudgetAllows(
                3840, 2160, 3840, 2160, 61f, scalingInChain = false, kind = kind))
            assertFalse("$kind at 8K30", media3SharpenBudgetAllows(
                7680, 4320, 7680, 4320, 30f, scalingInChain = false, kind = kind))
        }
    }

    @Test fun adaptiveSharpeningAllowsUhdFilmsButNotUhdSixty() {
        for (fps in listOf(23.976f, 24f, 29.97f, 30f)) {
            assertTrue(media3SharpenBudgetAllows(
                3840, 2160, 3840, 2160, fps, scalingInChain = false, kind = "adaptive"))
        }
        for (fps in listOf(31f, 50f, 59.94f, 60f)) {
            assertFalse(media3SharpenBudgetAllows(
                3840, 2160, 3840, 2160, fps, scalingInChain = false, kind = "adaptive"))
        }
    }

    @Test fun displayOnlyScalingDoesNotChangeTheShaderWorkload() {
        // Bilinear: sharpen processes 1080p60 before the display scales to a larger surface.
        assertTrue(media3SharpenBudgetAllows(1920, 1080, 7680, 4320, 60f, scalingInChain = false))
        assertFalse(media3SharpenBudgetAllows(1920, 1080, 7680, 4320, 60f, scalingInChain = true))
        // Display downscaling occurs afterwards too: a smaller surface does not evade the cap.
        assertFalse(media3SharpenBudgetAllows(7680, 4320, 1920, 1080, 30f, scalingInChain = false))
        assertTrue(media3SharpenBudgetAllows(7680, 4320, 1920, 1080, 30f, scalingInChain = true))
    }

    @Test fun cleanupAndUnrecognisedShadersKeepTheConservativeBudget() {
        assertTrue(media3FilterBudgetAllows(1920, 1088, 60f))
        assertFalse(media3FilterBudgetAllows(1920, 1088, 61f))
        assertFalse(media3FilterBudgetAllows(3840, 2160, 24f))
        assertFalse(media3SharpenBudgetAllows(
            3840, 2160, 3840, 2160, 24f, scalingInChain = false, kind = "future_shader"))
    }

    @Test fun unknownFrameRateStillUsesThirtyFps() {
        for (fps in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(media3SharpenBudgetAllows(1920, 1080, 3840, 2160, fps, scalingInChain = false))
            assertTrue(media3SharpenBudgetAllows(
                1920, 1080, 3840, 2160, fps, scalingInChain = true, kind = "adaptive"))
            assertFalse(media3SharpenBudgetAllows(1920, 1080, 7680, 4320, fps, scalingInChain = true))
            assertFalse(media3FilterBudgetAllows(3840, 2160, fps))
            assertEquals(30f, media3FilterFrameRate(fps), 0f)
        }
    }

    @Test fun uhdThirtyRunsAdaptiveAndGrainWhileCleanupKeepsItsOwnLimit() {
        val requested = listOf(
            Media3Stage.Deblock("light"), Media3Stage.Denoise("medium"), Media3Stage.Deband("light"),
            Media3Stage.Sharpen("adaptive"), Media3Stage.Grain("strong"), Media3Stage.Gamma(3),
        )
        val result = media3ApplyFilterBudget(requested, 3840, 2160, 3840, 2160, 30f)
        assertEquals(requested.drop(3), result.stages)
        assertEquals(requested.take(3), result.excluded.map { it.stage })
        assertTrue(result.excluded.all {
            it.width == 3840 && it.height == 2160 && it.frameRate == 30f &&
                it.maxPixelRate == MEDIA3_FILTER_MAX_PIXEL_RATE
        })
        assertEquals(3840 to 2160, result.processingSize)
    }

    @Test fun uhdSixtyRejectsAdaptiveWithoutDisablingGrainOrColor() {
        val requested = listOf(Media3Stage.Sharpen("adaptive"), Media3Stage.Grain("light"), Media3Stage.Contrast(2))
        val result = media3ApplyFilterBudget(requested, 3840, 2160, 3840, 2160, 60f)
        assertEquals(requested.drop(1), result.stages)
        assertEquals(Media3FilterBudgetExclusion(
            requested.first(), 3840, 2160, 60f, MEDIA3_ADAPTIVE_SHARPEN_MAX_PIXEL_RATE), result.excluded.single())
    }

    @Test fun fittedImageDoesNotPayForLetterboxPixels() {
        // 4:3 is fitted to 2880x2160. At 40fps it fits Adaptive's budget, unlike the full 16:9 surface.
        val requested = listOf(Media3Stage.Deband("light"), Media3Stage.Scale("catmull_rom"), Media3Stage.Sharpen("adaptive"))
        val result = media3ApplyFilterBudget(requested, 1440, 1080, 3840, 2160, 40f)
        assertEquals(2880 to 2160, result.processingSize)
        assertEquals(requested, result.stages)
        assertTrue(result.excluded.isEmpty())
        assertFalse(media3SharpenBudgetAllows(
            1440, 1080, 3840, 2160, 40f, scalingInChain = true, kind = "adaptive"))
    }

    @Test fun explicitDownscaleReducesSharpeningCostButNotEarlierCleanup() {
        val requested = listOf(Media3Stage.Deband("light"), Media3Stage.Downscale("lanczos"),
            Media3Stage.Sharpen("adaptive"), Media3Stage.Grain("medium"))
        val result = media3ApplyFilterBudget(requested, 3840, 2160, 1920, 1080, 60f)
        assertEquals(requested.drop(1), result.stages)
        assertEquals(requested.first(), result.excluded.single().stage)
        assertEquals(1920 to 1080, result.processingSize)
    }

    @Test fun displayScalingLeavesBudgetOnSourcePixels() {
        val requested = listOf(Media3Stage.Sharpen("adaptive"), Media3Stage.Grain("medium"))
        val result = media3ApplyFilterBudget(requested, 1920, 1080, 7680, 4320, 60f)
        assertEquals(requested, result.stages)
        assertTrue(result.excluded.isEmpty())
        assertEquals(1920 to 1080, result.processingSize)
    }

    @Test fun fsrBudgetUsesRcasInsteadOfTheInactiveSavedAdaptiveChoice() {
        val plan = media3EffectPlan(Media3ImageOptions(enabled = true, scaler = "fsr", sharpening = "adaptive"), upscaling = true)
        val result = media3ApplyFilterBudget(plan, 1920, 1080, 3840, 2160, 60f)
        assertEquals(listOf(Media3Stage.Scale("fsr"), Media3Stage.Sharpen("rcas")), result.stages)
        assertTrue(result.excluded.isEmpty())
    }

    @Test fun nisFallbackRestoresSavedSharpeningAndChecksThatShadersBudget() {
        val plan = media3EffectPlan(Media3ImageOptions(enabled = true, scaler = "nis", sharpening = "adaptive", filmGrain = "light"),
            upscaling = true, heavyScaleAllowed = false)
        val result = media3ApplyFilterBudget(plan, 1920, 1080, 3840, 2160, 60f)
        assertEquals(listOf(Media3Stage.Scale("catmull_rom"), Media3Stage.Grain("light")), result.stages)
        assertEquals(Media3Stage.Sharpen("adaptive"), result.excluded.single().stage)
    }

    @Test fun overBudgetLightFiltersAreReportedIndividually() {
        val requested = listOf(Media3Stage.Sharpen("cas"), Media3Stage.Grain("light"), Media3Stage.Gamma(2))
        val result = media3ApplyFilterBudget(requested, 7680, 4320, 7680, 4320, 30f)
        assertEquals(listOf(Media3Stage.Gamma(2)), result.stages)
        assertEquals(requested.take(2), result.excluded.map { it.stage })
        assertTrue(result.excluded.all { it.maxPixelRate == MEDIA3_LIGHT_FILTER_MAX_PIXEL_RATE })
    }
}
