package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioFacet
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioFacetAlphabetTest {
    private fun facet(id: String, name: String, code: String? = null) =
        RadioFacet(id = id, name = name, stationCount = 3, languageCode = code)

    @Test fun localizedCountryAndLanguageNamesAreUsedForAlphabeticalNavigation() {
        assertEquals("Deutschland",
            radioFacetDisplayName(RadioSection.COUNTRIES, facet("DE", "Germany"), Locale.GERMAN))
        assertEquals("Englisch",
            radioFacetDisplayName(RadioSection.LANGUAGES, facet("en", "English", "en"), Locale.GERMAN))
    }

    @Test fun diacriticsShareTheInitialLetterWithTheirBaseCharacter() {
        assertEquals("A", radioFacetInitial("Älpler"))
        assertEquals("O", radioFacetInitial("Österreich"))
        assertEquals("E", radioFacetInitial("Électronique"))
        assertEquals("#", radioFacetInitial("  80s"))
        assertEquals("Р", radioFacetInitial("Русский"))
    }

    @Test fun alphabetIndicesMatchSortedDisplayedRowsAndSkipUnavailableLetters() {
        val entries = radioAlphabetFacets(RadioSection.GENRES, listOf(
            facet("r", "Rock"), facet("e", "electro"), facet("b", "Blues"),
            facet("a", "ambient"), facet("s", "80s"), facet("a2", "Älpler"),
        ), Locale.GERMAN)
        val comparator = java.text.Collator.getInstance(Locale.GERMAN)
        assertTrue(entries.zipWithNext().all { (left, right) ->
            comparator.compare(left.title, right.title) <= 0
        })
        val index = radioAlphabetJumps(entries)
        assertTrue(index.isNotEmpty())
        assertTrue(index.none { it.letter == "Z" })
        assertEquals(entries.indexOfFirst { it.letter == "A" }, index.first { it.letter == "A" }.index)
        assertEquals(entries.indexOfFirst { it.letter == "#" }, index.first { it.letter == "#" }.index)
    }

    @Test fun categoryOverviewAlwaysUsesListWhileStationViewsRetainUserPreference() {
        val facet = facet("r", "Rock")
        assertEquals(RadioViewMode.LIST,
            RadioUiState(section = RadioSection.GENRES, viewMode = RadioViewMode.GRID).effectiveViewMode())
        assertEquals(RadioViewMode.LIST,
            RadioUiState(section = RadioSection.COUNTRIES, viewMode = RadioViewMode.GRID).effectiveViewMode())
        assertEquals(RadioViewMode.LIST,
            RadioUiState(section = RadioSection.LANGUAGES, viewMode = RadioViewMode.GRID).effectiveViewMode())
        assertEquals(RadioViewMode.GRID,
            RadioUiState(section = RadioSection.GENRES, selectedFacet = facet, viewMode = RadioViewMode.GRID).effectiveViewMode())
        assertEquals(RadioViewMode.GRID,
            RadioUiState(section = RadioSection.POPULAR, viewMode = RadioViewMode.GRID).effectiveViewMode())
        assertFalse(RadioUiState(section = RadioSection.GENRES, submittedQuery = "rock").isFacetOverview())
    }
}
