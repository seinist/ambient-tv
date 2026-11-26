package com.ambient.tv.moonlight

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge
import kotlin.math.max

/** Non-blocking PCM sink. Only a single owner may write, stop or release the AudioTrack. */
class MoonlightAudioTrackRenderer(private val pacing: String, private val onError: (String) -> Unit) : AudioRenderer {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var playing = false
    private var inputChannels = 2
    private var outputChannels = 2
    private var rate = 48_000
    private var frameSamples = 0
    private var scratch = ShortArray(0)
    private val lostSamples = java.util.concurrent.atomic.AtomicLong()
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
    @Volatile private var recovering = false
    private var closing = false
    private var recoveryThread: Thread? = null
    private var lastHead = 0L
    private var headEpoch = 0L
    private var lastQueueSample = 0L
    private var lastSnapshotNanos = 0L
    private var localQueuedMs = 0.0
    private var nativeQueuedMs = 0
    @Volatile private var snapshot = MoonlightAudioMetrics()
    private val audioTimestamp = android.media.AudioTimestamp()
    private var timestampBacked = false
    @Volatile private var queueReductionMs = 0
    private val nominalBudgetMs get() = when (pacing) { "latency" -> 35; "smooth" -> 80; else -> 55 }
    private val targetBudgetMs get() = (nominalBudgetMs - queueReductionMs).coerceAtLeast(25)

    internal fun setQueueReduction(ms: Int) { queueReductionMs = ms.coerceIn(0, 20) }

