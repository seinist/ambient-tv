package com.ambient.tv.ui.spotify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyInitialLoadingTest {
    @Test fun firstCompositionIsLoadingNotAnEmptyAuthorizedCatalog() {
        val initial = SpotifyHomeUiState(webAuthorized = true)
        assertTrue(initial.awaitingInitialContent("home"))
        assertFalse(initial.canConfirmEmptySection("home"))
    }

    @Test fun loginNotYetAvailableMustNotClaimSpotifyHasNoMedia() {
        val unauthorized = SpotifyHomeUiState(loading = false, webAuthorized = false)
        assertFalse(unauthorized.canConfirmEmptySection("home"))
        assertFalse(unauthorized.awaitingInitialContent("home"))
    }

    @Test fun finishedAuthorizedEmptyIsDifferentFromFetchingAnEmptyPage() {
        val confirmed = SpotifyHomeUiState(loading = false, refreshing = false, webAuthorized = true)
        assertTrue(confirmed.canConfirmEmptySection("home"))
        assertFalse(confirmed.copy(refreshing = true).canConfirmEmptySection("home"))
        assertTrue(confirmed.copy(refreshing = true).awaitingInitialContent("home"))
        assertTrue(confirmed.copy(sectionKey = "favorites").awaitingInitialContent("home"))
        assertFalse(confirmed.copy(sectionKey = "favorites").canConfirmEmptySection("home"))
    }
}
