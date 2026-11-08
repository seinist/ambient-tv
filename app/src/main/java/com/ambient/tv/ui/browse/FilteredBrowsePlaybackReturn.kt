package com.ambient.tv.ui.browse

import com.ambient.tv.ui.common.NavigationPlaybackReturn

internal fun prepareFilteredBrowsePlaybackReturn(
    state: FilteredBrowseUiState.Loaded,
    returned: NavigationPlaybackReturn?,
): FilteredBrowseUiState.Loaded? {
    if (returned == null || state.playbackReturnRevision == returned.revision) return null
    val item = state.items.firstOrNull { returned.itemId in it.allRatingKeys } ?: return null
    return state.copy(focusedRatingKey = item.ratingKey, playbackReturnRevision = returned.revision)
}
