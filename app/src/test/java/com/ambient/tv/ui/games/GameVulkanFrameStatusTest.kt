package com.ambient.tv.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameVulkanFrameStatusTest {
    @Test fun noNativeBufferNeverClaimsActiveVulkanOrHdrPrecision() {
        val beforeStart = GameVulkanFrameStatus()
        assertFalse(beforeStart.active)
        assertFalse(beforeStart.fp16)
        assertFalse(beforeStart.rgba8Fallback)
        assertEquals("OpenGL ES", beforeStart.badge(requestedVulkan = true))
    }

    @Test fun actualFramePrecisionOverridesRequestedPrecision() {
        val rgba8 = GameVulkanFrameStatus(1)
        val fp16 = GameVulkanFrameStatus(1 or 2 or 4)
        val fallback = GameVulkanFrameStatus(1 or 4 or 8)
        assertTrue(rgba8.active)
        assertFalse(rgba8.fp16)
        assertEquals("Vulkan → GLES · RGBA8", rgba8.badge(requestedVulkan = true))
        assertTrue(fp16.fp16)
        assertTrue(fp16.fp16Requested)
        assertFalse(fp16.rgba8Fallback)
        assertEquals("Vulkan → GLES · FP16", fp16.badge(requestedVulkan = true))
        assertFalse(fallback.fp16)
        assertTrue(fallback.fp16Requested)
        assertTrue(fallback.rgba8Fallback)
        assertEquals("Vulkan → GLES · RGBA8 fallback", fallback.badge(requestedVulkan = true))
        assertEquals("OpenGL ES", fp16.badge(requestedVulkan = false))
    }

    @Test fun unknownFutureBitsCannotTurnOnAFeatureWithoutItsBaseBit() {
        val unknown = GameVulkanFrameStatus(2 or 4 or 8 or 0x100)
        assertFalse(unknown.active)
        assertFalse(unknown.fp16)
        assertFalse(unknown.rgba8Fallback)
    }
}
