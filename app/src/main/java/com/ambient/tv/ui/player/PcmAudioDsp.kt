package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AudioCatalog
import com.ambient.tv.data.local.AudioOptions
import kotlin.math.*
import com.ambient.tv.ui.player.PcmSpeaker.*

/** Stateful PCM DSP. One instance per sink; never shared between playback threads. */
internal class PcmAudioDsp(private val sampleRate: Int, private val channelCount: Int) {
    private var options = AudioOptions()
    private var filters = emptyArray<Array<PeakingFilter>>()
    private var gain = 1.0
    private var normalizationGain = 1.0
    private var compressorGain = 1.0
    private var energy = 0.0
    private val energyRate = 1.0 - exp(-1.0 / (sampleRate * 0.25))
    private val rise = 1.0 - exp(-1.0 / (sampleRate * 0.5))
    private val fall = 1.0 - exp(-1.0 / (sampleRate * 0.01))

    fun configure(next: AudioOptions) {
        if (next == options) return
        options = next.normalized()
        gain = if (options.eqEnabled) 10.0.pow(options.preampDb / 20.0) else 1.0
        val bands = buildList {
            if (options.eqEnabled) options.eqDb.forEachIndexed { i, db ->
                if (db != 0 && AudioCatalog.frequencies[i] < sampleRate * 0.45) add(Triple(AudioCatalog.frequencies[i].toDouble(), db.toDouble(), 1.4))
            }
            if (options.dialogue > 0 && sampleRate > 4500) add(Triple(2000.0, options.dialogue.toDouble(), 0.8))
        }
        filters = Array(channelCount) { bands.map { (hz, db, q) -> PeakingFilter(sampleRate, hz, db, q) }.toTypedArray() }
        normalizationGain = 1.0
        compressorGain = 1.0
        energy = 0.0
    }

    /** In-place; channel linking preserves the stereo/surround image. No frame allocations. */
    fun process(frame: FloatArray) {
        val active = options.eqEnabled || options.dialogue > 0 || options.night || options.normalize
        if (!active) return
        var square = 0.0
        for (c in 0 until channelCount) {
            var value = frame[c].toDouble() * gain
            if (filters.isNotEmpty()) for (filter in filters[c]) value = filter.process(value)
            frame[c] = value.toFloat()
            square += value * value
        }
        energy += energyRate * (square / channelCount - energy)
        if (options.normalize) {
            val rms = sqrt(energy)
            // Do not amplify pauses/noise; cap gain at +6 dB and ride it slowly.
            val target = if (rms < 0.003) 1.0 else (0.14 / rms).coerceIn(0.25, 2.0)
            normalizationGain += (if (target < normalizationGain) fall else rise) * (target - normalizationGain)
        } else normalizationGain = 1.0
        var peak = 0.0
        for (c in 0 until channelCount) peak = max(peak, abs(frame[c].toDouble()) * normalizationGain)
        if (options.night) {
            val target = if (peak > 0.125) (peak / 0.125).pow(-0.75) else 1.0
            compressorGain += (if (target < compressorGain) fall else rise) * (target - compressorGain)
        } else compressorGain = 1.0
        val combined = normalizationGain * compressorGain
        val ceiling = if (peak * compressorGain > 0.95) 0.95 / (peak * compressorGain) else 1.0
        for (c in 0 until channelCount) frame[c] = (frame[c] * combined * ceiling).toFloat()
    }

    private class PeakingFilter(rate: Int, hz: Double, db: Double, q: Double) {
        private val a = 10.0.pow(db / 40.0)
        private val w = 2.0 * PI * hz / rate
        private val alpha = sin(w) / (2 * q)
        private val a0 = 1 + alpha / a
        private val b0 = (1 + alpha * a) / a0
        private val b1 = -2 * cos(w) / a0
        private val b2 = (1 - alpha * a) / a0
        private val a1 = b1
        private val a2 = (1 - alpha / a) / a0
        private var z1 = 0.0
        private var z2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }
}

/** Named speaker positions; adapters supply canonical Android/FFmpeg PCM order. */
internal enum class PcmSpeaker { FL, FR, FC, LFE, BL, BR, BC, SL, SR }

