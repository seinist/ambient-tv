package com.ambient.tv.data.repository

import com.ambient.tv.domain.model.MediaItem

/**
 * Picks the episode "continue watching" a show or season should start with: a partly watched
 * episode first, then the first unwatched one. Regular seasons come before specials (season 0),
 * so a show does not start with its specials; specials remain a fallback once all regular
 * episodes are watched.
 */
internal object EpisodeOrdering {
    private const val SPECIALS_SEASON = 0

    val comparator: Comparator<MediaItem> = compareBy<MediaItem> { it.parentIndex == SPECIALS_SEASON }
        .thenBy { it.parentIndex ?: Int.MAX_VALUE }
        .thenBy { it.index ?: Int.MAX_VALUE }

    fun nextUnwatched(episodes: List<MediaItem>, assumeUnwatched: Boolean = false): MediaItem? {
        val sorted = episodes.sortedWith(comparator)
        return sorted.firstOrNull { (it.progressFraction ?: 0f) in 0.01f..0.89f }
            ?: sorted.firstOrNull { assumeUnwatched || !it.isWatched }
    }
}
