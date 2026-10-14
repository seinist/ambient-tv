package com.ambient.tv.spotify

import com.ambient.tv.ui.player.backend.AudioSpectrumAnalyzer

/**
 * Process-wide analyzer fed by the optional PCM callback in the patched Rusty/librespot core.
 * The analyzer object is stable so Compose/projectM can attach and detach PCM sinks safely.
 */
object SpotifyAudioAnalysis {
    val analyzer = AudioSpectrumAnalyzer()

    @JvmStatic
    fun onPcm(samples: FloatArray, sampleRateHz: Int) {
        analyzer.feedMonoFloat(samples, sampleRateHz)
    }
}
