package com.ambient.tv.ui.player.backend

/**
 * NVIDIA SHIELD: MediaCodec's ByteBuffer output (hwdec=mediacodec-copy) of interlaced broadcast
 * (1080i H.264, 576i MPEG-2) can reach vo=gpu as a black picture, and gpu-next/Vulkan (which
 * always decodes through that copy path) as a blocky picture whose fields are never woven
 * together. Those frames also carry no interlace flag, so automatic deinterlacing never starts.
 * AImageReader is not a replacement: it still depends on the vendor surface producer, and its
 * opaque RGB output cannot feed LUMA/CHROMA shaders or CPU filters. Suspect broadcast sources
 * instead reopen with software decoding while keeping the OpenGL or Vulkan renderer and the
 * selected filters. Direct output, Vulkan Video decoding, other devices and progressive-only
 * codecs keep their decoder.
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

/** Scope of the vendor decoder workaround; never override explicit software or Vulkan decoding. */
internal fun mpvShieldSoftwareEligible(shield: Boolean, plan: MpvAccelerationPlan, broadcast: Boolean): Boolean =
    shield && broadcast && plan.gpu && plan.decoder != "software" && plan.decoder != "vulkan"

/**
 * MediaCodec has no trustworthy interlace flag. At 1080/1088 lines, 25/29.97 fps can mean
 * progressive frames OR interlaced frame pairs. Treat those ambiguous H.264/MPEG-2 broadcasts
 * as candidates too; a false negative leaves a black picture. This is a decoder workaround,
 * not a claim that the stream is interlaced: automatic deinterlacing uses software frame flags.
 * Unknown codec/cadence/size waits for metadata; 720p and UHD are not candidates.
 */
internal fun mpvShieldSoftwareRequired(signals: MpvInterlaceSignals): Boolean {
    val codec = signals.codec?.lowercase() ?: return false
    if (mpvCodecProgressiveOnly(codec)) return false
    if (listOf("h264", "h.264", "avc", "mpeg2", "mpeg-2").none { it in codec }) return false
    if (signals.frameFlagReliable && signals.frameInterlaced == false) return false
    val height = signals.height ?: return false
    if (height !in 1..576 && height !in 1080..1088) return false
    if (signals.frameInterlaced == true) return true
    val fps = signals.fps ?: return false
    return fps.isFinite() && (fps in 24.5..30.5 || fps in 49.0..60.5)
}

internal const val MPV_SHIELD_SOFTWARE_WARNING = "shield-broadcast-software"
