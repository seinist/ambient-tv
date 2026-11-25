package com.ambient.tv.ui.games

import com.ambient.tv.games.GameHdrToneMap

/**
 * Explicit, session-only gate for native Vulkan presentation. Both the output
 * transfer and each newly sized surface need their own successful presentation.
 */
internal object GameVulkanFullscreenPolicy {
    fun canEnable(
        vulkanCore: Boolean,
        input: GameHdrToneMap,
        diagnosticEnabled: Boolean,
        nativeStatus: GameVulkanSdrStatus,
        netplayActive: Boolean,
        display: GameHdrDisplayCapabilities,
        surface: GameVulkanSurfaceFormats,
    ): Boolean {
        val transfer = GameVulkanTransferMode.forInput(input)
        return vulkanCore && diagnosticEnabled && !netplayActive &&
            GameVulkanTransferGate.supported(transfer, display, surface) &&
            GameVulkanTransferGate.frameConfirmed(transfer, nativeStatus)
    }

    fun mustFallback(status: GameVulkanSdrStatus): Boolean =
        status.phase == GameVulkanSdrStatus.Phase.GLES_FALLBACK

    fun shouldShowFullscreen(
        requested: Boolean,
        status: GameVulkanSdrStatus,
        transfer: GameVulkanTransferMode = GameVulkanTransferMode.SDR,
    ): Boolean =
        requested && GameVulkanTransferGate.frameConfirmed(transfer, status)

    /** A successful 16px probe or a frame from an old surface cannot hide GLES. */
    fun canSkipGles(
        requested: Boolean,
        fullscreenVisible: Boolean,
        fullscreenSurfaceReady: Boolean,
        status: GameVulkanSdrStatus,
        transfer: GameVulkanTransferMode,
    ): Boolean = requested && fullscreenVisible && fullscreenSurfaceReady &&
        GameVulkanTransferGate.frameConfirmed(transfer, status)
}
