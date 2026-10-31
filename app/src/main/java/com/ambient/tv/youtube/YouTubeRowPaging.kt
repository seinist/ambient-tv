package com.ambient.tv.youtube

/** A continuable page without new visible items must back off, never end the row forever. */
internal fun youtubeContinuationStalled(page: YouTubeRowPage?, before: Int, after: Int): Boolean =
    page?.hasMore == true && after <= before

/** One continuation page of a SmartTube row. [hasMore] false marks the end of the row. */
data class YouTubeRowPage(val items: List<YouTubeItem>, val hasMore: Boolean)

/** Appends [incoming] to the row [rowId] in one step, keeping existing order and keys stable. */
internal fun appendYouTubeRowItems(
    rows: List<YouTubeRow>,
    rowId: String,
    incoming: List<YouTubeItem>,
    maxItems: Int = YouTubeRowPaging.MAX_ITEMS_PER_ROW,
): List<YouTubeRow> {
    if (incoming.isEmpty()) return rows
    var changed = false
    val result = rows.map { row ->
        if (row.id != rowId) return@map row
        val known = row.items.mapTo(HashSet()) { it.videoId }
        val fresh = incoming.filter { known.add(it.videoId) }
        if (fresh.isEmpty()) row else {
            changed = true
            row.copy(items = (row.items + fresh).take(maxItems))
        }
    }
    return if (changed) result else rows
}

/**
 * Decides when a SmartTube row loads its next page, like SmartTube's BrowsePresenter:
 * rows below [INITIAL_MIN_ITEMS] continue once after loading, and every row continues when
 * focus is within [ROW_CONTINUE_THRESHOLD] (grids: [GRID_CONTINUE_THRESHOLD]) items of its end.
 * At most one request per row runs; a failure blocks that row for [RETRY_AFTER_MS].
 * A session belongs to one feed/search key; [reset] discards every row state.
 */
internal class YouTubeRowPaging(private val nowMs: () -> Long) {
    enum class Status { IDLE, LOADING, ENDED, FAILED }

    var session: String? = null
        private set
    var token: Long = 0L
        private set
    private val status = HashMap<String, Status>()
    private val failedAt = HashMap<String, Long>()

    fun reset(session: String?) {
        this.session = session
        token++
        status.clear()
        failedAt.clear()
    }

    fun status(rowId: String): Status = status[rowId] ?: Status.IDLE

    fun canLoad(rowId: String, size: Int): Boolean {
        if (session == null || size >= MAX_ITEMS_PER_ROW) return false
        return when (status(rowId)) {
            Status.IDLE -> true
            Status.LOADING, Status.ENDED -> false
            Status.FAILED -> nowMs() - (failedAt[rowId] ?: 0L) >= RETRY_AFTER_MS
        }
    }

    fun isNearEnd(index: Int, size: Int, grid: Boolean): Boolean {
        if (index < 0 || size <= 0) return false
        val threshold = if (grid) GRID_CONTINUE_THRESHOLD else ROW_CONTINUE_THRESHOLD
        return index >= size - threshold
    }

    fun shouldLoadOnFocus(rowId: String, index: Int, size: Int, grid: Boolean): Boolean =
        isNearEnd(index, size, grid) && canLoad(rowId, size)

    /** Rows that continue right after the feed loaded, in display order. */
    fun initialCandidates(rows: List<YouTubeRow>): List<String> =
        rows.filter { it.items.isNotEmpty() && it.items.size < INITIAL_MIN_ITEMS && canLoad(it.id, it.items.size) }.map { it.id }

    /** Marks [rowId] loading; false when it may not load now. */
    fun begin(rowId: String, size: Int): Boolean {
        if (!canLoad(rowId, size)) return false
        status[rowId] = Status.LOADING
        return true
    }

    /** Applies a finished request. Results of an older session ([requestToken]) are ignored. */
    fun finish(rowId: String, requestToken: Long, page: YouTubeRowPage?, sizeAfter: Int): Boolean {
        if (requestToken != token) return false
        status[rowId] = if (page == null || !page.hasMore || sizeAfter >= MAX_ITEMS_PER_ROW) Status.ENDED else Status.IDLE
        failedAt.remove(rowId)
        return true
    }

    /** A cancelled request leaves the row loadable again. */
    fun abort(rowId: String, requestToken: Long) {
        if (requestToken == token && status[rowId] == Status.LOADING) status.remove(rowId)
    }

    fun fail(rowId: String, requestToken: Long) {
        if (requestToken != token) return
        status[rowId] = Status.FAILED
        failedAt[rowId] = nowMs()
    }

    companion object {
        const val ROW_CONTINUE_THRESHOLD = 4
        const val GRID_CONTINUE_THRESHOLD = 10
        const val INITIAL_MIN_ITEMS = 8
        const val RETRY_AFTER_MS = 30_000L
        const val MAX_INITIAL_PARALLEL = 2
        /** Pages without new playable items skipped in one continuation before the row ends. */
        const val MAX_EMPTY_PAGES = 3
        /** Focus must rest this long in a row before it continues; vertical passes never load. */
        const val FOCUS_SETTLE_MS = 350L
        const val MAX_ITEMS_PER_ROW = YouTubeContentCache.MAX_ITEMS_PER_ROW
    }
}
