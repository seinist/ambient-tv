package com.ambient.tv.ui.games

/**
 * Actual VkImage format reported by a Vulkan Libretro core's set_image callback.
 *
 * Bit-depth/packing is useful for device diagnostics, but Vulkan VkFormat does
 * NOT encode color primaries or transfer function. 10-bit source pixels alone
 * never prove HDR10/PQ or HLG, and FP16 alone never proves linear scRGB.
 */
internal data class GameVulkanCoreSourceFormat(val code: Int = 0) {
    val known: Boolean get() = code != 0

    val label: String get() = when (code) {
        0 -> "Unknown / no core frame"
        37 -> "R8G8B8A8 UNORM · 8-bit"
        44 -> "B8G8R8A8 UNORM · 8-bit"
        58 -> "A2R10G10B10 UNORM · 10-bit"
        64 -> "A2B10G10R10 UNORM · 10-bit"
        97 -> "R16G16B16A16 SFLOAT · FP16"
        else -> "VkFormat $code (unknown encoding)"
    }

    /** Input format check only; transfer and primaries must be proven separately. */
    val tenBitPacked: Boolean get() = code == 58 || code == 64
}
