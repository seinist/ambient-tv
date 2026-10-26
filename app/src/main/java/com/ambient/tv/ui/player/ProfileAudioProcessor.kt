@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal class ProfileAudioProcessor(private val session: AudioSession) : BaseAudioProcessor() {
    private var dsp: PcmAudioDsp? = null
    private var frame = FloatArray(0)
    private var channelMode = "auto"
    private val stereo = FloatArray(2)
    private var downmix: StereoDownmixMatrix? = null
    private var downmixLimiter: StereoPeakLimiter? = null
    private var surroundUpmix: Array<FloatArray>? = null

    private var inputLayout: List<PcmSpeaker>? = null

    /** AudioProcessor.AudioFormat drops the mask; retain it from the sink configuration. */
    fun setInputLayout(format: Format, mapping: IntArray?) {
        inputLayout = if (format.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW) {
            val layout = pcmSpeakerLayout(format.channelCount, format.channelMask)
            mapping?.map { layout[it] } ?: layout
        } else null
    }

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
            // Closest layout to the source: 3.0-5.0 go into 5.1 and 6.1 into 7.1, never to stereo.
            channelMode == "5.1" -> if (input.channelCount > 2 || upmix) 6 else 2
            channelMode == "7.1" -> if (input.channelCount >= 7) 8 else if (input.channelCount > 2 || upmix) 6 else 2
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
            downmix = StereoDownmixMatrix(inputLayout?.takeIf { it.size == format.channelCount } ?: pcmSpeakerLayout(format.channelCount))
            downmixLimiter = StereoPeakLimiter(format.sampleRate)
            surroundUpmix = if (format.channelCount > 2 && outputAudioFormat.channelCount > format.channelCount)
                surroundPlacement(inputLayout?.takeIf { it.size == format.channelCount } ?: pcmSpeakerLayout(format.channelCount),
                    pcmSpeakerLayout(outputAudioFormat.channelCount)) else null
        }
    }

    override fun onReset() { dsp = null; frame = FloatArray(0); downmix = null; downmixLimiter = null; surroundUpmix = null; inputLayout = null }

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
            } else if (channels > 2 && outputAudioFormat.channelCount > channels) {
                // Fewer speakers than the layout (3.0-5.0, 6.1): place each one, silence the rest.
                surroundUpmix?.let { map ->
                    for (o in 0 until outputAudioFormat.channelCount) {
                        var sample = 0f
                        for (c in 0 until channels) sample += frame[c] * map[o][c]
                        output.putShort(toPcm(sample))
                    }
                }
            } else if (outputAudioFormat.channelCount == 6 && channels >= 7) {
                // Android PCM order: FL FR FC LFE [BC or BL BR] [SL SR].
                // Fold the rear and side speakers into the two 5.1 surrounds.
                for (c in 0..3) output.putShort(toPcm(frame[c]))
                val left = if (channels == 7) frame[4] * 0.5f + frame[5] * 0.70710677f else (frame[4] + frame[6]) * 0.70710677f
                val right = if (channels == 7) frame[4] * 0.5f + frame[6] * 0.70710677f else (frame[5] + frame[7]) * 0.70710677f
                output.putShort(toPcm(left)); output.putShort(toPcm(right))
            } else if (outputAudioFormat.channelCount == 6) {
                // Dialogue already ran as the 2 kHz peaking filter. Do not boost center again.
                downmix?.mix(frame, "stereo", stereo, options.maintainDownmixVolume)
                if (options.maintainDownmixVolume && channels > 2) downmixLimiter?.process(stereo)
                val left = stereo[0]
                val right = stereo[1]
                val center = (left + right) * 0.5f
                output.putShort(toPcm(left)); output.putShort(toPcm(right))
                output.putShort(toPcm(center)); output.putShort(0)
                output.putShort(toPcm(left * 0.7f)); output.putShort(toPcm(right * 0.7f))
            } else {
                downmix?.mix(frame, if (channelMode == "5.1" || channelMode == "7.1") "stereo" else channelMode, stereo, options.maintainDownmixVolume)
                if (options.maintainDownmixVolume && channels > 2) downmixLimiter?.process(stereo)
                output.putShort(toPcm(stereo[0])); output.putShort(toPcm(stereo[1]))
            }
        }
        output.flip()
    }

    private fun toPcm(value: Float): Short = (value * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
}

/** Output x input gains that keep every source speaker where it is in a wider layout. */
internal fun surroundPlacement(input: List<PcmSpeaker>, output: List<PcmSpeaker>): Array<FloatArray> {
    val map = Array(output.size) { FloatArray(input.size) }
    input.forEachIndexed { c, speaker ->
        val direct = output.indexOf(speaker)
        val targets = when {
            direct >= 0 -> listOf(direct to 1f)
            // A back centre becomes both rears; side/back surrounds swap when only one pair exists.
            speaker == PcmSpeaker.BC -> listOf(PcmSpeaker.BL, PcmSpeaker.BR).map { output.indexOf(it) to 0.70710677f }
            speaker == PcmSpeaker.SL -> listOf(output.indexOf(PcmSpeaker.BL) to 1f)
            speaker == PcmSpeaker.SR -> listOf(output.indexOf(PcmSpeaker.BR) to 1f)
            speaker == PcmSpeaker.BL -> listOf(output.indexOf(PcmSpeaker.SL) to 1f)
            speaker == PcmSpeaker.BR -> listOf(output.indexOf(PcmSpeaker.SR) to 1f)
            else -> emptyList()
        }
        targets.filter { it.first >= 0 }.forEach { (o, gain) -> map[o][c] += gain }
    }
    return map
}
