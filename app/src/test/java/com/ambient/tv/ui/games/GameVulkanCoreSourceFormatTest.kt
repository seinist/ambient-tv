package com.ambient.tv.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameVulkanCoreSourceFormatTest {
    @Test fun formatNeverClaimsTransferOrPrimaries() {
        val hdrCandidate = GameVulkanCoreSourceFormat(64)
        assertTrue(hdrCandidate.tenBitPacked)
        assertEquals("A2B10G10R10 UNORM · 10-bit", hdrCandidate.label)
        assertFalse(GameVulkanCoreSourceFormat(97).tenBitPacked)
        assertFalse(GameVulkanCoreSourceFormat(37).tenBitPacked)
    }

    @Test fun noSourceAndFutureFormatsRemainExplicitlyUnknown() {
        assertFalse(GameVulkanCoreSourceFormat().known)
        assertEquals("Unknown / no core frame", GameVulkanCoreSourceFormat().label)
        assertEquals("VkFormat 987654 (unknown encoding)", GameVulkanCoreSourceFormat(987654).label)
    }

    @Test fun knownSdrPixelsAndRgba16FloatAreRecognized() {
        assertEquals("R8G8B8A8 UNORM · 8-bit", GameVulkanCoreSourceFormat(37).label)
        assertEquals("B8G8R8A8 UNORM · 8-bit", GameVulkanCoreSourceFormat(44).label)
        assertEquals("A2R10G10B10 UNORM · 10-bit", GameVulkanCoreSourceFormat(58).label)
        assertEquals("R16G16B16A16 SFLOAT · FP16", GameVulkanCoreSourceFormat(97).label)
    }
}
