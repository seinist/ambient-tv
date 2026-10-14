package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import org.junit.Assert.*
import org.junit.Test

class CflChromaShaderTest {
    @Test fun cflVariantsAreChromaShadersNextToKrig() {
        assertEquals(listOf("krig", "cfl_lite", "cfl"), VideoImageCatalog.chromaShaders.keys.toList())
        for (key in listOf("cfl_lite", "cfl")) {
            assertTrue(key in VideoImageCatalog.chromaScalers)
            assertEquals(key, MpvImageOptions(chromaScaler = key).normalized().chromaScaler)
            val asset = MPV_IMAGE_SHADERS.getValue(key)
            assertTrue(asset.requiresCopyBack)
            assertTrue(asset.requiresGpuNext)
            assertFalse(asset.requiresCompute)
        }
        assertFalse(MPV_IMAGE_SHADERS.getValue("krig").requiresGpuNext)
    }

    @Test fun selectedCflIsRequestedLikeKrig() {
        assertEquals(listOf("cfl"), mpvImageShaderKeys(MpvImageOptions(chromaScaler = "cfl"), sdr = true, upscaling = false))
        assertEquals(listOf("cfl_lite", "artcnn16"),
            mpvImageShaderKeys(MpvImageOptions(scaler = "artcnn16", chromaScaler = "cfl_lite"), sdr = true))
        assertEquals(emptyList<String>(), mpvImageShaderKeys(MpvImageOptions(chromaScaler = "cfl"), sdr = false))
    }

    @Test fun gpuNextGateNeedsVulkanAndGpuNext() {
        assertTrue(mpvGpuNextVulkan("gpu-next", "vulkan"))
        assertFalse(mpvGpuNextVulkan("gpu", "opengl"))
        assertFalse(mpvGpuNextVulkan("gpu-next", "opengl"))
        assertFalse(mpvGpuNextVulkan(null, null))
    }

    @Test fun bundledCflSourcesArePinnedAndUnmodified() {
        val raw = java.io.File("src/main/res/raw").listFiles()!!.filter { it.name.startsWith("quality_cfl") }
        assertEquals(2, raw.size)
        raw.forEach { assertTrue(it.readText().contains("//!HOOK CHROMA")) }
    }
}
