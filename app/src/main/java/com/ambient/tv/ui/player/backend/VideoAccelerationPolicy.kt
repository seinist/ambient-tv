package com.ambient.tv.ui.player.backend

import kotlinx.serialization.Serializable
import com.ambient.tv.data.local.MpvImageOptions

internal fun mpvNeedsGpuProcessing(o: MpvImageOptions): Boolean =
    o.scaler != "bilinear" || o.chromaScaler != "bilinear" || o.downscaler != "bilinear" ||
        o.deband != "off" || o.sharpening != "off" || o.denoise != "off" || o.deblock != "off" || o.filmGrain != "off" ||
        o.superResolution || o.antiring > 0 || o.sigmoid || o.svpMode != "off" ||
        o.brightness != 0 || o.contrast != 0 || o.saturation != 0 || o.gamma != 0 ||
        o.toneMapping != "auto" || o.ditherDepth !in setOf("auto", "no")

@Serializable
data class VulkanDecodeProfile(val codec: String, val bits: Int, val width: Int, val height: Int)

@Serializable
data class VulkanSurfaceFormat(val format: Int, val colorSpace: Int)

@Serializable
data class VulkanDeviceCapabilities(
    val render: Boolean = false,
    val device: String = "",
    val version: Long = 0,
    val profiles: List<VulkanDecodeProfile> = emptyList(),
    val reason: String = "probe_unavailable",
    val hostQueryReset: Boolean = false,
    // Present only in newer probes; absence must not be mistaken for a negative result.
    val surfaceColorSpaces: List<Int>? = null,
    val surfaceFormats: List<VulkanSurfaceFormat>? = null,
) {
    val hdr10SurfaceSupported: Boolean? get() = surfaceColorSpaces?.contains(1000104008)
}

data class VideoAccelerationCapabilities(
    val checked: Boolean = false,
    val device: VulkanDeviceCapabilities = VulkanDeviceCapabilities(),
    val mpvOutputBuilt: Boolean = false,
    val mpvDecodeBuilt: Boolean = false,
    val vlcOutputBuilt: Boolean = false,
    /** EGL offers EGL_EXT_gl_colorspace_bt2020_pq plus a 10-bit RGB window config ([MpvEglHdrProbe]). */
    val eglHdr10Pq: Boolean = false,
) {
    val mpvVulkanOutput get() = checked && device.render && device.hostQueryReset && device.version >= ((1L shl 22) or (2L shl 12)) && mpvOutputBuilt
    val mpvVulkanDecode get() = mpvVulkanOutput && mpvDecodeBuilt && device.profiles.isNotEmpty()
    val vlcVulkanOutput get() = checked && device.render && vlcOutputBuilt
}

/**
 * MPV image work the user actually chose: what still needs a GPU after untouched defaults are
 * replaced by their cheap variant ([effectiveMpvImageOptions] with cheapDefaults). The factory
 * Lanczos/deband/sigmoid alone do not count; Auto then keeps the direct surface.
 */
internal fun mpvUserImageProcessing(o: MpvImageOptions, reducedEffects: Boolean, allowSvp: Boolean): Boolean =
    mpvNeedsGpuProcessing(effectiveMpvImageOptions(o, reducedEffects, allowSvp, cheapDefaults = true))

