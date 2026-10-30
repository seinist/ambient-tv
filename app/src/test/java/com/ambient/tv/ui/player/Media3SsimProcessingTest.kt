package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import com.ambient.tv.ui.player.backend.media3ImageOptionsAfterOverload
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure planner and compatibility checks for the two multi-pass SSim shader ports. */
class Media3SsimProcessingTest {
    @Test fun oldProfilesRemainOffUntilTheUserExplicitlyEnablesSsim() {
        val old = Json.decodeFromString<Media3ImageOptions>(
            """{"enabled":true,"scaler":"spline36","downscaler":"mitchell"}""")
        assertFalse(old.superResolution)
        assertEquals("mitchell", old.downscaler)
        assertEquals("ssim_down",
            Media3ImageOptions(downscaler = "ssim_down").normalized().downscaler)
        assertEquals("SSimDownscaler", VideoImageCatalog.media3Downscalers.getValue("ssim_down"))
        assertFalse(Media3ImageOptions(scaler = "fsr", superResolution = true).normalized().superResolution)
        assertFalse(Media3ImageOptions(scaler = "nis", superResolution = true).normalized().superResolution)
        assertFalse(Media3ImageOptions(scaler = "bilinear", superResolution = true).normalized().superResolution)
        assertTrue(Media3ImageOptions(scaler = "spline36", superResolution = true).normalized().superResolution)
    }

    @Test fun superResWrapsTheChosenUpscalerOnlyWhileUpscaling() {
        val o = Media3ImageOptions(enabled = true, scaler = "spline36", superResolution = true)
        assertEquals(listOf(Media3Stage.Scale("spline36", superResolution = true)),
            media3EffectPlan(o, upscaling = true))
        assertEquals(listOf(Media3Stage.Scale("spline36")),
            media3EffectPlan(o, upscaling = true, ssimSuperAllowed = false))
        assertEquals(emptyList<Media3Stage>(), media3EffectPlan(o, upscaling = false))
        assertTrue(media3SuperResRowVisible(o, true))
        assertFalse(media3SuperResRowVisible(o, false))
        for (key in listOf("bilinear", "fsr", "nis")) {
            assertFalse(key, media3SuperResRowVisible(o.copy(scaler = key), true))
        }
        assertEquals(listOf(Media3Stage.Scale("ravu_lite_r3", superResolution = true)),
            media3EffectPlan(o.copy(scaler = "ravu_lite_r3"), upscaling = true))
    }

    @Test fun superResDoesNotFuseGammaIntoItsInputScaler() {
        val plan = media3EffectPlan(Media3ImageOptions(
            enabled = true, scaler = "catmull_rom", superResolution = true, gamma = 12), upscaling = true)
        assertEquals(listOf(
            Media3FusedStage(Media3Stage.Scale("catmull_rom", superResolution = true)),
            Media3FusedStage(Media3Stage.Gamma(12)),
        ), media3FusedStages(plan))
    }

    @Test fun ssimDownRunsOnlyWhileReducingWithinItsBudget() {
        val o = Media3ImageOptions(enabled = true, downscaler = "ssim_down", antiring = 70)
        assertEquals(listOf(Media3Stage.Downscale("ssim_down")),
            media3EffectPlan(o, upscaling = false, downscaling = true))
        assertEquals(listOf(Media3Stage.Downscale("lanczos")),
            media3EffectPlan(o, upscaling = false, downscaling = true, ssimDownAllowed = false))
        assertEquals(listOf(Media3Stage.Downscale("lanczos")),
            media3EffectPlan(o, upscaling = false, downscaling = true, heavyDownscaleAllowed = false))
        assertTrue(media3EffectPlan(o, upscaling = false, downscaling = false).isEmpty())
        assertFalse(media3AntiRingRowVisible(o, false, true))
    }

    @Test fun ssimBudgetsLimitPixelRateAndRatioToAvoidUnboundedGpuWork() {
        assertTrue(media3SsimDownBudgetAllows(1920, 1080, 1280, 720, 30f))
        assertTrue(media3SsimDownBudgetAllows(1920, 1080, 1280, 720, 0f))
        assertFalse(media3SsimDownBudgetAllows(1920, 1080, 1280, 720, 60f))
        assertFalse(media3SsimDownBudgetAllows(3840, 2160, 1920, 1080, 30f))
        assertFalse(media3SsimDownBudgetAllows(1920, 1080, 640, 360, 24f))
        assertFalse(media3SsimDownBudgetAllows(1920, 1080, 3840, 2160, 30f))
        assertTrue(media3SsimSuperBudgetAllows(640, 360, 1280, 720, 30f))
        assertTrue(media3SsimSuperBudgetAllows(1280, 720, 1920, 1080, 30f))
        assertFalse(media3SsimSuperBudgetAllows(1280, 720, 1920, 1080, 60f))
        assertFalse(media3SsimSuperBudgetAllows(1920, 1080, 3840, 2160, 30f))
        assertFalse(media3SsimSuperBudgetAllows(1280, 720, 3840, 2160, 30f))
        assertFalse(media3SsimSuperBudgetAllows(640, 360, 640, 360, 30f))
    }

    @Test fun statsShowWhatIsReallyPlannedAfterGpuFallback() {
        val superScale = Media3Stage.Scale("spline36", superResolution = true)
        val superStats = media3AppliedProcessing(true, "spline36", listOf(superScale))
        assertEquals("Spline36 + SSimSuperRes", superStats.upscaler)
        assertEquals("SSimDownscaler",
            media3StageSummary(Media3Stage.Downscale("ssim_down")).substringAfter("downscale "))
        val downStats = media3AppliedProcessing(true, null,
            listOf(Media3Stage.Downscale("ssim_down")))
        assertEquals("Down: SSimDownscaler", downStats.upscaler)
        assertEquals("off", downStats.videoFilters)
        val downgraded = media3AppliedProcessing(true, null,
            listOf(Media3Stage.Downscale("lanczos")))
        assertEquals("Down: Lanczos · 3", downgraded.upscaler)
    }

    @Test fun overloadingRemovesSsimSuperBeforeDestroyingTheSelectedScaler() {
        val saved = Media3ImageOptions(enabled = true, scaler = "spline36",
            superResolution = true, sharpening = "cas")
        val reduced = media3ImageOptionsAfterOverload(saved)
        assertEquals("spline36", reduced?.scaler)
        assertEquals(false, reduced?.superResolution)
        assertEquals("cas", reduced?.sharpening)
    }

    @Test fun kernelsUseFourSourceMomentsWithoutTemporalTextureCaches() {
        // The generated GLSL is intentionally ES 1.00 and has constant-time loops for GLES2.
        assertTrue(SSIM_RESIZE_SHADER.contains("uVariance"))
        assertTrue(SSIM_RESIZE_SHADER.contains("uSquare"))
        assertTrue(SSIM_RESIZE_SHADER.contains("for (int i = -4; i <= 5; i++)"))
        assertTrue(SSIM_FRAGMENT_COMMON.contains("precision highp float"))
        assertFalse(SSIM_RESIZE_SHADER.contains("textureLod"))
    }
}