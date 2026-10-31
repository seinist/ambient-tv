package com.ambient.tv.ui.section

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion

enum class SectionSort {
    TITLE,
    YEAR,
    RELEASE_DATE,
    RECENTLY_ADDED,
    RECENTLY_VIEWED,
    RATING,
    CRITIC_RATING,
    AUDIENCE_RATING,
    DURATION,
    PLAYS,
}

enum class SectionSortOrder { ASCENDING, DESCENDING }

enum class SectionFilter { ALL, UNWATCHED, IN_PROGRESS, WATCHED }

enum class SectionQualityFilter { ALL, UHD, HD, SD, HDR }

data class LibraryFilters(
    val watch: SectionFilter = SectionFilter.ALL,
    val quality: SectionQualityFilter = SectionQualityFilter.ALL,
    val genre: String? = null,
    val decade: Int? = null,
    val contentRating: String? = null,
) {
    val isDefault: Boolean
        get() = watch == SectionFilter.ALL &&
            quality == SectionQualityFilter.ALL &&
            genre == null &&
            decade == null &&
            contentRating == null

    val activeCount: Int
        get() = listOf(watch != SectionFilter.ALL, quality != SectionQualityFilter.ALL,
            genre != null, decade != null, contentRating != null).count { it }
}

data class LibraryFilterFacets(
    val genres: List<String> = emptyList(),
    val decades: List<Int> = emptyList(),
    val contentRatings: List<String> = emptyList(),
    val qualities: List<SectionQualityFilter> = emptyList(),
)

fun defaultSortOrder(sort: SectionSort): SectionSortOrder = when (sort) {
    SectionSort.TITLE -> SectionSortOrder.ASCENDING
    else -> SectionSortOrder.DESCENDING
}

/** Alphabet-bar bucket for a title: its uppercase first letter, or '#' for digits and symbols. */
fun libraryIndexLetter(title: String): Char? {
    val first = title.trimStart().firstOrNull() ?: return null
    return if (first.isLetter()) first.uppercaseChar() else '#'
}

/** The index follows the actual visible list: # first in A-Z, last in Z-A. */
fun libraryAlphabetLetters(sortedItems: List<MediaItem>): List<Char> =
    sortedItems.mapNotNull { libraryIndexLetter(it.title) }.distinct()

/** Initial indicator follows the last focused tile, then the first visible one. */
fun libraryFocusedAlphabetLetter(sortedItems: List<MediaItem>, focusedRatingKey: String?): Char? =
    sortedItems.firstOrNull { it.ratingKey == focusedRatingKey }
        ?.let { libraryIndexLetter(it.title) }
        ?: sortedItems.firstOrNull()?.let { libraryIndexLetter(it.title) }

/** Library and recommendation layouts in picker order; each artwork-heavy mode has a poster twin. */
val SELECTABLE_VIEW_MODES = listOf(
    SectionViewMode.POSTER,
    SectionViewMode.LANDSCAPE,
    SectionViewMode.LIST,
    SectionViewMode.POSTER_LIST,
    SectionViewMode.INFO_LIST,
    SectionViewMode.POSTER_INFO_LIST,
    SectionViewMode.WALL,
    SectionViewMode.POSTER_WALL,
    SectionViewMode.CINEMATIC,
    SectionViewMode.POSTER_CINEMATIC,
)

/** Recommendations keep their named hub rows in every view mode; only legacy aliases are normalized. */
fun recommendationViewMode(stored: SectionViewMode?): SectionViewMode = when (stored) {
    SectionViewMode.GRID -> SectionViewMode.POSTER
    SectionViewMode.ROWS -> SectionViewMode.LANDSCAPE
    null -> SectionViewMode.POSTER
    else -> stored
}

fun libraryFilterFacets(items: List<MediaItem>): LibraryFilterFacets {
    val genres = items.asSequence().flatMap { it.genres.asSequence() }
        .map { it.trim() }.filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }.toList()
    val decades = items.mapNotNull { it.year?.libraryDecade() }.distinct().sortedDescending()
    val ratings = items.asSequence().mapNotNull { it.contentRating?.trim()?.takeIf { rating -> rating.isNotEmpty() } }
        .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }.toList()
    val qualityTiers = linkedSetOf<SectionQualityFilter>()
    items.forEach { item ->
        item.mediaVersions.forEach { version ->
            version.qualityTier()?.let(qualityTiers::add)
            if (version.hasHdrMetadata()) qualityTiers.add(SectionQualityFilter.HDR)
        }
    }
    return LibraryFilterFacets(
        genres = genres,
        decades = decades,
        contentRatings = ratings,
        qualities = listOf(SectionQualityFilter.UHD, SectionQualityFilter.HD, SectionQualityFilter.SD, SectionQualityFilter.HDR)
            .filter { it in qualityTiers },
    )
}

