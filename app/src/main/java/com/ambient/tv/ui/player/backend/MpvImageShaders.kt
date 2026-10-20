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
    /**
     * Correct on a PQ/HLG source while mpv tone-maps to SDR: a hook that linearises with the
     * source curve (CAS), runs on the tone-mapped OUTPUT (Adaptive Sharpen, OUTPUT grain), or
     * is a light upscaler accepted as experimental (RAVU Lite, FSR). Everything else stays SDR-only.
     */
    val hdrSafe: Boolean = false,
    /**
     * CAS only: blend of the sharpened result with the original pixel (built-in Light/Medium on
     * gpu-next). Below 1.0 so Light < Medium < "AMD CAS" in strength.
     */
    val casBlend: String? = null,
    /** Film grain only: hook OUTPUT (after tone mapping, RGB) instead of source LUMA. */
    val outputHook: Boolean = false,
)

internal data class MpvCasTransferProfile(
    val suffix: String,
    val sourceTrc: Int,
    val targetTrc: Int,
    val customGamma: String = "2.2",
    /**
     * PQ/HLG: the single-pass HDR CAS ([MPV_CAS_HDR_RESOURCE]) that expands the range and
     * linearises the 3x3 neighbourhood in registers instead of writing linear light to an FBO.
     */
    val singlePassHdr: Boolean = false,
    /** Single-pass HDR only: the luma plane is full range (no 64..940 expansion). */
    val fullRange: Boolean = false,
)

/** [levels] is mpv's [MPV_PROP_COLORLEVELS] ("limited"/"full"); only HDR profiles use it. */
internal fun mpvCasTransferProfile(transfer: String?, levels: String? = null): MpvCasTransferProfile? {
    val full = levels?.trim()?.lowercase() in setOf("full", "pc", "jpeg")
    return when (transfer?.trim()?.lowercase()) {
        "bt.1886" -> MpvCasTransferProfile("bt1886", sourceTrc = 4, targetTrc = 4)
        "srgb" -> MpvCasTransferProfile("srgb", sourceTrc = 3, targetTrc = 3)
        "gamma1.8" -> MpvCasTransferProfile("gamma18", sourceTrc = 6, targetTrc = 6, customGamma = "1.8")
        "gamma2.0" -> MpvCasTransferProfile("gamma20", sourceTrc = 6, targetTrc = 6, customGamma = "2.0")
        "gamma2.2" -> MpvCasTransferProfile("gamma22", sourceTrc = 6, targetTrc = 6, customGamma = "2.2")
        "gamma2.4" -> MpvCasTransferProfile("gamma24", sourceTrc = 6, targetTrc = 6, customGamma = "2.4")
        "gamma2.6" -> MpvCasTransferProfile("gamma26", sourceTrc = 6, targetTrc = 6, customGamma = "2.6")
        "gamma2.8" -> MpvCasTransferProfile("gamma28", sourceTrc = 6, targetTrc = 6, customGamma = "2.8")
        "linear" -> MpvCasTransferProfile("linear", sourceTrc = 0, targetTrc = 0)
        // HDR sources: single-pass CAS that linearises with the source curve and re-encodes it.
        "pq", "smpte2084", "st2084" -> MpvCasTransferProfile(if (full) "pq1p_full" else "pq1p", sourceTrc = 2, targetTrc = 2,
            singlePassHdr = true, fullRange = full)
        "hlg", "arib-std-b67" -> MpvCasTransferProfile(if (full) "hlg1p_full" else "hlg1p", sourceTrc = 5, targetTrc = 5,
            singlePassHdr = true, fullRange = full)
        else -> null
    }
}

