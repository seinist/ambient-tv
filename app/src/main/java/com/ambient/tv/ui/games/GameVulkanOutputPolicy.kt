package com.ambient.tv.ui.games

/**
 * Display-advertised EOTFs are *capabilities*, not proof that Android is sending an
 * HDR signal, or that Vulkan has an HDR-capable swapchain on the game's window.
 */
internal data class GameHdrDisplayCapabilities(
    val hdr10: Boolean = false,
    val hlg: Boolean = false,
    val hdr10Plus: Boolean = false,
    val dolbyVision: Boolean = false,
    val peakNits: Float? = null,
    val averageNits: Float? = null,
    val minimumNits: Float? = null,
) {
    fun supportedTypesLabel(): String = buildList {
        if (hdr10) add("HDR10")
        if (hlg) add("HLG")
        if (hdr10Plus) add("HDR10+")
        if (dolbyVision) add("Dolby Vision")
    }.joinToString(" · ").ifEmpty { "SDR / unknown" }
}

/** A separate window/swapchain backend must report these, not infer them from the panel. */
internal data class GameVulkanHdrOutputReadiness(
    val presentingOnRealWindow: Boolean = false,
    val hasHdrSwapchain: Boolean = false,
    val hasReliableSourceContract: Boolean = false,
    val hasColorManagedPipeline: Boolean = false,
    val hasOutputMetadata: Boolean = false,
    val pqSwapchainFormat: Boolean = false,
    val hlgSwapchainFormat: Boolean = false,
)

/** This policy is intentionally not connected to the current GLES compositor. */
internal enum class GameHdrNegotiatedSource { SDR, HDR10_PQ_BT2020, HLG_BT2020, SCRGB_LINEAR }

internal enum class GameVulkanOutputMode { SDR, HDR10_PQ_BT2020, HLG_BT2020 }

internal object GameVulkanOutputPolicy {
    fun select(
        display: GameHdrDisplayCapabilities,
        readiness: GameVulkanHdrOutputReadiness,
        source: GameHdrNegotiatedSource,
    ): GameVulkanOutputMode {
        // Every prerequisite must come from a *live*, successfully created presenter.
        if (!readiness.presentingOnRealWindow ||
            !readiness.hasHdrSwapchain ||
            !readiness.hasReliableSourceContract ||
            !readiness.hasColorManagedPipeline ||
            !readiness.hasOutputMetadata
        ) return GameVulkanOutputMode.SDR

        return when (source) {
            GameHdrNegotiatedSource.HDR10_PQ_BT2020 ->
                if (display.hdr10 && readiness.pqSwapchainFormat) GameVulkanOutputMode.HDR10_PQ_BT2020
                else GameVulkanOutputMode.SDR
            GameHdrNegotiatedSource.HLG_BT2020 ->
                if (display.hlg && readiness.hlgSwapchainFormat) GameVulkanOutputMode.HLG_BT2020
                else GameVulkanOutputMode.SDR
            // scRGB requires a separately verified linear FP16 surface/transform path.
            GameHdrNegotiatedSource.SDR, GameHdrNegotiatedSource.SCRGB_LINEAR -> GameVulkanOutputMode.SDR
        }
    }
}
