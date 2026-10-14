package dev.rusty.app.audio

import dev.rusty.app.SpotifyService
import java.util.concurrent.ArrayBlockingQueue

/**
 * Optional visualizer tap on the Spotify output (replaces the v39 Rust-side PCM patch).
 *
 * Since Rusty 2.7 every decoded sample reaches [PcmOutput.write] on the JVM, so the tap no longer
 * needs a patched native core. It is off by default; Android enables it only while an
 * audio-reactive Spotify visualizer is visible. Interleaved stereo is downmixed to mono, batched
 * to [FRAME_SAMPLES] and handed to a worker thread through a bounded queue: frames are dropped
 * when the analyzer falls behind, so visualization never adds backpressure to playback.
 * Ambient TV: each batch is released when it becomes audible (the track queues ~1 s).
 */
object PcmTap {
    const val FRAME_SAMPLES = 1024
    /** ~1.5 s of batches: the AudioTrack queues up to a second that the tap must hold back. */
    private const val MAX_QUEUED = 64

    private class Batch(val samples: FloatArray, val dueNanos: Long, val generation: Int)

    @Volatile private var enabled = false
    @Volatile private var generation = 0
    private val queue = ArrayBlockingQueue<Batch>(MAX_QUEUED)

    val isEnabled: Boolean get() = enabled

    // Only touched from librespot's player thread (the single PcmOutput caller).
    private var batch = FloatArray(FRAME_SAMPLES)
    private var filled = 0

    private val worker: Lazy<Thread> = lazy {
        Thread({
            while (true) {
                val next = try {
                    queue.take().also { pending ->
                        // Ambient TV: hand the batch over when it is audible, not when written.
                        val waitNanos = pending.dueNanos - System.nanoTime()
                        if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
                    }
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (enabled && next.generation == generation) runCatching { SpotifyService.onNativePcm(next.samples, PcmOutput.SAMPLE_RATE) }
            }
        }, "spotify-pcm-tap").apply {
            isDaemon = true
            start()
        }
    }

    fun setEnabled(value: Boolean) {
        if (value) worker.value
        enabled = value
        if (!value) queue.clear()
    }

    /** Drops batches whose audio was flushed (pause) so the visual does not replay them. */
    fun flush() {
        generation++
        queue.clear()
        filled = 0
    }

    /**
     * Called with the first [count] floats (interleaved stereo) just written to the AudioTrack;
     * [unplayedFrames] is what the track still holds after that write.
     */
    fun capture(samples: FloatArray, count: Int, unplayedFrames: Int = 0) {
        if (!enabled) {
            filled = 0
            return
        }
        val now = System.nanoTime()
        var i = 0
        while (i + 1 < count) {
            batch[filled++] = ((samples[i] + samples[i + 1]) * 0.5f).coerceIn(-1f, 1f)
            i += 2
            if (filled == FRAME_SAMPLES) {
                val due = now + PcmBuffers.audibleDelayNanos(unplayedFrames, (count - i) / 2, PcmOutput.SAMPLE_RATE)
                if (queue.offer(Batch(batch, due, generation))) batch = FloatArray(FRAME_SAMPLES)
                filled = 0
            }
        }
    }
}
