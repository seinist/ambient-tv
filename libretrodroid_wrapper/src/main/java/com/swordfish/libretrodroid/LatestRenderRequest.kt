package com.swordfish.libretrodroid

import java.util.concurrent.atomic.AtomicLong

/**
 * Skip superseded work without moving newer requests ahead of unrelated GL FIFO tasks.
 * Already running work cannot be cancelled safely; only not-yet-started work is skipped.
 */
internal class LatestRenderRequest<T>(
    private val enqueue: (() -> Unit) -> Unit,
    private val apply: (T) -> Unit,
) {
    private val generation = AtomicLong()

    fun submit(value: T) {
        val ticket = generation.incrementAndGet()
        enqueue {
            if (generation.get() == ticket) apply(value)
        }
    }
}
