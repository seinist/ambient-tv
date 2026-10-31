package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioFacet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioInitialLoadTest {
    @Test fun firstCompositionMustNotReportEmptyBeforeEnterEffect() {
        val initial = RadioUiState()
        assertTrue(initial.loading)
        assertFalse(initial.contentReady)
        assertTrue(initial.awaitingInitialContent())
    }

    @Test fun emptyRemoteCategoriesAreOnlyConfirmedAfterTheirRequestSettles() {
        val before = RadioUiState(section = RadioSection.GENRES, loading = false, contentReady = false)
        assertTrue(before.awaitingInitialContent())
        assertFalse(before.copy(contentReady = true).awaitingInitialContent())
        assertTrue(before.copy(contentReady = true, loading = true).awaitingInitialContent())
        val loaded = before.copy(facets = listOf(RadioFacet("rock", "Rock", 30)))
        assertFalse(loaded.awaitingInitialContent())
    }

    @Test fun parentFacetListDoesNotProveItsStationsWereAlreadyLoaded() {
        for (section in listOf(RadioSection.GENRES, RadioSection.COUNTRIES, RadioSection.LANGUAGES)) {
            val parent = RadioFacet("rock", "Rock", 12)
            val pending = RadioUiState(
                section = section,
                selectedFacet = parent,
                facets = listOf(parent),
                loading = true,
                contentReady = false,
            )
            assertTrue(pending.awaitingInitialContent())
            assertTrue(pending.awaitingRequestedPage(section, entryPending = false))
            assertTrue(pending.needsRemoteRefreshOnReturn())
            val emptyAfterFetch = pending.copy(loading = false, contentReady = true)
            assertFalse(emptyAfterFetch.awaitingInitialContent())
            assertFalse(emptyAfterFetch.awaitingRequestedPage(section, entryPending = false))
        }
    }

    @Test fun facetRootUsesOnlyItsOwnSettledDataToDetermineEmptyState() {
        val pending = RadioUiState(section = RadioSection.COUNTRIES, loading = true, contentReady = false)
        assertTrue(pending.awaitingInitialContent())
        assertFalse(pending.copy(loading = false, contentReady = true).awaitingInitialContent())
        assertFalse(pending.copy(facets = listOf(RadioFacet("DE", "Germany", 500))).awaitingInitialContent())
    }

    @Test fun emptyLocalFavoritesAreAValidResolvedEmptyPage() {
        val local = RadioUiState(section = RadioSection.FAVORITES, loading = false, contentReady = true)
        assertFalse(local.awaitingInitialContent())
    }
}
