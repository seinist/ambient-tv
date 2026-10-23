package com.ambient.tv.ui.detail

internal data class DetailActionCapabilities(
    val canRestart: Boolean,
    val canSetWatched: Boolean,
    val canRemoveFromContinue: Boolean,
) {
    val hasMenuActions: Boolean get() = canRestart || canSetWatched || canRemoveFromContinue
}

private val WATCHABLE_TYPES = setOf("movie", "episode", "show", "season", "video")
private val DIRECTLY_PLAYABLE_TYPES = setOf("movie", "episode", "video", "clip", "track", "audio")

internal fun detailActionCapabilities(
    type: String,
    playbackAvailable: Boolean,
    hasMediaVersions: Boolean,
    inProgress: Boolean,
): DetailActionCapabilities = DetailActionCapabilities(
    canRestart = playbackAvailable && hasMediaVersions && type in DIRECTLY_PLAYABLE_TYPES,
    canSetWatched = playbackAvailable && type in WATCHABLE_TYPES,
    canRemoveFromContinue = playbackAvailable && inProgress,
)

internal fun detailShowsChildrenSection(type: String): Boolean =
    type == "show" || type == "season" || type == "collection"
