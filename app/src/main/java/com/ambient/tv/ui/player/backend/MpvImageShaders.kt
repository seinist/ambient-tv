package com.ambient.tv.ui.player.backend

import com.ambient.tv.R
import com.ambient.tv.data.local.Nnedi3QualityCatalog
import com.ambient.tv.data.local.Nnedi3QualityPreset

internal data class MpvShaderAsset(
    val resourceId: Int?,
    val fileName: String,
    val requiresCopyBack: Boolean,
    val resourceName: String? = null,
    val nnedi3Preset: Nnedi3QualityPreset? = null,
    val transferAware: Boolean = false,
    val requiresCompute: Boolean = false,
    /** Uses libplacebo-only hook syntax (//!PARAM, C-style initialisers): only vo=gpu-next on Vulkan. */
    val requiresGpuNext: Boolean = false,
    val cacheFileName: String? = null,
)

internal data class MpvCasTransferProfile(
    val suffix: String,
    val sourceTrc: Int,
    val targetTrc: Int,
    val customGamma: String = "2.2",
)

internal fun mpvCasTransferProfile(transfer: String?): MpvCasTransferProfile? = when (transfer?.trim()?.lowercase()) {
    "bt.1886" -> MpvCasTransferProfile("bt1886", sourceTrc = 4, targetTrc = 4)
    "srgb" -> MpvCasTransferProfile("srgb", sourceTrc = 3, targetTrc = 3)
    "gamma1.8" -> MpvCasTransferProfile("gamma18", sourceTrc = 6, targetTrc = 6, customGamma = "1.8")
    "gamma2.0" -> MpvCasTransferProfile("gamma20", sourceTrc = 6, targetTrc = 6, customGamma = "2.0")
    "gamma2.2" -> MpvCasTransferProfile("gamma22", sourceTrc = 6, targetTrc = 6, customGamma = "2.2")
    "gamma2.4" -> MpvCasTransferProfile("gamma24", sourceTrc = 6, targetTrc = 6, customGamma = "2.4")
    "gamma2.6" -> MpvCasTransferProfile("gamma26", sourceTrc = 6, targetTrc = 6, customGamma = "2.6")
    "gamma2.8" -> MpvCasTransferProfile("gamma28", sourceTrc = 6, targetTrc = 6, customGamma = "2.8")
    "linear" -> MpvCasTransferProfile("linear", sourceTrc = 0, targetTrc = 0)
    else -> null
}

private fun bundledNnedi3(key: String): MpvShaderAsset {
    val preset = Nnedi3QualityCatalog.byKey.getValue(key)
    val resourceId = when (key) {
        "nnedi3_nns16" -> R.raw.nnedi3_nns16_win8x4_b3f0a59
        "nnedi3_nns32" -> R.raw.nnedi3_nns32_win8x4_b3f0a59
        "nnedi3_nns64" -> R.raw.nnedi3_nns64_win8x4_b3f0a59
        "nnedi3_nns128" -> R.raw.nnedi3_nns128_win8x4_b3f0a59
        "nnedi3_nns256" -> R.raw.nnedi3_nns256_win8x4_b3f0a59
        "nnedi3_nns256_win8x6" -> R.raw.nnedi3_nns256_win8x6_b3f0a59
        else -> error("Unsupported NNEDI3 preset: $key")
    }
    return MpvShaderAsset(
        resourceId = resourceId,
        fileName = preset.cachedFileName,
        requiresCopyBack = true,
        resourceName = preset.bundledResourceName,
        nnedi3Preset = preset,
    )
}

