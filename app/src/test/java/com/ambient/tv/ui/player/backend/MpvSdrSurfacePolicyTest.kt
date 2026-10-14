package com.ambient.tv.ui.player.backend

import org.junit.Assert.*
import org.junit.Test

class MpvSdrSurfacePolicyTest {
    @Test fun hdr10RequiresAnAdvertisedPqSwapchainAndKeepsUnknownDistinct() {
        assertNull(VulkanDeviceCapabilities().hdr10SurfaceSupported)
        assertFalse(VulkanDeviceCapabilities(surfaceColorSpaces = emptyList()).hdr10SurfaceSupported!!)
        assertFalse(VulkanDeviceCapabilities(surfaceColorSpaces = listOf(0, 1000104002)).hdr10SurfaceSupported!!)
        assertTrue(VulkanDeviceCapabilities(surfaceColorSpaces = listOf(0, 1000104008)).hdr10SurfaceSupported!!)
    }

    @Test fun bt709GpuOutputUsesFullRangeVideoTransfer() {
        for (vo in listOf("gpu", "gpu-next"))
            assertEquals(MPV_BT709_FULL, mpvSdrSurfaceDataSpace(vo, "bt.1886", "bt.1886", "bt.709", "bt.1886", "bt.709", false))
        assertEquals(146866176, MPV_BT709_FULL)
    }

    @Test fun hdrUnknownAndConvertedTargetsAreNeverRelabelled() {
        for (value in listOf(null, "pq", "hlg", "srgb", "linear")) {
            assertNull(mpvSdrSurfaceDataSpace("gpu-next", value, "bt.1886", "bt.709", "bt.1886", "bt.709", false))
            assertNull(mpvSdrSurfaceDataSpace("gpu-next", "bt.1886", value, "bt.709", "bt.1886", "bt.709", false))
            assertNull(mpvSdrSurfaceDataSpace("gpu-next", "bt.1886", "bt.1886", "bt.709", value, "bt.709", false))
        }
        assertNull(mpvSdrSurfaceDataSpace("mediacodec_embed", "bt.1886", "bt.1886", "bt.709", "bt.1886", "bt.709", false))
        assertNull(mpvSdrSurfaceDataSpace("gpu-next", "bt.1886", "bt.1886", "bt.2020", "bt.1886", "bt.709", false))
        assertNull(mpvSdrSurfaceDataSpace("gpu-next", "bt.1886", "bt.1886", "bt.709", "bt.1886", "display-p3", false))
        assertNull(mpvSdrSurfaceDataSpace("gpu-next", "bt.1886", "bt.1886", "bt.709", "bt.1886", "bt.709", true))
    }
}
