package com.ambient.tv.data.files

import com.ambient.tv.games.library.GameLibraryFolder

/** Slash/document-ID boundaries keep sibling paths such as SNES-Extras independent. */
private fun insideFileTree(path: String, root: String): Boolean =
    root.isEmpty() || path == root || path.startsWith(root.trimEnd('/') + "/")

/**
 * A source may be Games-only with one explicitly published Files subtree. Hidden
 * ancestors are navigable only when they lead to a visible descendant.
 * The most-specific folder assignment decides Files visibility.
 */
internal fun filePathVisibleInBrowser(
    sourceId: String,
    path: String,
    sourceVisible: Boolean,
    assignments: List<GameLibraryFolder>,
): Boolean {
    val folders = assignments.filter { it.sourceId == sourceId }
    val matching = folders.filter { insideFileTree(path, it.path) }
        .maxByOrNull { it.path.length }
    val navigatesToVisibleChild = folders.any {
        it.alsoShowInFiles && insideFileTree(it.path, path)
    }
    return when (matching?.alsoShowInFiles) {
        true -> true
        false -> navigatesToVisibleChild
        null -> sourceVisible || navigatesToVisibleChild
    }
}

/** Compatibility helper for hiding Games trees in an otherwise visible Files source. */
internal fun isGamesOnlyFilePath(
    sourceId: String, path: String, assignments: List<GameLibraryFolder>,
): Boolean = !filePathVisibleInBrowser(sourceId, path, true, assignments)
