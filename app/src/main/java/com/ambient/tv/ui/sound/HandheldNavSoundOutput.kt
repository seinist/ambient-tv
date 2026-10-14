package com.ambient.tv.ui.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Media PCM output for phones, independent of the device's SoundPool sonification path.
 * All ownership changes happen on the main thread; only synthesis runs on a worker.
 */
internal class HandheldNavSoundOutput(private val scope: CoroutineScope) {
    private data class Playback(val track: AudioTrack, val cleanup: Job)
    private val playing = mutableListOf<Playback>()
    private val pending = mutableSetOf<Job>()
    private val cache = object : LinkedHashMap<Pair<UiSound, NavSoundDesign>, ShortArray>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<UiSound, NavSoundDesign>, ShortArray>?) = size > 8
    }

    fun play(kind: UiSound, design: NavSoundDesign, volume: Float, allowed: () -> Boolean, result: (Boolean) -> Unit = {}) {
        val job = scope.launch {
            var unowned: AudioTrack? = null
            try {
                val key = kind to design
                val pcm = cache[key] ?: withContext(Dispatchers.Default) {
                    NavSoundRenderer.pcm(design, kind)
                }.also { cache[key] = it }
                if (!allowed()) { result(false); return@launch }
                val gain = volume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                if (gain == 0f || design.volume == 0) { result(false); return@launch }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(NavSoundRenderer.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                unowned = track
                check(track.state == AudioTrack.STATE_NO_STATIC_DATA)
                check(track.write(pcm, 0, pcm.size) == pcm.size)
                check(track.setVolume(gain) == AudioTrack.SUCCESS)
                while (playing.size >= 3) release(playing.first())
                track.play()
                check(track.playState == AudioTrack.PLAYSTATE_PLAYING)
                val cleanup = scope.launch {
                    delay(pcm.size * 1000L / NavSoundRenderer.RATE + 150)
                    playing.firstOrNull { it.track === track }?.let(::release)
                }
                playing += Playback(track, cleanup)
                unowned = null
                result(true)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("UiSoundPlayer", "Handheld navigation audio failed", error)
                result(false)
            } finally {
                unowned?.release()
            }
        }
        pending += job
        job.invokeOnCompletion { pending -= job }
    }

    private fun release(playback: Playback) {
        playing -= playback
        playback.cleanup.cancel()
        playback.track.release()
    }

    fun stop() {
        pending.toList().forEach { it.cancel() }
        playing.toList().forEach(::release)
    }
}
