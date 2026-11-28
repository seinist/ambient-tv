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
        val compatible = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        // Media3 already orders device-specific decoder workarounds. Do not rank vendor over
        // non-vendor codecs or override its secure/tunneling and audio preferences.
        if (!mimeType.startsWith("video/") || requiresSecureDecoder || requiresTunnelingDecoder) return compatible
        return preferHardwareVideoDecoders(compatible, { it.hardwareAccelerated }, { it.softwareOnly })
    }
}

/** Stable partition: preserve Media3's device-specific order within each acceleration tier. */
internal fun <T> preferHardwareVideoDecoders(
    codecs: List<T>, hardware: (T) -> Boolean, software: (T) -> Boolean,
): List<T> {
    if (codecs.size < 2) return codecs
    val preferred = codecs.filter { hardware(it) && !software(it) }
    if (preferred.isEmpty() || preferred.size == codecs.size) return codecs
    return preferred + codecs.filterNot { hardware(it) && !software(it) }
}
