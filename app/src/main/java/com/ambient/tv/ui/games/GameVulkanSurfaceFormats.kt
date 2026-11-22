package com.ambient.tv.ui.games

/**
 * Result of probing a *real, distinct* Android display Surface using the same
 * Vulkan instance/device/graphics queue as the running libretro core.
 *
 * These bits express a *possible* WSI format pair, not an active swapchain or
 * Android HDR signal. No native HDR output is enabled by this probe.
 */
internal data class GameVulkanSurfaceFormats(val bits: Int = 0) {
    val presentable: Boolean get() = bits and 1 != 0
    val sdr: Boolean get() = presentable && bits and 2 != 0
    val hdr10Pq: Boolean get() = presentable && bits and 4 != 0
    val hlg: Boolean get() = presentable && bits and 8 != 0
    val scrgbFp16: Boolean get() = presentable && bits and 16 != 0

    /** A native presenter must still be created and supply its own active-state gates. */
    fun candidateTypesLabel(): String = if (!presentable) "Unavailable / unknown" else buildList {
        if (sdr) add("SDR")
        if (hdr10Pq) add("HDR10 PQ")
        if (hlg) add("HLG")
        if (scrgbFp16) add("scRGB FP16")
    }.joinToString(" · ").ifEmpty { "No known format" }
}
