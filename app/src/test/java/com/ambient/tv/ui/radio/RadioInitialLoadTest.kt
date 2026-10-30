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

    @Test fun emptyLocalFavoritesAreAValidResolvedEmptyPage() {
        val local = RadioUiState(section = RadioSection.FAVORITES, loading = false, contentReady = true)
        assertFalse(local.awaitingInitialContent())
    }
}
