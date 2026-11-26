package com.ambient.tv.moonlight

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge
import kotlin.math.max

/** Non-blocking PCM sink. Only a single owner may write, stop or release the AudioTrack. */
class MoonlightAudioTrackRenderer(private val onError: (String) -> Unit) : AudioRenderer {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var playing = false
    private var inputChannels = 2
    private var outputChannels = 2
    private var rate = 48_000
    private var frameSamples = 0
    private var scratch = ShortArray(0)
    private var lostSamples = 0L
    private var failures = 0
    private var lastUnderruns = 0
    private var lastTuneNanos = 0L

    override fun setup(configuration: MoonBridge.AudioConfiguration, sampleRate: Int, samplesPerFrame: Int): Int =
        synchronized(lock) {
            releaseLocked()
            if (sampleRate !in 8000..192000 || samplesPerFrame !in 1..8192) return@synchronized -1
            inputChannels = configuration.channelCount
            if (inputChannels !in setOf(2, 6, 8)) return@synchronized -1
            rate = sampleRate
            frameSamples = samplesPerFrame
            for (count in listOf(inputChannels, 2).distinct()) {
                val candidate = createTrack(count, sampleRate, samplesPerFrame) ?: continue
                track = candidate
                outputChannels = count
                scratch = if (count != inputChannels) ShortArray(samplesPerFrame * count) else ShortArray(0)
                return@synchronized 0
            }
            -1
        }

    private fun createTrack(channels: Int, sampleRate: Int, samplesPerFrame: Int): AudioTrack? {
        val channelMask = when (channels) {
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> return null
        }
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        val packetBytes = samplesPerFrame * channels * 2
        val bytes = max(minimum, packetBytes * 6)
        if (bytes > 512 * 1024) return null
        val format = AudioFormat.Builder().setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(channelMask).build()
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        for (lowLatency in listOf(true, false)) {
            val candidate = runCatching {
                AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bytes)
                    .apply { if (lowLatency) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
                    .build()
            }.getOrNull() ?: continue
            if (candidate.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { candidate.release() }
                continue
            }
            runCatching { candidate.setBufferSizeInFrames(max(minimum / (channels * 2), samplesPerFrame * 3)) }
            return candidate
        }
        return null
    }

    override fun start() {
        synchronized(lock) {
            val t = track ?: return
            try {
                t.play()
                playing = true
            } catch (_: Exception) {
                onError("Moonlight audio output could not start")
            }
        }
    }

    override fun playDecodedAudio(audioData: ShortArray) {
        synchronized(lock) {
            val t = track ?: return
            if (!playing || audioData.isEmpty()) return
            val data: ShortArray
            val length: Int
            if (inputChannels != outputChannels) {
                val frames = audioData.size / inputChannels
                if (frames > frameSamples) return
                // GameStream PCM uses the AudioTrack channel order: FL, FR, FC, LFE, rear, side.
                for (i in 0 until frames) {
                    val j = i * inputChannels
                    val center = audioData[j + 2] * .707f
                    val bass = audioData[j + 3] * .25f
                    var left = audioData[j].toFloat() + center + bass + audioData[j + 4] * .707f
                    var right = audioData[j + 1].toFloat() + center + bass + audioData[j + 5] * .707f
                    if (inputChannels == 8) { left += audioData[j + 6] * .5f; right += audioData[j + 7] * .5f }
                    scratch[i * 2] = (left * .5f).toInt().coerceIn(-32768, 32767).toShort()
                    scratch[i * 2 + 1] = (right * .5f).toInt().coerceIn(-32768, 32767).toShort()
                }
                data = scratch
                length = frames * 2
            } else {
                data = audioData
                length = audioData.size
            }
            try {
                // Drop only when the native/audio queues cannot keep up; never block Opus.
                val written = if (MoonBridge.getPendingAudioDuration() < 40)
                    t.write(data, 0, length, AudioTrack.WRITE_NON_BLOCKING) else 0
                if (written < 0) {
                    lostSamples += length
                    if (++failures >= 3) onError("Moonlight audio write failed ($written)")
                    return
                }
                failures = 0
                lostSamples += (length - written).coerceAtLeast(0)
                val now = System.nanoTime()
                if (now - lastTuneNanos >= 2_000_000_000L) {
                    val underruns = t.underrunCount
                    if (underruns > lastUnderruns) {
                        val target = (t.bufferSizeInFrames + frameSamples)
                            .coerceAtMost(minOf(t.bufferCapacityInFrames, rate / 12))
                        if (target > t.bufferSizeInFrames) t.setBufferSizeInFrames(target)
                    }
                    lastUnderruns = underruns
                    lastTuneNanos = now
                }
            } catch (_: Exception) {
                if (++failures >= 3) onError("Moonlight audio device failed")
            }
        }
    }

    internal fun metrics(): MoonlightAudioMetrics = synchronized(lock) {
        val t = track
        MoonlightAudioMetrics(outputChannels, rate, t?.bufferSizeInFrames ?: 0,
            t?.underrunCount ?: 0, lostSamples)
    }

    override fun stop() {
        synchronized(lock) {
            playing = false
            track?.let { runCatching { it.pause(); it.flush() } }
        }
    }

    override fun cleanup() { synchronized(lock) { releaseLocked() } }

    private fun releaseLocked() {
        playing = false
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
    }
}
