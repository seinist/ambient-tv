package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioFacet
import org.junit.Assert.assertEquals
import org.junit.Test

class RadioLoadingLayoutTest {
    @Test fun facetOverviewsAlwaysLoadAsTextLists() {
        for (section in listOf(RadioSection.GENRES, RadioSection.COUNTRIES, RadioSection.LANGUAGES)) {
            assertEquals(
                RadioLoadingLayout.FACETS,
                RadioUiState(section = section, viewMode = RadioViewMode.GRID).loadingLayout(),
            )
            assertEquals(
                RadioLoadingLayout.FACETS,
                RadioUiState(section = section, viewMode = RadioViewMode.LIST).loadingLayout(),
            )
        }
    }

    @Test fun stationsWithinFacetsFollowTheSavedStationView() {
        val selected = RadioFacet(id = "rock", name = "Rock", stationCount = 200)
        assertEquals(
            RadioLoadingLayout.GRID,
            RadioUiState(section = RadioSection.GENRES, selectedFacet = selected).loadingLayout(),
        )
        assertEquals(
            RadioLoadingLayout.LIST,
            RadioUiState(section = RadioSection.GENRES, selectedFacet = selected, viewMode = RadioViewMode.LIST).loadingLayout(),
        )
    }

    @Test fun searchAndLocalStationPagesHaveStationShapes() {
        assertEquals(
            RadioLoadingLayout.GRID,
            RadioUiState(section = RadioSection.LANGUAGES, submittedQuery = "jazz").loadingLayout(),
        )
        assertEquals(
            RadioLoadingLayout.LIST,
            RadioUiState(section = RadioSection.FAVORITES, viewMode = RadioViewMode.LIST).loadingLayout(),
        )
    }
}
