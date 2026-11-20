package com.ambient.tv.ui.games

import com.ambient.tv.games.GameHdrToneMap

/**
 * Transfer encoding of the *core's Vulkan image*. This is a manual declaration,
 * NOT automatic detection from a 10-bit Vulkan VkFormat, which tells us nothing
 * about PQ/HLG vs ordinary SDR. Wrong manual input must not be labeled verified.
 */
internal enum class GameVulkanTransferMode(val nativeCode: Int) {
    SDR(0),
    PQ_BT2020(1),
    HLG_BT2020(2);

    companion object {
        fun forInput(input: GameHdrToneMap): GameVulkanTransferMode = when (input) {
            GameHdrToneMap.OFF -> SDR
            GameHdrToneMap.PQ -> PQ_BT2020
            GameHdrToneMap.HLG -> HLG_BT2020
        }
    }
}

/** Prerequisites for even showing the experimental native output diagnostic. */
internal object GameVulkanTransferGate {
    fun supported(
        transfer: GameVulkanTransferMode,
        display: GameHdrDisplayCapabilities,
        surface: GameVulkanSurfaceFormats,
    ): Boolean = when (transfer) {
        GameVulkanTransferMode.SDR -> surface.presentable && surface.sdr
        GameVulkanTransferMode.PQ_BT2020 ->
            display.hdr10 && surface.presentable && surface.hdr10Pq
        GameVulkanTransferMode.HLG_BT2020 ->
            display.hlg && surface.presentable && surface.hlg
    }

    fun frameConfirmed(
        transfer: GameVulkanTransferMode,
        native: GameVulkanSdrStatus,
    ): Boolean = native.phase == GameVulkanSdrStatus.Phase.PRESENTING &&
        native.presentingTransfer == transfer
}
