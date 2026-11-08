package com.ambient.tv.youtube

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val YOUTUBE_REFRESH_BATCH_MS = 350L

/**
 * A subscription observable can remain open after emitting its rows. Publish its latest complete
 * partial snapshot once per bounded window, so bursts cause one insertion transition without
 * waiting for an upstream completion or for an indefinitely postponed debounce. A finished or
 * cancelled request discards pending batches; its caller applies the final snapshot itself.
 */
internal suspend fun <T> withYouTubeRefreshBatches(
    onBatch: suspend (List<YouTubeRow>) -> Unit,
    load: suspend (publish: (List<YouTubeRow>) -> Unit) -> T,
): T = coroutineScope {
    val pending = Channel<List<YouTubeRow>>(Channel.CONFLATED)
    val batches = launch {
        for (first in pending) {
            delay(YOUTUBE_REFRESH_BATCH_MS)
            var latest = first
            while (true) latest = pending.tryReceive().getOrNull() ?: break
            onBatch(latest)
        }
    }
    try {
        load { pending.trySend(it) }
    } finally {
        pending.close()
        batches.cancelAndJoin()
    }
}