data class MpvAccelerationPlan(
    val output: String,
    val decoder: String,
    val fallback: String? = null,
    /**
     * vo=gpu may sample MediaCodec output through AImageReader (hwdec=mediacodec) instead of
     * copying every frame to system memory and uploading it again (mediacodec-copy). mpv falls
     * back to the next hwdec entry when the interop is unavailable. Off after a recorded failure.
     */
    val zeroCopy: Boolean = false,
) {
    val vulkan get() = output == "vulkan"
    val gpu get() = output == "gpu" || vulkan
    val vo get() = when (output) { "vulkan" -> "gpu-next"; "gpu" -> "gpu"; else -> "mediacodec_embed" }
    val context get() = if (vulkan) "androidvk" else "android"
    val hwdec get() = when {
        decoder == "software" -> "no"
        decoder == "vulkan" -> "vulkan,mediacodec-copy"
        // gpu-next/Vulkan has no MediaCodec interop and needs CPU-accessible frames.
        vulkan -> "mediacodec-copy"
        // OpenGL: zero-copy through AImageReader first, mpv falls back to copy-back itself.
        // A runtime copy→zero-copy switch is never made (black plane after a decoder switch).
        output == "gpu" -> if (zeroCopy) MPV_ZERO_COPY_HWDEC else "mediacodec-copy"
        else -> "mediacodec"
    }
    /**
     * Decoder for OpenGL with/without copy-back.
     * [cpuFrames]: a CPU filter needs system-memory frames. Vulkan-decoded images are sampled by
     * libplacebo directly, so shader-only copy-back does not force vulkan-copy.
     */
    fun hwdecForProcessing(copyBack: Boolean, deinterlace: Boolean, cpuFrames: Boolean = copyBack): String = when {
        decoder == "software" -> "no"
        decoder == "vulkan" && (cpuFrames || deinterlace) -> "vulkan-copy,mediacodec-copy"
        vulkan -> hwdec
        copyBack || deinterlace -> "mediacodec-copy"
        else -> hwdec
    }
}

internal const val MPV_ZERO_COPY_HWDEC = "mediacodec,mediacodec-copy"

/** hwdec-current of a zero-copy plan that ended on copy-back: the AImageReader interop failed. */
internal fun mpvZeroCopyInteropFailed(plan: MpvAccelerationPlan, hwdecCurrent: String?): Boolean =
    plan.zeroCopy && plan.output == "gpu" && hwdecCurrent?.trim() == "mediacodec-copy"

/** Effective decoder path for stats: zero-copy, copy-back, Vulkan or software. */
internal fun mpvDecoderPathLabel(hwdecCurrent: String?): String = when (hwdecCurrent?.trim()) {
    null, "", "no" -> "Software"
    "mediacodec" -> "MediaCodec (zero-copy)"
    "mediacodec-copy" -> "MediaCodec (copy-back)"
    "vulkan" -> "Vulkan Video (zero-copy)"
    "vulkan-copy" -> "Vulkan Video (copy-back)"
    else -> hwdecCurrent.trim()
}

/** Software is an emergency path only: with a hardware decoder present a stored "software" acts as Auto. */
fun mpvDecoderChoices(hardwareDecoder: Boolean): List<String> =
    if (hardwareDecoder) listOf("auto", "mediacodec", "vulkan") else listOf("auto", "mediacodec", "vulkan", "software")

fun effectiveMpvDecoder(decoder: String, hardwareDecoder: Boolean): String =
    if (decoder == "software" && hardwareDecoder) "auto" else decoder

internal fun fallbackMpvAcceleration(current: MpvAccelerationPlan, reason: String): MpvAccelerationPlan =
    // OpenGL accepts both hardware-copy and software frames; an embedded MediaCodec Surface cannot.
    MpvAccelerationPlan("gpu", if (current.decoder == "software") "software" else "mediacodec", reason)

/** Device + bundled backend capability intersection. FFmpeg validates the exact stream profile
 * and Vulkan video formats/session limits before accepting hwdec=vulkan, then tries MediaCodec.
 * Auto preserves direct presentation unless GPU processing was requested. */
fun planMpvAcceleration(
    output: String,
    decoder: String,
    capabilities: VideoAccelerationCapabilities,
    gpuProcessing: Boolean = false,
    forceDefault: Boolean = false,
    nativeHdrPreferred: Boolean = false,
    /** [mpvHdrGpuDecision] chose gpu-next/Vulkan passthrough for HDR10 on an HDR10 display. */
    hdrPassthrough: Boolean = false,
    /** HDR that cannot be presented natively needs a shader-capable output, even with neutral filters. */
    toneMappingRequired: Boolean = false,
    /** vo=gpu may try zero-copy MediaCodec ([MpvAccelerationPlan.zeroCopy]); false after a failure. */
    zeroCopy: Boolean = false,
): MpvAccelerationPlan = planMpvAccelerationPath(output, decoder, capabilities, gpuProcessing, forceDefault,
    nativeHdrPreferred, hdrPassthrough, toneMappingRequired).let { plan ->
    if (zeroCopy && !hdrPassthrough && plan.output == "gpu" && plan.decoder == "mediacodec" && plan.fallback == null) plan.copy(zeroCopy = true) else plan
}

