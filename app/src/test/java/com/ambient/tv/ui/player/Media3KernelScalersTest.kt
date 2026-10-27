package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MPV's built-in kernels for Media3, checked against the properties MPV's definitions have. */
class Media3KernelScalersTest {
    @Test fun everyMpvBuiltinIsAMedia3ScalerWithTheSameName() {
        for ((key, label) in VideoImageCatalog.mpvBuiltins) {
            assertEquals(key, label, VideoImageCatalog.media3Scalers[key]?.substringBefore(" · "))
            assertEquals(key, Media3ImageOptions(scaler = key).normalized().scaler)
        }
        assertTrue(MEDIA3_KERNEL_SCALERS.containsAll(VideoImageCatalog.mpvBuiltins.keys -
            setOf("bilinear", "bicubic", "catmull_rom", "lanczos")))
    }

    @Test fun kernelsInterpolate() {
        // Every interpolating kernel is 1 at 0 and 0 at the other integers.
        for (key in listOf("mitchell", "hermite", "spline16", "spline36", "spline64", "sinc", "robidoux")) {
            val k = MEDIA3_KERNELS.getValue(key)
            assertEquals(key, if (key in setOf("mitchell", "robidoux")) k.value(0.0) else 1.0, k.value(0.0), 1e-9)
            if (key !in setOf("mitchell", "robidoux")) {
                for (n in 1 until k.taps) assertEquals("$key @ $n", 0.0, k.value(n.toDouble()), 1e-9)
            }
        }
        // Splines are continuous at their piece borders.
        for (key in listOf("spline16", "spline36", "spline64")) {
            val k = MEDIA3_KERNELS.getValue(key)
            for (n in 1 until k.taps) assertEquals("$key @ $n", k.value(n - 1e-9), k.value(n + 1e-9), 1e-6)
        }
    }

    @Test fun separableWeightsSumToOneAtAnyPhase() {
        for ((key, k) in MEDIA3_KERNELS) {
            if (k.polar || key in setOf("nearest", "sinc", "ginseng", "gaussian")) continue
            for (step in 0..10) {
                val f = step / 10.0
                val sum = (1 - k.taps..k.taps).sumOf { k.value(it - f) }
                assertEquals("$key @ $f", 1.0, sum, 1e-6)
            }
        }
    }

    @Test fun mpvConstantsAndWindows() {
        // Robidoux: Keys cubic with MPV's B and C.
        assertEquals(0.3782157550939987, 12 / (19 + 9 * kotlin.math.sqrt(2.0)), 1e-12)
        // jinc's first zero is the jinc window radius; EWA Lanczos' radius is jinc's third zero.
        assertEquals(0.0, media3Jinc(JINC_WINDOW_RADIUS), 1e-7)
        assertEquals(0.0, media3Jinc(JINC_R3), 1e-7)
        assertEquals(0.0, media3Jinc(JINC_R4), 1e-7)
        val ewa = MEDIA3_KERNELS.getValue("ewa_lanczos")
        assertTrue(ewa.polar)
        assertEquals(1.0, ewa.value(0.0), 1e-9)
        assertEquals(0.0, ewa.value(JINC_R3), 0.0)
        // Sharper EWA variants shrink the blur, the soft one widens it.
        val sharp = MEDIA3_KERNELS.getValue("ewa_lanczossharp")
        val soft = MEDIA3_KERNELS.getValue("ewa_lanczossoft")
        assertTrue(sharp.value(1.0) < ewa.value(1.0))
        assertTrue(soft.value(1.0) > ewa.value(1.0))
        // Blur scales the support (MPV: radius = blur * f.radius), not just the base.
        assertEquals(JINC_R3 * 0.9812505837223707, sharp.support, 1e-12)
        assertEquals(0.0, sharp.value(sharp.support), 0.0)
        assertTrue(soft.value(JINC_R3 + 0.01) != 0.0)
        // Ginseng is sinc windowed by jinc.
        val ginseng = MEDIA3_KERNELS.getValue("ginseng")
        assertEquals(media3Sinc(1.5) * media3Jinc(1.5 / 3.0 * JINC_WINDOW_RADIUS), ginseng.value(1.5), 1e-12)
        // Nearest takes exactly one tap.
        val nearest = MEDIA3_KERNELS.getValue("nearest")
        assertEquals(1.0, nearest.value(0.4), 0.0)
        assertEquals(0.0, nearest.value(0.6), 0.0)
    }

    @Test fun lutSamplesTheKernelAndPacksLosslessEnough() {
        for ((key, k) in MEDIA3_KERNELS) {
            val lut = media3KernelLut(k)
            assertEquals(MEDIA3_KERNEL_LUT_SIZE, lut.size)
            assertEquals(key, k.value(0.0).toFloat(), lut[0], 1e-6f)
            val planes = media3RavuLutPlanes(lut)
            val error = lut.indices.maxOf { abs(media3RavuLutDecode(planes, it) - lut[it]) }
            assertTrue("$key $error", error <= planes.range / 65535f + 1e-6f)
        }
    }

    @Test fun shadersAreGlslEs100WithTheirLoopBaked() {
        val ewa = media3KernelShader(MEDIA3_KERNELS.getValue("ewa_lanczos4sharpest"))
        assertTrue(ewa.startsWith("#version 100"))
        // Blur 0.8845 shrinks the support to 3.751 like MPV's mp_init_filter: taps -3..4.
        assertTrue(ewa.contains("for (int y = -3; y <= 4; y++)"))
        assertTrue(ewa.contains("3.751271391"))
        val spline = media3KernelShader(MEDIA3_KERNELS.getValue("spline36"))
        assertTrue(spline.contains("for (int i = -2; i <= 3; i++)"))
        for (glsl in listOf(ewa, spline)) {
            assertFalse(glsl.contains("$"))
            assertTrue(glsl.contains("(t * 1023.0 + 0.5) / 1024.0"))
        }
    }

    @Test fun kernelScalersAreTheirOwnPassAndKeepTheChosenSharpening() {
        val o = Media3ImageOptions(enabled = true, scaler = "ewa_robidoux", sharpening = "cas", gamma = 2)
        val plan = media3EffectPlan(o, upscaling = true)
        assertEquals(listOf(Media3Stage.Scale("ewa_robidoux"), Media3Stage.Sharpen("cas"), Media3Stage.Gamma(2)), plan)
        assertFalse(media3FsrSharpens(o, upscalerApplies = true))
        // The gamma fuses into the sharpening pass, not into the scaler.
        assertEquals(Media3FusedStage(Media3Stage.Sharpen("cas"), 2), media3FusedStages(plan).last())
    }
}
