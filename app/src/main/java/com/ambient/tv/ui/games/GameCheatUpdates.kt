package com.ambient.tv.ui.games

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Main-thread requests, one ordered native-thread batch; no caller waits for a core callback. */
internal class GameCheatUpdates(
    private val enqueue: (() -> Unit) -> Unit,
    private val reset: () -> Unit,
    private val enable: (Int, String) -> Unit,
) {
    private val open = AtomicBoolean(true)
    private val generation = AtomicLong()
    private var requested: List<String>? = null

    fun apply(codes: List<String>) {
        if (!open.get() || requested == codes) return
        val snapshot = codes.toList()
        requested = snapshot
        val revision = generation.incrementAndGet()
        enqueue {
            fun current() = open.get() && generation.get() == revision
            if (!current()) return@enqueue
            reset()
            snapshot.forEachIndexed { index, code ->
                if (!current()) return@enqueue
                enable(index, code)
            }
        }
    }

    /** Session teardown never lets a queued old update re-arm cheats. */
    fun close() {
        open.set(false)
        generation.incrementAndGet()
    }
}
