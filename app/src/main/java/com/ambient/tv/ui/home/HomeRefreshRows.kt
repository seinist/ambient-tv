package com.ambient.tv.ui.home

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.ContentRowData

/** Keep visible aggregate rows intact until all libraries contributing to them have answered. */
internal fun mergePartialHomeRows(
    current: List<ContentRowData>,
    incoming: List<ContentRowData>,
    system: String,
    previewsComplete: Boolean,
    releasesComplete: Boolean,
): List<ContentRowData> {
    val replacements = incoming.associateBy { it.id }
    val currentIds = current.mapTo(HashSet()) { it.id }
    val updated = current.map { row ->
        val pending = when {
            row.id == "$system.recentlyAddedMovies" || row.id.startsWith("$system.collection.") -> !previewsComplete
            row.id == "$system.recentlyReleased" -> !releasesComplete
            else -> false
        }
        if (pending) row else replacements[row.id] ?: row
    }
    // First load remains progressive; absent/empty rows are reconciled by the final publication.
    return updated + incoming.filterNot { it.id in currentIds }
}

/**
 * Hydrated media rows may arrive after independent Radio/SmartTube/Photo rows already
 * published a Loaded state. Merge by stable row id instead of skipping disk hydration.
 * The visible row wins if a newer network response already supplied the same id.
 */
internal fun mergeColdStartHomeRows(
    visible: List<ContentRowData>,
    restored: List<ContentRowData>,
): List<ContentRowData> {
    val visibleIds = visible.mapTo(HashSet()) { it.id }
    return restored.filterNot { it.id in visibleIds } + visible
}

/** Prefer a reachable duplicate, but retain titles whose only copy is a saved offline preview. */
internal fun homeContinueItems(
    itemsBySystem: Map<String, List<MediaItem>>,
    offline: Set<String>,
    limit: Int,
): List<MediaItem> = itemsBySystem.asSequence()
    .flatMap { (system, items) -> items.asSequence().map { (system !in offline) to it } }
    .sortedWith(compareByDescending<Pair<Boolean, MediaItem>> { it.first }
        .thenByDescending { it.second.lastViewedAt ?: 0L })
    .distinctBy { it.second.canonicalContentKey }
    .map { it.second }
    .sortedByDescending { it.lastViewedAt ?: 0L }
    .take(limit)
    .toList()
