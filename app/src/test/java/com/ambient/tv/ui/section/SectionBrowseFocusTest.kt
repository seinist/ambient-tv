package com.ambient.tv.ui.section

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.BrowseFocusSnapshot
import org.junit.Assert.*
import org.junit.Test

class SectionBrowseFocusTest {
    @Test fun finishingABackgroundRebuildKeepsTheNewestUnpersistedRowAndCollectionFocus() {
        val media = item("latest")
        val old = SectionUiState.Loaded("Library", SectionPage.RECOMMENDATIONS, listOf(media),
            listOf(RecommendationRow("row", "Row", listOf(media))), emptyList(), SectionSort.TITLE,
            SectionSortOrder.ASCENDING, SectionFilter.ALL, SectionQualityFilter.ALL,
            viewMode = SectionViewMode.POSTER, focusedRatingKey = "old", focusedRecommendationRowId = "old-row")
        val current = old.withLatestBrowseFocus(BrowseFocusSnapshot("latest", "row"))
        assertEquals("latest", current.focusedRatingKey)
        assertEquals("row", current.focusedRecommendationRowId)
        assertNull(old.withLatestBrowseFocus(BrowseFocusSnapshot("latest", null)).focusedRecommendationRowId)
        assertNull(old.withLatestBrowseFocus(BrowseFocusSnapshot("latest", "removed-row")).focusedRecommendationRowId)
        val collection = current.withLatestBrowseFocus(BrowseFocusSnapshot("collection:server:123", null))
        assertEquals("server:123", collection.focusedCollectionId)
        assertNull(collection.focusedRatingKey)
        assertNull(collection.focusedRecommendationRowId)
        assertSame(old, old.withLatestBrowseFocus(null))
    }

    private fun item(key: String) = MediaItem(ratingKey = key, title = key, type = "movie",
        index = null, thumbUrl = null, artUrl = "art-$key", year = null, summary = null,
        tagline = null, durationMs = null, viewOffsetMs = null, parentTitle = null,
        parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
        contentRating = null, rating = null, studio = null)
}
