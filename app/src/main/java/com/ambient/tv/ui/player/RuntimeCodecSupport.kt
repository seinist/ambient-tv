package com.ambient.tv.ui.player

import android.media.MediaCodecList
import androidx.media3.common.Format

data class RuntimeDecoderAssessment(
    val mimeType: String?,
    val decoderName: String?,
    val hardwareAccelerated: Boolean,
    val supported: Boolean,
)

object RuntimeCodecSupport {
    /** True when the device has any hardware video decoder. Software decoding is then only
     * mpv's automatic per-stream fallback, never a user choice. */
    val hasHardwareVideoDecoder: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.startsWith("video/") }
            }
        }.getOrDefault(true)
    }

    fun assess(format: Format?): RuntimeDecoderAssessment {
        val mime = format?.sampleMimeType
        if (mime.isNullOrBlank()) return RuntimeDecoderAssessment(null, null, false, false)
        val match = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .firstOrNull { info ->
                    runCatching {
                        val caps = info.getCapabilitiesForType(mime)
                        val videoCapabilities = caps.videoCapabilities
                        val videoOk = if ((format.width > 0 || format.height > 0) && videoCapabilities != null) {
                            val w = format.width.takeIf { it > 0 } ?: 1
                            val h = format.height.takeIf { it > 0 } ?: 1
                            videoCapabilities.isSizeSupported(w, h)
                        } else true

                        val audioCapabilities = caps.audioCapabilities
                        val audioOk = if (format.channelCount > 0 && audioCapabilities != null) {
                            val max = audioCapabilities.maxInputChannelCount
                            max <= 1 || format.channelCount <= max
                        } else true
                        videoOk && audioOk
                    }.getOrDefault(true)
                }
        }.getOrNull()
        return RuntimeDecoderAssessment(
            mimeType = mime,
            decoderName = match?.name,
            hardwareAccelerated = match?.isHardwareAccelerated == true,
            supported = match != null,
        )
    }
}
