package com.ambient.tv.ui.player.backend

/**
 * OpenGL (vo=gpu) needs CPU-accessible frames. Opaque MediaCodec output can leave a black
 * plane after software→hardware switches; always copy back on this path.
 * [lumaShaderLoaded]/[deinterlaceEnabled] are retained for call-site compatibility.
 */
@Suppress("UNUSED_PARAMETER")
internal fun mpvGpuDecoderMode(lumaShaderLoaded: Boolean, deinterlaceEnabled: Boolean): String =
    "mediacodec-copy"

internal enum class MpvComputeSupport {
    SUPPORTED,
    UNKNOWN,
    UNSUPPORTED,
}

internal fun mpvComputeSupport(gpuApi: String?): MpvComputeSupport = when (gpuApi?.trim()?.lowercase()) {
    "vulkan" -> MpvComputeSupport.SUPPORTED
    "opengl", "opengl-es", "opengles" -> MpvComputeSupport.UNKNOWN
    null, "" -> MpvComputeSupport.UNKNOWN
    else -> MpvComputeSupport.UNSUPPORTED
}

/** Shaders flagged [MpvShaderAsset.requiresGpuNext] only compile with libplacebo on Vulkan. */
internal fun mpvGpuNextVulkan(vo: String?, gpuApi: String?): Boolean =
    vo?.trim()?.lowercase() == "gpu-next" && gpuApi?.trim()?.lowercase() == "vulkan"

/** TVHeadend stores one common VLC-oriented vocabulary; MPV gets the closest exact mode. */
internal fun tvheadendMpvDeinterlaceMode(key: String): String =
    key.takeIf { it in setOf("off", "auto", "on") } ?: "off"

internal fun tvheadendMpvDeinterlaceFilter(key: String): String = when (key) {
    "yadif2x", "bob" -> "yadif2x"
    else -> com.ambient.tv.data.local.DeinterlaceCatalog.normalize("mpv", key)
}
