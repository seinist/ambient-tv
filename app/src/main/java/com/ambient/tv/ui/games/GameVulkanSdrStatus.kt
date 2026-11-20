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

    /** Actual transfer only after successful vkQueuePresentKHR; never guess from VkFormat. */
    val presentingTransfer: GameVulkanTransferMode? get() = if (phase != Phase.PRESENTING) null else
        when (bits and (16 or 32)) {
            0 -> GameVulkanTransferMode.SDR
            16 -> GameVulkanTransferMode.PQ_BT2020
            32 -> GameVulkanTransferMode.HLG_BT2020
            else -> null // impossible/inconsistent native bits: never label as HDR
        }
}
