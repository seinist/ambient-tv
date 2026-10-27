package com.ambient.tv.ui.detail

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.navigation.Destinations

internal data class DetailActionCapabilities(
    val canRestart: Boolean,
    val canSetWatched: Boolean,
    val canRemoveFromContinue: Boolean,
    /** Server administrators only: fix match and artwork selection. */
    val canEdit: Boolean = false,
) {
    val hasMenuActions: Boolean get() = canRestart || canSetWatched || canRemoveFromContinue || canEdit
}

private val WATCHABLE_TYPES = setOf("movie", "episode", "show", "season", "video")
private val DIRECTLY_PLAYABLE_TYPES = setOf("movie", "episode", "video", "clip", "track", "audio")

internal fun detailActionCapabilities(
    type: String,
    playbackAvailable: Boolean,
    hasMediaVersions: Boolean,
    inProgress: Boolean,
    canEdit: Boolean = false,
): DetailActionCapabilities = DetailActionCapabilities(
    canRestart = playbackAvailable && hasMediaVersions && type in DIRECTLY_PLAYABLE_TYPES,
    canSetWatched = playbackAvailable && type in WATCHABLE_TYPES,
    canRemoveFromContinue = playbackAvailable && inProgress,
    canEdit = canEdit,
)

internal fun detailShowsChildrenSection(type: String): Boolean =
    type == "show" || type == "season" || type == "collection"

/** An episode tile on a detail page plays. Seasons, collections and recommendations still open. */
internal fun detailChildPlaysDirectly(type: String): Boolean = type == "episode"

/** Show that owns this season or episode, so the page can list the other seasons. */
internal fun seriesKeyForSeasonStrip(type: String, parentRatingKey: String?, grandparentRatingKey: String?): String? =
    when (type) {
        "season" -> parentRatingKey?.takeIf { it.isNotBlank() }
        "episode" -> grandparentRatingKey?.takeIf { it.isNotBlank() }
        else -> null
    }

/** Season highlighted in the strip. Episodes point at their parent season. */
internal fun currentSeasonKey(type: String, ratingKey: String, parentRatingKey: String?): String? =
    when (type) {
        "season" -> ratingKey
        "episode" -> parentRatingKey?.takeIf { it.isNotBlank() }
        else -> null
    }

/**
 * Seasons to offer beside the open one. A single season is omitted: the show page already
 * flattens that case onto its episodes. Specials (season 0) follow the numbered seasons.
 */
internal fun seasonStripItems(children: List<MediaItem>): List<MediaItem> {
    val seasons = children.filter { it.type == "season" }
    if (seasons.size <= 1) return emptyList()
    return seasons.sortedWith(
        compareBy<MediaItem> { it.index == 0 }
            .thenBy { it.index ?: Int.MAX_VALUE }
            .thenBy { it.title },
    )
}

/** The series page is already the previous entry, so Back is enough. */
internal fun seriesDetailIsDirectlyUnder(previousRoute: String?, previousRatingKey: String?, seriesKey: String): Boolean =
    previousRoute == Destinations.DETAIL && previousRatingKey == seriesKey

/**
 * Detail page to open when a player that was started from a detail page closes.
 * Null keeps the normal back stack: the page under the player is already the right one,
 * the item was not an episode, or playback never got far enough to know the item.
 */
internal fun episodeDetailAfterPlayback(
    detailOrigin: Boolean,
    mediaType: String?,
    playedRatingKey: String?,
    underneathRoute: String?,
    underneathRatingKey: String?,
): String? {
    if (!detailOrigin) return null
    if (mediaType == null || !mediaType.equals("episode", ignoreCase = true)) return null
    val key = playedRatingKey?.takeIf { it.isNotBlank() } ?: return null
    if (seriesDetailIsDirectlyUnder(underneathRoute, underneathRatingKey, key)) return null
    return key
}
