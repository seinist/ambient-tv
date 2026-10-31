package com.ambient.tv.ui.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keep partial Home updates responsive as section responses arrive close together.
 * The first response is visible immediately; subsequent bursts are conflated.
 * Stop before the caller publishes its final complete row snapshot.
 */
internal class HomePartialPublishQueue(
    scope: CoroutineScope,
    minIntervalMs: Long = 120L,
    private val publish: () -> Unit,
) {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val worker: Job = scope.launch {
        for (ignored in changes) {
            publish()
            delay(minIntervalMs)
        }
    }

    fun request() { changes.trySend(Unit) }

    suspend fun stop() {
        changes.close()
        worker.cancelAndJoin()
    }
}
