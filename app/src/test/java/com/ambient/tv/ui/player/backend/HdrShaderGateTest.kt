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
        val pqProfile = MpvCasTransferProfile("pq1p", sourceTrc = 2, targetTrc = 2, singlePassHdr = true)
        val hlgProfile = MpvCasTransferProfile("hlg1p", sourceTrc = 5, targetTrc = 5, singlePassHdr = true)
        assertEquals(pqProfile, mpvCasTransferProfile("pq"))
        assertEquals(pqProfile, mpvCasTransferProfile("smpte2084"))
        assertEquals(pqProfile, mpvCasTransferProfile("pq", "limited"))
        assertEquals(hlgProfile, mpvCasTransferProfile("hlg"))
        assertEquals(hlgProfile, mpvCasTransferProfile("arib-std-b67"))
        assertEquals(MpvCasTransferProfile("pq1p_full", 2, 2, singlePassHdr = true, fullRange = true), mpvCasTransferProfile("pq", "full"))
        assertEquals(4, mpvCasTransferProfile("bt.1886")!!.sourceTrc)
        // SDR profiles ignore the range: the two-pass upstream shader stays as before.
        assertEquals(mpvCasTransferProfile("bt.1886"), mpvCasTransferProfile("bt.1886", "full"))
        assertFalse(mpvCasTransferProfile("bt.1886")!!.singlePassHdr)
        assertNull(mpvCasTransferProfile(null))
        val cas = MPV_IMAGE_SHADERS.getValue("cas")
        val pqFile = mpvCasSourceFile(cas, mpvCasTransferProfile("pq"))
        assertEquals(MPV_CAS_HDR_FILE, pqFile)
        val pq = mpvShaderSourceFor(cas, raw(pqFile), mpvCasTransferProfile("pq"), artCnnOpenGl = false)
        assertTrue(pq.contains("#define SOURCE_TRC 2"))
        assertTrue(pq.contains("#define TARGET_TRC 2"))
        assertFalse(pq.contains("#define SOURCE_TRC 4"))
        assertTrue(pq.contains("#define LIMITED_RANGE 1"))
        assertTrue(pq.contains("#define CAS_BLEND 1.0"))
        assertTrue(mpvCasSourceValid(pq, mpvCasTransferProfile("pq")!!, null))
        assertFalse(mpvCasSourceValid(pq, mpvCasTransferProfile("pq", "full")!!, null))
        val hlg = mpvShaderSourceFor(cas, raw(mpvCasSourceFile(cas, mpvCasTransferProfile("hlg"))), mpvCasTransferProfile("hlg"), artCnnOpenGl = false)
        assertTrue(hlg.contains("#define SOURCE_TRC 5") && hlg.contains("#define TARGET_TRC 5"))
        val full = mpvShaderSourceFor(cas, raw(MPV_CAS_HDR_FILE), mpvCasTransferProfile("pq", "full"), artCnnOpenGl = false)
        assertTrue(full.contains("#define LIMITED_RANGE 0") && mpvCasSourceValid(full, mpvCasTransferProfile("pq", "full")!!, null))
        // SDR keeps the pinned two-pass upstream source.
        assertEquals(cas.fileName, mpvCasSourceFile(cas, mpvCasTransferProfile("bt.1886")))
    }

    @Test fun hdrCasIsSinglePassAndLinearisesInRegisters() {
        val text = raw(MPV_CAS_HDR_FILE)
        assertEquals(1, Regex("//!HOOK ").findAll(text).count())
        assertFalse(text.contains("//!SAVE"))
        assertFalse(text.contains("//!BIND LUMA_LINEAR") || text.contains("//!WIDTH"))
        // Limited-range expansion (64..940 of 1023) before the EOTF and back after the OETF.
        assertTrue(text.contains("64.0 / 1023.0") && text.contains("876.0 / 1023.0"))
        // Pure black must not divide by zero in the CAS amplitude.
        assertTrue(text.contains("max(mxL, 1e-10)"))
        assertTrue(text.contains("mix(le, max(sharpened, 0.0), float(CAS_BLEND))"))
        // PQ taps come from the bundled EOTF table (no pow per tap); one LUT, bound by the hook.
        assertEquals(1, Regex("//!TEXTURE ").findAll(text).count())
        assertTrue(text.contains("//!TEXTURE CAS_HDR_PQ_LUT") && text.contains("//!BIND CAS_HDR_PQ_LUT"))
        assertTrue(text.contains("//!SIZE 1024 2") && text.contains("//!FORMAT rgba8") && text.contains("//!FILTER NEAREST"))
        // Footroom/headroom and flat areas keep their code.
        assertTrue(text.contains("if (outL == le) return pix;"))
        assertTrue(text.contains("<= FLAT_CODES) return pix;"))
        assertTrue(text.contains("#define SHARPENING 0.0"))
        // The upstream two-pass file stays byte-identical (manifest hash) and has no blend define.
        assertFalse(raw(MPV_IMAGE_SHADERS.getValue("cas").fileName).contains("CAS_BLEND"))
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
        val lightPq = mpvShaderSourceFor(light, raw(MPV_CAS_HDR_FILE), mpvCasTransferProfile("pq"), artCnnOpenGl = false)
        assertTrue(lightPq.contains("#define CAS_BLEND $MPV_CAS_LIGHT_BLEND") && lightPq.contains("#define SOURCE_TRC 2"))
        assertTrue(mpvCasSourceValid(lightPq, mpvCasTransferProfile("pq")!!, MPV_CAS_LIGHT_BLEND))
        val mediumSdr = mpvShaderSourceFor(medium, raw(medium.fileName), mpvCasTransferProfile("bt.1886"), artCnnOpenGl = false)
        assertTrue(mediumSdr.contains("#define CAS_BLEND $MPV_CAS_MEDIUM_BLEND") && mediumSdr.contains("#define SOURCE_TRC 4"))
        assertTrue(mediumSdr.contains("\tpix.r = mix(e, pix.r, float(CAS_BLEND));\n\tpix.r = clamp(pix.r, 0.0, 1.0);"))
        // CAS strength stays at the upstream 0.0: Light < Medium < plain "AMD CAS" (blend 1.0).
        assertTrue(mediumSdr.contains("#define SHARPENING 0.0"))
        assertTrue(MPV_CAS_LIGHT_BLEND.toDouble() < MPV_CAS_MEDIUM_BLEND.toDouble())
        assertTrue(MPV_CAS_MEDIUM_BLEND.toDouble() < 1.0)
        assertNull(MPV_IMAGE_SHADERS.getValue("cas").casBlend)
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
