@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Keeps Media3's compatibility filtering, but prefers hardware accelerated MediaCodec decoders.
 *
 * Software codecs intentionally remain in the returned list. They are important on the Android
 * emulator and provide a last-resort fallback on devices with incomplete/broken vendor codecs.
 */
object HardwareFirstMediaCodecSelector : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        return MediaCodecSelector.DEFAULT
            .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            .sortedWith(
                compareByDescending<MediaCodecInfo> { it.hardwareAccelerated }
                    .thenBy { it.softwareOnly }
                    .thenByDescending { it.vendor },
            )
    }
}