fun applyLibraryViewOptions(
    items: List<MediaItem>,
    sort: SectionSort,
    order: SectionSortOrder,
    filters: LibraryFilters,
): List<MediaItem> = sortLibraryItems(applyLibraryFilters(items, filters), sort, order)

fun applyLibraryFilters(items: List<MediaItem>, filters: LibraryFilters): List<MediaItem> {
    if (filters.isDefault) return items
    return items.filter { item ->
        (filters.watch == SectionFilter.ALL || item.libraryWatchStatus() == filters.watch) &&
            item.matchesQuality(filters.quality) &&
            (filters.genre == null || item.genres.any { it.equals(filters.genre, ignoreCase = true) }) &&
            (filters.decade == null || item.year?.libraryDecade() == filters.decade) &&
            (filters.contentRating == null || item.contentRating.equals(filters.contentRating, ignoreCase = true))
    }
}

fun sortLibraryItems(items: List<MediaItem>, sort: SectionSort, order: SectionSortOrder): List<MediaItem> {
    val comparator = when (sort) {
        SectionSort.TITLE -> compareBy<MediaItem, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        SectionSort.YEAR -> compareBy<MediaItem> { it.year ?: Int.MIN_VALUE }
        SectionSort.RELEASE_DATE -> compareBy<MediaItem> { it.originallyAvailableAt ?: it.year?.toString().orEmpty() }
        SectionSort.RECENTLY_ADDED -> compareBy<MediaItem> { it.addedAt ?: 0L }
        SectionSort.RECENTLY_VIEWED -> compareBy<MediaItem> { it.lastViewedAt ?: 0L }
        SectionSort.RATING -> compareBy<MediaItem> { it.librarySortRating() }
        SectionSort.CRITIC_RATING -> compareBy<MediaItem> { it.librarySortCritic() }
        SectionSort.AUDIENCE_RATING -> compareBy<MediaItem> { it.librarySortAudience() }
        SectionSort.DURATION -> compareBy<MediaItem> { it.durationMs ?: 0L }
        SectionSort.PLAYS -> compareBy<MediaItem> { it.viewCount ?: 0 }
    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    return items.sortedWith(if (order == SectionSortOrder.ASCENDING) comparator else comparator.reversed())
}

internal fun MediaItem.libraryWatchStatus(): SectionFilter {
    if (type == "show" || type == "season") {
        val total = leafCount
        val watchedLeaves = viewedLeafCount
        if (total != null && total > 0 && watchedLeaves != null) {
            return when {
                watchedLeaves <= 0 -> SectionFilter.UNWATCHED
                watchedLeaves >= total -> SectionFilter.WATCHED
                else -> SectionFilter.IN_PROGRESS
            }
        }
    }
    val progress = progressFraction ?: 0f
    return when {
        progress in 0.01f..0.89f -> SectionFilter.IN_PROGRESS
        isWatched -> SectionFilter.WATCHED
        else -> SectionFilter.UNWATCHED
    }
}

internal fun MediaItem.librarySortRating(): Double =
    rating ?: audienceRating ?: normalizedCriticRating() ?: 0.0

internal fun MediaItem.librarySortCritic(): Double =
    rating ?: normalizedCriticRating() ?: 0.0

internal fun MediaItem.librarySortAudience(): Double = audienceRating ?: 0.0

private fun MediaItem.normalizedCriticRating(): Double? {
    val value = criticRating ?: return null
    return if (value > 10.0) value / 10.0 else value
}

internal fun MediaItem.matchesQuality(filter: SectionQualityFilter): Boolean {
    if (filter == SectionQualityFilter.ALL) return true
    return mediaVersions.any { version ->
        if (filter == SectionQualityFilter.HDR) version.hasHdrMetadata() else version.qualityTier() == filter
    }
}

internal fun MediaVersion.qualityTier(): SectionQualityFilter? {
    val heightPx = height ?: when (val raw = resolution?.lowercase()?.removeSuffix("p")?.trim()) {
        null, "" -> null
        "4k", "uhd", "2160" -> 2160
        "1080", "hd" -> 1080
        "720" -> 720
        "576", "480", "sd" -> 480
        else -> raw.toIntOrNull()
    } ?: return null
    return when {
        heightPx >= 2100 -> SectionQualityFilter.UHD
        heightPx >= 700 -> SectionQualityFilter.HD
        else -> SectionQualityFilter.SD
    }
}

internal fun MediaVersion.hasHdrMetadata(): Boolean {
    val value = videoDynamicRange?.lowercase()?.trim().orEmpty()
    if (value.isEmpty() || value == "sdr") return false
    return "hdr" in value || "dolby" in value || "dovi" in value || "hlg" in value || "pq" in value || "2084" in value
}

internal fun Int.libraryDecade(): Int = (this / 10) * 10
