@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal class ProfileAudioProcessor(private val session: AudioSession) : BaseAudioProcessor() {
    private var dsp: PcmAudioDsp? = null
    private var frame = FloatArray(0)
    private var channelMode = "auto"
    private val stereo = FloatArray(2)

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(input)
        // Read the effective plan, not the stored profile: a forced stereo fallback or a
        // stereo route must stay stereo even when the profile has upmix enabled.
        val effective = session.pcmOptionsForAudioThread()
        channelMode = effective.channels
        val upmix = effective.upmix && !session.forceStereoPcm
        // Match MPV: upmix only on auto expands a stereo input to fake 5.1; stereo stays stereo.
        val upmixStereo = upmix && input.channelCount == 2 && channelMode == "auto"
        val channels = when {
            upmixStereo -> 6
            channelMode == "5.1" -> if (input.channelCount >= 6 || upmix) 6 else 2
            channelMode == "7.1" -> if (input.channelCount >= 8) 8 else if (input.channelCount >= 6 || upmix) 6 else 2
            channelMode == "auto" -> input.channelCount
            else -> 2
        }
        return AudioProcessor.AudioFormat(input.sampleRate, channels, input.encoding)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val format = inputAudioFormat
        if (format.sampleRate > 0 && format.channelCount > 0) {
            dsp = PcmAudioDsp(format.sampleRate, format.channelCount)
            frame = FloatArray(format.channelCount)
        }
    }

    override fun onReset() { dsp = null; frame = FloatArray(0) }

    override fun queueInput(input: ByteBuffer) {
        // An empty input makes replaceOutputBuffer() hand back the shared EMPTY_BUFFER that is
        // also the input, and ByteBuffer.put rejects copying a buffer into itself.
        if (!input.hasRemaining()) return
        val channels = inputAudioFormat.channelCount
        val frames = input.remaining() / (channels * 2)
        val output = replaceOutputBuffer(frames * outputAudioFormat.channelCount * 2)
        val options = session.pcmOptionsForAudioThread()
        if (channelMode == "auto" && !options.eqEnabled && options.dialogue == 0 && !options.night && !options.normalize && !options.upmix) {
            output.put(input)
            output.flip()
            return
        }
        dsp?.configure(options)
        repeat(frames) {
            for (c in 0 until channels) frame[c] = input.short / 32768f
            dsp?.process(frame)
            if (channels == outputAudioFormat.channelCount && (channelMode == "auto" || channelMode == "5.1" || channelMode == "7.1")) {
                for (c in 0 until channels) output.putShort(toPcm(frame[c]))
            } else if (outputAudioFormat.channelCount == 6 && channels >= 7) {
                // Android PCM order: FL FR FC LFE [BC or BL BR] [SL SR].
                // Fold the rear and side speakers into the two 5.1 surrounds.
                for (c in 0..3) output.putShort(toPcm(frame[c]))
                val left = if (channels == 7) frame[4] * 0.5f + frame[5] * 0.70710677f else (frame[4] + frame[6]) * 0.70710677f
                val right = if (channels == 7) frame[4] * 0.5f + frame[6] * 0.70710677f else (frame[5] + frame[7]) * 0.70710677f
                output.putShort(toPcm(left)); output.putShort(toPcm(right))
            } else if (outputAudioFormat.channelCount == 6) {
                // Dialogue already ran as the 2 kHz peaking filter. Do not boost center again.
                stereoDownmix(frame, "stereo", stereo, 0, options.maintainDownmixVolume)
                val left = stereo[0]
                val right = stereo[1]
                val center = (left + right) * 0.5f
                output.putShort(toPcm(left)); output.putShort(toPcm(right))
                output.putShort(toPcm(center)); output.putShort(0)
                output.putShort(toPcm(left * 0.7f)); output.putShort(toPcm(right * 0.7f))
            } else {
                stereoDownmix(frame, if (channelMode == "5.1" || channelMode == "7.1") "stereo" else channelMode, stereo, 0, options.maintainDownmixVolume)
                output.putShort(toPcm(stereo[0])); output.putShort(toPcm(stereo[1]))
            }
        }
        output.flip()
    }

    private fun toPcm(value: Float): Short = (value * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
}
