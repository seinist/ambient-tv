package com.ambient.tv.ui.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPaginationTest {
    @Test fun focusLoadsOnlyNearTheGridEnd() {
        assertFalse(radioShouldLoadOnFocus(index = 10, size = 48, hasMore = true, loading = false, loadingMore = false))
        assertTrue(radioShouldLoadOnFocus(index = 38, size = 48, hasMore = true, loading = false, loadingMore = false))
        assertTrue(radioShouldLoadOnFocus(index = 47, size = 48, hasMore = true, loading = false, loadingMore = false))
    }

    @Test fun focusDoesNotDuplicateRequestsOrLoadWhenFinished() {
        assertFalse(radioShouldLoadOnFocus(index = 47, size = 48, hasMore = false, loading = false, loadingMore = false))
        assertFalse(radioShouldLoadOnFocus(index = 47, size = 48, hasMore = true, loading = true, loadingMore = false))
        assertFalse(radioShouldLoadOnFocus(index = 47, size = 48, hasMore = true, loading = false, loadingMore = true))
        assertFalse(radioShouldLoadOnFocus(index = -1, size = 48, hasMore = true, loading = false, loadingMore = false))
    }

    @Test fun pagingTailRequestsNextPageOnlyWhenIdle() {
        assertTrue(radioPagingTailReady(hasMore = true, loading = false, loadingMore = false))
        assertFalse(radioPagingTailReady(hasMore = false, loading = false, loadingMore = false))
        assertFalse(radioPagingTailReady(hasMore = true, loading = true, loadingMore = false))
        assertFalse(radioPagingTailReady(hasMore = true, loading = false, loadingMore = true))
    }

    @Test fun shortCountryPageUsesCountHintButStopsAfterEmptyPage() {
        assertTrue(radioHasMoreStations(apiHasMore = true, mappedCount = 48,
            nextOffset = 48, facetStationCount = null))
        assertTrue(radioHasMoreStations(apiHasMore = false, mappedCount = 37,
            nextOffset = 48, facetStationCount = 250))
        assertFalse(radioHasMoreStations(apiHasMore = false, mappedCount = 0,
            nextOffset = 96, facetStationCount = 250))
        assertFalse(radioHasMoreStations(apiHasMore = false, mappedCount = 37,
            nextOffset = 96, facetStationCount = 80))
    }

    @Test fun paginatedCatalogsNeverOfferIncompleteAlphabetJump() {
        val facet = com.ambient.tv.data.radio.RadioFacet("DE", "Germany", 2300)
        assertFalse(radioStationAlphabetAllowed(RadioSection.COUNTRIES, facet, ""))
        assertFalse(radioStationAlphabetAllowed(RadioSection.GENRES, facet, ""))
        assertFalse(radioStationAlphabetAllowed(RadioSection.LANGUAGES, facet, ""))
        assertFalse(radioStationAlphabetAllowed(RadioSection.POPULAR, null, "radio"))
        assertTrue(radioStationAlphabetAllowed(RadioSection.POPULAR, null, ""))
        assertTrue(radioStationAlphabetAllowed(RadioSection.FAVORITES, null, ""))
        assertTrue(radioStationAlphabetAllowed(RadioSection.RECENT, null, ""))
    }

    @Test fun cappedFacetOverviewsUseScrollbarInsteadOfPartialAlphabet() {
        assertTrue(radioFacetAlphabetAllowed(149))
        assertFalse(radioFacetAlphabetAllowed(150))
        assertFalse(radioFacetAlphabetAllowed(5))
    }

    @Test fun pagingMatchesSmartTubeGridTuning() {
        assertEquals(10, RADIO_GRID_CONTINUE_THRESHOLD)
        assertEquals(350L, RADIO_FOCUS_SETTLE_MS)
        assertEquals(2, RADIO_GRID_END_PLACEHOLDERS)
        assertEquals("radio-grid-more:1", radioGridEndPlaceholderKey(1))
    }
}
