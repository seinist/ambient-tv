package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3EffectPlanTest {
    private val all = Media3ImageOptions(
        enabled = true, scaler = "catmull_rom", brightness = 2, contrast = 3, saturation = 4,
        sharpening = "cas", deband = "light", gamma = -5,
    )

    @Test
    fun orderIsDebandScaleSharpenColor() {
        assertEquals(
            listOf(
                Media3Stage.Deband("light"), Media3Stage.Scale("catmull_rom"), Media3Stage.Sharpen("cas"),
                Media3Stage.Brightness(2), Media3Stage.Contrast(3), Media3Stage.Saturation(4), Media3Stage.Gamma(-5),
            ),
            media3EffectPlan(all, upscaling = true),
        )
    }

    @Test
    fun scalersOnlyRunWhileUpscaling() {
        for (scaler in VideoImageCatalog.media3Scalers.keys) {
            val plan = media3EffectPlan(Media3ImageOptions(enabled = true, scaler = scaler), upscaling = false)
            assertTrue(scaler, plan.none { it is Media3Stage.Scale })
        }
        // Bilinear stays on the display path even when upscaling.
        assertTrue(media3EffectPlan(Media3ImageOptions(enabled = true), upscaling = true).isEmpty())
        assertEquals(listOf(Media3Stage.Scale("bicubic")),
            media3EffectPlan(Media3ImageOptions(enabled = true, scaler = "bicubic"), upscaling = true))
    }

    @Test
    fun fsrBringsRcasInsteadOfTheChosenSharpening() {
        val fsr = all.copy(scaler = "fsr")
        val plan = media3EffectPlan(fsr, upscaling = true)
        assertEquals(1, plan.count { it is Media3Stage.Sharpen })
        assertTrue(Media3Stage.Sharpen("rcas") in plan)
        assertTrue(media3FsrSharpens(fsr, upscalerApplies = true))
        // Without upscaling FSR does not run, so the chosen sharpening applies again.
        assertTrue(Media3Stage.Sharpen("cas") in media3EffectPlan(fsr, upscaling = false))
        assertFalse(media3FsrSharpens(fsr, upscalerApplies = false))
    }

    @Test
    fun disabledOrUnknownValuesPlanNothingExtra() {
        assertTrue(media3EffectPlan(all.copy(enabled = false), upscaling = true).isEmpty())
        val junk = Media3ImageOptions(enabled = true, scaler = "x", sharpening = "adaptive", deband = "extreme", gamma = 99)
        assertEquals(listOf(Media3Stage.Gamma(20)), media3EffectPlan(junk, upscaling = true))
    }

    @Test
    fun olderProfilesWithoutTheNewFieldsReadAsOff() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<Media3ImageOptions>(
            """{"enabled":true,"scaler":"lanczos","brightness":1,"contrast":0,"saturation":0}""")
        assertEquals("off", old.sharpening)
        assertEquals("off", old.deband)
        assertEquals(0, old.gamma)
        assertEquals(listOf(Media3Stage.Scale("lanczos"), Media3Stage.Brightness(1)), media3EffectPlan(old, upscaling = true))
    }

    @Test
    fun fitSizeNeverExceedsTheOutputOrShrinks() {
        assertEquals(3840 to 2160, media3FitSize(1920, 1080, 3840, 2160))
        assertEquals(2880 to 2160, media3FitSize(1440, 1080, 3840, 2160))
        assertEquals(1920 to 1080, media3FitSize(1920, 1080, 1280, 720))
        assertEquals(1920 to 1080, media3FitSize(1920, 1080, 1920, 1080))
    }

    @Test
    fun statsNameEveryStageAndTheScalerLabelsMatchTheCatalog() {
        assertEquals("FSR · EASU + RCAS", media3ScalerLabel("fsr"))
        assertEquals(VideoImageCatalog.media3Scalers.toList(), media3ScalerChoices())
        val applied = media3AppliedProcessing(true, "fsr", media3EffectPlan(all.copy(scaler = "fsr"), upscaling = true))
        assertEquals("FSR · EASU + RCAS", applied.upscaler)
        assertEquals("deband light, sharpen RCAS (FSR), brightness 2, contrast 3, saturation 4, gamma -5", applied.videoFilters)
    }

    @Test
    fun gammaExponentBrightensForPositiveValues() {
        assertEquals(1f, media3GammaExponent(0), 0f)
        assertTrue(media3GammaExponent(20) < 1f)
        assertTrue(media3GammaExponent(-20) > 1f)
    }
}