private fun planMpvAccelerationPath(
    output: String,
    decoder: String,
    capabilities: VideoAccelerationCapabilities,
    gpuProcessing: Boolean,
    forceDefault: Boolean,
    nativeHdrPreferred: Boolean,
    hdrPassthrough: Boolean,
    toneMappingRequired: Boolean,
): MpvAccelerationPlan {
    // EGL PQ passthrough ([MpvHdrGpuDecision.openGl]): vo=gpu with copy-back frames (P010 stays 10-bit).
    if (hdrPassthrough && output == "gpu") return MpvAccelerationPlan("gpu", if (decoder == "software") "software" else "mediacodec")
    if (hdrPassthrough && capabilities.mpvVulkanOutput) return MpvAccelerationPlan("vulkan", when {
        decoder == "software" -> "software"
        decoder in setOf("auto", "vulkan") && capabilities.mpvVulkanDecode -> "vulkan"
        else -> "mediacodec"
    })
    // A GPU copy is not a verified Android HDR surface. Preserve the codec's native
    // HDR dataspace instead of sending PQ/BT.2020 pixels through an SDR vout.
    if (nativeHdrPreferred) return MpvAccelerationPlan("direct", "mediacodec",
        if (gpuProcessing || output !in setOf("auto", "direct") || decoder !in setOf("auto", "mediacodec")) "hdr_native_surface" else null)
    if (forceDefault) return fallbackMpvAcceleration(MpvAccelerationPlan(output, decoder), "runtime")
    var reason: String? = null
    var selectedOutput = when (output) {
        "vulkan" -> if (capabilities.mpvVulkanOutput) "vulkan" else "direct".also { reason = "output_unavailable" }
        "gpu" -> "gpu"
        "auto" -> if (gpuProcessing) { if (capabilities.mpvVulkanOutput) "vulkan" else "gpu" } else "direct"
        else -> "direct"
    }
    if (toneMappingRequired && selectedOutput == "direct") {
        selectedOutput = if (capabilities.mpvVulkanOutput) "vulkan" else "gpu"
        reason = "hdr_to_sdr"
    }
    // Auto and explicit MediaCodec always prefer hardware. Software is only used when chosen.
    var selectedDecoder = when (decoder) {
        "software" -> "software"
        "vulkan" -> if (capabilities.mpvVulkanDecode && selectedOutput in setOf("vulkan", "direct") && output != "direct") {
            selectedOutput = "vulkan"; "vulkan"
        } else "mediacodec".also { reason = "decode_unavailable" }
        "auto" -> if (selectedOutput == "vulkan" && capabilities.mpvVulkanDecode) "vulkan" else "mediacodec"
        else -> "mediacodec"
    }
    if (selectedDecoder == "software" && selectedOutput == "direct") selectedOutput = "gpu"
    return MpvAccelerationPlan(selectedOutput, selectedDecoder, reason)
}

fun useVlcVulkan(output: String, capabilities: VideoAccelerationCapabilities, gpuProcessing: Boolean = false,
    nativeHdrPreferred: Boolean = false): Boolean =
    !nativeHdrPreferred && capabilities.vlcVulkanOutput && (output == "vulkan" || output == "auto" && gpuProcessing)

/** Direct/opaque Android presentation bypasses VLC's GPU colorspace conversion. */
internal fun vlcHdrToSdrOutputOptions(vulkan: Boolean, toneMappingRequired: Boolean): List<String> =
    if (toneMappingRequired && !vulkan) listOf("--vout=gles2,none", "--no-mediacodec-dr")
    else emptyList()
