package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AudioCatalog
import com.ambient.tv.data.local.AudioOptions
import kotlin.math.*

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

/** Android PCM channel order: FL, FR, FC, LFE, surrounds. LFE is omitted in stereo. */
internal fun stereoDownmix(
    frame: FloatArray,
    mode: String,
    output: FloatArray,
    centerBoostDb: Int = 0,
    maintainVolume: Boolean = false,
) {
    if (frame.size == 1) { output[0] = frame[0]; output[1] = frame[0]; return }
    var l = frame[0]
    var r = frame[1]
    if (frame.size >= 3 && frame.size != 4) {
        val boost = if (centerBoostDb > 0) Math.pow(10.0, centerBoostDb / 20.0).toFloat() else 1f
        l += frame[2] * 0.7071f * boost
        r += frame[2] * 0.7071f * boost
    }
    // Three channels: L/R/C. Four channels: quad L/R/BL/BR, not L/R/C/LFE.
    if (frame.size == 4) { l = frame[0] + frame[2] * 0.7071f; r = frame[1] + frame[3] * 0.7071f }
    if (frame.size == 5) { l += frame[3] * 0.7071f; r += frame[4] * 0.7071f }
    if (frame.size == 7) {
        l += frame[4] * 0.5f + frame[5] * 0.7071f
        r += frame[4] * 0.5f + frame[6] * 0.7071f
    }
    if (frame.size == 6 || frame.size >= 8) for (c in 4 until frame.size) {
        if (c % 2 == 0) l += frame[c] * 0.7071f else r += frame[c] * 0.7071f
    }
    val headroom = when (frame.size) { 3, 4 -> 1.7071f; 5, 6 -> 2.4142f; 7 -> 2.9142f; 8 -> 3.1213f; else -> 1f }
    val kept = if (maintainVolume) 0.7f else 1f
    l = l / headroom / kept; r = r / headroom / kept
    when (mode) {
        "left" -> r = l
        "right" -> l = r
        "mono" -> { l = (l + r) * 0.5f; r = l }
    }
    output[0] = l; output[1] = r
}