/** Ambient TV's single-pass HDR CAS (see the file header). */
internal val MPV_CAS_HDR_RESOURCE: Int get() = R.raw.quality_cas_hdr
internal const val MPV_CAS_HDR_FILE = "quality_cas_hdr.glsl"

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
    "ravu_lite_r2" to MpvShaderAsset(R.raw.quality_ravu_lite_r2_cbbabe0a, "quality_ravu_lite_r2_cbbabe0a.glsl", true, hdrSafe = true),
    "ravu_lite_r3" to MpvShaderAsset(R.raw.quality_ravu_lite_r3_6225c236, "quality_ravu_lite_r3_6225c236.glsl", true, hdrSafe = true),
    "ravu_zoom_r3" to MpvShaderAsset(R.raw.quality_ravu_zoom_r3_eb0c3744, "quality_ravu_zoom_r3_eb0c3744.glsl", true),
    "ravu_lite_r4" to MpvShaderAsset(R.raw.quality_ravu_lite_r4_ee844074, "quality_ravu_lite_r4_ee844074.glsl", true, hdrSafe = true),
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
    // Same sources hooked on OUTPUT for HDR sources: after tone mapping the grain no longer
    // depends on the PQ/HLG code values (LUMA grain in PQ would be far too strong in highlights).
    // An OUTPUT hook reads rendered RGB, so it needs no decoder copy-back. SDR keeps the LUMA
    // hook: on OUTPUT the grain would be generated at display size (finer when upscaling).
    "filmgrain_light_output" to MpvShaderAsset(R.raw.quality_filmgrain_light_4aee719a, "quality_filmgrain_light_4aee719a.glsl", false,
        hdrSafe = true, outputHook = true, cacheFileName = "quality_filmgrain_light_4aee719a_output.glsl"),
    "filmgrain_medium_output" to MpvShaderAsset(R.raw.quality_filmgrain_medium_4921afc1, "quality_filmgrain_medium_4921afc1.glsl", false,
        hdrSafe = true, outputHook = true, cacheFileName = "quality_filmgrain_medium_4921afc1_output.glsl"),
    "cfl_lite" to MpvShaderAsset(R.raw.quality_cfl_lite_159c1dad, "quality_cfl_lite_159c1dad.glsl", true, requiresGpuNext = true),
    "cfl" to MpvShaderAsset(R.raw.quality_cfl_f9d12d38, "quality_cfl_f9d12d38.glsl", true, requiresGpuNext = true),
    "ssim_down" to MpvShaderAsset(R.raw.quality_ssim_down_f46f4710, "quality_ssim_down_f46f4710.glsl", false),
    "ssim_super" to MpvShaderAsset(R.raw.quality_ssim_super_a8b27115, "quality_ssim_super_a8b27115.glsl", false),
    "adaptive" to MpvShaderAsset(R.raw.quality_adaptive_44e81f12, "quality_adaptive_44e81f12.glsl", false, hdrSafe = true),
    "fsr" to MpvShaderAsset(R.raw.quality_fsr_56d8597f, "quality_fsr_56d8597f.glsl", true, hdrSafe = true),
    "nis" to MpvShaderAsset(
        R.raw.quality_nis_nvscaler_bac778ec,
        "quality_nis_nvscaler_bac778ec.glsl",
        true,
        requiresCompute = true,
    ),
    "cas" to MpvShaderAsset(
        R.raw.quality_cas_a945e104,
        "quality_cas_a945e104.glsl",
        true,
        transferAware = true,
        cacheFileName = "quality_cas_939ed74a.glsl",
        hdrSafe = true,
    ),
    // Built-in sharpen Light/Medium on vo=gpu-next, where mpv's --sharpen has no effect.
    "cas_light" to MpvShaderAsset(
        R.raw.quality_cas_a945e104,
        "quality_cas_a945e104.glsl",
        true,
        transferAware = true,
        cacheFileName = "quality_cas_939ed74a_b50.glsl",
        hdrSafe = true,
        casBlend = MPV_CAS_LIGHT_BLEND,
    ),
    "cas_medium" to MpvShaderAsset(
        R.raw.quality_cas_a945e104,
        "quality_cas_a945e104.glsl",
        true,
        transferAware = true,
        cacheFileName = "quality_cas_939ed74a_b80.glsl",
        hdrSafe = true,
        casBlend = MPV_CAS_MEDIUM_BLEND,
    ),
)

/**
 * CAS blends standing in for mpv's unsharp mask (sharpen=0.2 / 0.5) on gpu-next. The plain
 * "AMD CAS" choice is the full CAS result (blend 1.0), so Light < Medium < AMD CAS.
 */
internal const val MPV_CAS_LIGHT_BLEND = "0.5"
internal const val MPV_CAS_MEDIUM_BLEND = "0.8"

