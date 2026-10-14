package com.ambient.tv.ui.navigation

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.repository.MediaRepository

/**
 * Converts a library-card selection into a concrete playable item when possible.
 *
 * Shows/seasons deliberately resolve to an episode. Other item types only qualify for quick
 * playback when provider metadata exposes at least one media version; otherwise the caller can
 * fall back to the normal detail page.
 */
data class ResolvedMediaSelection(
    val original: MediaItem,
    val playable: MediaItem?,
)

object MediaSelectionResolver {
    suspend fun resolve(repository: MediaRepository, ratingKey: String): ResolvedMediaSelection? {
        var original = repository.cachedMediaItem(ratingKey)
            ?: repository.getMetadataFast(ratingKey).getOrNull()
            ?: return null

        // List caches are intentionally lightweight. Refresh an apparently playable item once if
        // the cached projection has no stream versions, so quick play does not reject a movie or
        // episode merely because the library card did not need playback metadata.
        if (original.type !in setOf("show", "season") && original.mediaVersions.isEmpty()) {
            repository.getMetadataFast(ratingKey).getOrNull()?.let { original = it }
        }

        val playable = when (original.type) {
            "show", "season" -> resolveEpisode(repository, original)
            else -> original.takeIf { it.mediaVersions.isNotEmpty() }
        }

        return ResolvedMediaSelection(original = original, playable = playable)
    }

    private suspend fun resolveEpisode(repository: MediaRepository, item: MediaItem): MediaItem? {
        val next = repository.getNextUnwatchedEpisode(item.ratingKey).getOrNull()
            ?: firstEpisode(repository, item)
            ?: return null
        // Jellyfin's optimized next-episode query already returns full metadata, while Plex may
        // return a lightweight child. One fast refresh makes both paths equally safe to play.
        return repository.getMetadataFast(next.ratingKey).getOrNull() ?: next
    }

    private suspend fun firstEpisode(repository: MediaRepository, item: MediaItem): MediaItem? {
        val episodes = if (item.type == "season") {
            repository.getChildren(item.ratingKey).getOrDefault(emptyList())
        } else {
            val seasons = repository.getChildren(item.ratingKey).getOrDefault(emptyList())
                .filter { it.type == "season" }
                .sortedWith(compareBy<MediaItem> { it.index ?: Int.MAX_VALUE }.thenBy { it.ratingKey })
            for (season in seasons) {
                val candidate = repository.getChildren(season.ratingKey).getOrDefault(emptyList())
                    .filter { it.type == "episode" }
                    .minWithOrNull(compareBy<MediaItem> { it.index ?: Int.MAX_VALUE }.thenBy { it.ratingKey })
                if (candidate != null) return candidate
            }
            emptyList()
        }
        return episodes.filter { it.type == "episode" }
            .minWithOrNull(compareBy<MediaItem> { it.index ?: Int.MAX_VALUE }.thenBy { it.ratingKey })
    }
}