/** Bundled immutable sources; provenance and full notices: SHADER_SOURCES_027.json + nnedi3_notice.txt. */
internal val MPV_IMAGE_SHADERS = mapOf(
    "nnedi3_nns16" to bundledNnedi3("nnedi3_nns16"),
    "nnedi3_nns32" to bundledNnedi3("nnedi3_nns32"),
    "nnedi3_nns64" to bundledNnedi3("nnedi3_nns64"),
    "nnedi3_nns128" to bundledNnedi3("nnedi3_nns128"),
    "nnedi3_nns256" to bundledNnedi3("nnedi3_nns256"),
    "nnedi3_nns256_win8x6" to bundledNnedi3("nnedi3_nns256_win8x6"),
    "ravu_lite_r2" to MpvShaderAsset(R.raw.quality_ravu_lite_r2_cbbabe0a, "quality_ravu_lite_r2_cbbabe0a.glsl", true),
    "ravu_lite_r3" to MpvShaderAsset(R.raw.quality_ravu_lite_r3_6225c236, "quality_ravu_lite_r3_6225c236.glsl", true),
    "ravu_zoom_r3" to MpvShaderAsset(R.raw.quality_ravu_zoom_r3_eb0c3744, "quality_ravu_zoom_r3_eb0c3744.glsl", true),
    "ravu_lite_r4" to MpvShaderAsset(R.raw.quality_ravu_lite_r4_ee844074, "quality_ravu_lite_r4_ee844074.glsl", true),
    "anime4k" to MpvShaderAsset(R.raw.quality_anime4k_4c53ec2e, "quality_anime4k_4c53ec2e.glsl", false),
    "anime4k_denoise" to MpvShaderAsset(R.raw.quality_anime4k_denoise_1a45ad3a, "quality_anime4k_denoise_1a45ad3a.glsl", false),
    "anime4k_m" to MpvShaderAsset(R.raw.quality_anime4k_m_9b8e956d, "quality_anime4k_m_9b8e956d.glsl", false),
    "anime4k_denoise_m" to MpvShaderAsset(R.raw.quality_anime4k_denoise_m_967804d7, "quality_anime4k_denoise_m_967804d7.glsl", false),
    "artcnn16" to MpvShaderAsset(R.raw.quality_artcnn16_03d0b3d3, "quality_artcnn16_03d0b3d3.glsl", true),
    "artcnn32" to MpvShaderAsset(R.raw.quality_artcnn32_f773bce6, "quality_artcnn32_f773bce6.glsl", true),
    "artcnn16_ds" to MpvShaderAsset(R.raw.quality_artcnn16_ds_57df650f, "quality_artcnn16_ds_57df650f.glsl", true),
    "artcnn16_dn" to MpvShaderAsset(R.raw.quality_artcnn16_dn_047f33cd, "quality_artcnn16_dn_047f33cd.glsl", true),
    "artcnn32_ds" to MpvShaderAsset(R.raw.quality_artcnn32_ds_a04c9cba, "quality_artcnn32_ds_a04c9cba.glsl", true),
    "artcnn32_dn" to MpvShaderAsset(R.raw.quality_artcnn32_dn_6b51a6f7, "quality_artcnn32_dn_6b51a6f7.glsl", true),
    "cunny_fast_ds" to MpvShaderAsset(R.raw.quality_cunny_fast_ds_86681e39, "quality_cunny_fast_ds_86681e39.glsl", true),
    "cunny_faster_ds" to MpvShaderAsset(R.raw.quality_cunny_faster_ds_bdb1fc57, "quality_cunny_faster_ds_bdb1fc57.glsl", true),
    "cunny_fast_soft" to MpvShaderAsset(R.raw.quality_cunny_fast_soft_7f5077eb, "quality_cunny_fast_soft_7f5077eb.glsl", true),
    "cunny_faster_soft" to MpvShaderAsset(R.raw.quality_cunny_faster_soft_6166d08a, "quality_cunny_faster_soft_6166d08a.glsl", true),
    "cunny_veryfast_soft" to MpvShaderAsset(R.raw.quality_cunny_veryfast_soft_9c63e86e, "quality_cunny_veryfast_soft_9c63e86e.glsl", true),
    "cunny_fast_soft_q" to MpvShaderAsset(R.raw.quality_cunny_fast_soft_q_96af5170, "quality_cunny_fast_soft_q_96af5170.glsl", true, requiresGpuNext = true),
    "cunny_faster_soft_q" to MpvShaderAsset(R.raw.quality_cunny_faster_soft_q_9caf1113, "quality_cunny_faster_soft_q_9caf1113.glsl", true, requiresGpuNext = true),
    "cunny_veryfast_soft_q" to MpvShaderAsset(R.raw.quality_cunny_veryfast_soft_q_aff8624c, "quality_cunny_veryfast_soft_q_aff8624c.glsl", true, requiresGpuNext = true),
    "fsrcnnx8" to MpvShaderAsset(R.raw.quality_fsrcnnx8_e800dbc5, "quality_fsrcnnx8_e800dbc5.glsl", true),
    "fsrcnnx16" to MpvShaderAsset(R.raw.quality_fsrcnnx16_d5a24a27, "quality_fsrcnnx16_d5a24a27.glsl", true),
    "krig" to MpvShaderAsset(R.raw.quality_krig_8a4798ab, "quality_krig_8a4798ab.glsl", true),
    "filmgrain_light" to MpvShaderAsset(R.raw.quality_filmgrain_light_4aee719a, "quality_filmgrain_light_4aee719a.glsl", true),
    "filmgrain_medium" to MpvShaderAsset(R.raw.quality_filmgrain_medium_4921afc1, "quality_filmgrain_medium_4921afc1.glsl", true),
    "cfl_lite" to MpvShaderAsset(R.raw.quality_cfl_lite_159c1dad, "quality_cfl_lite_159c1dad.glsl", true, requiresGpuNext = true),
    "cfl" to MpvShaderAsset(R.raw.quality_cfl_f9d12d38, "quality_cfl_f9d12d38.glsl", true, requiresGpuNext = true),
    "ssim_down" to MpvShaderAsset(R.raw.quality_ssim_down_f46f4710, "quality_ssim_down_f46f4710.glsl", false),
    "ssim_super" to MpvShaderAsset(R.raw.quality_ssim_super_a8b27115, "quality_ssim_super_a8b27115.glsl", false),
    "adaptive" to MpvShaderAsset(R.raw.quality_adaptive_44e81f12, "quality_adaptive_44e81f12.glsl", false),
    "fsr" to MpvShaderAsset(R.raw.quality_fsr_56d8597f, "quality_fsr_56d8597f.glsl", true),
    "nis" to MpvShaderAsset(
        R.raw.quality_nis_nvscaler_7948bc72,
        "quality_nis_nvscaler_7948bc72.glsl",
        true,
        requiresCompute = true,
    ),
    "cas" to MpvShaderAsset(
        R.raw.quality_cas_a945e104,
        "quality_cas_a945e104.glsl",
        true,
        transferAware = true,
        cacheFileName = "quality_cas_939ed74a.glsl",
    ),
)
