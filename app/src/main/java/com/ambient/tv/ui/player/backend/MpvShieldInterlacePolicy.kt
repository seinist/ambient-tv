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
 * Live TS reports interlaced channels at the field rate ([mpvFieldRateInput]). 720p50 shares that
 * rate but is progressive, so the coded height decides: 1080/1088 lines or SD (≤ 576).
 */
internal fun mpvLiveInterlacedSource(liveStream: Boolean, containerFps: Double?, height: Int?): Boolean =
    liveStream && mpvFieldRateInput(containerFps) && height != null &&
        (height in 1080..1088 || height in 1..576)

/** Only OpenGL (vo=gpu) on a SHIELD with live interlaced content takes the zero-copy decoder. */
internal fun mpvShieldZeroCopyEligible(shield: Boolean, plan: MpvAccelerationPlan, liveStream: Boolean): Boolean =
    shield && liveStream && plan.output == "gpu" && !plan.vulkan && plan.decoder != "software" && plan.decoder != "vulkan"

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