internal fun pcmSpeakerLayout(channels: Int, channelMask: Int = 0): List<PcmSpeaker> {
    if (channelMask > 0) {
        // Android CHANNEL_OUT_* bits, in interleaved sample order. Side and back
        // surrounds are distinct; 3.1 must never be mistaken for four-channel quad.
        val speakers = listOf(0x4 to FL, 0x8 to FR, 0x10 to FC, 0x20 to LFE,
            0x40 to BL, 0x80 to BR, 0x100 to FL, 0x200 to FR,
            0x400 to BC, 0x800 to SL, 0x1000 to SR)
        require(channelMask and 0x1ffc == channelMask && Integer.bitCount(channelMask) == channels) {
            "Unsupported PCM channel mask: $channelMask"
        }
        return speakers.filter { (bit, _) -> channelMask and bit != 0 }.map { it.second }
    }
    return when (channels) {
        1 -> listOf(FC)
        2 -> listOf(FL, FR)
        3 -> listOf(FL, FR, FC)
        4 -> listOf(FL, FR, BL, BR)
        5 -> listOf(FL, FR, FC, BL, BR)
        6 -> listOf(FL, FR, FC, LFE, BL, BR)
        7 -> listOf(FL, FR, FC, LFE, BC, SL, SR)
        8 -> listOf(FL, FR, FC, LFE, BL, BR, SL, SR)
        else -> error("Unsupported PCM channel count: $channels")
    }
}

/** Constructed once per format, never per frame. LFE is deliberately not folded into stereo. */
internal class StereoDownmixMatrix(layout: List<PcmSpeaker>, centerBoostDb: Int = 0) {
    private val left = FloatArray(layout.size)
    private val right = FloatArray(layout.size)
    private val headroom: Float

    init {
        val center = 0.70710677f * 10.0.pow(centerBoostDb.coerceIn(0, 6) / 20.0).toFloat()
        layout.forEachIndexed { index, speaker ->
            when (speaker) {
                PcmSpeaker.FL -> left[index] = 1f
                PcmSpeaker.FR -> right[index] = 1f
                PcmSpeaker.FC -> { left[index] = if (layout.size == 1) 1f else center; right[index] = left[index] }
                PcmSpeaker.BL, PcmSpeaker.SL -> left[index] = 0.70710677f
                PcmSpeaker.BR, PcmSpeaker.SR -> right[index] = 0.70710677f
                PcmSpeaker.BC -> { left[index] = 0.5f; right[index] = 0.5f }
                PcmSpeaker.LFE -> Unit
            }
        }
        if (layout.size == 1 && layout[0] != LFE) { left[0] = 1f; right[0] = 1f }
        headroom = maxOf(1f, left.sum(), right.sum())
    }

    fun mix(frame: FloatArray, mode: String, output: FloatArray, maintainVolume: Boolean = false) {
        require(frame.size == left.size)
        var l = 0f
        var r = 0f
        for (c in frame.indices) { l += frame[c] * left[c]; r += frame[c] * right[c] }
        if (!maintainVolume) { l /= headroom; r /= headroom }
        when (mode) {
            "left" -> r = l
            "right" -> l = r
            "mono" -> { l = (l + r) * 0.5f; r = l }
        }
        output[0] = l
        output[1] = r
    }
}

/** Instant attack and a smooth release; both channels share gain to preserve stereo balance. */
internal class StereoPeakLimiter(sampleRate: Int) {
    private val release = 1.0 - exp(-1.0 / (sampleRate * 0.05))
    private var gain = 1.0

    fun process(frame: FloatArray) {
        val peak = max(abs(frame[0]), abs(frame[1])).toDouble()
        val target = if (peak > 0.95) 0.95 / peak else 1.0
        gain = if (target < gain) target else gain + release * (target - gain)
        frame[0] = (frame[0] * gain).toFloat()
        frame[1] = (frame[1] * gain).toFloat()
    }
}

internal fun stereoDownmix(
    frame: FloatArray,
    mode: String,
    output: FloatArray,
    centerBoostDb: Int = 0,
    maintainVolume: Boolean = false,
) = StereoDownmixMatrix(pcmSpeakerLayout(frame.size), centerBoostDb).mix(frame, mode, output, maintainVolume)
