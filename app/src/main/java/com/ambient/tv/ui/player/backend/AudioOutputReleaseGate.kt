package com.ambient.tv.ui.player.backend

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How long a new player waits for the previous one to close its native output before it reports
 * a real error. A live stream's teardown (network read, VLC 4's stop, a passthrough AudioTrack)
 * regularly takes several seconds; that is not a hang.
 */
internal const val PREVIOUS_RELEASE_LIMIT_MS = 15_000L

/** How the wait for the previous player's teardown ended. */
internal enum class PreviousReleaseOutcome {
    /** Closed normally. */
    READY,
    /** Its teardown failed: logged once by this start, which opens anyway. */
    READY_AFTER_FAILURE,
    /** Still closing after [PREVIOUS_RELEASE_LIMIT_MS]: this start reports an error. */
    TIMED_OUT,
}

/** Null while the wait is still running. */
internal fun previousReleaseOutcome(done: Boolean, failed: Boolean, waitedMs: Long,
    limitMs: Long = PREVIOUS_RELEASE_LIMIT_MS): PreviousReleaseOutcome? = when {
    done && failed -> PreviousReleaseOutcome.READY_AFTER_FAILURE
    done -> PreviousReleaseOutcome.READY
    waitedMs >= limitMs -> PreviousReleaseOutcome.TIMED_OUT
    else -> null
}

/** A new native output must not open while an earlier owner is still closing. */
internal class AudioOutputReleaseGate {
    private val pending = mutableListOf<CompletableFuture<Unit>>()

    /** Finished entries leave the gate, failed ones included: a failure only reaches the next start. */
    @Synchronized fun beginRelease(): CompletableFuture<Unit> = CompletableFuture<Unit>().also {
        pending.removeAll { entry -> entry.isDone }
        pending.add(it)
    }

    /**
     * The releases a new owner waits for. Entries that are already finished (failed included) are
     * handed to this start only and leave the gate, so one failed teardown cannot block every
     * later playback until the app restarts.
     */
    @Synchronized fun snapshot(): PreviousRelease {
        val entries = pending.toList()
        pending.removeAll { it.isDone }
        return PreviousRelease(this, entries)
    }

    /** A teardown that outlived [PREVIOUS_RELEASE_LIMIT_MS] no longer holds back later starts. */
    @Synchronized internal fun abandon(entries: List<CompletableFuture<Unit>>) {
        pending.removeAll { entry -> entries.any { it === entry } }
    }

    @Synchronized internal fun pendingCount(): Int = pending.size
}

/** The previous owners' teardown as seen by one starting owner. */
internal class PreviousRelease(private val gate: AudioOutputReleaseGate, private val entries: List<CompletableFuture<Unit>>) {
    val future: CompletableFuture<Void> = CompletableFuture.allOf(*entries.toTypedArray())
    val isDone: Boolean get() = future.isDone
    val failed: Boolean get() = entries.any { it.isCompletedExceptionally }
    val failure: Throwable? get() = entries.firstNotNullOfOrNull { entry ->
        if (!entry.isCompletedExceptionally) null
        else runCatching { entry.join(); null }.exceptionOrNull()?.let { it.cause ?: it }
    }

    /**
     * Calls [onSettled] exactly once: when every previous teardown finished, or with
     * [PreviousReleaseOutcome.TIMED_OUT] after [limitMs]. [delay] runs its block after the given time
     * (a main-thread Handler in the app, a manual clock in tests). The callback runs on the thread
     * that settled it; callers post to their own thread.
     */
    fun whenSettled(limitMs: Long = PREVIOUS_RELEASE_LIMIT_MS, delay: (Long, () -> Unit) -> Unit,
        onSettled: (PreviousReleaseOutcome) -> Unit) {
        val fired = AtomicBoolean(false)
        future.whenComplete { _, _ ->
            if (fired.compareAndSet(false, true))
                onSettled(requireNotNull(previousReleaseOutcome(true, failed, 0L, limitMs)))
        }
        if (fired.get()) return
        delay(limitMs) {
            if (!isDone && fired.compareAndSet(false, true)) {
                gate.abandon(entries)
                onSettled(PreviousReleaseOutcome.TIMED_OUT)
            }
        }
    }
}

internal val nativeAudioReleases = AudioOutputReleaseGate()
