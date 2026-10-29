package com.ambient.tv.ui.detail

import com.ambient.tv.domain.model.MediaItem

/** Only identifiers live for the app session; no screen, focus requester or saved-state bundle. */
internal class DetailFocusMemory {
    var key: String? = null
        private set
    private val positions = mutableMapOf<String, String>()

    fun record(focusKey: String?) {
        key = focusKey
        detailRowForFocusKey(focusKey)?.let { row -> positions[row] = checkNotNull(focusKey) }
    }

    fun rowItems(): Map<String, String> = positions.toMap()
}

/** Popping a detail route does not forget its rows; restarting the process does. */
internal class DetailFocusSessionStore {
    private val pages = mutableMapOf<Pair<String, String>, DetailFocusMemory>()
    fun page(scope: String, ratingKey: String): DetailFocusMemory = pages.getOrPut(scope to ratingKey) { DetailFocusMemory() }
}

internal val DetailFocusSessions = DetailFocusSessionStore()

internal fun detailRowForFocusKey(key: String?): String? = when {
    key == null -> null
    key.startsWith("actor:") -> key.substringBefore('\u0000')
    key.startsWith("collection:") -> "collections"
    else -> key.substringBefore(':')
}

/** The preferred first entry is used only until the row has an explicit remembered item. */
internal fun detailPreferredChildKey(item: MediaItem, children: List<MediaItem>, nextEpisode: MediaItem?): String? {
    if (children.isEmpty()) return null
    if (item.type == "episode") {
        children.firstOrNull { child -> child.allRatingKeys.any { it in item.allRatingKeys } }
            ?.let { return "children:${it.ratingKey}" }
    }
    if (item.type !in setOf("show", "season", "collection")) return "children:${children.first().ratingKey}"
    val next = nextEpisode?.let { episode ->
        children.firstOrNull { child ->
            child.allRatingKeys.any { it in episode.allRatingKeys } ||
                (child.type == "season" && episode.parentRatingKey in child.allRatingKeys)
        }
    }
    val preferred = next
        ?: children.firstOrNull { !it.isWatched && (it.progressFraction ?: 0f) in 0.01f..0.89f }
        ?: children.firstOrNull { !it.isWatched }
        ?: children.first()
    return "children:${preferred.ratingKey}"
}

/** A container's playback belongs to its descendants, so missing own media is normal. */
internal fun detailShowsMissingPlaybackSource(type: String, hasMediaVersions: Boolean, hasActivePlayback: Boolean): Boolean =
    !hasMediaVersions && !hasActivePlayback && type !in setOf("show", "season", "collection", "artist", "album")
