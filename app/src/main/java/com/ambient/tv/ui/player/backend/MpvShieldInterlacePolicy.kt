package com.ambient.tv.ui.player.backend

/**
 * NVIDIA SHIELD: MediaCodec's ByteBuffer output (hwdec=mediacodec-copy) of interlaced broadcast
 * (1080i H.264, 576i MPEG-2) reaches vo=gpu as a black picture, while every Surface consumer
 * (MPV direct, VLC, Media3) shows it. On this one path the decoder renders into mpv's
 * AImageReader interop (hwdec=mediacodec, driver "aimagereader"): zero-copy, the vendor
 * deinterlaces like on the direct path. Phones, progressive sources, Vulkan and direct output
 * keep their existing decoder.
 */
private val SHIELD_DEVICES = setOf("foster", "darcy", "mdarcy", "sif")

internal fun isNvidiaShield(manufacturer: String?, model: String?, device: String?): Boolean =
    manufacturer.equals("NVIDIA", ignoreCase = true) &&
        (model?.contains("SHIELD", ignoreCase = true) == true || device?.lowercase() in SHIELD_DEVICES)

internal val currentDeviceIsShield: Boolean by lazy {
    isNvidiaShield(android.os.Build.MANUFACTURER, android.os.Build.MODEL, android.os.Build.DEVICE)
}

/**
 * What the source says about interlacing. [frameInterlaced] is mpv's per-frame flag
 * (video-frame-info/interlaced); it is only trusted when the decoder fills it, i.e. software or
 * copy decoding of the codec itself ([frameFlagReliable]). MediaCodec output never carries it.
 */
internal data class MpvInterlaceSignals(
    val codec: String?,
    val frameInterlaced: Boolean?,
    val frameFlagReliable: Boolean,
    val fps: Double?,
    val height: Int?,
)

/** Codecs broadcast never codes as interlaced fields (DVB HEVC/AV1 is progressive). */
private val PROGRESSIVE_ONLY_CODECS = listOf("hevc", "h265", "av1", "vp9", "vp8")

internal fun mpvCodecProgressiveOnly(codec: String?): Boolean {
    val name = codec?.lowercase() ?: return false
    return PROGRESSIVE_ONLY_CODECS.any { it in name }
}

/**
 * Interlace verdict for broadcast sources (live TV and TVHeadend recordings).
 * 1. Progressive-only codecs (HEVC 1080p50) are never interlaced.
 * 2. A reliable decoder flag decides.
 * 3. Otherwise MPEG-2/H.264 cadence: field rate (50/59.94) at 1080 or SD lines, or SD at the
 *    frame rate (576i/480i reported as 25/29.97). 720p50 and 1080p25 stay progressive.
 */
internal fun mpvBroadcastInterlacedSource(broadcast: Boolean, signals: MpvInterlaceSignals): Boolean {
    if (!broadcast) return false
    if (mpvCodecProgressiveOnly(signals.codec)) return false
    if (signals.frameInterlaced == true) return true
    if (signals.frameFlagReliable && signals.frameInterlaced == false) return false
    val fps = signals.fps?.takeIf { it.isFinite() && it > 0.0 } ?: return false
    val height = signals.height?.takeIf { it > 0 } ?: return false
    val sd = height <= 576
    return when {
        mpvFieldRateInput(fps) -> height in 1080..1088 || sd
        else -> sd && fps in 24.5..30.5
    }
}

/** Kept for callers that only know cadence; equals [mpvBroadcastInterlacedSource] without flags. */
internal fun mpvLiveInterlacedSource(liveStream: Boolean, containerFps: Double?, height: Int?, codec: String? = null): Boolean =
    mpvBroadcastInterlacedSource(liveStream, MpvInterlaceSignals(codec, null, false, containerFps, height))

/** Only OpenGL (vo=gpu) on a SHIELD with interlaced broadcast (live or recording) takes the zero-copy decoder. */
internal fun mpvShieldZeroCopyEligible(shield: Boolean, plan: MpvAccelerationPlan, broadcast: Boolean): Boolean =
    shield && broadcast && plan.output == "gpu" && !plan.vulkan && plan.decoder != "software" && plan.decoder != "vulkan"

internal const val MPV_ZERO_COPY_HWDEC = "mediacodec"

/** Shaders that sample YUV planes (LUMA/CHROMA hooks) need copied frames; opaque RGB cannot feed them. */
internal fun mpvZeroCopyShaderKeys(keys: List<String>): List<String> =
    keys.filterNot { MPV_IMAGE_SHADERS[it]?.requiresCopyBack == true }

/** Shader keys a zero-copy session drops (shown honestly in the image panel). */
internal fun mpvZeroCopySkippedShaders(keys: List<String>): List<String> =
    keys.filter { MPV_IMAGE_SHADERS[it]?.requiresCopyBack == true }

/** CPU vf (SVP, lavfi deinterlace, CPU image filters) need software frames: none on this path. */
internal fun mpvZeroCopyCpuFilters(filters: Map<String, String>, zeroCopy: Boolean): Map<String, String> =
    if (zeroCopy) emptyMap() else filters

/**
 * Zero-copy removes every CPU filter, plex-svp included, for the interlaced source. Report that
 * as a format passthrough instead of a filter that is forever "starting".
 */
internal fun mpvZeroCopySvpReason(zeroCopy: Boolean): SvpInactiveReason? =
    if (zeroCopy) SvpInactiveReason.FORMAT else null

/** Image-panel / status key while the SHIELD interlaced zero-copy path is active. */
internal const val MPV_ZERO_COPY_WARNING = "shield-interlaced-zero-copy"

/**
 * Channels already identified as interlaced in this process start zero-copy before loadfile,
 * so only the first tune of a channel needs the one reopen.
 */
internal object MpvShieldInterlacedChannels {
    private val known = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    fun remember(url: String) { known += url }
    fun isKnown(url: String): Boolean = url in known
    internal fun clear() = known.clear()
}
