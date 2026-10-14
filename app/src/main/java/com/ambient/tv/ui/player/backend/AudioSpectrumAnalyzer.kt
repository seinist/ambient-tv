@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player.backend

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Low-overhead PCM spectrum analyser used by the Media3 audio pipeline.
 *
 * PCM is downmixed to mono, windowed and transformed with a 1024-point radix-2 FFT. FFT bins are
 * then grouped into logarithmic bands (roughly 55 Hz..18 kHz) and smoothed for TV-friendly motion.
 * Analysis runs off the audio renderer thread so playback cannot be stalled by drawing work.
 */
fun interface AudioPcmSink {
    fun onPcm(samples: FloatArray)
}

class AudioSpectrumAnalyzer : TeeAudioProcessor.AudioBufferSink {
    companion object {
        const val BAND_COUNT = 32
        private const val FFT_SIZE = 1024
        private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "audio-spectrum").apply { isDaemon = true } }
    }

    private val pending = AtomicBoolean(false)
    private val latest = AtomicReference(FloatArray(BAND_COUNT))
    private val accumulator = FloatArray(FFT_SIZE)
    private val lock = Any()
    private var accumulatorSize = 0
    private var sampleRate = 48_000
    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var smoothed = FloatArray(BAND_COUNT)
    private val pcmSinks = CopyOnWriteArraySet<AudioPcmSink>()
    private val pcmTap = FloatArray(512)
    private var pcmTapSize = 0
    private val spectrumConsumers = java.util.concurrent.atomic.AtomicInteger(0)

    fun addPcmSink(sink: AudioPcmSink) { pcmSinks += sink }
    fun removePcmSink(sink: AudioPcmSink) { pcmSinks -= sink }

    /** Spectrum UI acquires while visible so FFT/tap work is skipped when hidden. */
    fun addSpectrumConsumer() { spectrumConsumers.incrementAndGet() }
    fun removeSpectrumConsumer() { spectrumConsumers.updateAndGet { (it - 1).coerceAtLeast(0) } }

    private fun hasActiveConsumers(): Boolean =
        spectrumConsumers.get() > 0 || pcmSinks.isNotEmpty()

    /** Feed already-decoded mono float PCM from non-Media3 native players (for example Spotify/librespot). */
    fun feedMonoFloat(samples: FloatArray, sampleRateHz: Int = 44_100) {
        if (samples.isEmpty() || !hasActiveConsumers()) return
        synchronized(lock) {
            sampleRate = sampleRateHz.coerceAtLeast(8_000)
            channelCount = 1
            encoding = C.ENCODING_PCM_FLOAT
            for (mono in samples) {
                pushMonoSample(mono.coerceIn(-1f, 1f))
            }
        }
    }

    private fun pushMonoSample(mono: Float) {
        accumulator[accumulatorSize++] = mono
        pcmTap[pcmTapSize++] = mono
        if (pcmTapSize == pcmTap.size) {
            if (pcmSinks.isNotEmpty()) {
                val frame = pcmTap.copyOf()
                pcmSinks.forEach { sink -> runCatching { sink.onPcm(frame) } }
            }
            pcmTapSize = 0
        }
        if (accumulatorSize == FFT_SIZE) {
            val frame = accumulator.copyOf()
            accumulatorSize = 0
            submit(frame, sampleRate)
        }
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(lock) {
            sampleRate = sampleRateHz.coerceAtLeast(8_000)
            this.channelCount = channelCount.coerceAtLeast(1)
            this.encoding = encoding
            accumulatorSize = 0
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!hasActiveConsumers()) return
        val copy = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        synchronized(lock) {
            while (copy.hasRemaining()) {
                val mono = readFrame(copy) ?: break
                pushMonoSample(mono)
            }
        }
    }

    fun snapshot(): FloatArray = latest.get().copyOf()

    private fun readFrame(buffer: ByteBuffer): Float? {
        var sum = 0f
        var readChannels = 0
        repeat(channelCount) {
            val sample = when (encoding) {
                C.ENCODING_PCM_8BIT -> if (buffer.remaining() >= 1) ((buffer.get().toInt() and 0xff) - 128) / 128f else return@repeat
                C.ENCODING_PCM_16BIT -> if (buffer.remaining() >= 2) buffer.short / 32768f else return@repeat
                C.ENCODING_PCM_24BIT -> {
                    if (buffer.remaining() < 3) return@repeat
                    val b0 = buffer.get().toInt() and 0xff
                    val b1 = buffer.get().toInt() and 0xff
                    val b2 = buffer.get().toInt()
                    val raw = b0 or (b1 shl 8) or (b2 shl 16)
                    raw / 8_388_608f
                }
                C.ENCODING_PCM_32BIT -> if (buffer.remaining() >= 4) buffer.int / 2_147_483_648f else return@repeat
                C.ENCODING_PCM_FLOAT -> if (buffer.remaining() >= 4) buffer.float.coerceIn(-1f, 1f) else return@repeat
                else -> return null
            }
            sum += sample
            readChannels++
        }
        return if (readChannels == channelCount) sum / readChannels else null
    }

    private fun submit(frame: FloatArray, rate: Int) {
        if (!pending.compareAndSet(false, true)) return
        executor.execute {
            try {
                latest.set(analyse(frame, rate))
            } finally {
                pending.set(false)
            }
        }
    }

    private fun analyse(samples: FloatArray, rate: Int): FloatArray {
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        for (i in samples.indices) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))
            real[i] = samples[i] * window
        }
        fft(real, imag)

        val magnitudes = DoubleArray(FFT_SIZE / 2)
        for (i in 1 until magnitudes.size) {
            magnitudes[i] = hypot(real[i], imag[i]) / FFT_SIZE
        }

        val output = FloatArray(BAND_COUNT)
        val minHz = 55.0
        val maxHz = minOf(18_000.0, rate / 2.0)
        val logMin = ln(minHz)
        val logMax = ln(maxHz)
        for (band in 0 until BAND_COUNT) {
            val f0 = kotlin.math.exp(logMin + (logMax - logMin) * band / BAND_COUNT)
            val f1 = kotlin.math.exp(logMin + (logMax - logMin) * (band + 1) / BAND_COUNT)
            val start = max(1, (f0 * FFT_SIZE / rate).toInt())
            val end = max(start + 1, (f1 * FFT_SIZE / rate).toInt()).coerceAtMost(magnitudes.size)
            var peak = 0.0
            for (bin in start until end) peak = max(peak, magnitudes[bin])
            // Convert to a display-friendly logarithmic magnitude. Around -60 dB is floor,
            // 0 dB is full scale. The clamp also makes quiet radio speech readable.
            val db = 20.0 * kotlin.math.log10(max(peak, 1e-6))
            val normalized = ((db + 58.0) / 58.0).coerceIn(0.0, 1.0).toFloat()
            val previous = smoothed[band]
            smoothed[band] = if (normalized > previous) {
                previous * 0.38f + normalized * 0.62f
            } else {
                previous * 0.82f + normalized * 0.18f
            }
            output[band] = smoothed[band]
        }
        return output
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        var j = 0
        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= FFT_SIZE) {
            val angle = -2.0 * PI / len
            val wLenR = cos(angle)
            val wLenI = sin(angle)
            var i = 0
            while (i < FFT_SIZE) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * wr - imag[i + k + len / 2] * wi
                    val vI = real[i + k + len / 2] * wi + imag[i + k + len / 2] * wr
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR
                    imag[i + k + len / 2] = uI - vI
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
