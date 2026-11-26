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
    private var stableIntervals = 0
    private var minimumFrames = 0
    private var pending = ShortArray(0)
    private var pendingOffset = 0
    private var pendingLength = 0
    private var pendingSince = 0L
    private var writtenFrames = 0L
    private var muted = false
    private var recoveryAttempts = 0
    private val timestamp = android.media.AudioTimestamp()

    override fun setup(configuration: MoonBridge.AudioConfiguration, sampleRate: Int, samplesPerFrame: Int): Int =
        synchronized(lock) {
            if (!releaseLocked()) return@synchronized -1
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
                pending = ShortArray(samplesPerFrame * count)
                minimumFrames = candidate.bufferSizeInFrames
                pendingLength = 0
                writtenFrames = 0L
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
                if (!muted) t.play()
                playing = true
            } catch (_: Exception) {
                onError("Moonlight audio output could not start")
            }
        }
    }

    override fun playDecodedAudio(audioData: ShortArray) {
        synchronized(lock) {
            val t = track ?: return
            if (!playing || muted || audioData.isEmpty() || audioData.size % inputChannels != 0) return
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
                val now = System.nanoTime()
                val backlog = MoonBridge.getPendingAudioDuration()
                // Keep at most one PCM packet's tail. Never retain samples past a short
                // latency budget or grow a second unbounded queue behind common-c.
                if (backlog >= 40 || (pendingLength > 0 && now - pendingSince > 20_000_000L))
                    discardPending()
                if (backlog >= 40) { lostSamples += length; return }
                if (pendingLength > 0) {
                    val n = t.write(pending, pendingOffset, pendingLength, AudioTrack.WRITE_NON_BLOCKING)
                    if (n < 0) { handleWriteError(n); lostSamples += length; return }
                    writtenFrames += n / outputChannels
                    pendingOffset += n
                    pendingLength -= n
                    // If the older tail could not drain, bound latency by dropping it.
                    if (pendingLength > 0) discardPending()
                }
                val written = t.write(data, 0, length, AudioTrack.WRITE_NON_BLOCKING)
                if (written < 0) {
                    lostSamples += length
                    handleWriteError(written)
                    return
                }
                failures = 0
                writtenFrames += written / outputChannels
                val remaining = length - written
                if (remaining > 0) {
                    if (remaining <= pending.size) {
                        data.copyInto(pending, 0, written, length)
                        pendingOffset = 0
                        pendingLength = remaining
                        pendingSince = now
                    } else lostSamples += remaining
                }
                if (now - lastTuneNanos >= 2_000_000_000L) {
                    val underruns = t.underrunCount
                    val buffer = t.bufferSizeInFrames
                    if (underruns > lastUnderruns) {
                        stableIntervals = 0
                        val target = (buffer + frameSamples).coerceAtMost(minOf(t.bufferCapacityInFrames, rate / 12))
                        if (target > buffer) t.setBufferSizeInFrames(target)
                    } else if (++stableIntervals >= 10) {
                        stableIntervals = 0
                        val target = (buffer - frameSamples).coerceAtLeast(minimumFrames)
                        if (target < buffer) t.setBufferSizeInFrames(target)
                    }
                    lastUnderruns = underruns
                    lastTuneNanos = now
                }
            } catch (_: Exception) {
                if (++failures >= 3) onError("Moonlight audio device failed")
            }
        }
    }

    private fun discardPending() {
        lostSamples += pendingLength
        pendingOffset = 0
        pendingLength = 0
    }

    private fun handleWriteError(code: Int) {
        discardPending()
        if (code == AudioTrack.ERROR_DEAD_OBJECT && recoveryAttempts++ < 2) {
            val wasPlaying = playing
            if (releaseLocked()) {
                val rebuilt = createTrack(outputChannels, rate, frameSamples)
                if (rebuilt != null) {
                    track = rebuilt
                    minimumFrames = rebuilt.bufferSizeInFrames
                    writtenFrames = 0L
                    lastUnderruns = 0
                    stableIntervals = 0
                    failures = 0
                    if (wasPlaying) start()
                    return
                }
            }
            onError("Moonlight audio device could not be restored")
        } else if (++failures >= 3) onError("Moonlight audio write failed ($code)")
    }

    /** A transient focus loss mutes the local sink; the remote game and video continue. */
    internal fun setMuted(value: Boolean) = synchronized(lock) {
        if (muted == value) return@synchronized
        muted = value
        val t = track ?: return@synchronized
        if (!playing) return@synchronized
        try {
            if (value) {
                discardPending()
                t.pause()
                t.flush()
                writtenFrames = 0L
            } else t.play()
        } catch (_: Exception) { onError("Moonlight audio focus recovery failed") }
    }

    internal fun metrics(): MoonlightAudioMetrics = synchronized(lock) {
        val t = track
        val queuedMs = if (t != null && playing && !muted && t.getTimestamp(timestamp)) {
            val elapsed = (System.nanoTime() - timestamp.nanoTime).coerceAtLeast(0L)
            val played = timestamp.framePosition + (elapsed * (rate / 1_000_000_000.0)).toLong()
            ((writtenFrames - played).coerceAtLeast(0L) * 1000.0 / rate).takeIf { it <= 1000.0 }
        } else null
        MoonlightAudioMetrics(outputChannels, rate, t?.bufferSizeInFrames ?: 0,
            t?.underrunCount ?: 0, lostSamples, queuedMs)
    }

    override fun stop() {
        synchronized(lock) {
            playing = false
            discardPending()
            track?.let { runCatching { it.pause(); it.flush() } }
        }
    }

    override fun cleanup() { finishCleanup() }

    internal fun finishCleanup(): Boolean = synchronized(lock) { releaseLocked() }

    private fun releaseLocked(): Boolean {
        playing = false
        discardPending()
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            if (runCatching { it.release() }.isFailure) return false
        }
        track = null
        return true
    }
}
