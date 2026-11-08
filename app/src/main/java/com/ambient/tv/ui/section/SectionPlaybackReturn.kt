package com.ambient.tv.ui.section

import com.ambient.tv.ui.common.NavigationPlaybackReturn

/** Resolve the actual final player item before a saved lazy layout is restored. */
internal fun prepareSectionPlaybackReturn(
    state: SectionUiState.Loaded,
    returned: NavigationPlaybackReturn?,
): SectionUiState.Loaded? {
    if (returned == null || state.playbackReturnRevision == returned.revision) return null
    return when (state.page) {
        SectionPage.LIBRARY -> {
            val item = state.items.firstOrNull { returned.itemId in it.allRatingKeys } ?: return null
            state.copy(
                focusedRatingKey = item.ratingKey,
                focusedRecommendationRowId = null,
                selectedLetter = null,
                collectionsLoading = false,
                playbackReturnRevision = returned.revision,
            )
        }
        SectionPage.RECOMMENDATIONS -> {
            val row = state.recommendationRows.firstOrNull { row ->
                row.id == state.focusedRecommendationRowId && row.items.any { returned.itemId in it.allRatingKeys }
            } ?: state.recommendationRows.firstOrNull { row -> row.items.any { returned.itemId in it.allRatingKeys } }
                ?: return null
            val item = row.items.first { returned.itemId in it.allRatingKeys }
            state.copy(
                focusedRatingKey = item.ratingKey,
                focusedRecommendationRowId = row.id,
                selectedLetter = null,
                collectionsLoading = false,
                playbackReturnRevision = returned.revision,
            )
        }
        SectionPage.COLLECTIONS -> null
    }
}

/** Each recommendation section contributes its header followed by its cards. */
internal fun recommendationItemLayoutIndex(
    rows: List<RecommendationRow>,
    rowId: String?,
    itemKey: String?,
): Int {
    var offset = 0
    for (row in rows) {
        if (row.id == rowId) {
            val itemIndex = row.items.indexOfFirst { itemKey != null && itemKey in it.allRatingKeys }
            if (itemIndex >= 0) return offset + 1 + itemIndex
        }
        offset += 1 + row.items.size
    }
    return 0
}
