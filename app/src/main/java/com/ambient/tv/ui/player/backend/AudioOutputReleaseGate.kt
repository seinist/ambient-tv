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
    /** Native destruction failed; ownership is unknown. Fail closed until process restart. */
    BLOCKED,
    /**
     * Still closing after [PREVIOUS_RELEASE_LIMIT_MS]: report the timeout without transferring ownership.
     */
    TIMED_OUT,
}

/** Null while the wait is still running. */
internal fun previousReleaseOutcome(done: Boolean, failed: Boolean, waitedMs: Long,
    limitMs: Long = PREVIOUS_RELEASE_LIMIT_MS, blocked: Boolean = false): PreviousReleaseOutcome? = when {
    done && blocked -> PreviousReleaseOutcome.BLOCKED
    done && failed -> PreviousReleaseOutcome.READY_AFTER_FAILURE
    done -> PreviousReleaseOutcome.READY
    waitedMs >= limitMs -> PreviousReleaseOutcome.TIMED_OUT
    else -> null
}

/** A failed native destruction is different from a failed ancillary cleanup. */
internal class NativeOutputReleaseUncertain(cause: Throwable) : IllegalStateException("Native output ownership could not be retired", cause)

private fun CompletableFuture<Unit>.uncertain(): Boolean = isCompletedExceptionally &&
    (runCatching { join() }.exceptionOrNull()?.let { it.cause ?: it } is NativeOutputReleaseUncertain)

/** A new native output must not open while an earlier owner is still closing. */
internal class AudioOutputReleaseGate {
    private val pending = mutableListOf<CompletableFuture<Unit>>()

    /** Finished entries leave the gate, except uncertain native destruction. */
    @Synchronized fun beginRelease(): CompletableFuture<Unit> = CompletableFuture<Unit>().also {
        pending.removeAll { entry -> entry.isDone && !entry.uncertain() }
        pending.add(it)
    }

    /**
     * The releases a new owner waits for. Entries that are already finished (ordinary failures included) are
     * handed to this start only and leave the gate, so one failed teardown cannot block every
     * later playback until the app restarts. Uncertain native ownership is deliberately retained.
     */
    @Synchronized fun snapshot(): PreviousRelease {
        val entries = pending.toList()
        pending.removeAll { it.isDone && !it.uncertain() }
        return PreviousRelease(entries)
    }

    @Synchronized internal fun pendingCount(): Int = pending.size
    @Synchronized fun hasUncertainOwnership(): Boolean = pending.any { it.uncertain() }
}

/** The previous owners' teardown as seen by one starting owner. */
internal class PreviousRelease(private val entries: List<CompletableFuture<Unit>>) {
    val future: CompletableFuture<Void> = CompletableFuture.allOf(*entries.toTypedArray())
    val isDone: Boolean get() = future.isDone
    val failed: Boolean get() = entries.any { it.isCompletedExceptionally }
    val blocked: Boolean get() = entries.any { it.uncertain() }
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
                onSettled(requireNotNull(previousReleaseOutcome(true, failed, 0L, limitMs, blocked)))
        }
        if (fired.get()) return
        delay(limitMs) {
            if (!isDone && fired.compareAndSet(false, true)) {
                // A timeout is not a release. Subsequent owners must still wait.
                onSettled(PreviousReleaseOutcome.TIMED_OUT)
            }
        }
    }
}

/** Retain the Java owner, including its Surface/scene references, if a
 * native deadline expires. Never cancel or destroy it from the watchdog. */
internal object MpvRetainedOwners {
    private val retained = mutableListOf<Any>()
    @Synchronized fun retain(owner: Any) {
        if (retained.none { it === owner }) retained.add(owner)
    }
}

internal fun armNativeRetirementDeadline(
    release: CompletableFuture<Unit>,
    limitMs: Long = PREVIOUS_RELEASE_LIMIT_MS,
    delay: (Long, () -> Unit) -> Unit,
    onQuarantine: () -> Unit,
) {
    delay(limitMs) {
        if (release.completeExceptionally(NativeOutputReleaseUncertain(
                java.util.concurrent.TimeoutException("Native retirement exceeded ${limitMs}ms")))) {
            onQuarantine()
        }
    }
}

internal val nativeAudioReleases = AudioOutputReleaseGate()
