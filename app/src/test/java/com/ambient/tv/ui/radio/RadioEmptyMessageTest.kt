package com.ambient.tv.ui.radio

import com.ambient.tv.R
import org.junit.Assert.assertEquals
import org.junit.Test

class RadioEmptyMessageTest {
    @Test fun categoriesDoNotClaimNoStations() {
        assertEquals(R.string.radio_no_genres, RadioUiState(section = RadioSection.GENRES).emptyMessageRes())
        assertEquals(R.string.radio_no_countries, RadioUiState(section = RadioSection.COUNTRIES).emptyMessageRes())
        assertEquals(R.string.radio_no_languages, RadioUiState(section = RadioSection.LANGUAGES).emptyMessageRes())
    }

    @Test fun localPagesHaveTheirOwnEmptyMessages() {
        assertEquals(R.string.radio_no_favorites, RadioUiState(section = RadioSection.FAVORITES).emptyMessageRes())
        assertEquals(R.string.radio_no_recent, RadioUiState(section = RadioSection.RECENT).emptyMessageRes())
    }

    @Test fun searchesOverrideTheCurrentSection() {
        assertEquals(R.string.radio_no_search_results,
            RadioUiState(section = RadioSection.GENRES, submittedQuery = "jazz").emptyMessageRes())
        assertEquals(R.string.radio_no_stations,
            RadioUiState(section = RadioSection.GENRES, selectedFacet = com.ambient.tv.data.radio.RadioFacet("jazz", "Jazz", 1)).emptyMessageRes())
    }
}
