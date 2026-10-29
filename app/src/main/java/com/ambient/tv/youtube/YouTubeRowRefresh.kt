package com.ambient.tv.youtube

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * A refresh starts again at each row's first page. Refill only rows that were already expanded,
 * stopping at their former extent (or the actual end). The caller keeps its visible snapshot until
 * this completes; failed or non-advancing continuations must not replace it with a shorter page.
 */
internal suspend fun refreshYouTubeRowExtents(
    freshRows: List<YouTubeRow>,
    previousRows: List<YouTubeRow>,
    continueRow: suspend (String) -> YouTubeRowPage?,
): List<YouTubeRow> = coroutineScope {
    if (previousRows.isEmpty()) return@coroutineScope freshRows
    val targets = previousRows.associate { it.id to it.items.size.coerceAtMost(YouTubeRowPaging.MAX_ITEMS_PER_ROW) }
    val permits = Semaphore(YouTubeRowPaging.MAX_INITIAL_PARALLEL)
    freshRows.map { row ->
        async {
            permits.withPermit {
                val items = row.items.distinctBy { it.videoId }.take(YouTubeRowPaging.MAX_ITEMS_PER_ROW).toMutableList()
                val target = targets[row.id] ?: items.size
                val known = items.mapTo(HashSet()) { it.videoId }
                var emptyPages = 0
                var pages = 0
                while (items.size < target) {
                    currentCoroutineContext().ensureActive()
                    // An upstream playlist that never ends cannot turn recovery into a crawl.
                    if (++pages > YouTubeRowPaging.MAX_ITEMS_PER_ROW) {
                        throw IOException("YouTube row refresh exceeded its page limit")
                    }
                    val page = continueRow(row.id)
                    currentCoroutineContext().ensureActive()
                    if (page == null) break
                    val before = items.size
                    for (item in page.items) {
                        if (items.size >= YouTubeRowPaging.MAX_ITEMS_PER_ROW) break
                        if (known.add(item.videoId)) items += item
                    }
                    if (!page.hasMore || items.size >= target) break
                    emptyPages = if (items.size == before) emptyPages + 1 else 0
                    if (emptyPages >= YouTubeRowPaging.MAX_EMPTY_PAGES) {
                        throw IOException("YouTube row refresh did not advance")
                    }
                }
                row.copy(items = items)
            }
        }
    }.awaitAll()
}
