package com.ambient.tv.ui.games

/**
 * Evidence of the source transfer, independent of the user's manual PQ/HLG request.
 *
 * A Vulkan VkFormat proves packing only. The opt-in Beetle PSX HW color-format
 * setting is a core-defined PQ/Rec.2020 output contract, but we accept that
 * declaration only when the effective renderer is Vulkan and the *observed*
 * core image is packed 10-bit UNORM. This does not verify a TV HDR signal or
 * supply authored mastering metadata, MaxCLL or MaxFALL.
 *
 * No HLG or scRGB core is whitelisted until its own source contract is known.
 * Unknown/buildbot/older cores retain GLES, not speculative HDR passthrough.
 */
internal data class GameVulkanHdrSourceContract(
    val transfer: GameVulkanTransferMode? = null,
    val origin: String? = null,
    val primariesH273: Int = 0,
    val transferH273: Int = 0,
    val masteringMaxNits: Float? = null,
    val masteringMinNits: Float? = null,
    val maxCll: Float? = null,
    val maxFall: Float? = null,
) {
    fun supports(output: GameVulkanTransferMode): Boolean =
        output == GameVulkanTransferMode.SDR || transfer == output
}

internal object GameVulkanHdrSourceEvidence {
    /** Only pinned *known* core option semantics; not a guess from bit depth. */
    fun observe(
        coreId: String,
        vulkan: Boolean,
        effectiveOptions: Map<String, String>,
        observedFormat: GameVulkanCoreSourceFormat,
    ): GameVulkanHdrSourceContract {
        if (!vulkan || !observedFormat.tenBitPacked) return GameVulkanHdrSourceContract()
        if (coreId == "mednafen_psx_hw" &&
            effectiveOptions["beetle_psx_hw_renderer"] == "hardware_vk" &&
            effectiveOptions["beetle_psx_hw_color_format"] == "30bit_hdr"
        ) return GameVulkanHdrSourceContract(
            transfer = GameVulkanTransferMode.PQ_BT2020,
            origin = "Beetle PSX HW: color_format=30bit_hdr + observed Vulkan 10-bit source",
            primariesH273 = 9, // BT.2020, per core's explicit color-format contract
            transferH273 = 16, // SMPTE ST 2084, per core's explicit color-format contract
            // Authored source mastering values are not supplied by libretro: leave unknown.
        )
        return GameVulkanHdrSourceContract()
    }
}
