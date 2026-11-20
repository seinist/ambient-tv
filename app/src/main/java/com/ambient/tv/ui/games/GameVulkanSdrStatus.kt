package com.ambient.tv.ui.games

/** Native status from the optional Vulkan SDR WSI presenter, not the GLES bridge. */
internal data class GameVulkanSdrStatus(val bits: Int = 0) {
    enum class Phase { OFF, REQUESTED, SWAPCHAIN, PRESENTING, GLES_FALLBACK }

    val phase: Phase get() = when {
        bits and 1 == 0 -> Phase.OFF
        bits and 8 != 0 -> Phase.GLES_FALLBACK
        bits and 4 != 0 && bits and 2 != 0 -> Phase.PRESENTING
        bits and 2 != 0 -> Phase.SWAPCHAIN
        else -> Phase.REQUESTED
    }
}
