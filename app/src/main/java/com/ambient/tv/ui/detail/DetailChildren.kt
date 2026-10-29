package com.ambient.tv.ui.detail

import com.ambient.tv.domain.model.MediaItem

/** Episodes show their siblings; containers show their own children. */
internal fun detailChildrenKey(item: MediaItem, detailKey: String): String? = when (item.type) {
    "show", "season", "collection" -> detailKey
    "episode" -> item.parentRatingKey?.takeIf(String::isNotBlank)
    else -> null
}

private fun singleDetailSeason(item: MediaItem, children: List<MediaItem>?): MediaItem? =
    children?.singleOrNull()?.takeIf { item.type == "show" && it.type == "season" }

/**
 * Cache reads use the same shape as the completed row. A single season is an intermediate
 * container, never a temporary row: show its cached episodes, or wait for their load.
 */
internal fun cachedDetailChildren(
    item: MediaItem,
    detailKey: String,
    cachedChildren: (String) -> List<MediaItem>?,
): List<MediaItem> {
    val childKey = detailChildrenKey(item, detailKey) ?: return emptyList()
    val children = cachedChildren(childKey) ?: return emptyList()
    val onlySeason = singleDetailSeason(item, children) ?: return children
    return cachedChildren(onlySeason.ratingKey).orEmpty()
}

/** Publish once the child row is resolved; an episode-load failure remains a failure. */
internal suspend fun loadDetailChildren(
    item: MediaItem,
    childKey: String,
    getChildren: suspend (String) -> Result<List<MediaItem>>,
): Result<List<MediaItem>> {
    val initial = getChildren(childKey)
    val onlySeason = singleDetailSeason(item, initial.getOrNull()) ?: return initial
    return getChildren(onlySeason.ratingKey)
}
