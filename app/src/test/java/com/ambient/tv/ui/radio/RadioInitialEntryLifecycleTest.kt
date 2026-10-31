package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioFacet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioInitialEntryLifecycleTest {
    @Test fun coldScreenRendersSkeletonUntilItsFirstRequestStarts() {
        val state = RadioUiState()
        assertTrue(state.awaitingRequestedPage(RadioSection.POPULAR, entryPending = true))
        assertTrue(state.awaitingRequestedPage(RadioSection.POPULAR, entryPending = false))
        assertEquals(RadioLoadingLayout.GRID, state.requestedLoadingLayout(RadioSection.POPULAR))
    }

    @Test fun oldConfirmedEmptyOrErrorCannotFlashOnResumingDestination() {
        val oldEmpty = RadioUiState(
            section = RadioSection.POPULAR,
            loading = false,
            contentReady = true,
        )
        val oldError = oldEmpty.copy(error = true)
        assertTrue(oldEmpty.needsRemoteRefreshOnReturn())
        assertTrue(oldError.needsRemoteRefreshOnReturn())
        assertTrue(oldEmpty.awaitingRequestedPage(RadioSection.POPULAR, entryPending = true))
        assertTrue(oldError.awaitingRequestedPage(RadioSection.POPULAR, entryPending = true))
        // After the entry has actually completed, a genuine empty result is allowed.
        assertFalse(oldEmpty.awaitingRequestedPage(RadioSection.POPULAR, entryPending = false))
    }

    @Test fun navigationToDifferentCategoryCannotRenderFormerPagesEmptyState() {
        val old = RadioUiState(section = RadioSection.POPULAR, loading = false, contentReady = true, error = true)
        for (target in listOf(RadioSection.GENRES, RadioSection.COUNTRIES, RadioSection.LANGUAGES)) {
            assertTrue(old.awaitingRequestedPage(target, entryPending = false))
            assertEquals(RadioLoadingLayout.FACETS, old.requestedLoadingLayout(target))
        }
        assertTrue(old.awaitingRequestedPage(RadioSection.RECENT, entryPending = false))
    }

    @Test fun existingRemoteCardsAreRetainedDuringRefreshAndPlayerReturn() {
        val loaded = RadioUiState(
            section = RadioSection.GENRES,
            facets = listOf(RadioFacet("rock", "Rock", 15)),
            loading = false,
            contentReady = true,
        )
        assertFalse(loaded.needsRemoteRefreshOnReturn())
        assertFalse(loaded.awaitingRequestedPage(RadioSection.GENRES, entryPending = false))
        assertFalse(loaded.copy(loading = true).awaitingRequestedPage(RadioSection.GENRES, entryPending = false))
    }

    @Test fun localFavoritesAndRecentDoNotNeedNetworkToConfirmEmpty() {
        for (section in listOf(RadioSection.FAVORITES, RadioSection.RECENT)) {
            val local = RadioUiState(section = section, loading = false, contentReady = true)
            assertFalse(local.needsRemoteRefreshOnReturn())
            assertFalse(local.awaitingRequestedPage(section, entryPending = false))
        }
    }

    @Test fun pendingSearchOrFacetRefreshIsDistinguishedFromResolvedEmpty() {
        val pendingSearch = RadioUiState(
            section = RadioSection.POPULAR,
            submittedQuery = "jazz",
            contentReady = false,
            loading = true,
        )
        assertTrue(pendingSearch.needsRemoteRefreshOnReturn())
        assertTrue(pendingSearch.awaitingRequestedPage(RadioSection.POPULAR, entryPending = false))
        val completed = pendingSearch.copy(contentReady = true, loading = false)
        assertFalse(completed.awaitingRequestedPage(RadioSection.POPULAR, entryPending = false))
    }
}
