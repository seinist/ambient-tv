package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioFacet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioFocusPolicyTest {
    private val visible = listOf("station-a", "station-b", "station-c")

    @Test fun lastFocusedWinsOverPlaying() {
        assertEquals("station-c", radioInitialFocusId("station-c", "station-b", visible))
    }

    @Test fun playingIsPreferredWhenNothingHasBeenFocused() {
        assertEquals("station-b", radioInitialFocusId(null, "station-b", visible))
    }

    @Test fun missingPreviousFocusFallsBackToPlayingThenFirst() {
        assertEquals("station-b", radioInitialFocusId("station-gone", "station-b", visible))
        assertEquals("station-a", radioInitialFocusId("station-gone", "station-not-listed", visible))
    }

    @Test fun emptyResultsNeverSelectStaleItem() {
        assertNull(radioInitialFocusId("station-a", "station-b", emptyList()))
    }

    @Test fun focusScopeDistinguishesFacetAndSearchFromCategory() {
        val categories = RadioUiState(section = RadioSection.GENRES).focusScope()
        val nested = RadioUiState(
            section = RadioSection.GENRES,
            selectedFacet = RadioFacet(id = "rock", name = "Rock", stationCount = 20),
        ).focusScope()
        val search = RadioUiState(section = RadioSection.GENRES, submittedQuery = "jazz").focusScope()
        assertEquals(RadioFocusScope(RadioSection.GENRES, null, ""), categories)
        assertEquals(RadioFocusScope(RadioSection.GENRES, "rock", ""), nested)
        assertEquals(RadioFocusScope(RadioSection.GENRES, null, "jazz"), search)
    }
}
