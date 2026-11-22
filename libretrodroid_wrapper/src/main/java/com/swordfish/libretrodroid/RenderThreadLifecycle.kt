package com.swordfish.libretrodroid

import java.util.concurrent.atomic.AtomicLong

/**
 * Serializes native lifecycle work with frames. Callers only enqueue: Android's main thread
 * must never wait for a core, a surface or a driver to acknowledge a lifecycle transition.
 * [enqueue] must use the same FIFO queue as the renderer and checkpoint capture.
 */
internal class RenderThreadLifecycle(
    private val enqueue: (() -> Unit) -> Unit,
    private val resumeNative: () -> Unit,
    private val pauseNative: () -> Unit,
    private val onReady: () -> Unit,
) {
    private val requestGeneration = AtomicLong()
    /** Accessed only by queued render-thread work. */
    private var nativeResumed = false

    fun resume() {
        val generation = requestGeneration.incrementAndGet()
        enqueue {
            if (!nativeResumed) {
                resumeNative()
                nativeResumed = true
            }
            // Only the latest request may enable frames. A later resume does not make an
            // older resume current again when a pause is still queued between the two.
            if (requestGeneration.get() == generation) onReady()
        }
    }

    fun pause() {
        requestGeneration.incrementAndGet()
        enqueue {
            if (nativeResumed) {
                pauseNative()
                nativeResumed = false
            }
        }
    }
}
