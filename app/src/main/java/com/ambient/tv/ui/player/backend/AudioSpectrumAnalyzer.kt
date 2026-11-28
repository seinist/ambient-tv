@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player.backend

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    /** Borrowed audio-thread buffer, valid only during the callback. Copy if retention is needed. */
    fun onPcm(samples: FloatArray)
}

class AudioSpectrumAnalyzer : TeeAudioProcessor.AudioBufferSink {
    companion object {
        const val BAND_COUNT = 32
        private const val FFT_SIZE = 1024
        private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "audio-spectrum").apply { isDaemon = true } }
    }

    private val pending = AtomicBoolean(false)
    private data class SpectrumFrame(val generation: Long, val values: FloatArray)
    private val generation = AtomicLong()
    private val latest = AtomicReference(SpectrumFrame(0L, FloatArray(BAND_COUNT)))
    private var analysedGeneration = -1L
    private var bandRate = 0
    private val bandStarts = IntArray(BAND_COUNT)
    private val bandEnds = IntArray(BAND_COUNT)
    private val accumulator = FloatArray(FFT_SIZE)
    private val fftInput = FloatArray(FFT_SIZE)
    private val lock = Any()
    private var accumulatorSize = 0
    private var sampleRate = 48_000
    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private val smoothed = FloatArray(BAND_COUNT)
    // Accessed only by the single analysis executor.
    private val real = DoubleArray(FFT_SIZE)
    private val imag = DoubleArray(FFT_SIZE)
    private val magnitudes = DoubleArray(FFT_SIZE / 2)
    private val window = DoubleArray(FFT_SIZE) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1)) }
    private val bitReverse = IntArray(FFT_SIZE) { Integer.reverse(it) ushr (32 - Integer.numberOfTrailingZeros(FFT_SIZE)) }
    private val twiddleReal = DoubleArray(FFT_SIZE / 2) { cos(-2.0 * PI * it / FFT_SIZE) }
    private val twiddleImag = DoubleArray(FFT_SIZE / 2) { sin(-2.0 * PI * it / FFT_SIZE) }
    private val pcmSinks = CopyOnWriteArraySet<AudioPcmSink>()
    private val pcmTap = FloatArray(512)
    private var pcmTapSize = 0
    private val spectrumConsumers = java.util.concurrent.atomic.AtomicInteger(0)
    private val resetSpectrum = AtomicBoolean(false)
    private val resetTap = AtomicBoolean(false)

    fun addPcmSink(sink: AudioPcmSink) { pcmSinks += sink }
    fun removePcmSink(sink: AudioPcmSink) {
        pcmSinks -= sink
        if (pcmSinks.isEmpty()) resetTap.set(true)
    }

    /** Spectrum UI acquires while visible so FFT/tap work is skipped when hidden. */
    fun addSpectrumConsumer() { spectrumConsumers.incrementAndGet() }
    fun removeSpectrumConsumer() {
        if (spectrumConsumers.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) {
            resetSpectrum.set(true)
            generation.incrementAndGet()
        }
    }

    private fun hasActiveConsumers(): Boolean =
        spectrumConsumers.get() > 0 || pcmSinks.isNotEmpty()

    /** Feed already-decoded mono float PCM from non-Media3 native players (for example Spotify/librespot). */
    fun feedMonoFloat(samples: FloatArray, sampleRateHz: Int = 44_100) {
        if (samples.isEmpty() || !hasActiveConsumers()) return
        synchronized(lock) {
            val rate = sampleRateHz.coerceAtLeast(8_000)
            if (sampleRate != rate) {
                generation.incrementAndGet()
                accumulatorSize = 0
                pcmTapSize = 0
            }
            sampleRate = rate
            channelCount = 1
            encoding = C.ENCODING_PCM_FLOAT
            val tap = pcmSinks.isNotEmpty()
            val spectrum = spectrumConsumers.get() > 0
            prepareConsumers(tap, spectrum)
            for (mono in samples) pushMonoSample(sanitize(mono), tap, spectrum)
        }
    }

    private fun prepareConsumers(tap: Boolean, spectrum: Boolean) {
        if (resetTap.getAndSet(false) || !tap) pcmTapSize = 0
        if (resetSpectrum.getAndSet(false) || !spectrum) accumulatorSize = 0
    }

    private fun pushMonoSample(mono: Float, tap: Boolean, spectrum: Boolean) {
        if (tap) {
            pcmTap[pcmTapSize++] = mono
            if (pcmTapSize == pcmTap.size) {
                pcmSinks.forEach { sink -> runCatching { sink.onPcm(pcmTap) } }
                pcmTapSize = 0
            }
        } else {
            pcmTapSize = 0
        }
        // projectM computes its own FFT; PCM-only sinks must not trigger ours.
        if (spectrum) {
            accumulator[accumulatorSize++] = mono
            if (accumulatorSize == FFT_SIZE) {
                accumulatorSize = 0
                submit(sampleRate)
            }
        } else {
            accumulatorSize = 0
        }
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(lock) {
            sampleRate = sampleRateHz.coerceAtLeast(8_000)
            this.channelCount = channelCount.coerceAtLeast(1)
            this.encoding = encoding
            generation.incrementAndGet()
            accumulatorSize = 0
            pcmTapSize = 0
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!hasActiveConsumers()) return
        val copy = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        synchronized(lock) {
            val bytesPerSample = when (encoding) {
                C.ENCODING_PCM_8BIT -> 1
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> return
            }
            val frameBytes = bytesPerSample.toLong() * channelCount
            val tap = pcmSinks.isNotEmpty()
            val spectrum = spectrumConsumers.get() > 0
            prepareConsumers(tap, spectrum)
            while (copy.remaining() >= frameBytes) {
                pushMonoSample(readFrame(copy), tap, spectrum)
            }
        }
    }

    fun snapshot(): FloatArray {
        val frame = latest.get()
        return if (frame.generation == generation.get()) frame.values.copyOf() else FloatArray(BAND_COUNT)
    }

    private fun sanitize(value: Float): Float = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f

    private fun readFrame(buffer: ByteBuffer): Float {
        var sum = 0f
        repeat(channelCount) {
            sum += when (encoding) {
                C.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128f
                C.ENCODING_PCM_16BIT -> buffer.short / 32768f
                C.ENCODING_PCM_24BIT -> {
                    val b0 = buffer.get().toInt() and 0xff
                    val b1 = buffer.get().toInt() and 0xff
                    val b2 = buffer.get().toInt()
                    (b0 or (b1 shl 8) or (b2 shl 16)) / 8_388_608f
                }
                C.ENCODING_PCM_32BIT -> buffer.int / 2_147_483_648f
                C.ENCODING_PCM_FLOAT -> sanitize(buffer.float)
                else -> 0f // Encoding validated before processing the complete frames.
            }
        }
        return sum / channelCount
    }

    private fun submit(rate: Int) {
        if (!pending.compareAndSet(false, true)) return
        accumulator.copyInto(fftInput)
        val submittedGeneration = generation.get()
        executor.execute {
            try {
                if (spectrumConsumers.get() > 0 && generation.get() == submittedGeneration) {
                    if (analysedGeneration != submittedGeneration) {
                        smoothed.fill(0f)
                        analysedGeneration = submittedGeneration
                    }
                    val result = analyse(fftInput, rate)
                    if (generation.get() == submittedGeneration) latest.set(SpectrumFrame(submittedGeneration, result))
                }
            } finally {
                pending.set(false)
            }
        }
    }

    private fun analyse(samples: FloatArray, rate: Int): FloatArray {
        imag.fill(0.0)
        for (i in samples.indices) {
            real[i] = samples[i] * window[i]
        }
        fft(real, imag)

        for (i in 1 until magnitudes.size) {
            magnitudes[i] = hypot(real[i], imag[i]) / FFT_SIZE
        }

        val output = FloatArray(BAND_COUNT)
        if (bandRate != rate) {
            val logMin = ln(55.0)
            val logMax = ln(minOf(18_000.0, rate / 2.0))
            for (band in 0 until BAND_COUNT) {
                val f0 = kotlin.math.exp(logMin + (logMax - logMin) * band / BAND_COUNT)
                val f1 = kotlin.math.exp(logMin + (logMax - logMin) * (band + 1) / BAND_COUNT)
                val start = max(1, (f0 * FFT_SIZE / rate).toInt())
                bandStarts[band] = start
                bandEnds[band] = max(start + 1, (f1 * FFT_SIZE / rate).toInt()).coerceAtMost(magnitudes.size)
            }
            bandRate = rate
        }
        for (band in 0 until BAND_COUNT) {
            val start = bandStarts[band]
            val end = bandEnds[band]
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
        for (i in 1 until FFT_SIZE) {
            val j = bitReverse[i]
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= FFT_SIZE) {
            val stride = FFT_SIZE / len
            var i = 0
            while (i < FFT_SIZE) {
                for (k in 0 until len / 2) {
                    val wr = twiddleReal[k * stride]
                    val wi = twiddleImag[k * stride]
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * wr - imag[i + k + len / 2] * wi
                    val vI = real[i + k + len / 2] * wi + imag[i + k + len / 2] * wr
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR
                    imag[i + k + len / 2] = uI - vI
                }
                i += len
            }
            len = len shl 1
        }
    }
}
