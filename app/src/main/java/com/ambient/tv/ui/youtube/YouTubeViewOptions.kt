package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow

enum class YouTubeSort { DEFAULT, TITLE, DURATION }
enum class YouTubeFilter(val maxAgeMs: Long? = null) {
    ALL, UNWATCHED, IN_PROGRESS, LIVE, SHORTS,
    /** Published within the last day/week/month/year; videos without a known date are hidden. */
    TODAY(86_400_000L), WEEK(7 * 86_400_000L), MONTH(31 * 86_400_000L), YEAR(366 * 86_400_000L),
}

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
    nowMs: Long = System.currentTimeMillis(),
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
                YouTubeFilter.TODAY, YouTubeFilter.WEEK, YouTubeFilter.MONTH, YouTubeFilter.YEAR ->
                    item.publishedAtMs?.let { nowMs - it <= filter.maxAgeMs!! } == true
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

/** Card text from SmartTube's second title ("@kanal • 1,2 Mio. Aufrufe • vor 2 Jahren"), without repeats. */
internal data class YouTubeCardInfo(val channel: String?, val details: List<String>)

internal fun youtubeCardInfo(item: YouTubeItem): YouTubeCardInfo {
    val parts = item.subtitle.orEmpty().split('•', '·').map(String::trim).filter(String::isNotEmpty)
        .distinctBy(String::lowercase)
    val author = item.author?.trim()?.takeIf(String::isNotEmpty)
    val published = item.publishedText?.trim()?.takeIf(String::isNotEmpty)
    fun isHandle(s: String) = s.startsWith("@")
    fun isPublished(s: String) = published != null && s.equals(published, ignoreCase = true)
    val channel = author
        ?: parts.firstOrNull { !isHandle(it) && !it.any(Char::isDigit) }
        ?: parts.firstOrNull(::isHandle)
    val sameChannel = { s: String ->
        channel != null && (s.equals(channel, ignoreCase = true) || isHandle(s) ||
            s.removePrefix("@").replace(" ", "").equals(channel.replace(" ", ""), ignoreCase = true))
    }
    val extra = parts.filterNot { sameChannel(it) || isPublished(it) }
    val showPublished = published?.takeIf { !item.isUpcoming && !item.isLive }
    return YouTubeCardInfo(channel, extra + listOfNotNull(showPublished))
}
