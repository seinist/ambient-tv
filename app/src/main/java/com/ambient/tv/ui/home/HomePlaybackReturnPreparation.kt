package com.ambient.tv.ui.home

import com.ambient.tv.ui.common.ContentRowData

internal enum class HomePlaybackProvider { TRAILERS, YOUTUBE, TELEGRAM, MEDIA }

/** A Home row, not a similarly named library/feed, owns the queue that its card started. */
internal data class HomePlaybackOrigin(
    val provider: HomePlaybackProvider,
    val focusStateKey: String,
    val scope: String,
    val sessionToken: Long,
    val source: ContentRowData,
)

/** Non-consuming return address, accepted only at a real navigation entry. */
internal data class HomePlaybackReturnRequest(
    val provider: HomePlaybackProvider,
    val scope: String,
    val contextRevision: Long,
    val source: ContentRowData,
    val target: HomeFocusSnapshot,
) {
    val key: String get() = "${provider.name}:$scope:$contextRevision"
}

/** Regular Plex/Jellyfin/Emby/file playback already has the selected card in Home's snapshot. */
internal fun homeNavigationPlaybackReturn(
    state: HomeUiState,
    returned: com.ambient.tv.ui.common.NavigationPlaybackReturn?,
): HomePlaybackReturnRequest? {
    val current = state as? HomeUiState.Loaded ?: return null
    val event = returned ?: return null
    val row = current.rows.firstOrNull { row -> row.id == current.focusedRowId &&
        row.items.any { event.itemId in it.allRatingKeys } }
        ?: current.rows.firstOrNull { row -> row.items.any { event.itemId in it.allRatingKeys } }
        ?: return null
    return HomePlaybackReturnRequest(HomePlaybackProvider.MEDIA, event.entryId, event.revision, row,
        HomeFocusSnapshot(event.itemId, row.id))
}

/** The same first-frame rows, focus and hero remain in place when the entry commits. */
internal fun prepareHomePlaybackReturn(
    current: HomeUiState.Loaded,
    request: HomePlaybackReturnRequest,
): HomeUiState.Loaded? {
    val source = request.source
    if (request.target.rowId != source.id) return null
    val item = source.items.firstOrNull { request.target.ratingKey in it.allRatingKeys } ?: return null
    val rows = if (current.rows.any { it.id == source.id }) {
        current.rows.map { if (it.id == source.id) source else it }
    } else current.rows + source
    val rowKeys = current.rowKeys.takeIf { source.id in it } ?: (current.rowKeys + source.id)
    val revision = if (current.playbackReturnToken == request.key) current.playbackReturnRevision
        else current.playbackReturnRevision + 1
    return current.copy(
        rows = rows,
        rowKeys = rowKeys,
        focusedRatingKey = item.ratingKey,
        focusedRowId = source.id,
        hero = item.toHomeHeroContent(),
        loading = false,
        focusRecovery = null,
        initialFocusTarget = HomeInitialFocusTarget.NONE,
        playbackReturnRevision = revision,
        playbackReturnToken = request.key,
    )
}
