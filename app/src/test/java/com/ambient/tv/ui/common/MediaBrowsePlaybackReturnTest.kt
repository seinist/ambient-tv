package com.ambient.tv.ui.common

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvSearchResults
import com.ambient.tv.livetv.LiveTvSource
import com.ambient.tv.spotify.SpotifyBrowseItem
import com.ambient.tv.spotify.SpotifyEntityType
import com.ambient.tv.ui.browse.BrowseSort
import com.ambient.tv.ui.browse.BrowseViewMode
import com.ambient.tv.ui.browse.FilteredBrowseUiState
import com.ambient.tv.ui.browse.prepareFilteredBrowsePlaybackReturn
import com.ambient.tv.ui.search.SearchSourceState
import com.ambient.tv.ui.search.SearchUiState
import com.ambient.tv.ui.search.SearchViewMode
import com.ambient.tv.ui.search.prepareSearchPlaybackReturn
import com.ambient.tv.ui.search.searchPlaybackReturnItemKey
import com.ambient.tv.ui.section.RecommendationRow
import com.ambient.tv.ui.section.SectionFilter
import com.ambient.tv.ui.section.SectionPage
import com.ambient.tv.ui.section.SectionQualityFilter
import com.ambient.tv.ui.section.SectionSort
import com.ambient.tv.ui.section.SectionSortOrder
import com.ambient.tv.ui.section.SectionUiState
import com.ambient.tv.ui.section.SectionViewMode
import com.ambient.tv.ui.section.prepareSectionPlaybackReturn
import com.ambient.tv.ui.section.recommendationItemLayoutIndex
import com.ambient.tv.youtube.YouTubeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MediaBrowsePlaybackReturnTest {
    @Test fun recommendationReturnKeepsItsSourceRowAndCountsHeadersBeforeTheLastPlayedCard() {
        val first = RecommendationRow("first", "First", listOf(item("shared"), item("a")))
        val second = RecommendationRow("second", "Second", listOf(item("shared"), item("b"), item("last")))
        val current = section(listOf(first, second)).copy(focusedRatingKey = "b", focusedRecommendationRowId = "second")
        val prepared = prepareSectionPlaybackReturn(current, returned("last"))!!
        assertEquals("last", prepared.focusedRatingKey)
        assertEquals("second", prepared.focusedRecommendationRowId)
        assertSame(current.recommendationRows, prepared.recommendationRows)
        assertEquals(6, recommendationItemLayoutIndex(prepared.recommendationRows, "second", "last"))
        assertEquals("second", prepareSectionPlaybackReturn(current, returned("shared"))!!.focusedRecommendationRowId)
        assertNull(prepareSectionPlaybackReturn(prepared, returned("last")))
        assertEquals(2L, prepareSectionPlaybackReturn(prepared, returned("last", 2))!!.playbackReturnRevision)
    }

    @Test fun libraryReturnUsesTheVisibleAliasAndDoesNotReplayAnEarlierAlphabetJump() {
        val canonical = item("canonical").copy(linkedRatingKeys = listOf("player-alias"))
        val current = section(emptyList()).copy(page = SectionPage.LIBRARY, items = listOf(item("first"), canonical), selectedLetter = 'A')
        val prepared = prepareSectionPlaybackReturn(current, returned("player-alias"))!!
        assertEquals("canonical", prepared.focusedRatingKey)
        assertNull(prepared.selectedLetter)
        assertNull(prepareSectionPlaybackReturn(current, returned("filtered-away")))
        assertEquals(0, recommendationItemLayoutIndex(emptyList(), "missing", "missing"))
    }

    @Test fun filteredBrowseKeepsItsSnapshotAndCommitsThePreviewRevisionOnlyOnce() {
        val current = FilteredBrowseUiState.Loaded("Collection", listOf(item("a"), item("b")), BrowseViewMode.GRID, BrowseSort.TITLE)
        val prepared = prepareFilteredBrowsePlaybackReturn(current, returned("b"))!!
        assertSame(current.items, prepared.items)
        assertEquals("b", prepared.focusedRatingKey)
        assertEquals(1L, prepared.playbackReturnRevision)
        assertNull(prepareFilteredBrowsePlaybackReturn(prepared, returned("b")))
        assertNull(prepareFilteredBrowsePlaybackReturn(current, returned("missing")))
    }

    @Test fun searchReturnUsesTheActualPlayerAliasAndStopsAnOlderPartialSearch() {
        val canonical = item("canonical").copy(linkedRatingKeys = listOf("next-player-alias"))
        val current = SearchUiState(query = "title", hasSearched = true,
            results = listOf(item("opened")), jellyfinResults = listOf(canonical),
            plexState = SearchSourceState.LOADING, jellyfinState = SearchSourceState.READY,
            youtubeState = SearchSourceState.LOADING)
        val prepared = prepareSearchPlaybackReturn(current, returned("next-player-alias"))!!
        assertEquals("canonical", prepared.target.item)
        assertEquals("search:jellyfin:${com.ambient.tv.R.string.search_movies}", prepared.target.row)
        assertSame(current.results, prepared.state.results)
        assertFalse(prepared.state.isSearching)
        assertNull(prepareSearchPlaybackReturn(prepared.state, returned("next-player-alias")))
        assertNull(prepareSearchPlaybackReturn(current, returned("missing")))
        val list = prepareSearchPlaybackReturn(current.copy(viewMode = SearchViewMode.LIST), returned("next-player-alias"))!!
        assertNull(list.target.row)
        assertEquals(prepared.target.item, list.target.item)
    }

    @Test fun searchConvertsExternalPlayerIdsToTheKeysActuallyRegisteredByTheCards() {
        val state = SearchUiState(
            youtubeResults = listOf(YouTubeItem("video", "Video")),
            spotifyResults = listOf(SpotifyBrowseItem("track", SpotifyEntityType.TRACK, "Track", uri = "spotify:track:track")),
            tvheadendResults = LiveTvSearchResults(channels = listOf(
                LiveTvChannel("channel:uuid", "Channel", null, null, sourceId = "source:id"),
            )),
            liveTvSources = mapOf("source:id" to LiveTvSource("source:id", "tvheadend", "TV")),
        )
        assertEquals("search:youtube:video", searchPlaybackReturnItemKey(state, "youtube:video"))
        assertEquals("search:spotify:TRACK:track", searchPlaybackReturnItemKey(state, "spotify:track:track"))
        val television = prepareSearchPlaybackReturn(state, returned("live-channel:source:id:channel:uuid"))!!
        assertEquals("search:tvh-channel:source:id:channel:uuid", television.target.item)
        assertEquals("search:tvh-channels:source:id", television.target.row)
        assertNull(prepareSearchPlaybackReturn(state.copy(liveTvSources = emptyMap()), returned("live-channel:source:id:channel:uuid")))
    }

    private fun returned(itemId: String, revision: Long = 1) = NavigationPlaybackReturn("origin-entry", itemId, revision)

    private fun section(rows: List<RecommendationRow>) = SectionUiState.Loaded("Library", SectionPage.RECOMMENDATIONS,
        rows.flatMap { it.items }, rows, emptyList(), SectionSort.TITLE, SectionSortOrder.ASCENDING,
        SectionFilter.ALL, SectionQualityFilter.ALL, viewMode = SectionViewMode.POSTER)

    private fun item(key: String) = MediaItem(ratingKey = key, title = key, type = "movie",
        index = null, thumbUrl = null, artUrl = null, year = null, summary = null,
        tagline = null, durationMs = null, viewOffsetMs = null, parentTitle = null,
        parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
        contentRating = null, rating = null, studio = null)
}
