@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player.audio

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import com.ambient.tv.ui.player.AudioSession

/** Keeps Media3's video renderer (including Dolby Vision) and converts audio packets locally. */
internal class LocalAudioRenderer(
    handler: Handler, listener: AudioRendererEventListener, sink: AudioSink,
    private val session: AudioSession,
) : DecoderAudioRenderer<LocalAudioDecoder>(handler, listener, sink) {
    override fun getName() = "LocalAudioRenderer"

    private fun codec(format: Format): String? = when (format.sampleMimeType) {
        "audio/eac3", "audio/eac3-joc" -> "eac3"
        "audio/ac3" -> "ac3"
        "audio/true-hd" -> "truehd"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "dca"
        else -> null
    }

    private fun encode(format: Format): Boolean = localAc3Eligible(
        session.options.localFallback, session.options.output, session.options.channels,
        format.channelCount, "ac3" in session.route.passthrough && "ac3" in session.options.allowedCodecs,
    ) && !session.localEncoderFailed && !session.audioOnly

    private fun output(format: Format): Format = output(encode(format), if (encode(format)) 6 else pcmChannels(format))

    private fun output(ac3: Boolean, channels: Int): Format = Format.Builder()
        .setSampleMimeType(if (ac3) "audio/ac3" else "audio/raw")
        .setChannelCount(channels).setSampleRate(48000)
        .setPcmEncoding(C.ENCODING_PCM_16BIT).build()

    /** Planned PCM channel count; the native decoder accepts 2, 6 or 8. */
    private fun pcmChannels(format: Format): Int {
        val plan = session.pcmOptionsForAudioThread()
        return localPcmChannelCandidates(format.channelCount, session.route.pcmChannels, plan.channels)
            .firstOrNull { it == 2 || sinkSupportsFormat(output(false, it)) } ?: 2
    }

    private val main = Handler(android.os.Looper.getMainLooper())

    /** Compose state: only write it from the main thread, never from the playback thread. */
    private fun publishConversion(value: String?) { main.post { session.activeLocalConversion = value } }

    override fun supportsFormatInternal(format: Format): Int {
        val codec = codec(format) ?: return C.FORMAT_UNSUPPORTED_TYPE
        if (!LocalAudioBridge.AVAILABLE || !LocalAudioBridge.supports(codec) || session.options.localFallback == "off")
            return C.FORMAT_UNSUPPORTED_TYPE
        if (format.drmInitData != null) return C.FORMAT_UNSUPPORTED_DRM
        // Leave confirmed original bitstream to MediaCodecAudioRenderer.
        val key = com.ambient.tv.ui.player.Media3AudioProcessing.codecKey(format.sampleMimeType)
        if (session.options.permitsBitstream(session.sourcePassthrough) && key in session.route.passthrough &&
            key in session.options.allowedCodecs && key !in com.ambient.tv.ui.player.AudioCodecRejects.snapshot())
            return C.FORMAT_UNSUPPORTED_TYPE
        return if (sinkSupportsFormat(output(format))) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): LocalAudioDecoder {
        val ac3 = encode(format)
        return LocalAudioDecoder(requireNotNull(codec(format)), format, if (ac3) 6 else pcmChannels(format), ac3).also { decoder ->
            decoder.onReleased = Runnable { publishConversion(null) }
        }
    }
    override fun getOutputFormat(decoder: LocalAudioDecoder): Format {
        val input = decoder.inputFormat
        val output = decoder.outputFormat
        publishConversion(com.ambient.tv.ui.player.audioConversionFormats(
            input.sampleMimeType, input.channelCount, output.sampleMimeType, output.channelCount))
        return output
    }
}

internal fun localAc3Eligible(fallback: String, output: String, channels: String, sourceChannels: Int, ac3Supported: Boolean): Boolean =
    fallback == "auto" && output != "pcm" && channels !in setOf("mono", "left", "right", "stereo") &&
        sourceChannels > 2 && ac3Supported

/**
 * Channel counts the local decoder may output for a source, best first. 7.1 needs an 8-channel
 * source, route and plan; 5.1 a 6+ channel source and route; stereo is always last.
 */
internal fun localPcmChannelCandidates(sourceChannels: Int, routeChannels: Int, planned: String): List<Int> = buildList {
    if (sourceChannels >= 8 && routeChannels >= 8 && planned in setOf("auto", "7.1")) add(8)
    if (sourceChannels >= 6 && routeChannels >= 6 && planned in setOf("auto", "5.1", "7.1")) add(6)
    add(2)
}
