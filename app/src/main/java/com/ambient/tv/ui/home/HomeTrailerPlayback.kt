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
    val pendingTarget: HomeFocusSnapshot? get() = target.takeUnless { landed }

    fun start(row: ContentRowData, videoId: String) {
        source = row
        origin = HomeFocusSnapshot(HOME_TRAILER_ITEM_PREFIX + videoId, row.id)
        target = null
        landed = false
    }

    fun restore(rowId: String, videoId: String): HomeFocusSnapshot? {
        val row = source?.takeIf { it.id == rowId } ?: return null
        val key = HOME_TRAILER_ITEM_PREFIX + videoId
        if (row.items.none { it.ratingKey == key }) return null
        return HomeFocusSnapshot(key, rowId).also {
            target = it
            landed = false
            revision++
        }
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
    }
}
