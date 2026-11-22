package com.ambient.tv.ui.games

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** LibretroDroid owns one native frontend. A UI wait timeout is not proof that it was destroyed. */
internal class GameNativeSessionGate(private val timeoutMs: Long = 10_000) {
    @Volatile private var closed: CompletableFuture<Unit>? = null

    @Synchronized fun tryOpen(): CompletableFuture<Unit>? {
        val previous = closed
        if (previous != null && (!previous.isDone || previous.isCompletedExceptionally)) return null
        return CompletableFuture<Unit>().also { closed = it }
    }

    /** IO-only and bounded. Late confirmed teardown naturally permits the next attempt. */
    fun awaitClosed() { closed?.get(timeoutMs, TimeUnit.MILLISECONDS) }
}

internal val gameNativeSessions = GameNativeSessionGate()
