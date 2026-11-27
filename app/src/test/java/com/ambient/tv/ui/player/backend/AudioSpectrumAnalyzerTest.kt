package com.ambient.tv.ui.player.backend

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioSpectrumAnalyzerTest {
    @Test
    fun pcmOnlySinkReceivesFiniteClampedValuesWithoutSpectrumWork() {
        val analyzer = AudioSpectrumAnalyzer()
        val packets = mutableListOf<FloatArray>()
        val sink = AudioPcmSink { packets.add(it.copyOf()) }
        analyzer.addPcmSink(sink)
        val pcm = FloatArray(1024) { 0.25f }
        pcm[0] = Float.NaN
        pcm[1] = Float.POSITIVE_INFINITY
        pcm[2] = 2f
        pcm[3] = -2f
        analyzer.feedMonoFloat(pcm)
        assertEquals(2, packets.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, -1f), packets[0].copyOf(4), 0f)
        assertTrue(analyzer.snapshot().all { it == 0f })
        analyzer.removePcmSink(sink)
        analyzer.feedMonoFloat(pcm)
        assertEquals(2, packets.size)
    }

    @Test
    fun fftDetectsToneAndFlushInvalidatesOldSpectrum() {
        val analyzer = AudioSpectrumAnalyzer()
        analyzer.addSpectrumConsumer()
        try {
            val rate = 48000
            val samples = FloatArray(1024) { sin(2 * PI * 1000 * it / rate).toFloat() }
            analyzer.feedMonoFloat(samples, rate)
            val deadline = System.nanoTime() + 3_000_000_000L
            var snapshot = analyzer.snapshot()
            while (snapshot.all { it == 0f } && System.nanoTime() < deadline) {
                Thread.sleep(5)
                snapshot = analyzer.snapshot()
            }
            assertTrue(snapshot.any { it > 0.4f })
            val strongest = snapshot.indices.maxByOrNull { snapshot[it] }!!
            // 1 kHz lies in the middle of the logarithmic 55 Hz–18 kHz range.
            assertTrue("dominant band: $strongest", strongest in 14..18)
            analyzer.flush(rate, 1, C.ENCODING_PCM_FLOAT)
            assertTrue(analyzer.snapshot().all { it == 0f })
            analyzer.feedMonoFloat(samples, rate)
            analyzer.removeSpectrumConsumer()
            assertTrue(analyzer.snapshot().all { it == 0f })
        } finally {
            analyzer.removeSpectrumConsumer()
        }
    }
}
