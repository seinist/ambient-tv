@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.media3.common.MimeTypes
import com.ambient.tv.domain.model.MediaVersion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight device pre-flight. Media3 remains authoritative at runtime, but this avoids
 * obviously impossible Direct Play attempts (for example 4K HEVC on a decoder limited to HD).
 */
@Singleton
class DeviceCodecCapabilities @Inject constructor() {

    data class Assessment(
        val videoCodec: String?,
        val audioCodec: String?,
        val videoSupported: Boolean,
        val audioSupported: Boolean,
        val hardwareVideoDecoder: Boolean,
        val hardwareAudioDecoder: Boolean,
        val videoMimeType: String?,
        val audioMimeType: String?,
    ) {
        val videoFallbackRecommended: Boolean
            get() = !videoSupported && videoMimeType != null

        val audioOnlyFallbackRecommended: Boolean
            get() = videoSupported && !audioSupported && audioMimeType != null
    }

    fun assess(version: MediaVersion): Assessment {
        val selectedAudio = version.selectedAudioTrack
        val audioCodec = selectedAudio?.codec ?: version.audioCodec
        val videoMime = videoMimeType(version.videoCodec, version.videoDynamicRange)
        val audioMime = audioMimeType(audioCodec)
        val video = decoderSupport(
            mimeType = videoMime,
            width = version.width,
            height = version.height,
        )
        val audio = decoderSupport(
            mimeType = audioMime,
            channelCount = selectedAudio?.channels,
        )

        // Unknown codec labels remain optimistic: Direct Play is the default and Media3 gets
        // the final runtime decision instead of us forcing an unnecessary server transcode.
        return Assessment(
            videoCodec = version.videoCodec,
            audioCodec = audioCodec,
            videoSupported = videoMime == null || video.anyDecoder,
            audioSupported = audioMime == null || audio.anyDecoder,
            hardwareVideoDecoder = video.hardwareDecoder,
            hardwareAudioDecoder = audio.hardwareDecoder,
            videoMimeType = videoMime,
            audioMimeType = audioMime,
        )
    }

    private data class DecoderSupport(val anyDecoder: Boolean, val hardwareDecoder: Boolean)

    private fun decoderSupport(
        mimeType: String?,
        width: Int? = null,
        height: Int? = null,
        channelCount: Int? = null,
    ): DecoderSupport {
        if (mimeType == null) return DecoderSupport(anyDecoder = false, hardwareDecoder = false)

        val compatible = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filterNot { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
            .filter { info -> supportsRequestedFormat(info, mimeType, width, height, channelCount) }
            .toList()

        return DecoderSupport(
            anyDecoder = compatible.isNotEmpty(),
            hardwareDecoder = compatible.any { it.isHardwareAccelerated },
        )
    }

    private fun supportsRequestedFormat(
        codecInfo: MediaCodecInfo,
        mimeType: String,
        width: Int?,
        height: Int?,
        channelCount: Int?,
    ): Boolean = runCatching {
        val caps = codecInfo.getCapabilitiesForType(mimeType)
        if (width != null && height != null && width > 0 && height > 0) {
            val videoCaps = caps.videoCapabilities ?: return@runCatching false
            if (!videoCaps.isSizeSupported(width, height)) return@runCatching false
        }
        if (channelCount != null && channelCount > 0) {
            val audioCaps = caps.audioCapabilities ?: return@runCatching false
            // Some platform codecs report 0/1 despite supporting more channels via adaptive
            // paths. Only reject when Android reports a meaningful limit > 1.
            val maxChannels = audioCaps.maxInputChannelCount
            if (maxChannels > 1 && channelCount > maxChannels) return@runCatching false
        }
        true
    }.getOrDefault(false)

    private fun videoMimeType(codec: String?, dynamicRange: String?): String? {
        val range = dynamicRange?.lowercase().orEmpty()
        if (range.contains("dolby vision") || range.contains("dolbyvision") || range.contains("dovi")) {
            return MimeTypes.VIDEO_DOLBY_VISION
        }
        return when (codec?.lowercase()) {
        "h264", "avc" -> MimeTypes.VIDEO_H264
        "hevc", "h265" -> MimeTypes.VIDEO_H265
        "dovi", "dvhe", "dolbyvision", "dolby-vision" -> MimeTypes.VIDEO_DOLBY_VISION
        "av1" -> MimeTypes.VIDEO_AV1
        "vp9" -> MimeTypes.VIDEO_VP9
        "vp8" -> MimeTypes.VIDEO_VP8
        "mpeg2video", "mpeg2" -> MimeTypes.VIDEO_MPEG2
        "mpeg4", "mpeg4video" -> MimeTypes.VIDEO_MP4V
        else -> null
        }
    }

    private fun audioMimeType(codec: String?): String? = when (codec?.lowercase()) {
        "aac", "aac_latm" -> MimeTypes.AUDIO_AAC
        "ac3" -> MimeTypes.AUDIO_AC3
        "eac3", "eac3_eac3" -> MimeTypes.AUDIO_E_AC3
        "eac3_joc", "eac3-joc", "e-ac-3-joc", "atmos" -> MimeTypes.AUDIO_E_AC3_JOC
        "truehd" -> MimeTypes.AUDIO_TRUEHD
        "dca", "dts", "dts-es" -> MimeTypes.AUDIO_DTS
        "dca-ma", "dts-hd", "dts-hra" -> MimeTypes.AUDIO_DTS_HD
        "flac" -> MimeTypes.AUDIO_FLAC
        "mp3", "mp2" -> MimeTypes.AUDIO_MPEG
        "opus" -> MimeTypes.AUDIO_OPUS
        "vorbis" -> MimeTypes.AUDIO_VORBIS
        "alac" -> MimeTypes.AUDIO_ALAC
        "pcm", "lpcm", "s16le", "s24le" -> MimeTypes.AUDIO_RAW
        else -> null
    }
}
