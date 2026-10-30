package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media3 parity with MPV: no inactive controls, safe geometry and stable old profiles. */
class Media3ProcessingParityPhase1Test {
    @Test fun `new settings remain backwards compatible with previous Media3 profiles`() {
        val old = Json.decodeFromString<Media3ImageOptions>(
            """{"enabled":true,"scaler":"lanczos","brightness":4}""",
        )
        assertEquals("display", old.downscaler)
        assertEquals(0, old.antiring)
        assertEquals(true, old.sigmoid)
        assertEquals("off", old.deblock)
        assertEquals("lanczos", old.normalized().scaler)
        val invalid = old.copy(downscaler = "unrecognized", antiring = 199, deblock = "extreme")
        assertEquals("display", invalid.normalized().downscaler)
        assertEquals(100, invalid.normalized().antiring)
        assertEquals("off", invalid.normalized().deblock)
    }

    @Test fun `Media3 filter pipeline mirrors MPV cleanup sharpening and grain sequence`() {
        val settings = Media3ImageOptions(enabled = true, scaler = "spline36", antiring = 45,
            sigmoid = false, deblock = "strong", denoise = "light", deband = "medium",
            sharpening = "adaptive", filmGrain = "light")
        assertEquals(listOf(
            Media3Stage.Deblock("strong"),
            Media3Stage.Denoise("light"),
            Media3Stage.Deband("medium"),
            Media3Stage.Scale("spline36", antiring = 45, sigmoid = false),
            Media3Stage.Sharpen("adaptive"),
            Media3Stage.Grain("light"),
        ), media3EffectPlan(settings, upscaling = true))
        assertTrue(media3EffectPlan(settings, upscaling = true, debandAllowed = false)
            .none { it is Media3Stage.Deblock || it is Media3Stage.Denoise || it is Media3Stage.Deband })
    }

    @Test fun `downscaler only runs when reducing and is independent of upscale choice`() {
        val settings = Media3ImageOptions(enabled = true, scaler = "fsr",
            downscaler = "mitchell", antiring = 30)
        assertEquals(listOf(Media3Stage.Downscale("mitchell", 30)),
            media3EffectPlan(settings, upscaling = false, downscaling = true))
        assertEquals(listOf(Media3Stage.Scale("fsr"), Media3Stage.Sharpen("rcas")),
            media3EffectPlan(settings, upscaling = true, downscaling = false))
        assertTrue(media3EffectPlan(settings, upscaling = false, downscaling = false).isEmpty())
        assertTrue(media3EffectPlan(settings.copy(downscaler = "display"),
            upscaling = false, downscaling = true).isEmpty())
        assertEquals(listOf(Media3Stage.Downscale("lanczos")),
            media3EffectPlan(settings, upscaling = false, downscaling = true,
                heavyDownscaleAllowed = false))
    }

    @Test fun `geometry and heavy downscale budget prevent excessive GPU work`() {
        assertEquals(1280 to 720, media3DownscaleSize(1920, 1080, 1280, 720))
        assertEquals(1920 to 1080, media3DownscaleSize(1920, 1080, 3840, 2160))
        assertEquals(1280 to 960, media3DownscaleSize(1920, 1440, 1280, 720 + 240))
        assertTrue(media3NeedsDownscaling(1920, 1080, 1280, 720))
        assertFalse(media3NeedsDownscaling(1920, 1080, 3840, 2160))
        assertFalse(media3NeedsDownscaling(1920, 1080, 1280, 720, rotationDegrees = 90))
        assertFalse(media3NeedsDownscaling(1920, 1080, 1280, 720, pixelWidthHeightRatio = 1.3f))
        assertTrue(media3HeavyDownscaleBudgetAllows(3840, 2160, 1920, 1080, 30f))
        assertFalse(media3HeavyDownscaleBudgetAllows(3840, 2160, 1920, 1080, 60f))
        assertFalse(media3HeavyDownscaleBudgetAllows(3840, 2160, 1280, 720, 24f))
    }

    @Test fun `visibility matches MPV scaling boundaries without inert shader controls`() {
        val options = Media3ImageOptions(enabled = true, scaler = "spline36",
            downscaler = "mitchell")
        assertTrue(media3AntiRingRowVisible(options, upscalerVisible = true, downscalerVisible = false))
        assertTrue(media3AntiRingRowVisible(options, upscalerVisible = false, downscalerVisible = true))
        assertFalse(media3AntiRingRowVisible(options, upscalerVisible = false, downscalerVisible = false))
        assertFalse(media3AntiRingRowVisible(options.copy(scaler = "nis", downscaler = "display"),
            upscalerVisible = true, downscalerVisible = false))
        assertTrue(media3SigmoidRowVisible(options, upscalerVisible = true))
        assertFalse(media3SigmoidRowVisible(options, upscalerVisible = false))
        assertFalse(media3SigmoidRowVisible(options.copy(scaler = "fsr"), upscalerVisible = true))

        fun visibility(scale: VideoScaleRelation) = playerPanelVideoVisibility(
            "media3", gpuFilters = false, shadersApply = false,
            media3ScalerApplies = true, media3SdrAllowed = true, scale = scale)
        assertTrue(visibility(VideoScaleRelation.UPSCALE).upscaler)
        assertFalse(visibility(VideoScaleRelation.UPSCALE).downscaler)
        assertTrue(visibility(VideoScaleRelation.DOWNSCALE).downscaler)
        assertFalse(visibility(VideoScaleRelation.DOWNSCALE).upscaler)
        assertFalse(visibility(VideoScaleRelation.NATIVE).upscaler)
        assertFalse(visibility(VideoScaleRelation.NATIVE).downscaler)
        assertTrue(visibility(VideoScaleRelation.UNKNOWN).upscaler)
        assertTrue(visibility(VideoScaleRelation.UNKNOWN).downscaler)
        assertFalse(visibility(VideoScaleRelation.DOWNSCALE).sparsam(true, media3 = true).downscaler)
    }

    @Test fun `deblock presets and anti ringing shader are bounded`() {
        assertEquals(0f, media3DeblockStrength("off"), 0f)
        assertEquals(0.25f, media3DeblockStrength("light"), 0f)
        assertEquals(0.5f, media3DeblockStrength("medium"), 0f)
        assertEquals(0.75f, media3DeblockStrength("strong"), 0f)
        assertTrue("spline36" in MEDIA3_ANTIRING_SCALERS)
        assertFalse("nearest" in MEDIA3_ANTIRING_SCALERS)
        assertTrue(VideoImageCatalog.media3Downscalers.keys.containsAll(
            listOf("display", "lanczos", "mitchell", "spline36", "spline64")))
        val upscaleShader = media3KernelShader(MEDIA3_KERNELS.getValue("spline36"))
        val downscaleShader = media3KernelShader(MEDIA3_KERNELS.getValue("spline36"), downscaling = true)
        assertTrue(upscaleShader.contains("uniform float uAntiRing"))
        assertTrue(downscaleShader.contains("uniform float uFilterScale"))
        assertTrue(downscaleShader.contains("for (int i = -5; i <= 6; i++)"))
    }
}
