package com.ambient.tv.ui.detail

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion
import com.ambient.tv.ui.common.formatResolution

/** Only identifiers live for the app session; no screen, focus requester or saved-state bundle. */
internal class DetailFocusMemory {
    var key: String? = null
        private set
    var hasRecordedFocus: Boolean = false
        private set
    private val positions = mutableMapOf<String, String>()

    fun record(focusKey: String?) {
        key = focusKey
        hasRecordedFocus = true
        detailRowForFocusKey(focusKey)?.let { row -> positions[row] = checkNotNull(focusKey) }
    }

    fun rowItems(): Map<String, String> = positions.toMap()
}

/** Captured only when the fullscreen player actually returns to this detail destination. */
internal data class DetailPlaybackReturn(
    val pageRatingKey: String,
    val playedRatingKey: String,
    val revision: Long,
)

/** Popping a detail route keeps its rows; starting a new app session clears them. */
internal class DetailFocusSessionStore {
    private val pages = mutableMapOf<Pair<String, String>, DetailFocusMemory>()
    private val playbackReturns = mutableMapOf<String, DetailPlaybackReturn>()
    private var returnRevision = 0L
    fun page(scope: String, ratingKey: String): DetailFocusMemory = pages.getOrPut(scope to ratingKey) { DetailFocusMemory() }

    fun preparePlaybackReturn(pageRatingKey: String?, playedRatingKey: String?) {
        val page = pageRatingKey?.takeIf { it.isNotBlank() } ?: return
        val played = playedRatingKey?.takeIf { it.isNotBlank() } ?: return
        playbackReturns[page] = DetailPlaybackReturn(page, played, ++returnRevision)
    }

    fun pendingPlaybackReturn(pageRatingKey: String): DetailPlaybackReturn? = playbackReturns[pageRatingKey]

    fun consumePlaybackReturn(returned: DetailPlaybackReturn) {
        if (playbackReturns[returned.pageRatingKey] == returned) playbackReturns.remove(returned.pageRatingKey)
    }

    fun clear() {
        pages.clear()
        playbackReturns.clear()
    }
}

internal val DetailFocusSessions = DetailFocusSessionStore()

/** Each actual entry gets fresh lazy states; recomposition and background playback do not. */
internal class DetailFocusEntry {
    private var wasActive = false
    private var generation = 0L

    fun enter(active: Boolean): Long {
        if (active && !wasActive) generation++
        wasActive = active
        return generation
    }
}

internal data class DetailFocusRow(
    val key: String,
    val sectionIndex: Int,
    val items: List<String>,
    val defaultItem: String? = null,
)

internal data class DetailEntryFocusTarget(
    val row: String,
    val item: String,
    val sectionIndex: Int,
    val itemIndex: Int,
)

/** Resolve the row and both lazy indices before either lazy container is constructed. */
internal fun detailEntryFocusTarget(requested: String?, rows: List<DetailFocusRow>): DetailEntryFocusTarget? {
    val rowKey = detailRowForFocusKey(requested)
    val row = rows.firstOrNull { it.key == rowKey && it.items.isNotEmpty() }
        ?: rows.firstOrNull { it.items.isNotEmpty() }
        ?: return null
    val item = requested?.takeIf { it in row.items }
        ?: row.defaultItem?.takeIf { it in row.items }
        ?: row.items.first()
    return DetailEntryFocusTarget(row.key, item, row.sectionIndex, row.items.indexOf(item))
}

/** The played episode can differ from the episode that originally opened the player. */
internal fun detailPlaybackFocusKey(
    item: MediaItem,
    children: List<MediaItem>,
    returned: DetailPlaybackReturn?,
    remembered: String?,
): String? {
    val played = returned?.playedRatingKey ?: return remembered
    children.firstOrNull { played in it.allRatingKeys }?.let { return "children:${it.ratingKey}" }
    if (item.type == "episode" && played in item.allRatingKeys) return "children:${item.ratingKey}"
    return remembered
}

internal fun detailEntryWaitsForChildren(
    item: MediaItem,
    children: List<MediaItem>,
    childrenLoading: Boolean,
    returned: DetailPlaybackReturn?,
    remembered: String?,
    hasRecordedFocus: Boolean = false,
): Boolean {
    if (!childrenLoading) return false
    if (returned != null && item.type in setOf("show", "season", "collection", "episode") &&
        children.none { returned.playedRatingKey in it.allRatingKeys } &&
        (item.type == "episode" || returned.playedRatingKey !in item.allRatingKeys)
    ) return true
    val requested = detailPlaybackFocusKey(item, children, returned, remembered)
    val requestedChild = requested?.takeIf { detailRowForFocusKey(it) == "children" }
    if (requestedChild != null) return children.none { "children:${it.ratingKey}" == requestedChild }
    return children.isEmpty() && item.type in setOf("season", "collection") && remembered == null && !hasRecordedFocus
}

internal fun detailRowForFocusKey(key: String?): String? = when {
    key == null -> null
    key.startsWith("versions:") -> key
    key.startsWith("actor:") -> key.substringBefore('\u0000')
    key.startsWith("collection:") -> "collections"
    else -> key.substringBefore(':')
}

/**
 * Resolution is only a quality label, not a physical file identity. When versions share it,
 * number the buttons while keeping the distinct file names and full source list below them.
 */
internal fun detailVersionLabels(versions: List<MediaVersion>): List<String> {
    val resolutions = versions.mapIndexed { index, version ->
        formatResolution(version.resolution) ?: "#${index + 1}"
    }
    val frequencies = resolutions.groupingBy { it }.eachCount()
    val occurrences = mutableMapOf<String, Int>()
    return resolutions.map { label ->
        if (frequencies.getValue(label) == 1) label
        else {
            val number = occurrences.getOrDefault(label, 0) + 1
            occurrences[label] = number
            "$label #$number"
        }
    }
}

/** Keep focus on the same physical source when late metadata inserts or reorders versions. */
internal fun detailVersionFocusKeys(item: MediaItem): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return item.mediaVersions.map { version ->
        val source = version.sourceRatingKey ?: item.ratingKey
        val media = version.sourceId?.takeIf { it.isNotBlank() }?.let { "id:$it" }
            ?: "index:${version.sourceMediaIndex}"
        val identity = "versions:$source\u0000$media"
        val occurrence = occurrences.getOrDefault(identity, 0)
        occurrences[identity] = occurrence + 1
        "$identity\u0000$occurrence"
    }
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
