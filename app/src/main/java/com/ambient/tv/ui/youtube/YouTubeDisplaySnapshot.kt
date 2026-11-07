package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.YouTubeLocalProgress
import com.ambient.tv.youtube.YouTubeRow

/** The order shown by the UI, also used to decide which source row needs another page. */
internal class YouTubeDisplaySnapshot(
    val rows: List<YouTubeRow>,
    sourceRows: List<YouTubeRow>,
) {
    private val rowsById = buildMap {
        rows.forEach { row -> putIfAbsent(row.id, row) }
    }
    private val sourceSizes = buildMap {
        sourceRows.forEach { row -> putIfAbsent(row.id, row.items.size) }
    }
    private val positions = buildMap {
        rows.forEach { row ->
            putIfAbsent(row.id, buildMap {
                row.items.forEachIndexed { index, item -> putIfAbsent(item.videoId, index) }
            })
        }
    }

    fun row(rowId: String?): YouTubeRow? = if (rowId == null) rows.singleOrNull() else rowsById[rowId]
    fun itemIndex(rowId: String, videoId: String): Int = positions[rowId]?.get(videoId) ?: -1
    fun sourceSize(rowId: String): Int? = sourceSizes[rowId]
}

/**
 * Source rows are immutable snapshots. Comparing their identity keeps focus/viewport callbacks
 * independent of feed size; replacements, options and watch-state changes invalidate together.
 */
internal class YouTubeDisplaySnapshotCache {
    private var sourceRows: List<YouTubeRow>? = null
    private var sort: YouTubeSort? = null
    private var filter: YouTubeFilter? = null
    private var descending = false
    private var progressRevision = 0
    private var snapshot: YouTubeDisplaySnapshot? = null

    @Synchronized
    fun get(
        rows: List<YouTubeRow>,
        sort: YouTubeSort,
        filter: YouTubeFilter,
        descending: Boolean,
        progressRevision: Int = if (filter.usesWatchProgress()) YouTubeLocalProgress.revision else 0,
    ): YouTubeDisplaySnapshot {
        // Ordinary feeds need not be recomputed when only a player's exact position changes.
        val revision = if (filter.usesWatchProgress()) progressRevision else 0
        val current = snapshot
        if (current != null && rows === sourceRows && sort == this.sort && filter == this.filter &&
            descending == this.descending && revision == this.progressRevision) return current
        return YouTubeDisplaySnapshot(applyYouTubeViewOptions(rows, sort, filter, descending), rows).also {
            sourceRows = rows
            this.sort = sort
            this.filter = filter
            this.descending = descending
            this.progressRevision = revision
            snapshot = it
        }
    }
}

private fun YouTubeFilter.usesWatchProgress(): Boolean =
    this == YouTubeFilter.UNWATCHED || this == YouTubeFilter.IN_PROGRESS
