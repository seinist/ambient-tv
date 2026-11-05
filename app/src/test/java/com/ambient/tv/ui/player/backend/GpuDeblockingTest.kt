package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.ui.player.summarizeMpvVideoFilters
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** The same saved MPV strength selects GPU on a usable output, CPU only on hook failure. */
class GpuDeblockingTest {
    @Test fun allStrengthsHaveSourceLumaShaderDescriptors() {
        for ((strength, value) in listOf("light" to "0.25", "medium" to "0.50", "strong" to "0.75")) {
            val key = "deblock_$strength"
            val descriptor = MPV_IMAGE_SHADERS.getValue(key)
            assertEquals("quality_deblock_gpu.glsl", descriptor.fileName)
            assertTrue(descriptor.requiresCopyBack)
            val raw = File("src/main/res/raw/quality_deblock_gpu.glsl").readText()
            val source = mpvShaderSourceFor(descriptor, raw, transferProfile = null, artCnnOpenGl = false)
            assertTrue(source.contains("//!HOOK LUMA"))
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
        // A YUV LUMA hook can still require MediaCodec copy-back, but keeps hardware decoding.
        assertTrue(mpvCopyBackNeeded(listOf("deblock_strong"), cpuFilters = false, deinterlaceEnabled = false))
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
