package com.ambient.tv.ui.games

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A reservation exists before native capture starts, so reopening also waits for that capture. */
internal class GameCheckpointTicket<T> internal constructor(private val result: CompletableFuture<T>) {
    val pending: Boolean get() = !result.isDone
    fun complete(value: T): Boolean = result.complete(value)
    fun fail(error: Throwable) { result.completeExceptionally(error) }
}

/**
 * Serial checkpoint persistence with bounded capture and read barriers. Slow native capture
 * releases the IO worker after its timeout, while its reservation blocks a new game's read.
 * When that capture eventually completes, it is persisted and the barrier recovers naturally.
 */
internal class GameCheckpointQueue(
    private val writer: ExecutorService,
    private val captureTimeoutMs: Long = 8_000,
    private val barrierTimeoutMs: Long = 20_000,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val sequence = AtomicLong()
    private val pendingCaptures = ConcurrentHashMap.newKeySet<Long>()
    private val captureFailure = AtomicReference<Throwable?>()
    /** Writer-thread only: an old late capture cannot undo a newer checkpoint or reset. */
    private var appliedSequence = 0L

    fun <T> reserve(write: (T) -> Unit): GameCheckpointTicket<T> {
        val result = CompletableFuture<T>()
        val number = sequence.incrementAndGet()
        pendingCaptures += number
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(captureTimeoutMs)
        fun finish(value: T?, error: Throwable?) {
            try {
                if (error != null) {
                    captureFailure.compareAndSet(null, error)
                    onFailure(error)
                } else if (number > appliedSequence) {
                    @Suppress("UNCHECKED_CAST")
                    write(value as T)
                    appliedSequence = number
                }
            } catch (failure: Exception) {
                captureFailure.compareAndSet(null, failure)
                onFailure(failure)
            } finally {
                pendingCaptures -= number
            }
        }
        writer.execute {
            try {
                finish(result.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS), null)
            } catch (_: TimeoutException) {
                // Do not park the process-wide writer on a stuck renderer. The ticket remains
                // pending, and completion later re-enters the FIFO instead of poisoning it.
                result.whenComplete { value, error -> writer.execute { finish(value, error) } }
            } catch (error: Exception) {
                finish(null, error)
            }
        }
        return GameCheckpointTicket(result)
    }

    fun execute(write: () -> Unit) {
        val number = sequence.incrementAndGet()
        writer.execute {
            runCatching(write).onFailure(onFailure)
            appliedSequence = maxOf(appliedSequence, number)
        }
    }

    /** IO-only; report incomplete capture/IO instead of loading stale state or waiting forever. */
    fun awaitPendingWrites() {
        writer.submit {}.get(barrierTimeoutMs, TimeUnit.MILLISECONDS)
        check(pendingCaptures.isEmpty()) { "Previous game capture has not finished" }
        // A completed failed capture is reported once. A later fresh session can use the last
        // intact on-disk save after that error, provided native teardown has also completed.
        captureFailure.getAndSet(null)?.let { throw IllegalStateException("Previous game capture failed", it) }
    }
}
