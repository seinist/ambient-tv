package com.ambient.tv.data.repository

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion
import com.ambient.tv.domain.model.dedupeCastMembers

/**
 * Merges Plex library entries that refer to the same logical content.
 *
 * Plex can expose the same movie/episode more than once (for example 1080p and 4K files
 * scanned as separate items). The UI should show one title while retaining every physical
 * version for playback selection.
 */
object ContentDeduplicator {
    fun merge(items: List<MediaItem>): List<MediaItem> = items
        .groupBy { it.canonicalContentKey }
        .values
        .map(::mergeGroup)

    fun mergeGroup(items: List<MediaItem>): MediaItem {
        require(items.isNotEmpty())
        if (items.size == 1) {
            val item = items.first()
            return item.copy(linkedRatingKeys = item.allRatingKeys)
        }

        val inProgressItems = items.filter { (it.progressFraction ?: 0f) in 0.01f..0.94f }
        val representativePool = inProgressItems.ifEmpty { items }
        val representative = representativePool.maxWithOrNull(
            compareBy<MediaItem> { progressScore(it) }
                .thenBy { it.mediaVersions.maxOfOrNull(MediaVersion::pixelCount) ?: 0L }
                .thenBy { it.addedAt ?: 0L },
        ) ?: items.first()

        val orderedItems = listOf(representative) + items.filterNot { it === representative }
        val versions = orderedItems
            .flatMap { it.mediaVersions }
            .distinctBy { version ->
                listOf(
                    version.sourceRatingKey.orEmpty(),
                    version.sourceMediaIndex.toString(),
                    version.streamUrl.orEmpty(),
                ).joinToString("|")
            }
            // List endpoints only carry a resolution placeholder (no URL, no source). Once full
            // metadata is merged in, the placeholder must not survive as a selectable version.
            .let { all -> all.filterNot { it.isPlaceholder }.ifEmpty { all } }

        val bestProgressItem = representativePool.maxByOrNull(::progressScore) ?: representative
        val anyWatched = items.any { it.isWatched }
        val shouldExposeWatched = inProgressItems.isEmpty() && anyWatched

        return representative.copy(
            ratingKey = representative.ratingKey,
            thumbUrl = representative.thumbUrl ?: items.firstNotNullOfOrNull { it.thumbUrl },
            artUrl = representative.artUrl ?: items.firstNotNullOfOrNull { it.artUrl },
            parentThumbUrl = representative.parentThumbUrl ?: items.firstNotNullOfOrNull { it.parentThumbUrl },
            grandparentThumbUrl = representative.grandparentThumbUrl ?: items.firstNotNullOfOrNull { it.grandparentThumbUrl },
            themeMusicUrl = representative.themeMusicUrl ?: items.firstNotNullOfOrNull { it.themeMusicUrl },
            logoUrl = representative.logoUrl ?: items.firstNotNullOfOrNull { it.logoUrl },
            summary = representative.summary ?: items.firstNotNullOfOrNull { it.summary },
            tagline = representative.tagline ?: items.firstNotNullOfOrNull { it.tagline },
            durationMs = representative.durationMs ?: items.firstNotNullOfOrNull { it.durationMs },
            viewOffsetMs = if (shouldExposeWatched) representative.durationMs ?: bestProgressItem.viewOffsetMs else bestProgressItem.viewOffsetMs,
            viewCount = if (shouldExposeWatched) maxOf(1, items.maxOfOrNull { it.viewCount ?: 0 } ?: 1) else 0,
            leafCount = items.maxOfOrNull { it.leafCount ?: 0 }?.takeIf { it > 0 } ?: representative.leafCount,
            viewedLeafCount = items.maxOfOrNull { it.viewedLeafCount ?: 0 }?.takeIf { it > 0 } ?: representative.viewedLeafCount,
            addedAt = items.maxOfOrNull { it.addedAt ?: 0L }?.takeIf { it > 0 } ?: representative.addedAt,
            lastViewedAt = items.maxOfOrNull { it.lastViewedAt ?: 0L }?.takeIf { it > 0 } ?: representative.lastViewedAt,
            guid = representative.guid ?: items.firstNotNullOfOrNull { it.guid },
            guids = items.flatMap { it.guids + listOfNotNull(it.guid) }.distinct(),
            linkedRatingKeys = items.flatMap { it.allRatingKeys }.distinct(),
            genres = items.flatMap { it.genres }.distinct(),
            directors = items.flatMap { it.directors }.distinct(),
            writers = items.flatMap { it.writers }.distinct(),
            collections = items.flatMap { it.collections }.distinctBy { it.id to it.name },
            cast = items.flatMap { it.cast }.dedupeCastMembers(),
            mediaVersions = versions,
        )
    }

    private val MediaVersion.isPlaceholder: Boolean
        get() = streamUrl == null && sourceRatingKey == null

    private fun progressScore(item: MediaItem): Double {
        if (item.isWatched) return 2.0
        return item.progressFraction?.toDouble() ?: 0.0
    }
}
