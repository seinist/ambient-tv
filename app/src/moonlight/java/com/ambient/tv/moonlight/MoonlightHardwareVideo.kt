package com.ambient.tv.moonlight

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.limelight.nvstream.jni.MoonBridge

/** Query real hardware decoder limits before requesting a codec from Sunshine. */
internal object MoonlightHardwareVideo {
    private val profiles = listOf(
        "av1" to (MediaFormat.MIMETYPE_VIDEO_AV1 to MoonBridge.VIDEO_FORMAT_AV1_MAIN8),
        "hevc" to (MediaFormat.MIMETYPE_VIDEO_HEVC to MoonBridge.VIDEO_FORMAT_H265),
        "h264" to (MediaFormat.MIMETYPE_VIDEO_AVC to MoonBridge.VIDEO_FORMAT_H264),
    )

    internal fun decoder(mime: String, width: Int, height: Int, fps: Int): MediaCodecInfo? =
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { codec ->
                !codec.isEncoder && codec.isHardwareAccelerated &&
                    codec.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    runCatching {
                        codec.getCapabilitiesForType(mime).videoCapabilities
                            ?.areSizeAndRateSupported(width, height, fps.toDouble()) == true
                    }.getOrDefault(false)
            }
        }.getOrNull()

    /**
     * Moonlight 10-bit/HDR is deliberately not advertised until color metadata and display
     * ownership have passed device tests. Sending Main10 to an 8-bit codec causes corrupt colors.
     */
    fun supportedFormat(profile: MoonlightStreamProfile): Int {
        val valid = profile.validated()
        val choices = if (valid.codec == "auto") profiles else profiles.filter { it.first == valid.codec }
        return choices.firstNotNullOfOrNull { (_, pair) ->
            if (decoder(pair.first, valid.width, valid.height, valid.fps) != null) pair.second else null
        } ?: 0
    }

    fun mimeFor(format: Int): String? = when {
        (format and MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0 -> null
        (format and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0 -> MediaFormat.MIMETYPE_VIDEO_AV1
        (format and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0 -> MediaFormat.MIMETYPE_VIDEO_HEVC
        (format and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0 -> MediaFormat.MIMETYPE_VIDEO_AVC
        else -> null
    }
}
