package com.ambient.tv.ui.player.backend

import org.junit.Assert.*
import org.junit.Test

class SdrToHdrInputPolicyTest {
    @Test fun requiresActualVulkanGpuNextAndKnownSdrAtEveryStage() {
        assertTrue(supportsMpvSdrToHdrInput("gpu-next", "vulkan", "bt.1886", "bt.1886", "srgb", "bt.709"))
        for (unknown in listOf(null, "", "auto", "pq", "smpte2084", "hlg", "arib-std-b67", "linear")) {
            assertFalse(supportsMpvSdrToHdrInput("gpu-next", "vulkan", unknown, "srgb", "srgb", "bt.709"))
            assertFalse(supportsMpvSdrToHdrInput("gpu-next", "vulkan", "srgb", unknown, "srgb", "bt.709"))
            assertFalse(supportsMpvSdrToHdrInput("gpu-next", "vulkan", "srgb", "srgb", unknown, "bt.709"))
        }
        for (vo in listOf(null, "", "mediacodec_embed", "gpu", "null"))
            assertFalse(supportsMpvSdrToHdrInput(vo, "vulkan", "srgb", "srgb", "srgb", "bt.709"))
        for (api in listOf(null, "", "auto", "opengl", "opengl-es"))
            assertFalse(supportsMpvSdrToHdrInput("gpu-next", api, "srgb", "srgb", "srgb", "bt.709"))
    }

    @Test fun unknownOrWideGamutSourceIsNotAssumedToBeOrdinarySdr() {
        for (primaries in listOf(null, "", "auto", "bt.2020", "display-p3"))
            assertFalse(supportsMpvSdrToHdrInput("gpu-next", "vulkan", "srgb", "srgb", "srgb", primaries))
        for (primaries in listOf("bt.709", "bt.601-525", "bt.601-625"))
            assertTrue(supportsMpvSdrToHdrInput("gpu-next", "vulkan", "gamma2.2", "gamma2.2", "gamma2.2", primaries))
    }
}
