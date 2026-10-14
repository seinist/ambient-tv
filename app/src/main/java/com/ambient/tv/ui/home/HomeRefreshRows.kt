package com.ambient.tv.ui.home

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
