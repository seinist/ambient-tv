package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.ui.player.summarizeMpvVideoFilters
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** The same saved MPV strength selects GPU on a usable output, CPU only on hook failure. */
class GpuDeblockingTest {
    @Test fun allStrengthsHaveSourceMainShaderDescriptors() {
        for ((strength, value) in listOf("light" to "0.25", "medium" to "0.50", "strong" to "0.75")) {
            val key = "deblock_$strength"
            val descriptor = MPV_IMAGE_SHADERS.getValue(key)
            assertEquals("quality_deblock_gpu.glsl", descriptor.fileName)
            assertFalse(descriptor.requiresCopyBack)
            val raw = File("src/main/res/raw/quality_deblock_gpu.glsl").readText()
            val source = mpvShaderSourceFor(descriptor, raw, transferProfile = null, artCnnOpenGl = false)
            assertTrue(source.contains("//!HOOK MAIN"))
            assertTrue(source.contains("#define DEBLOCK_STRENGTH $value"))
            assertEquals(listOf(key), mpvImageShaderKeys(MpvImageOptions(deblock = strength), sdr = true, upscaling = false))
        }
        assertNotEquals(MPV_IMAGE_SHADERS.getValue("deblock_light").cacheFileName,
            MPV_IMAGE_SHADERS.getValue("deblock_strong").cacheFileName)
    }

    @Test fun gpuPathNeverStacksCpuDeblocking() {
        val options = MpvImageOptions(denoise = "light", deblock = "strong")
        val cpu = mpvCpuImageFilters(options, sdr = true)
        assertTrue("plex-deblock" in cpu)
        val gpu = mpvCpuImageFilters(options, sdr = true, hardwareDeblock = true)
        assertFalse("plex-deblock" in gpu)
        assertTrue("plex-denoise" in gpu)
        // MAIN shader runs on source-size RGB before scaling without forcing a CPU frame copy.
        assertFalse(mpvCopyBackNeeded(listOf("deblock_strong"), cpuFilters = false, deinterlaceEnabled = false))
    }

    @Test fun liveSwitchRequiresRemovingCpuFilterBeforeEnablingGpuHook() {
        val cpuActive = listOf("plex-denoise", "plex-deblock")
        for (strength in listOf("light", "medium", "strong")) {
            val shader = "deblock_$strength"
            assertTrue(mpvDeblockCpuMustRetireBeforeGpu(listOf(shader), cpuActive))
            // Once CPU is retired, the same single saved strength executes only on GPU.
            val cpuPlan = mpvCpuImageFilters(MpvImageOptions(deblock = strength), sdr = true,
                hardwareDeblock = true)
            assertFalse("plex-deblock" in cpuPlan)
            assertFalse(mpvDeblockCpuMustRetireBeforeGpu(listOf(shader), cpuPlan.keys))
        }
        // CPU fallback can continue if the requested shader cannot be materialized/loaded.
        assertFalse(mpvDeblockCpuMustRetireBeforeGpu(emptyList(), cpuActive))
        assertFalse(mpvDeblockCpuMustRetireBeforeGpu(listOf("cas", "ravu_lite_r3"), cpuActive))
        assertFalse(mpvDeblockCpuMustRetireBeforeGpu(listOf("deblock_medium"), listOf("plex-denoise")))
    }

    @Test fun failedGpuLoadUsesExactlyOneCpuFallbackFilter() {
        val options = MpvImageOptions(deblock = "medium")
        val requested = mpvImageShaderKeys(options, sdr = true)
        assertEquals(listOf("deblock_medium"), requested)
        // When GPU loading is rejected the loaded set is empty: no active GPU and
        // the desired CPU vf chain contains one deblock implementation.
        val loaded = emptyList<String>()
        val cpu = mpvCpuImageFilters(options, sdr = true,
            hardwareDeblock = "deblock_medium" in loaded)
        assertEquals(setOf("plex-deblock"), cpu.keys)
        assertFalse(mpvDeblockCpuMustRetireBeforeGpu(loaded, cpu.keys))
    }

    @Test fun hdrAndUnknownTransferMustNotStartOptionalDeblocking() {
        for ((sdr, hdr) in listOf(false to false, false to true)) {
            assertTrue(mpvImageShaderKeys(MpvImageOptions(deblock = "medium"), sdr = sdr, hdr = hdr).isEmpty())
        }
        assertTrue(mpvCpuImageFilters(MpvImageOptions(deblock = "medium"), sdr = false).isEmpty())
    }

    @Test fun nerdStatsReportsTheLoadedRouteRatherThanOnlyTheCpuVf() {
        assertEquals("deblock GPU (medium)", summarizeMpvVideoFilters("[]", listOf("deblock_medium")))
        assertEquals("denoise, deblock GPU (light)",
            summarizeMpvVideoFilters("@plex-denoise:lavfi=[hqdn3d]", listOf("deblock_light")))
        assertEquals("deblock CPU", summarizeMpvVideoFilters("@plex-deblock:lavfi=[deblock]", emptyList()))
        assertEquals("off", summarizeMpvVideoFilters("[]", emptyList()))
    }
}
