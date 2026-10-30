package com.ambient.tv.ui.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoFrameProcessingException
import com.ambient.tv.data.local.Media3ImageOptions
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit fixes: heavy-scaler budget, effect-failure detection, MPV's sigmoid constants. */
class Media3ScalerSafetyTest {
    @Test fun heavyScalersFallBackToCatmullRomAbove4k30() {
        for (key in listOf("nis", "ravu_lite_r3", "ravu_zoom_r3", "ewa_lanczos", "ewa_robidoux", "spline36", "spline64", "ginseng")) {
            assertTrue(key, key in MEDIA3_HEAVY_SCALERS)
        }
        for (key in listOf("bicubic", "catmull_rom", "fsr", "lanczos", "mitchell", "robidoux", "spline16", "nearest", "oversample")) {
            assertFalse(key, key in MEDIA3_HEAVY_SCALERS)
        }
        assertTrue(media3HeavyScalerBudgetAllows(3840, 2160, 30f))
        assertTrue(media3HeavyScalerBudgetAllows(3840, 2160, 0f))
        assertFalse(media3HeavyScalerBudgetAllows(3840, 2160, 60f))
        assertTrue(media3HeavyScalerBudgetAllows(1920, 1080, 60f))

        val nis = Media3ImageOptions(enabled = true, scaler = "nis", sharpening = "cas")
        assertEquals(listOf(Media3Stage.Scale("catmull_rom"), Media3Stage.Sharpen("cas")),
            media3EffectPlan(nis, upscaling = true, heavyScaleAllowed = false))
        assertEquals(listOf(Media3Stage.Scale("nis")), media3EffectPlan(nis, upscaling = true, heavyScaleAllowed = true))
        // Light scalers are not affected by the heavy budget.
        val fsr = Media3ImageOptions(enabled = true, scaler = "fsr")
        assertEquals(media3EffectPlan(fsr, upscaling = true), media3EffectPlan(fsr, upscaling = true, heavyScaleAllowed = false))
    }

    @Test fun onlyEffectChainErrorsCountAsEffectFailures() {
        assertTrue(media3EffectFailure(PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED, null))
        assertTrue(media3EffectFailure(PlaybackException.ERROR_CODE_UNSPECIFIED,
            RuntimeException("wrapped", VideoFrameProcessingException("shader"))))
        assertFalse(media3EffectFailure(PlaybackException.ERROR_CODE_DECODING_FAILED, RuntimeException("decoder")))
        assertFalse(media3EffectFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
    }

    @Test fun sigmoidConstantsAreMpvsDefaults() {
        val center = 0.75
        val slope = 6.5
        val offset = 1.0 / (1 + exp(slope * center))
        val scale = 1.0 / (1 + exp(slope * (center - 1))) - offset
        val shader = media3KernelShader(MEDIA3_KERNELS.getValue("spline36"))
        fun baked(name: String) = Regex("""const float $name = ([0-9.]+);""").find(shader)!!.groupValues[1].toDouble()
        assertEquals(center, baked("kSigCenter"), 1e-9)
        assertEquals(slope, baked("kSigSlope"), 1e-9)
        assertEquals(offset, baked("kSigOffset"), 1e-8)
        assertEquals(scale, baked("kSigScale"), 1e-7)
        // Media3 input is already linear RGB. Unlike MPV's gamma-coded source, there
        // must not be a second pow(2.4) before/after the sigmoid transformation.
        assertFalse(shader.contains("pow(clamp(c, 0.0, 1.0), vec3(2.4))"))
        assertFalse(shader.contains("vec3(1.0 / 2.4)"))
        for (step in 0..10) {
            val c = step / 10.0
            val sigmoid = center - kotlin.math.ln(1.0 / (c * scale + offset) - 1.0) / slope
            val back = (1.0 / (1.0 + exp(slope * (center - sigmoid))) - offset) / scale
            assertEquals(c, back, 1e-9)
        }
        assertTrue(shader.contains("uSigmoid.x > 0.5 ? toSigmoid(c) : c"))
    }
}