/** CAS keys whose materialised file depends on the source transfer. */
internal fun mpvTransferAwareShaderLoaded(keys: Collection<String>): Boolean =
    keys.any { MPV_IMAGE_SHADERS[it]?.transferAware == true }

/** Shader-based upscalers usable on an HDR source; the UI marks them experimental. */
internal val MPV_HDR_UPSCALERS: Set<String> get() =
    com.ambient.tv.data.local.VideoImageCatalog.mpvShaders.keys.filterTo(linkedSetOf()) { MPV_IMAGE_SHADERS[it]?.hdrSafe == true }

/** Film grain source hooked on OUTPUT instead of LUMA (same noise on R, G and B = luma grain). */
internal fun mpvOutputGrainSource(source: String): String =
    source.replace("//!HOOK LUMA", "//!HOOK OUTPUT")
        .replace("//!DESC Ambient TV film grain (", "//!DESC Ambient TV film grain, output (")
        .replace("color.x += INTENSITY * 2.0 * g;", "color.rgb += vec3(INTENSITY * 2.0 * g);")

/**
 * CAS source with the given blend. The single-pass HDR shader has a `CAS_BLEND` define; the
 * pinned upstream two-pass source gets the define and the mix injected (its file stays untouched).
 */
internal fun mpvCasBlendSource(source: String, blend: String): String =
    if ("#define CAS_BLEND 1.0" in source) source.replace("#define CAS_BLEND 1.0", "#define CAS_BLEND $blend")
    else source
        .replace("#define CAS_BETTER_DIAGONALS 1", "#define CAS_BLEND $blend\n#define CAS_BETTER_DIAGONALS 1")
        .replace("\tpix.r = clamp(pix.r, 0.0, 1.0);", "\tpix.r = mix(e, pix.r, float(CAS_BLEND));\n\tpix.r = clamp(pix.r, 0.0, 1.0);")

/** Bundled source for a transfer-aware shader: PQ/HLG use the single-pass HDR CAS. */
internal fun mpvCasSourceFile(shader: MpvShaderAsset, profile: MpvCasTransferProfile?): String =
    if (profile?.singlePassHdr == true) MPV_CAS_HDR_FILE else shader.fileName

/** A cached CAS file carries exactly the requested curve, range and blend. */
internal fun mpvCasSourceValid(source: String, profile: MpvCasTransferProfile, blend: String?): Boolean =
    ("#define SOURCE_TRC " + profile.sourceTrc) in source &&
        ("#define TARGET_TRC " + profile.targetTrc) in source &&
        ("#define CUSTOM_GAMMA " + profile.customGamma) in source &&
        (blend == null || "#define CAS_BLEND $blend" in source) &&
        (!profile.singlePassHdr || ("#define LIMITED_RANGE " + (if (profile.fullRange) 0 else 1)) in source)

/**
 * Text written to the shader cache: CAS gets its transfer curve (and Light/Medium blend; the HDR
 * single-pass variant also its range), ArtCNN its OpenGL variant, OUTPUT grain its hook change.
 * Other sources stay byte-identical.
 */
internal fun mpvShaderSourceFor(
    shader: MpvShaderAsset,
    source: String,
    transferProfile: MpvCasTransferProfile?,
    artCnnOpenGl: Boolean,
): String = when {
    transferProfile != null -> source
        .replace("#define SOURCE_TRC 4", "#define SOURCE_TRC " + transferProfile.sourceTrc)
        .replace("#define TARGET_TRC 4", "#define TARGET_TRC " + transferProfile.targetTrc)
        .replace("#define CUSTOM_GAMMA 2.2", "#define CUSTOM_GAMMA " + transferProfile.customGamma)
        .let { if (transferProfile.singlePassHdr && transferProfile.fullRange) it.replace("#define LIMITED_RANGE 1", "#define LIMITED_RANGE 0") else it }
        .let { cas -> shader.casBlend?.let { mpvCasBlendSource(cas, it) } ?: cas }
    artCnnOpenGl -> artCnnOpenGlSource(source)
    shader.outputHook -> mpvOutputGrainSource(source)
    else -> source
}
