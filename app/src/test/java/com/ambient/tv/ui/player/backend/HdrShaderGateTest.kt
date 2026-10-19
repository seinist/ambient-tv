package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrShaderGateTest {
    private fun raw(name: String) = File("src/main/res/raw/$name").readText()

    @Test fun onlyTheReviewedShadersAreHdrSafe() {
        assertEquals(
            setOf("ravu_lite_r2", "ravu_lite_r3", "ravu_lite_r4", "fsr", "adaptive", "cas", "cas_light", "cas_medium",
                "filmgrain_light_output", "filmgrain_medium_output"),
            MPV_IMAGE_SHADERS.filterValues { it.hdrSafe }.keys,
        )
        assertEquals(setOf("ravu_lite_r2", "ravu_lite_r3", "ravu_lite_r4", "fsr"), MPV_HDR_UPSCALERS)
    }

    @Test fun hdrSourceLoadsOnlyHdrSafeHooksPerShader() {
        val all = MpvImageOptions(scaler = "ravu_lite_r3", chromaScaler = "krig", downscaler = "ssim_down",
            sharpening = "cas", filmGrain = "light", superResolution = true)
        assertEquals(listOf("ravu_lite_r3", "cas", "filmgrain_light_output"),
            mpvImageShaderKeys(all, sdr = false, fittedScale = 2.0, hdr = true))
        // The same options on SDR keep every hook as before.
        assertEquals(listOf("krig", "ravu_lite_r3", "ssim_super", "ssim_down", "cas", "filmgrain_light"),
            mpvImageShaderKeys(all, sdr = true, fittedScale = 2.0))
        // Neural upscalers, Anime4K, NNEDI and SDR-only chroma hooks are not loaded on HDR.
        for (scaler in listOf("artcnn16", "fsrcnnx8", "nnedi3_nns32", "anime4k", "ravu_zoom_r3", "cunny_fast_ds", "nis")) {
            assertEquals(scaler, emptyList<String>(), mpvImageShaderKeys(MpvImageOptions(scaler = scaler), sdr = false, fittedScale = 2.0, hdr = true))
        }
        assertEquals(listOf("fsr"), mpvImageShaderKeys(MpvImageOptions(scaler = "fsr", sharpening = "adaptive"), sdr = false, fittedScale = 2.0, hdr = true))
        assertEquals(listOf("adaptive"), mpvImageShaderKeys(MpvImageOptions(sharpening = "adaptive"), sdr = false, hdr = true))
        // Unknown transfer (not yet SDR or HDR) still loads nothing.
        assertEquals(emptyList<String>(), mpvImageShaderKeys(all, sdr = false, fittedScale = 2.0))
    }

    @Test fun hdrUpscalerStillSkipsNativeSizeLike4kOn4k() {
        assertEquals(emptyList<String>(), mpvImageShaderKeys(MpvImageOptions(scaler = "ravu_lite_r2"), sdr = false, fittedScale = 1.0, hdr = true))
        assertEquals(emptyList<String>(), mpvImageShaderKeys(MpvImageOptions(scaler = "fsr"), sdr = false, fittedScale = 1.0, hdr = true))
    }

    @Test fun casHasPqAndHlgProfiles() {
        assertEquals(MpvCasTransferProfile("pq", sourceTrc = 2, targetTrc = 2), mpvCasTransferProfile("pq"))
        assertEquals(MpvCasTransferProfile("pq", sourceTrc = 2, targetTrc = 2), mpvCasTransferProfile("smpte2084"))
        assertEquals(MpvCasTransferProfile("hlg", sourceTrc = 5, targetTrc = 5), mpvCasTransferProfile("hlg"))
        assertEquals(MpvCasTransferProfile("hlg", sourceTrc = 5, targetTrc = 5), mpvCasTransferProfile("arib-std-b67"))
        assertEquals(4, mpvCasTransferProfile("bt.1886")!!.sourceTrc)
        assertNull(mpvCasTransferProfile(null))
        val cas = MPV_IMAGE_SHADERS.getValue("cas")
        val source = raw(cas.fileName)
        val pq = mpvShaderSourceFor(cas, source, mpvCasTransferProfile("pq"), artCnnOpenGl = false)
        assertTrue(pq.contains("#define SOURCE_TRC 2"))
        assertTrue(pq.contains("#define TARGET_TRC 2"))
        assertFalse(pq.contains("#define SOURCE_TRC 4"))
        assertTrue(pq.contains("#define SHARPENING 0.0"))
        val hlg = mpvShaderSourceFor(cas, source, mpvCasTransferProfile("hlg"), artCnnOpenGl = false)
        assertTrue(hlg.contains("#define SOURCE_TRC 5") && hlg.contains("#define TARGET_TRC 5"))
    }

    @Test fun pqSourcesNeverGetLumaGrain() {
        for (level in listOf("light", "medium")) {
            val keys = mpvImageShaderKeys(MpvImageOptions(filmGrain = level), sdr = false, hdr = true)
            assertEquals(listOf("filmgrain_${level}_output"), keys)
            val asset = MPV_IMAGE_SHADERS.getValue(keys.single())
            val text = mpvShaderSourceFor(asset, raw(asset.fileName), null, artCnnOpenGl = false)
            assertTrue(text.contains("//!HOOK OUTPUT"))
            assertFalse(text.contains("//!HOOK LUMA"))
            assertTrue(text.contains("color.rgb += vec3(INTENSITY * 2.0 * g);"))
            assertFalse(text.contains("color.x +="))
            // OUTPUT reads rendered RGB: no decoder copy-back and no SHIELD zero-copy exclusion.
            assertFalse(asset.requiresCopyBack)
        }
        // SDR keeps the unchanged LUMA hook.
        val sdr = MPV_IMAGE_SHADERS.getValue("filmgrain_light")
        assertEquals(raw(sdr.fileName), mpvShaderSourceFor(sdr, raw(sdr.fileName), null, artCnnOpenGl = false))
    }

    @Test fun builtinSharpenBecomesCasOnGpuNext() {
        assertEquals(listOf("cas_light"), mpvImageShaderKeys(MpvImageOptions(sharpening = "light"), sdr = true, gpuNext = true))
        assertEquals(listOf("cas_medium"), mpvImageShaderKeys(MpvImageOptions(sharpening = "medium"), sdr = true, gpuNext = true))
        assertEquals(listOf("cas_medium"), mpvImageShaderKeys(MpvImageOptions(sharpening = "medium"), sdr = false, hdr = true, gpuNext = true))
        assertEquals(emptyList<String>(), mpvImageShaderKeys(MpvImageOptions(sharpening = "light"), sdr = true))
        assertEquals(listOf("fsr"), mpvImageShaderKeys(MpvImageOptions(scaler = "fsr", sharpening = "light"), sdr = true, gpuNext = true))
        assertEquals(listOf("cas"), mpvImageShaderKeys(MpvImageOptions(sharpening = "cas"), sdr = true, gpuNext = true))

        assertEquals("0.2", mpvBuiltinSharpen("light", "lanczos", processing = true, gpuNext = false))
        assertEquals("0.5", mpvBuiltinSharpen("medium", "lanczos", processing = true, gpuNext = false))
        assertEquals("0", mpvBuiltinSharpen("light", "lanczos", processing = true, gpuNext = true))
        assertEquals("0", mpvBuiltinSharpen("light", "fsr", processing = true, gpuNext = false))
        assertEquals("0", mpvBuiltinSharpen("light", "lanczos", processing = false, gpuNext = false))
        assertEquals("0", mpvBuiltinSharpen("cas", "lanczos", processing = true, gpuNext = false))

        val light = MPV_IMAGE_SHADERS.getValue("cas_light")
        val medium = MPV_IMAGE_SHADERS.getValue("cas_medium")
        val lightPq = mpvShaderSourceFor(light, raw(light.fileName), mpvCasTransferProfile("pq"), artCnnOpenGl = false)
        assertTrue(lightPq.contains("#define SHARPENING $MPV_CAS_LIGHT_SHARPENING") && lightPq.contains("#define SOURCE_TRC 2"))
        val mediumSdr = mpvShaderSourceFor(medium, raw(medium.fileName), mpvCasTransferProfile("bt.1886"), artCnnOpenGl = false)
        assertTrue(mediumSdr.contains("#define SHARPENING $MPV_CAS_MEDIUM_SHARPENING") && mediumSdr.contains("#define SOURCE_TRC 4"))
        assertTrue(MPV_CAS_LIGHT_SHARPENING.toDouble() < MPV_CAS_MEDIUM_SHARPENING.toDouble())
        assertTrue(mpvTransferAwareShaderLoaded(listOf("cas_light")))
        assertFalse(mpvTransferAwareShaderLoaded(listOf("adaptive", "filmgrain_light_output")))
    }

    @Test fun overloadDropsMappedSharpenToOff() {
        assertEquals("off", mpvSharpeningAfterOverload("light", listOf("cas_light")))
        assertEquals("off", mpvSharpeningAfterOverload("medium", listOf("cas_medium")))
        assertNull(mpvSharpeningAfterOverload("light", emptyList()))
    }

    @Test fun shaderCacheFilesStayDistinct() {
        val cacheNames = MPV_IMAGE_SHADERS.values.map { it.cacheFileName ?: it.fileName }
        assertEquals(cacheNames.size, cacheNames.toSet().size)
        assertTrue(MPV_IMAGE_SHADERS.keys.containsAll(VideoImageCatalog.mpvShaders.keys))
    }
}
