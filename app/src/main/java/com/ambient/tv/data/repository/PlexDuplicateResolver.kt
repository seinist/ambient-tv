package com.ambient.tv.data.repository

import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class PlexDuplicateResolution(val item: MediaItem, val complete: Boolean)

/** Resolve physical entries, including keys restored from a display-only browse snapshot. */
internal suspend fun resolvePlexDuplicateVersions(
    item: MediaItem,
    cached: MediaItem?,
    sections: List<LibrarySection>,
    sectionItems: suspend (LibrarySection) -> List<MediaItem>,
    metadata: suspend (String) -> MediaItem?,
): PlexDuplicateResolution = coroutineScope {
    val requests = Semaphore(4)
    val candidates = mutableListOf(item)
    cached?.takeIf { it.canonicalContentKey == item.canonicalContentKey }?.let(candidates::add)
    val lookups = sections.map { section ->
        async {
            suspendResult {
                requests.withPermit { sectionItems(section) }
                    .filter { it.canonicalContentKey == item.canonicalContentKey }
            }
        }
    }.awaitAll()
    candidates += lookups.flatMap { it.getOrDefault(emptyList()) }

    // A library row may be merged already, and a disk snapshot deliberately has no streams.
    // Resolve every linked physical key; the number of visible/known rows is not a version cap.
    val keys = candidates.flatMap { it.allRatingKeys }.distinct().filterNot { it == item.ratingKey }
    val details = keys.map { key ->
        async {
            key to suspendResult {
                requests.withPermit {
                    try {
                        metadata(key)
                    } catch (error: retrofit2.HttpException) {
                        if (error.code() == 404 || error.code() == 410) null else throw error
                    }
                }
            }
        }
    }.awaitAll()
    val hydrated = listOf(item) + details.mapNotNull { (key, result) ->
        if (result.isSuccess) {
            // A separately edited/rematched library entry must not remain attached to this film.
            result.getOrNull()?.takeIf { it.canonicalContentKey == item.canonicalContentKey }
        } else {
            // A failed sibling must not prevent the opened version from playing. Keep whatever
            // is already known for that key, but retry discovery instead of marking it complete.
            val fallback = candidates.filter { key in it.allRatingKeys }.map { candidate ->
                candidate.copy(
                    ratingKey = key,
                    linkedRatingKeys = listOf(key),
                    mediaVersions = candidate.mediaVersions.filter {
                        (it.sourceRatingKey ?: candidate.ratingKey) == key
                    },
                )
            }
            fallback.takeIf { it.isNotEmpty() }?.let(ContentDeduplicator::mergeGroup)
        }
    }
    val merged = ContentDeduplicator.mergeGroup(hydrated)
    PlexDuplicateResolution(
        // Detail's route/composition identity must stay stable when a better-resolution sibling
        // is discovered. Each version still carries its own playback rating key and media index.
        item = merged.copy(ratingKey = item.ratingKey, linkedRatingKeys = merged.allRatingKeys),
        complete = lookups.all { it.isSuccess } && details.all { it.second.isSuccess },
    )
}
