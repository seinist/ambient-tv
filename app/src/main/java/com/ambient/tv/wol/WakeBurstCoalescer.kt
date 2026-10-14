package com.ambient.tv.wol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class WakeBurstOutcome { SENT, COALESCED }

/** Coalesces both in-flight and recently completed bursts without serializing different NICs. */
internal class WakeBurstCoalescer(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Attempt(
        val startedAtMs: Long,
        val completion: CompletableDeferred<Result<Unit>>,
    )

    private val mutex = Mutex()
    private val attempts = mutableMapOf<String, Attempt>()

    suspend fun run(
        targetKey: String,
        coalesceWindowMs: Long,
        dispatch: suspend () -> Result<Unit>,
    ): Result<WakeBurstOutcome> {
        require(coalesceWindowMs >= 0) { "coalesceWindowMs must not be negative" }
        val now = nowMs()
        var ownsAttempt = false
        val attempt = mutex.withLock {
            attempts.entries.removeAll { now - it.value.startedAtMs >= coalesceWindowMs }
            attempts[targetKey] ?: Attempt(
                startedAtMs = now,
                completion = CompletableDeferred(),
            ).also {
                attempts[targetKey] = it
                ownsAttempt = true
            }
        }

        if (!ownsAttempt) {
            return attempt.completion.await().map { WakeBurstOutcome.COALESCED }
        }

        val result = try {
            dispatch()
        } catch (error: Throwable) {
            attempt.completion.complete(Result.failure(error))
            mutex.withLock { if (attempts[targetKey] === attempt) attempts.remove(targetKey) }
            throw error
        }
        attempt.completion.complete(result)
        if (result.isFailure) {
            mutex.withLock { if (attempts[targetKey] === attempt) attempts.remove(targetKey) }
        }
        return result.map { WakeBurstOutcome.SENT }
    }
}
