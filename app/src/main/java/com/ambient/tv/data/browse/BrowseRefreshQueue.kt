package com.ambient.tv.data.browse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/** Entries share cache expiry with recovery, but do not announce a new connection to other views. */
internal class BrowseRefreshQueue {
    private data class Entry(
        val sources: Set<String>,
        val ready: CompletableDeferred<Map<String, Long>>,
    )

    private val recovery = mutableSetOf<String>()
    private val entries = mutableListOf<Entry>()
    val signals = Channel<Unit>(Channel.CONFLATED)

    @Synchronized fun recover(sources: Set<String>) {
        if (sources.isEmpty()) return
        recovery.addAll(sources)
        signals.trySend(Unit)
    }

    @Synchronized fun enter(sources: Set<String>): CompletableDeferred<Map<String, Long>> {
        val ready = CompletableDeferred<Map<String, Long>>()
        entries += Entry(sources.toSet(), ready)
        signals.trySend(Unit)
        return ready
    }

    @Synchronized fun drain(): Batch {
        val active = entries.filter { it.ready.isActive }
        val recovered = recovery.toSet()
        val batch = Batch(
            sources = recovered + active.flatMap { it.sources },
            recoveredSources = recovered,
            waiters = active.map { it.ready },
        )
        recovery.clear()
        entries.clear()
        return batch
    }

    /** Include requests that arrived while a remote mutation held an expiry lock. */
    suspend fun preparePending(
        locks: BrowseSourceLocks,
        expire: suspend (Set<String>) -> Unit,
        publish: (Set<String>) -> Map<String, Long>,
    ) {
        var batch = drain()
        try {
            while (true) {
                val held = batch.sources
                var prepared = false
                locks.withSources(held) {
                    batch = batch.merge(drain())
                    // More source families may have arrived during the wait. Reacquire all locks
                    // in sorted order before expiring anything or starting any of these entries.
                    if (locks.covers(held, batch.sources)) {
                        if (batch.sources.isNotEmpty()) expire(batch.sources)
                        batch.complete(publish(batch.recoveredSources))
                        prepared = true
                    }
                }
                if (prepared) return
            }
        } catch (error: Throwable) {
            batch.fail(error)
            throw error
        }
    }

    class Batch internal constructor(
        val sources: Set<String>,
        val recoveredSources: Set<String>,
        private val waiters: List<CompletableDeferred<Map<String, Long>>>,
    ) {
        fun merge(other: Batch) = Batch(sources + other.sources, recoveredSources + other.recoveredSources, waiters + other.waiters)
        /** Called only after expiry and publication of any simultaneous recovery. */
        fun complete(revisions: Map<String, Long>) { waiters.forEach { it.complete(revisions) } }
        fun fail(error: Throwable) { waiters.forEach { it.completeExceptionally(error) } }
    }
}
