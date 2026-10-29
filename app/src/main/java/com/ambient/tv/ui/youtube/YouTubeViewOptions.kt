package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow

enum class YouTubeSort { DEFAULT, TITLE, DURATION }
enum class YouTubeFilter { ALL, UNWATCHED, IN_PROGRESS, LIVE, SHORTS }

/** Channel tiles have no watch status or video flags. A channel's uploads still do. */
internal fun youtubeVideoFiltersAvailable(rows: List<YouTubeRow>): Boolean =
    rows.any { row -> row.items.any { !it.isChannel } }

internal fun youtubeAvailableSorts(rows: List<YouTubeRow>): List<YouTubeSort> =
    if (youtubeVideoFiltersAvailable(rows)) YouTubeSort.entries
    else listOf(YouTubeSort.DEFAULT, YouTubeSort.TITLE)

/** A saved video-only sort must not reorder a channel directory by missing durations. */
internal fun youtubeEffectiveSort(rows: List<YouTubeRow>, sort: YouTubeSort): YouTubeSort =
    sort.takeIf { it in youtubeAvailableSorts(rows) } ?: YouTubeSort.DEFAULT

internal fun applyYouTubeViewOptions(
    rows: List<YouTubeRow>,
    sort: YouTubeSort,
    filter: YouTubeFilter,
    descending: Boolean = sort == YouTubeSort.DURATION,
): List<YouTubeRow> {
    val effectiveSort = youtubeEffectiveSort(rows, sort)
    return rows.mapNotNull { row ->
        val filtered = row.items.filter { item ->
            item.isChannel || when (filter) {
                YouTubeFilter.ALL -> true
                YouTubeFilter.UNWATCHED -> item.percentWatched <= 0
                YouTubeFilter.IN_PROGRESS -> item.percentWatched in 1..99
                YouTubeFilter.LIVE -> item.isLive || item.isUpcoming
                YouTubeFilter.SHORTS -> item.isShorts
            }
        }
        val sorted = when (effectiveSort) {
            YouTubeSort.DEFAULT -> filtered
            YouTubeSort.TITLE -> if (descending) filtered.sortedByDescending { it.title.lowercase() } else filtered.sortedBy { it.title.lowercase() }
            YouTubeSort.DURATION -> filtered.sortedWith(
                (if (descending) compareByDescending<YouTubeItem> { it.durationMs } else compareBy { it.durationMs })
                    .thenBy { it.title.lowercase() },
            )
        }
        row.copy(items = sorted).takeIf { it.items.isNotEmpty() }
    }
}

/** Second gray line for info list / cinematic. Skips channel text already shown in the primary line. */
internal fun youtubeInfoSubtitle(item: YouTubeItem): String? {
    val extra = item.subtitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val primary = item.author?.trim()?.takeIf { it.isNotEmpty() } ?: extra
    return extra.takeUnless { it.equals(primary, ignoreCase = true) }
}
