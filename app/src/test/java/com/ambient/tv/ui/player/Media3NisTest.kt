package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import com.ambient.tv.ui.player.backend.MPV_IMAGE_SHADERS
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NVScaler for Media3 follows the bundled MPV shader: same constants, same filter banks. */
class Media3NisTest {
    private val mpv = File("src/main/res/raw").listFiles()!!
        .single { it.name == MPV_IMAGE_SHADERS.getValue("nis").fileName }.readText()

    @Test fun constantsMatchTheMpvShaderAtItsSharpness() {
        assertTrue(mpv.contains("#define SHARPNESS 0.25"))
        assertTrue(mpv.contains("#define NIS_HDR_MODE 0"))
        val c = media3NisConstants()
        assertEquals(2.201171875f, c.detectRatio, 1e-6f)
        assertEquals(0.0625f, c.detectThres, 1e-6f)
        assertEquals(0.125f, c.ratioNorm, 1e-6f)
        assertEquals(0.1f, c.sharpStrengthMin, 1e-6f)
        assertEquals(0.8125f - 0.1f, c.sharpStrengthScale, 1e-6f)
        assertEquals(0.1f, c.sharpLimitMin, 1e-6f)
        assertEquals(0.35f - 0.1f, c.sharpLimitScale, 1e-6f)
        assertEquals(1f / 0.45f, c.sharpScaleY, 1e-5f)
    }

    @Test fun filterBanksComeFromTheMpvFileSideBySide() {
        val banks = media3NisCoefficients(mpv)
        assertNotNull(banks)
        val v = banks!!.values
        assertEquals(4 * 64 * 4, v.size)
        // Phase 0: the scaler passes the center tap through, USM is -0.6 / 1.2 / -0.6.
        assertEquals(listOf(0f, 0f, 1f, 0f), v.slice(0 until 4))
        assertEquals(1.2002f, v[(0 * 4 + 2) * 4 + 2], 1e-4f)
        assertEquals(-0.6001f, v[(0 * 4 + 2) * 4 + 1], 1e-4f)
        // Every scaler phase sums to 1 over its six taps.
        for (phase in 0 until 64) {
            val sum = (0 until 4).sumOf { v[(phase * 4) * 4 + it].toDouble() } + v[(phase * 4 + 1) * 4].toDouble() + v[(phase * 4 + 1) * 4 + 1]
            assertEquals("phase $phase", 1.0, sum, 2e-3)
        }
        assertNull(media3NisCoefficients(""))
    }

    @Test fun fragmentShaderIsGlslEs100WithTheConstantsBakedIn() {
        val glsl = media3NisFragmentShader(media3NisConstants())
        assertTrue(glsl.startsWith("#version 100"))
        for (token in listOf("shared ", "texelFetch", "imageStore", "barrier", "HOOKED", "[6][6]")) assertFalse(token, token in glsl)
        assertTrue(glsl.contains("const float kSharpStrengthMin = 0.100000001"))
        assertFalse(glsl.contains("$"))
    }

    @Test fun nisIsAMedia3ScalerThatBringsItsOwnSharpening() {
        assertEquals(VideoImageCatalog.mpvShaders["nis"], VideoImageCatalog.media3Scalers["nis"])
        val nis = Media3ImageOptions(enabled = true, scaler = "nis", sharpening = "cas", gamma = 3)
        assertEquals(listOf(Media3Stage.Scale("nis"), Media3Stage.Gamma(3)), media3EffectPlan(nis, upscaling = true))
        assertTrue(media3FsrSharpens(nis, upscalerApplies = true))
        // Without upscaling NIS does not run and the chosen sharpening applies again.
        assertTrue(Media3Stage.Sharpen("cas") in media3EffectPlan(nis, upscaling = false))
        val plan = media3EffectPlan(nis, upscaling = true)
        assertEquals(plan.map { Media3FusedStage(it) }, media3FusedStages(plan))
    }
}
