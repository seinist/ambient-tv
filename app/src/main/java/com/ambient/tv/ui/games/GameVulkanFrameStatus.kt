package com.ambient.tv.ui.games

/**
 * Actual, atomically reported native Vulkan → GLES transport.
 *
 * Unlike the requested core graphics mode, this describes a buffer that really exists.
 * Native bits: Vulkan image=1, FP16=2, FP16 requested=4, RGBA8 fallback=8.
 * 0 is deliberately treated as unknown/SDR rather than claiming HDR.
 */
internal data class GameVulkanFrameStatus(val bits: Int = 0) {
    val active: Boolean get() = bits and 1 != 0
    val fp16: Boolean get() = active && bits and 2 != 0
    val fp16Requested: Boolean get() = active && bits and 4 != 0
    val rgba8Fallback: Boolean get() = active && !fp16 && bits and 8 != 0

    fun badge(requestedVulkan: Boolean): String = when {
        !requestedVulkan || !active -> "OpenGL ES"
        fp16 -> "Vulkan → GLES · FP16"
        rgba8Fallback -> "Vulkan → GLES · RGBA8 fallback"
        else -> "Vulkan → GLES · RGBA8"
    }
}
