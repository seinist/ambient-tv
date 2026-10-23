@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player.audio

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import com.ambient.tv.ui.player.AudioOutputMode
import com.ambient.tv.ui.player.AudioSession

/**
 * Keeps Media3's video renderer (including Dolby Vision) and converts audio packets locally:
 * FFmpeg decode/AC3 encode when libambientaudio is present, DTS-HD → DTS core always.
 */
internal class LocalAudioRenderer(
    handler: Handler, listener: AudioRendererEventListener, sink: AudioSink,
    private val session: AudioSession,
) : DecoderAudioRenderer<Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>>(handler, listener, sink) {
    override fun getName() = "LocalAudioRenderer"

    private fun codec(format: Format): String? = localDecoderName(format.sampleMimeType)

    /** DTS-HD whose plan is DTS core: strip the extension instead of decoding. */
    private fun dtsCore(format: Format): Boolean = format.sampleMimeType == MimeTypes.AUDIO_DTS_HD &&
        session.outputPlanFor("dts-hd", format.channelCount.takeIf { it > 0 }).mode == AudioOutputMode.DTS_CORE

    private fun encode(format: Format): Boolean = localAc3Eligible(
        session.options.localFallback, session.options.output, session.options.channels,
        format.channelCount, "ac3" in session.route.passthrough && "ac3" in session.options.allowedCodecs,
    ) && !session.localEncoderFailed && !session.audioOnly && !session.forceStereoPcm

    private fun output(format: Format): Format = output(encode(format), if (encode(format)) 6 else pcmChannels(format))

    private fun output(ac3: Boolean, channels: Int): Format = Format.Builder()
        .setSampleMimeType(if (ac3) "audio/ac3" else "audio/raw")
        .setChannelCount(channels).setSampleRate(48000)
        .setPcmEncoding(C.ENCODING_PCM_16BIT).build()

    /** Planned PCM channel count; the native decoder accepts 2, 6 or 8. */
    private fun pcmChannels(format: Format): Int {
        val plan = session.pcmOptionsForAudioThread()
        // Keep center/surround samples until ProfileAudioProcessor applies the selected
        // downmix and limiter. The native bridge remaps named FFmpeg positions to this
        // canonical layout; decoding straight to S16 stereo would lose peak headroom.
        if (plan.channels in setOf("stereo", "mono", "left", "right"))
            return localDownmixInputChannels(format.channelCount)
        return localPcmChannelCandidates(format.channelCount, session.route.pcmChannels, plan.channels)
            .firstOrNull { it == 2 || sinkSupportsFormat(output(false, it)) } ?: 2
    }

    private val main = Handler(android.os.Looper.getMainLooper())

    /** Compose state: only write it from the main thread, never from the playback thread. */
    private fun publishConversion(value: String?) { main.post { session.activeLocalConversion = value } }

    override fun supportsFormatInternal(format: Format): Int {
        val codec = codec(format) ?: return C.FORMAT_UNSUPPORTED_TYPE
        if (dtsCore(format)) {
            if (format.drmInitData != null) return C.FORMAT_UNSUPPORTED_DRM
            return if (sinkSupportsFormat(dtsCoreOutputFormat(format))) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        if (!LocalAudioBridge.AVAILABLE || !LocalAudioBridge.supports(codec) || session.options.localFallback == "off")
            return C.FORMAT_UNSUPPORTED_TYPE
        if (format.drmInitData != null) return C.FORMAT_UNSUPPORTED_DRM
        // Leave confirmed original bitstream to MediaCodecAudioRenderer.
        val key = com.ambient.tv.ui.player.Media3AudioProcessing.codecKey(format.sampleMimeType)
        if (session.outputPlanFor(key, format.channelCount.takeIf { it > 0 }).mode == AudioOutputMode.PASSTHROUGH)
            return C.FORMAT_UNSUPPORTED_TYPE
        return if (sinkSupportsFormat(output(format))) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {
        if (dtsCore(format)) {
            val local = LocalAudioBridge.AVAILABLE && session.options.localFallback != "off" && LocalAudioBridge.supports("dca")
            return DtsHdDecoder(format, if (local) ({ localDecoder(format) }) else null) {
                // The plan follows on the playback thread at once; Compose state stays on main.
                com.ambient.tv.ui.player.AudioCodecRejects.markDtsCoreMissing()
            }
        }
        return localDecoder(format)
    }

    private fun localDecoder(format: Format): LocalAudioDecoder {
        val ac3 = encode(format)
        return LocalAudioDecoder(requireNotNull(codec(format)), format, if (ac3) 6 else pcmChannels(format), ac3).also { decoder ->
            decoder.onReleased = Runnable { publishConversion(null) }
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        publishConversion(null)
    }

    override fun getOutputFormat(decoder: Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>): Format {
        val active = (decoder as? DtsHdDecoder)?.delegate ?: decoder
        if (active is DtsCoreDecoder) {
            val output = active.outputFormat
            publishConversion(dtsCoreConversionText(active.inputFormat, active.header))
            return output
        }
        val (input, output) = when (active) {
            is LocalAudioDecoder -> active.inputFormat to active.outputFormat
            else -> error("Unknown local audio decoder")
        }
        publishConversion(com.ambient.tv.ui.player.audioConversionFormats(
            input.sampleMimeType, input.channelCount, output.sampleMimeType,
            if (output.sampleMimeType == MimeTypes.AUDIO_RAW && session.pcmOptionsForAudioThread().channels in setOf("stereo", "mono", "left", "right")) 2 else output.channelCount))
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

/**
 * FFmpeg decoder name for a Media3 sample MIME type. MPEG audio layers I/II are common in
 * DVB broadcasts; most Android devices have no MediaCodec for them, FFmpeg always does.
 */
internal fun localDecoderName(mime: String?): String? = when (mime) {
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3"
    MimeTypes.AUDIO_AC3 -> "ac3"
    MimeTypes.AUDIO_TRUEHD -> "truehd"
    MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "dca"
    MimeTypes.AUDIO_MPEG_L2 -> "mp2"
    MimeTypes.AUDIO_MPEG_L1 -> "mp1"
    else -> null
}

/** Server/container codec names the local renderer can take over from Media3. */
internal fun localDecodableSourceCodec(codec: String?): Boolean =
    com.ambient.tv.ui.player.normalizeSourceCodec(codec) != null ||
        codec?.trim()?.lowercase() in setOf("mp2", "mp1", "mpeg-l2", "mpeg-l1", "audio/mpeg-l2", "audio/mpeg-l1")

/** "DTS-HD 7.1 → DTS 6.1": the target names the core layout from its header, not the AudioTrack's 5.1 cap. */
internal fun dtsCoreConversionText(input: Format, header: DtsCoreHeader?): String {
    val base = com.ambient.tv.ui.player.audioConversionFormats(input.sampleMimeType, input.channelCount, MimeTypes.AUDIO_DTS, null)
    val layout = header?.channelLabel ?: com.ambient.tv.ui.player.audioChannelLabel(dtsCoreOutputFormat(input).channelCount)
    return "$base $layout"
}

/** Canonical native PCM feeding the stereo mixer, independent of the physical sink limit. */
internal fun localDownmixInputChannels(sourceChannels: Int): Int = when {
    sourceChannels >= 7 -> 8
    sourceChannels > 2 -> 6
    else -> 2
}
