package com.ambient.tv.ui.games

import com.ambient.tv.games.GameHdrToneMap

/**
 * Explicit, session-only gate for a RAW native Vulkan SDR image.
 *
 * This experimental path bypasses the GLES shader/aspect/rotation pipeline,
 * so it must never be advertised as a drop-in replacement for normal playback.
 * It is not an HDR renderer, not persisted and never enabled automatically.
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
}
