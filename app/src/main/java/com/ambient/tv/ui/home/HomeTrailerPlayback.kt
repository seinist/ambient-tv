package com.ambient.tv.ui.home

import com.ambient.tv.ui.common.ContentRowData
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow

internal const val HOME_TRAILER_ITEM_PREFIX = "trailer-video:"

internal fun homeTrailerVideoId(ratingKey: String): String? = ratingKey
    .takeIf { it.startsWith(HOME_TRAILER_ITEM_PREFIX) }
    ?.removePrefix(HOME_TRAILER_ITEM_PREFIX)
    ?.takeIf(String::isNotBlank)

/** The clicked Home row is the queue, including when touch has never focused any of its cards. */
internal fun homeTrailerPlaybackRow(row: ContentRowData, videoId: String): YouTubeRow? {
    if (!row.id.startsWith("trailers.")) return null
    val items = row.items.mapNotNull { item ->
        homeTrailerVideoId(item.ratingKey)?.let { id ->
            YouTubeItem(id, item.title, imageUrl = item.thumbUrl, backgroundUrl = item.artUrl)
        }
    }.distinctBy { it.videoId }
    return items.takeIf { it.any { item -> item.videoId == videoId } }
        ?.let { YouTubeRow(row.title, it, row.id) }
}

/** Keep the played row visible across a background TMDB refresh until the user browses away. */
internal class HomeTrailerPlaybackReturn {
    var source: ContentRowData? = null
        private set
    var target: HomeFocusSnapshot? = null
        private set
    var revision: Int = 0
        private set
    private var landed = false
    private var origin: HomeFocusSnapshot? = null
    private var preserveForNextEntry = false
    val pendingTarget: HomeFocusSnapshot? get() = target.takeUnless { landed }

    fun start(row: ContentRowData, videoId: String) {
        startItem(row, HOME_TRAILER_ITEM_PREFIX + videoId)
    }

    /** Other direct-play Home providers use their existing media keys unchanged. */
    fun startItem(row: ContentRowData, itemKey: String) {
        source = row
        origin = HomeFocusSnapshot(itemKey, row.id)
        target = null
        landed = false
        preserveForNextEntry = false
    }

    fun restore(rowId: String, videoId: String): HomeFocusSnapshot? {
        return restoreItem(rowId, HOME_TRAILER_ITEM_PREFIX + videoId)
    }

    fun restoreItem(rowId: String, itemKey: String, returnRevision: Int = revision + 1): HomeFocusSnapshot? {
        val row = source?.takeIf { it.id == rowId } ?: return null
        if (row.items.none { itemKey in it.allRatingKeys }) return null
        return HomeFocusSnapshot(itemKey, rowId).also {
            target = it
            landed = false
            preserveForNextEntry = true
            revision = returnRevision
        }
    }

    /** A return can be consumed before composition or during enter; both own just this entry. */
    fun enter() {
        if (preserveForNextEntry) preserveForNextEntry = false else clear()
    }

    fun focused(rowId: String?, ratingKey: String?) {
        val returned = target ?: run {
            // A companion launch can leave Home visible and never report a local return.
            if (origin != null && origin != HomeFocusSnapshot(ratingKey, rowId)) clear()
            return
        }
        if (returned.rowId == rowId && returned.ratingKey == ratingKey) landed = true
        else if (landed) clear()
    }

    fun row(id: String, fresh: ContentRowData?): ContentRowData? = source?.takeIf { it.id == id } ?: fresh

    fun clear() {
        source = null
        origin = null
        target = null
        landed = false
        preserveForNextEntry = false
    }
}
