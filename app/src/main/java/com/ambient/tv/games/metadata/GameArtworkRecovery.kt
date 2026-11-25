package com.ambient.tv.games.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One bounded, demand-driven worker for missing copies of already identified covers. Revisions
 * wake it without cancelling a download. Each new request reads the current focus/viewport;
 * offscreen cards and whole folders never acquire work merely because they are in the catalog.
 */
internal class GameArtworkRecovery<T>(
    private val priority: GameArtworkPriority,
    private val changes: Flow<Unit>,
    /** Local lookup only. Null means the cover exists or there is no stored image to restore. */
    private val pending: suspend (String) -> T?,
    private val identity: (T) -> String,
    /** False is a completed lookup without an image, such as a cached provider miss. */
    private val restore: suspend (T) -> Boolean,
    private val onFailure: (Exception) -> Unit = {},
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val retryAfterMillis: Long = 60_000L,
    private val maxRemembered: Int = 256,
) {
    private enum class Outcome { RESTORED, UNAVAILABLE, FAILED }
    private data class Attempt(val identity: String?, val at: Long, val outcome: Outcome)

    init { require(retryAfterMillis > 0 && maxRemembered >= priority.maxOwners) }

    suspend fun run(): Unit = coroutineScope {
        val requests = Channel<Unit>(Channel.CONFLATED)
        launch { priority.demands.collect { requests.trySend(Unit) } }
        launch { changes.collect { requests.trySend(Unit) } }
        val attempted = linkedMapOf<GameArtworkDemand, Attempt>()
        try {
            while (true) {
                val visible = priority.demands.value.toSet()
                attempted.keys.retainAll(visible)
                // Only a failed, still-visible request owns a retry timer. Successful images
                // and cached misses do not poll; a revision or new viewport can recheck them.
                val retryAt = attempted.values.filter { it.outcome == Outcome.FAILED }
                    .minOfOrNull { it.at + retryAfterMillis }
                if (retryAt == null) requests.receive()
                else withTimeoutOrNull((retryAt - nowMillis()).coerceAtLeast(1L)) { requests.receive() }
                val checked = hashSetOf<GameArtworkDemand>()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val visible = priority.demands.value
                    attempted.keys.retainAll(visible.toSet())
                    checked.retainAll(visible.toSet())
                    val demand = visible.firstOrNull { it !in checked } ?: break
                    checked += demand
                    var key: String? = null
                    try {
                        val target = pending(demand.fileId)
                        if (target == null) {
                            // A restored cover's retention guard outlives the file: another
                            // visible image may evict it later. Off/removed/present failures
                            // have no remaining work and must release their retry timer.
                            if (attempted[demand]?.outcome != Outcome.RESTORED) attempted.remove(demand)
                            continue
                        }
                        // A local query may suspend while the view is rebuilt. The same ID
                        // under a new generation belongs to a fresh request, even if the
                        // intermediate removal was conflated before the collector resumed.
                        if (demand !in priority.demands.value) {
                            attempted.remove(demand)
                            continue
                        }
                        key = identity(target)
                        val now = nowMillis()
                        val previous = attempted[demand]
                        if (previous?.identity == key && (previous.outcome == Outcome.RESTORED ||
                            now - previous.at < retryAfterMillis)) continue
                        // Never expire a successful visible identity merely with time. A slow
                        // set of covers exceeding the disk budget must not download forever.
                        attempted.remove(demand)
                        attempted[demand] = Attempt(key, now, Outcome.UNAVAILABLE)
                        val retained = priority.demands.value.toSet()
                        while (attempted.size > maxRemembered) {
                            val oldest = attempted.keys.first { it !in retained }
                            attempted.remove(oldest)
                        }
                        val restored = supervisorScope {
                            // Folder removal cancels its registered request, never the worker
                            // needed for the remaining folders. Focus changes keep it running.
                            val request = async { restore(target) }
                            try { request.await() }
                            catch (cancelled: CancellationException) {
                                currentCoroutineContext().ensureActive()
                                false
                            }
                        }
                        attempted[demand] = Attempt(key, nowMillis(),
                            if (restored) Outcome.RESTORED else Outcome.UNAVAILABLE)
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    } catch (error: Exception) {
                        // Count cooldown from completion, including a slow network failure.
                        attempted[demand] = Attempt(key, nowMillis(), Outcome.FAILED)
                        onFailure(error)
                    }
                }
            }
        } finally { requests.close() }
    }
}
