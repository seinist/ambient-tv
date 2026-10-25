package com.ambient.tv.ui.player

import com.ambient.tv.R
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import com.ambient.tv.ui.performance.PerformanceMode
import com.ambient.tv.ui.performance.PlaybackPerformanceRequests
import com.ambient.tv.ui.performance.playbackModeLiftingSparsam
import com.ambient.tv.ui.player.backend.effectiveMpvImageOptions
import com.ambient.tv.ui.player.backend.mpvEffectiveScalers
import com.ambient.tv.ui.player.backend.mpvImageOptionsLogLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SparsamConsistencyTest {
    private val tuned = MpvImageOptions(
        scaler = "ewa_lanczos", chromaScaler = "spline36", downscaler = "spline36",
        deband = "strong", sharpening = "light", antiring = 50, sigmoid = true, superResolution = true,
    )

    @Test fun `no overrides without Sparsam`() {
        assertEquals(emptyList<Any>(), mpvSparsamOverrides(tuned, reduced = false))
    }

    @Test fun `Sparsam lists only untouched defaults, chosen values are applied`() {
        // tuned keeps the default sigmoid, so only that field runs cheap.
        assertEquals(listOf(MpvSparsamField.SIGMOID to "on"), mpvSparsamOverrides(tuned, reduced = true))
        val fields = mpvSparsamOverrides(MpvImageOptions(), reduced = true).toMap()
        assertEquals("lanczos", fields[MpvSparsamField.UPSCALER])
        assertEquals("lanczos", fields[MpvSparsamField.CHROMA])
        assertEquals("mitchell", fields[MpvSparsamField.DOWNSCALER])
        assertEquals("medium", fields[MpvSparsamField.DEBAND])
        assertEquals("on", fields[MpvSparsamField.SIGMOID])
        assertEquals(setOf(MpvSparsamField.UPSCALER, MpvSparsamField.CHROMA, MpvSparsamField.DOWNSCALER,
            MpvSparsamField.DEBAND, MpvSparsamField.SIGMOID), fields.keys)
        // A field already at the Sparsam value is not listed.
        val plain = MpvImageOptions(scaler = "bilinear", chromaScaler = "bilinear", downscaler = "bilinear", deband = "off", sigmoid = false)
        assertEquals(emptyList<Any>(), mpvSparsamOverrides(plain, reduced = true))
    }

    @Test fun `stats show effective value and saved one only when Sparsam changed it`() {
        assertEquals("Bilinear (Sparsam; saved: Lanczos)", formatSparsamStat("Bilinear", "Lanczos", true))
        assertEquals("Bilinear", formatSparsamStat("Bilinear", "bilinear", true))
        assertEquals("Lanczos", formatSparsamStat("Lanczos", "Spline36", false))
        assertEquals("Bilinear", formatSparsamStat("Bilinear", null, true))
        assertNull(formatSparsamStat(null, "Lanczos", true))
    }

    @Test fun `upscaler stat names forced bilinear and the saved scaler`() {
        assertEquals("Bilinear (Sparsam; saved: EWA Lanczos)",
            formatMpvUpscalerForStats("ewa_lanczos", "bilinear", null, sparsam = true))
        assertEquals("Bilinear (Sparsam; saved: EWA Lanczos)",
            formatMpvUpscalerForStats("ewa_lanczos", null, "fsr", sparsam = true))
        assertEquals("off", formatMpvUpscalerForStats("ewa_lanczos", "bilinear", null, upscaling = false, sparsam = true))
        assertEquals("EWA Lanczos", formatMpvUpscalerForStats("ewa_lanczos", "ewa_lanczos", null))
    }

    @Test fun `chroma, downscaler and deband stats`() {
        assertEquals("Bilinear (Sparsam; saved: Spline36)", formatMpvScalerStat("bilinear", "spline36", true))
        assertEquals("Spline36", formatMpvScalerStat("spline36", "spline36", false))
        assertNull(formatMpvScalerStat(null, "spline36", true))
        assertNull(formatMpvDebandStat("off", true, "on"))
        assertEquals("off (Sparsam; saved: strong)", formatMpvDebandStat("strong", true, "on"))
        assertEquals("an", formatMpvDebandStat("strong", false, "an"))
    }

    @Test fun `effective scalers match what mpv receives`() {
        assertEquals(Triple("bilinear", "bilinear", "bilinear"), mpvEffectiveScalers(effectiveMpvImageOptions(MpvImageOptions(), true)))
        assertEquals(Triple("ewa_lanczos", "spline36", "spline36"), mpvEffectiveScalers(effectiveMpvImageOptions(tuned, true)))
        assertEquals(Triple("ewa_lanczos", "spline36", "spline36"), mpvEffectiveScalers(effectiveMpvImageOptions(tuned, false)))
        val shaderScaler = VideoImageCatalog.mpvScalers.keys.first { it !in VideoImageCatalog.mpvBuiltins }
        val chromaShader = VideoImageCatalog.chromaShaders.keys.first()
        assertEquals(Triple("lanczos", "lanczos", "mitchell"),
            mpvEffectiveScalers(MpvImageOptions(scaler = shaderScaler, chromaScaler = chromaShader, downscaler = "ssim_down")))
    }

    @Test fun `diagnostic log line carries profile, effective scalers, shaders and svp`() {
        val line = mpvImageOptionsLogLine("auto", lowEnd = true, reduced = true, stored = tuned,
            effective = effectiveMpvImageOptions(MpvImageOptions(), true), shaderKeys = emptyList(), svpAllowed = false,
            fittedScale = 2.0, sdr = true, hdr = false, vo = "gpu-next")
        assertTrue(line.startsWith("MPV image profile=auto lowEnd=true reduced=true vo=gpu-next "))
        assertTrue("scale=bilinear cscale=bilinear dscale=bilinear storedScaler=ewa_lanczos" in line)
        assertTrue("deband=off sharpen=off shaders=none svpAllowed=false svp=off fittedScale=2.000 sdr=true hdr=false" in line)
        val full = mpvImageOptionsLogLine("maximum", false, false, tuned, effectiveMpvImageOptions(tuned, false),
            listOf("fsr", "cas"), true, Double.NaN, false, true)
        assertTrue("vo=? " in full && "scale=ewa_lanczos" in full && "shaders=fsr,cas" in full && "fittedScale=unknown" in full)
    }

    @Test fun `upscaler hint only where no upscaling happens`() {
        assertEquals(R.string.image_upscaler_not_needed_native, upscalerNotNeededHint(VideoScaleRelation.NATIVE))
        assertEquals(R.string.image_upscaler_not_needed_downscale, upscalerNotNeededHint(VideoScaleRelation.DOWNSCALE))
        assertNull(upscalerNotNeededHint(VideoScaleRelation.UPSCALE))
        assertNull(upscalerNotNeededHint(VideoScaleRelation.UNKNOWN))
    }

    @Test fun `one-tap switch targets the lightest mode that lifts the gate`() {
        assertEquals(PerformanceMode.BALANCED, playbackModeLiftingSparsam(forSvp = false))
        assertEquals(PerformanceMode.MAXIMUM, playbackModeLiftingSparsam(forSvp = true))
        assertFalse(PlaybackPerformanceRequests.request("turbo"))
        assertTrue(PlaybackPerformanceRequests.request(PerformanceMode.BALANCED.key))
    }

    @Test fun `GPU output heads-up only for explicit mpv GPU outputs under Sparsam`() {
        assertTrue(sparsamGpuOutputHeadsUp(true, "vulkan", true))
        assertTrue(sparsamGpuOutputHeadsUp(true, "gpu", true))
        assertFalse(sparsamGpuOutputHeadsUp(true, "auto", true))
        assertFalse(sparsamGpuOutputHeadsUp(true, "vulkan", false))
        assertFalse(sparsamGpuOutputHeadsUp(false, "vulkan", true))
    }

    @Test fun `reference white choices map presets, auto and custom`() {
        assertEquals("0", sdrReferenceWhiteChoiceKey(0))
        assertEquals("203", sdrReferenceWhiteChoiceKey(203))
        assertEquals("400", sdrReferenceWhiteChoiceKey(400))
        assertEquals(SDR_REFERENCE_WHITE_CUSTOM, sdrReferenceWhiteChoiceKey(250))
        assertEquals(300, sdrReferenceWhiteForChoice("300", 250))
        assertEquals(0, sdrReferenceWhiteForChoice("0", 250))
        assertEquals(270, sdrReferenceWhiteForChoice(SDR_REFERENCE_WHITE_CUSTOM, 270))
        assertEquals(SDR_REFERENCE_WHITE_CUSTOM_START, sdrReferenceWhiteForChoice(SDR_REFERENCE_WHITE_CUSTOM, 203))
        assertEquals(SDR_REFERENCE_WHITE_CUSTOM, sdrReferenceWhiteChoiceKey(SDR_REFERENCE_WHITE_CUSTOM_START))
    }
}
