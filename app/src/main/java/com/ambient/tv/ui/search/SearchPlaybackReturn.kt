package com.ambient.tv.ui.search

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.spotify.SpotifyEntityType
import com.ambient.tv.ui.common.BrowseFocusKey
import com.ambient.tv.ui.common.NavigationPlaybackReturn

internal data class PreparedSearchPlaybackReturn(val state: SearchUiState, val target: BrowseFocusKey)

/** Player IDs and the UI's provider-qualified focus IDs intentionally have separate formats. */
internal fun searchPlaybackReturnItemKey(state: SearchUiState, itemId: String): String? {
    sequenceOf(state.results, state.jellyfinResults, state.embyResults, state.trailerResults, state.telegramResults)
        .flatten().firstOrNull { itemId in it.allRatingKeys }?.let { return it.ratingKey }
    state.youtubeResults.firstOrNull { itemId == "youtube:${it.videoId}" }
        ?.let { return "search:youtube:${it.videoId}" }
    state.spotifyResults.firstOrNull { it.type == SpotifyEntityType.TRACK && itemId == "spotify:track:${it.id}" }
        ?.let { return "search:spotify:${it.type}:${it.id}" }
    state.radioResults.firstOrNull { itemId == "radio-station:${it.id}" }
        ?.let { return "search:radio:${it.id}" }
    state.tvheadendResults.channels.firstOrNull { itemId == "live-channel:${it.sourceId}:${it.uuid}" }
        ?.let { return "search:tvh-channel:${it.sourceId}:${it.uuid}" }
    state.tvheadendResults.programs.firstOrNull { itemId == "live-channel:${it.sourceId}:${it.channelUuid}" }
        ?.let { return "search:tvh-program:${it.sourceId}:${it.eventId}" }
    state.tvheadendResults.recordings.firstOrNull { itemId == "live-recording:${it.sourceId}:${it.uuid}" }
        ?.let { return "search:tvh-recording:${it.sourceId}:${it.uuid}" }
    return null
}

/** The same key names are registered by both result layouts; only the row component differs. */
internal fun searchResultFocusAddress(state: SearchUiState, itemKey: String?): BrowseFocusKey? {
    if (itemKey == null) return null
    fun address(row: String) = BrowseFocusKey(row.takeIf { state.viewMode == SearchViewMode.ROWS }, itemKey, true)
    fun media(provider: String, groups: List<Pair<Int, List<MediaItem>>>): BrowseFocusKey? =
        groups.firstOrNull { (_, items) -> items.any { it.ratingKey == itemKey } }
            ?.let { (group, _) -> address("search:$provider:$group") }
    media("plex", searchMediaGroups(state.results))?.let { return it }
    media("jellyfin", searchMediaGroups(state.jellyfinResults))?.let { return it }
    media("emby", searchMediaGroups(state.embyResults))?.let { return it }
    media("trailers", searchMediaGroups(state.trailerResults))?.let { return it }
    media("telegram", telegramSearchGroups(state.telegramResults))?.let { return it }
    state.youtubeResults.firstOrNull { itemKey == "search:youtube:${it.videoId}" }?.let { return address("search:youtube") }
    state.spotifyResults.firstOrNull { itemKey == "search:spotify:${it.type}:${it.id}" }
        ?.let { return address("search:spotify:${it.type}") }
    state.radioResults.firstOrNull { itemKey == "search:radio:${it.id}" }?.let { return address("search:radio") }
    state.tvheadendResults.channels.firstOrNull { itemKey == "search:tvh-channel:${it.sourceId}:${it.uuid}" && it.sourceId in state.liveTvSources }
        ?.let { return address("search:tvh-channels:${it.sourceId}") }
    state.tvheadendResults.programs.firstOrNull { itemKey == "search:tvh-program:${it.sourceId}:${it.eventId}" && it.sourceId in state.liveTvSources }
        ?.let { return address("search:tvh-programs:${it.sourceId}") }
    state.tvheadendResults.recordings.firstOrNull { itemKey == "search:tvh-recording:${it.sourceId}:${it.uuid}" && it.sourceId in state.liveTvSources }
        ?.let { return address("search:tvh-recordings:${it.sourceId}") }
    return null
}

internal fun prepareSearchPlaybackReturn(state: SearchUiState, returned: NavigationPlaybackReturn?): PreparedSearchPlaybackReturn? {
    if (returned == null || state.playbackReturnRevision == returned.revision) return null
    val itemKey = searchPlaybackReturnItemKey(state, returned.itemId) ?: return null
    val target = searchResultFocusAddress(state, itemKey) ?: return null
    fun SearchSourceState.completed(hasResults: Boolean): SearchSourceState =
        if (this == SearchSourceState.LOADING) {
            if (hasResults) SearchSourceState.READY else SearchSourceState.IDLE
        } else this
    return PreparedSearchPlaybackReturn(
        state.copy(
            playbackReturnRevision = returned.revision,
            plexState = state.plexState.completed(state.results.isNotEmpty()),
            jellyfinState = state.jellyfinState.completed(state.jellyfinResults.isNotEmpty()),
            embyState = state.embyState.completed(state.embyResults.isNotEmpty()),
            trailerState = state.trailerState.completed(state.trailerResults.isNotEmpty()),
            telegramState = state.telegramState.completed(state.telegramResults.isNotEmpty()),
            youtubeState = state.youtubeState.completed(state.youtubeResults.isNotEmpty()),
            spotifyState = state.spotifyState.completed(state.spotifyResults.isNotEmpty()),
            radioState = state.radioState.completed(state.radioResults.isNotEmpty()),
            tvheadendState = state.tvheadendState.completed(state.tvheadendResults.size > 0),
        ),
        target,
    )
}
