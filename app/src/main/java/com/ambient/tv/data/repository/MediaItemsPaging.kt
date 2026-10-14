package com.ambient.tv.data.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class MediaItemsPage<T>(val items: List<T>, val total: Int? = null)

/**
 * Read the entire container before caching it; offsets count raw entries, not merged titles.
 * With [truncateAtLimit] a container larger than [maxItems] returns its first [maxItems] entries.
 */
internal suspend fun <T> fetchAllMediaPages(
    pageSize: Int,
    key: (T) -> String,
    maxItems: Int = 100_000,
    truncateAtLimit: Boolean = false,
    onPage: suspend (List<T>) -> Unit = {},
    fetch: suspend (start: Int, size: Int) -> MediaItemsPage<T>,
): List<T> {
    require(pageSize > 0)
    val items = linkedMapOf<String, T>()
    var start = 0
    var expectedTotal: Int? = null
    while (true) {
        currentCoroutineContext().ensureActive()
        if (start >= maxItems) {
            // Browsing shows the first [maxItems] titles instead of failing the whole library.
            check(truncateAtLimit) { "Media container exceeds the safe paging limit" }
            break
        }
        val page = fetch(start, pageSize)
        page.total?.let { total ->
            check(expectedTotal == null || expectedTotal == total) { "Media container changed during paging; retry" }
            expectedTotal = total
        }
        if (page.items.isEmpty()) {
            check(page.total == null || start >= page.total) { "Media page ended before all items were returned" }
            break
        }
        onPage(page.items)
        var added = false
        page.items.forEach { item ->
            if (items.putIfAbsent(key(item), item) == null) added = true
        }
        check(added) { "Media server repeated a page without advancing" }
        start = Math.addExact(start, page.items.size)
        if (page.total != null && start >= page.total) break
        // With no total, even a short page may be a server-side cap. Continue until empty.
    }
    return items.values.toList()
}

/**
 * Collects per-library results: failed libraries are skipped as long as at least one library
 * answered. Only when every library failed is the first error rethrown.
 */
internal fun <T> List<Result<List<T>>>.successfulOrThrow(): List<T> {
    if (isNotEmpty() && none { it.isSuccess }) throw first().exceptionOrNull()!!
    return flatMap { it.getOrDefault(emptyList()) }
}
