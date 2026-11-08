package com.ambient.tv.ui.youtube

import com.ambient.tv.ui.common.ContentFocusTarget
import com.ambient.tv.ui.common.resolveContentFocusTarget
import com.ambient.tv.youtube.YouTubeRow

/** The raw rows, visible focus target and one stable identity for the return's first layout. */
internal data class PreparedYouTubeBrowseReturn(
    val rows: List<YouTubeRow>,
    val target: ContentFocusTarget,
    val revision: Int,
)

/** Mirrors feed/channel/search ownership without depending on a later load or entry effect. */
internal fun youtubeBrowseReturnScope(feedKey: String, channelId: String?, query: String): String {
    val search = query.trim().takeIf { it.length >= 2 }?.lowercase()
    return when {
        channelId != null && search != null -> "channel:$channelId:search:$search"
        channelId != null -> "channel:$channelId"
        search != null -> "search:$search"
        else -> "feed:$feedKey"
    }
}

/**
 * Pure, non-consuming projection used before composition builds any Lazy state. The current
 * view options still apply: if watching hid the video, return to the first remaining card.
 * A new account/language cache generation invalidates the old playback visit entirely.
 */
internal fun prepareYouTubeBrowseReturn(
    scope: String,
    cacheRevision: Long,
    pending: YouTubeBrowsePlaybackContext.ReturnSnapshot?,
    sort: YouTubeSort,
    filter: YouTubeFilter,
    descending: Boolean,
): PreparedYouTubeBrowseReturn? {
    val snapshot = pending?.takeIf { it.scope == scope && it.cacheRevision == cacheRevision } ?: return null
    val visibleRows = applyYouTubeViewOptions(snapshot.rows, sort, filter, descending)
    val target = resolveContentFocusTarget(snapshot.target.videoId, snapshot.target.rowId,
        visibleRows.map { row -> row.id to row.items.map { it.videoId } })
    return PreparedYouTubeBrowseReturn(snapshot.rows, target, snapshot.revision)
}
