package com.ambient.tv.trailers

import com.ambient.tv.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

fun isTmdbV3Key(value: String): Boolean = Regex("[a-fA-F0-9]{32}").matches(value)

enum class TrailerFeed(val path: String, val mediaType: String, val label: Int) {
    CINEMA("movie/now_playing", "movie", R.string.trailers_cinema),
    UPCOMING("movie/upcoming", "movie", R.string.trailers_upcoming),
    MOVIES("movie/popular", "movie", R.string.trailers_movies),
    SERIES("tv/popular", "tv", R.string.trailers_series),
}

enum class TrailerViewMode { LANDSCAPE, POSTER, LIST }
enum class TrailerSort { DEFAULT, TITLE, DATE }
enum class TrailerFilter { ALL, OFFICIAL, TRAILERS, TEASERS }

@Serializable
data class TrailerVideo(
    val key: String = "",
    val name: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
    @SerialName("iso_639_1") val language: String = "",
)

@Serializable
data class TrailerTitle(
    val id: Int,
    val title: String = "",
    val name: String = "",
    val overview: String = "",
    @SerialName("original_title") val originalTitle: String = "",
    @SerialName("original_name") val originalName: String = "",
    @SerialName("original_language") val originalLanguage: String = "en",
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("first_air_date") val firstAirDate: String = "",
    @SerialName("backdrop_path") val backdrop: String? = null,
    @SerialName("poster_path") val poster: String? = null,
    val popularity: Double = 0.0,
) {
    val displayTitle: String get() = title.ifBlank { name }.ifBlank { originalTitle }.ifBlank { originalName }
    val releaseSortKey: String get() = releaseDate.ifBlank { firstAirDate }
    val year: Int? get() = releaseDate.ifBlank { firstAirDate }.take(4).toIntOrNull()
    val imageUrl: String? get() = (backdrop ?: poster)?.let { "https://image.tmdb.org/t/p/w780$it" }
    val posterUrl: String? get() = poster?.let { "https://image.tmdb.org/t/p/w500$it" }
}

data class TrailerEntry(val title: TrailerTitle, val mediaType: String, val videos: List<TrailerVideo>) {
    val key: String get() = "$mediaType:${title.id}"
}

data class TrailerPage(
    val entries: List<TrailerEntry>,
    val page: Int,
    val totalPages: Int,
    val partialFailure: Boolean = false,
) {
    val hasNextPage: Boolean get() = page < totalPages
}

data class TrailerSearchItem(val title: TrailerTitle, val mediaType: String) {
    val key: String get() = "$mediaType:${title.id}"
}

fun applyTrailerViewOptions(
    entries: List<TrailerEntry>,
    sort: TrailerSort,
    filter: TrailerFilter,
    descending: Boolean = sort == TrailerSort.DATE,
): List<TrailerEntry> {
    val filtered = entries.mapNotNull { entry ->
        val videos = entry.videos.filter { video -> when (filter) {
            TrailerFilter.ALL -> true
            TrailerFilter.OFFICIAL -> video.official
            TrailerFilter.TRAILERS -> video.type == "Trailer"
            TrailerFilter.TEASERS -> video.type == "Teaser"
        } }
        entry.copy(videos = videos).takeIf { videos.isNotEmpty() }
    }
    return when (sort) {
        TrailerSort.DEFAULT -> filtered
        TrailerSort.TITLE -> {
            val title: (TrailerEntry) -> String = { it.title.displayTitle.lowercase(java.util.Locale.ROOT) }
            if (descending) filtered.sortedByDescending(title) else filtered.sortedBy(title)
        }
        TrailerSort.DATE -> filtered.sortedWith(
            (if (descending) compareByDescending<TrailerEntry> { it.title.releaseSortKey } else compareBy { it.title.releaseSortKey })
                .thenBy { it.title.displayTitle.lowercase(java.util.Locale.ROOT) },
        )
    }
}

fun rankTrailers(videos: List<TrailerVideo>, language: String, originalLanguage: String): List<TrailerVideo> =
    videos.filter { it.site == "YouTube" && it.type in setOf("Trailer", "Teaser") && Regex("[A-Za-z0-9_-]{11}").matches(it.key) }
        .sortedWith(compareByDescending<TrailerVideo> { it.official }
            .thenBy { if (it.type == "Trailer") 0 else 1 }
            .thenBy { when (it.language) { language -> 0; originalLanguage -> 1; "en" -> 2; else -> 3 } })
        .distinctBy { it.key }

/** Title search is deliberately conservative: ambiguous remakes never silently play another film. */
/** A programme title without a year: only exact (case-insensitive) title matches with art, most popular first. */
fun matchProgrammeTitle(candidates: List<TrailerTitle>, title: String): TrailerTitle? {
    val normalized = title.trim().lowercase(java.util.Locale.ROOT)
    if (normalized.length < 2) return null
    return candidates.filter { candidate ->
        candidate.imageUrl != null && listOf(candidate.title, candidate.name, candidate.originalTitle, candidate.originalName)
            .any { it.trim().lowercase(java.util.Locale.ROOT) == normalized }
    }.maxByOrNull { it.popularity }
}

fun matchTrailerTitle(candidates: List<TrailerTitle>, title: String, year: Int?): TrailerTitle? {
    if (year == null) return null
    val normalized = title.trim().lowercase(java.util.Locale.ROOT)
    return candidates.filter { candidate ->
        candidate.year == year && listOf(candidate.title, candidate.name, candidate.originalTitle, candidate.originalName)
            .any { it.trim().lowercase(java.util.Locale.ROOT) == normalized }
    }.singleOrNull()
}
