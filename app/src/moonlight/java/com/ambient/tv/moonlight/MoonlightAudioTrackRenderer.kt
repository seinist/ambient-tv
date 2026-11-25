package com.ambient.tv.moonlight

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge
import kotlin.math.max

/**
 * Direct 16-bit PCM AudioTrack sink for Moonlight's already Opus-decoded audio callback.
 * It is not a Media3/MPV/VLC track. Low latency mode is advisory; an unsupported mode
 * falls back to an ordinary hardware AudioTrack without breaking the stream.
 */
class MoonlightAudioTrackRenderer : AudioRenderer {
    private val lock = Any()
    private var track: AudioTrack? = null

    override fun setup(configuration: MoonBridge.AudioConfiguration, sampleRate: Int, samplesPerFrame: Int): Int =
        synchronized(lock) {
            releaseLocked()
            if (sampleRate !in 8000..192000 || samplesPerFrame !in 1..8192) return@synchronized -1
            val channels = when (configuration.channelCount) {
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                6 -> AudioFormat.CHANNEL_OUT_5POINT1
                8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                else -> return@synchronized -1
            }
            val minimum = AudioTrack.getMinBufferSize(sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return@synchronized -1
            // Keep a bounded buffer; excessive buffering is perceptible as input-to-audio lag.
            val frameBytes = samplesPerFrame * configuration.channelCount * 2
            val byteCount = max(minimum, frameBytes * 3).coerceAtMost(512 * 1024)
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channels)
                .build()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val built = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(audioFormat)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(byteCount)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            }.recoverCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(audioFormat)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(byteCount)
                    .build()
            }.getOrNull()
            if (built?.state != AudioTrack.STATE_INITIALIZED) {
                built?.release()
                return@synchronized -1
            }
            track = built
            0
        }

    override fun start() {
        synchronized(lock) { runCatching { track?.play() } }
    }

    override fun playDecodedAudio(audioData: ShortArray) {
        // Native Opus delivery must never block on a slow OS mixer. A partial nonblocking
        // write is deliberately dropped rather than extending the stream's audio latency.
        val current = synchronized(lock) { track } ?: return
        if (audioData.isEmpty()) return
        try { current.write(audioData, 0, audioData.size, AudioTrack.WRITE_NON_BLOCKING) }
        catch (_: IllegalStateException) { /* audio focus/lifecycle race: stop owns the track */ }
    }

    override fun stop() {
        synchronized(lock) {
            val t = track ?: return
            runCatching { t.pause(); t.flush() }
        }
    }

    override fun cleanup() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
    }
}
