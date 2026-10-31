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

    @Test fun lastFocusSurvivesViewModelRecreationWithinTheAppSession() {
        val scope = RadioFocusScope(RadioSection.COUNTRIES, "focus-test-country", "focus-test-query")
        RadioFocusMemory.save(scope, "focused-station")
        assertEquals("focused-station", RadioFocusMemory.last(scope))
        assertEquals("focused-station", radioInitialFocusId(RadioFocusMemory.last(scope), null, visible + "focused-station"))
    }

    @Test fun newUiSessionDropsSavedRadioTilesEvenIfTheServiceKeepsTheProcessAlive() {
        val scope = RadioFocusScope(RadioSection.POPULAR, null, "")
        RadioFocusMemory.save(scope, "previous-session")
        assertEquals("previous-session", RadioFocusMemory.last(scope))
        RadioFocusMemory.clear()
        assertNull(RadioFocusMemory.last(scope))
        assertEquals("station-a", radioInitialFocusId(RadioFocusMemory.last(scope), null, visible))
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
