package com.ambient.tv.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keep the last background browse snapshot while a TV remote/scroll gesture is active.
 * Does not delay the first (cold) content: callers decide whether [deferWhile] applies.
 *
 * All calls and deliveries must run on the UI dispatcher. The pending value is not Compose
 * state, so incoming snapshots do not invalidate a LazyColumn while it is moving.
 */
internal class DeferredBrowsePublisher<T : Any>(
    private val scope: CoroutineScope,
    private val deferWhile: () -> Boolean,
    private val onPublish: (T) -> Unit,
) {
    private var pending: T? = null
    private var worker: Job? = null

    fun submit(value: T) {
        if (!deferWhile()) {
            cancelPending()
            onPublish(value)
            return
        }
        pending = value // CONFLATED: do not replay intermediate feed/row states.
        if (worker?.isActive == true) return
        worker = scope.launch {
            try {
                while (true) {
                    while (deferWhile()) delay(32L)
                    val latest = pending ?: break
                    pending = null
                    onPublish(latest)
                }
            } finally {
                if (worker === coroutineContext[Job]) worker = null
            }
        }
    }

    /** A new feed, account or playback return invalidates old publications. */
    fun cancelPending() {
        pending = null
        worker?.cancel()
        worker = null
    }
}
