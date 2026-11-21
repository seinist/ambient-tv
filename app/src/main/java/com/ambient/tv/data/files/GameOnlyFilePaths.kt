package com.ambient.tv.data.files

import com.ambient.tv.games.library.GameLibraryFolder

/**
 * Filters the normal Files view, not the transport or Libretro player.
 * Paths must be source-relative. Slash boundaries avoid hiding "SNES-Extras"
 * when only "SNES" is assigned to Games.
 */
internal fun isGamesOnlyFilePath(
    sourceId: String, path: String, assignments: List<GameLibraryFolder>,
): Boolean = assignments.any { folder ->
    folder.sourceId == sourceId && !folder.alsoShowInFiles && (
        folder.path.isEmpty() || path == folder.path ||
            path.startsWith(folder.path.trimEnd('/') + "/")
    )
}
