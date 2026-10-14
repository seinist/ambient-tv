package com.ambient.tv.domain.model

/**
 * A list a media server ranks itself. Home only holds the newest titles of a library, so these
 * cannot be derived from what is already loaded.
 */
sealed interface LibraryRecommendation {
    /** Stable part of cache keys. */
    val cacheKey: String

    /** Finished movies and episodes, most recently played first. */
    data object RecentlyWatched : LibraryRecommendation {
        override val cacheKey = "watched"
    }

    /** Best rated titles that have not been played. */
    data object TopUnwatched : LibraryRecommendation {
        override val cacheKey = "unwatched"
    }

    /** Best rated titles of a genre, named as the server spells it. */
    data class TopGenre(val genre: String) : LibraryRecommendation {
        override val cacheKey get() = "genre:${genre.lowercase()}"
    }
}

/** A ready-made row a server suggests for a library, e.g. Plex's "More from this director". */
@kotlinx.serialization.Serializable
data class LibraryHub(val id: String, val title: String, val items: List<MediaItem>)

/** One rating per title, whichever the server's metadata agent happens to fill in. */
val MediaItem.recommendationScore: Double
    get() = audienceRating ?: rating ?: criticRating?.div(10.0) ?: 0.0
