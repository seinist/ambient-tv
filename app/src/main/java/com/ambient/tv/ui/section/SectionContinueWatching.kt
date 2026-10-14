package com.ambient.tv.ui.section

import com.ambient.tv.domain.model.MediaItem

/** Preserve the server's resume/next-up ordering, using IDs rather than ambiguous titles. */
internal fun sectionContinueWatching(onDeck: List<MediaItem>, library: List<MediaItem>): List<MediaItem> {
    val members = library.flatMap { it.allRatingKeys }.toHashSet()
    return onDeck.filter { item ->
        item.allRatingKeys.any(members::contains) ||
            (item.type == "episode" && item.grandparentRatingKey in members)
    }.distinctBy { it.ratingKey }
}