    override fun setup(configuration: MoonBridge.AudioConfiguration, sampleRate: Int, samplesPerFrame: Int): Int =
        synchronized(lock) {
            if (closing || recovering || !releaseLocked()) return@synchronized -1
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
                resetQueue()
                publishSnapshot(candidate)
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
        val bytes = max(minimum, packetBytes * 8)
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
            val packets = when (pacing) { "latency" -> 2; "smooth" -> 4; else -> 3 }
            runCatching { candidate.setBufferSizeInFrames(max(minimum / (channels * 2), samplesPerFrame * packets)) }
            return candidate
        }
        return null
    }

    override fun start() {
        synchronized(lock) {
            if (closing) return
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
        if (recovering) { lostSamples.addAndGet(audioData.size.toLong()); return }
        synchronized(lock) {
            val t = track ?: return
            if (closing || recovering || !playing || muted || audioData.isEmpty() || audioData.size % inputChannels != 0) return
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
                // The native queue is a slow-moving latency estimate, not a per-packet
                // requirement. Sample it at the same 20 ms cadence as the local playback
                // head instead of taking a JNI/native lock for every decoded PCM callback.
                if (lastQueueSample == 0L || now - lastQueueSample >= 20_000_000L) {
                    nativeQueuedMs = MoonBridge.getPendingAudioDuration().coerceAtLeast(0)
                    val head = t.playbackHeadPosition.toLong() and 0xffffffffL
                    if (head < lastHead && lastHead - head > 0x80000000L) headEpoch += 0x100000000L
                    lastHead = head
                    val playbackHead = headEpoch + head
                    // Query the device timestamp only four times a second on the existing PCM
                    // owner. Between timestamp samples use the continuously sampled head.
                    localQueuedMs = ((writtenFrames - playbackHead).coerceAtLeast(0L) * 1000.0 / rate)
                        .coerceAtMost(1000.0)
                    lastQueueSample = now
                    if (now - lastSnapshotNanos >= 250_000_000L) {
                        val projected = runCatching {
                            if (t.getTimestamp(audioTimestamp)) moonlightProjectAudioHead(
                                audioTimestamp.framePosition, audioTimestamp.nanoTime, playbackHead,
                                writtenFrames, rate, now) else null
                        }.getOrNull()
                        timestampBacked = projected != null
                        if (projected != null)
                            localQueuedMs = ((writtenFrames - projected).coerceAtLeast(0L) * 1000.0 / rate)
                                .coerceAtMost(1000.0)
                        publishSnapshot(t, now)
                        lastSnapshotNanos = now
                    }
                }
                // The device may impose a larger hardware floor. Account for both queues
                // rather than letting a second PCM queue accumulate behind common-c.
                val packetMs = frameSamples * 1000.0 / rate
                val budget = max(targetBudgetMs.toDouble(), minimumFrames * 1000.0 / rate + packetMs * 2)
                val total = nativeQueuedMs + localQueuedMs + pendingLength * 1000.0 / outputChannels / rate
                if (total >= budget || (pendingLength > 0 && now - pendingSince > 20_000_000L))
                    discardPending()
                if (nativeQueuedMs + localQueuedMs >= budget) { lostSamples.addAndGet(length.toLong()); return }
                if (pendingLength > 0) {
                    val n = t.write(pending, pendingOffset, pendingLength, AudioTrack.WRITE_NON_BLOCKING)
                    if (n < 0) { handleWriteError(n); lostSamples.addAndGet(length.toLong()); return }
                    writtenFrames += n / outputChannels
                    pendingOffset += n
                    pendingLength -= n
                    // If the older tail could not drain, bound latency by dropping it.
                    if (pendingLength > 0) discardPending()
                }
                val written = t.write(data, 0, length, AudioTrack.WRITE_NON_BLOCKING)
                if (written < 0) {
                    lostSamples.addAndGet(length.toLong())
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
                    } else lostSamples.addAndGet(remaining.toLong())
                }
                if (now - lastTuneNanos >= 2_000_000_000L) {
                    val underruns = t.underrunCount
                    val buffer = t.bufferSizeInFrames
                    if (underruns > lastUnderruns) {
                        stableIntervals = 0
                        val cap = max(minimumFrames, rate * targetBudgetMs / 1000)
                        val target = (buffer + frameSamples).coerceAtMost(minOf(t.bufferCapacityInFrames, cap))
                        if (target > buffer) t.setBufferSizeInFrames(target)
                    } else {
                        val adaptiveCap = max(minimumFrames, rate * targetBudgetMs / 1000)
                        if (queueReductionMs > 0 && buffer > adaptiveCap) {
                            t.setBufferSizeInFrames((buffer - frameSamples).coerceAtLeast(adaptiveCap))
                            stableIntervals = 0
                        } else if (++stableIntervals >= 10) {
                            stableIntervals = 0
                            val target = (buffer - frameSamples).coerceAtLeast(minimumFrames)
                            if (target < buffer) t.setBufferSizeInFrames(target)
                        }
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
        lostSamples.addAndGet(pendingLength.toLong())
        pendingOffset = 0
        pendingLength = 0
    }

    private fun handleWriteError(code: Int) {
        discardPending()
        if (code == AudioTrack.ERROR_DEAD_OBJECT && recoveryAttempts++ < 2 && !closing && !recovering) {
            recovering = true
            // Detach the dead track under the owner lock, rebuild off the PCM callback.
            val old = track
            track = null
            val worker = Thread({
                val released = runCatching { old?.release() }.isSuccess
                val rebuilt = if (released) runCatching { createTrack(outputChannels, rate, frameSamples) }.getOrNull() else null
                var failed = false
                synchronized(lock) {
                    if (!released) track = old // preserve failed release for explicit teardown
                    else if (rebuilt != null) {
                        track = rebuilt
                        minimumFrames = rebuilt.bufferSizeInFrames
                        resetQueue()
                        lastUnderruns = 0
                        stableIntervals = 0
                        failures = 0
                        if (!closing && playing && !muted) failed = runCatching { rebuilt.play() }.isFailure
                        publishSnapshot(rebuilt)
                    } else failed = true
                    failed = failed || !released
                    recovering = false
                }
                if (failed) onError("Moonlight audio device could not be restored")
            }, "moonlight-audio-recovery").apply { isDaemon = true }
            recoveryThread = worker
            worker.start()
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
                resetQueue()
                publishSnapshot(t)
            } else t.play()
        } catch (_: Exception) { onError("Moonlight audio focus recovery failed") }
    }

    private fun resetQueue() {
        writtenFrames = 0L
        lastHead = 0L
        headEpoch = 0L
        lastQueueSample = 0L
        lastSnapshotNanos = 0L
        localQueuedMs = 0.0
        nativeQueuedMs = 0
        timestampBacked = false
        queueReductionMs = 0
    }

    private fun publishSnapshot(t: AudioTrack, sampledAt: Long = 0L) {
        // A failed metrics query is not an audio device failure or a reason to abort recovery.
        runCatching {
            snapshot = MoonlightAudioMetrics(outputChannels, rate, t.bufferSizeInFrames,
                t.underrunCount, lostSamples.get(), localQueuedMs, nativeQueuedMs, recovering,
                sampledAt, timestampBacked, queueReductionMs)
        }
    }

    // No AudioTrack/native calls or callback lock contention on the telemetry worker.
    internal fun metrics(): MoonlightAudioMetrics = snapshot.copy(droppedSamples = lostSamples.get(), recovering = recovering)

    override fun stop() {
        synchronized(lock) {
            closing = true
            playing = false
            discardPending()
            track?.let { runCatching { it.pause(); it.flush() } }
        }
    }

    override fun cleanup() { finishCleanup() }

    internal fun finishCleanup(): Boolean {
        val worker = synchronized(lock) { closing = true; playing = false; recoveryThread }
        if (worker != null) {
            if (worker === Thread.currentThread()) return false
            runCatching { worker.join(1500L) }
            if (worker.isAlive) return false
        }
        return synchronized(lock) { recoveryThread = null; releaseLocked() }
    }

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
