package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.YouTubeRow

/**
 * Capture the exact visible sequence at the moment of the click.
 * Sectioned layouts navigate only inside the clicked row; flat grids, lists, walls and
 * cinematic views navigate their complete displayed sequence (including all source rows).
 *
 * Input rows are already filtered and sorted with applyYouTubeViewOptions.
 */
internal fun youtubePlaybackSourceRow(
    rows: List<YouTubeRow>,
    videoId: String,
    preferredRowId: String?,
    sectioned: Boolean,
): YouTubeRow? {
    if (sectioned) {
        return rows.firstOrNull { it.id == preferredRowId && it.items.any { item -> item.videoId == videoId } }
            ?: rows.firstOrNull { it.items.any { item -> item.videoId == videoId } }
    }
    val items = rows.flatMap { it.items }.distinctBy { it.videoId }
    if (items.none { it.videoId == videoId }) return null
    return rows.singleOrNull()?.copy(items = items)
        ?: YouTubeRow("Browse", items, id = "youtube:flat-browse")
}
