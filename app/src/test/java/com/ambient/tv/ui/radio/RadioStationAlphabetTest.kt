package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStationAlphabetTest {
    private fun station(id: String, name: String = id) = RadioStation(
        id = id,
        name = name,
        streamUrl = "https://example.test/$id",
        homepage = null,
        artworkUrl = null,
        country = null,
        countryCode = null,
        state = null,
        languages = emptyList(),
        tags = emptyList(),
        codec = null,
        bitrateKbps = null,
        isHls = false,
        votes = 0,
        clickCount = 0,
    )

    @Test fun alphabetSortPlacesNumbersFirstAndLastByDirection() {
        val source = listOf(
            station("z", "Zulu"),
            station("b", "Blues"),
            station("digit", "80s FM"),
            station("a2", "Älpler"),
            station("a1", "Ambient"),
            station("other", "! Radio"),
        )
        val asc = radioAlphabeticalStations(source, locale = Locale.GERMAN)
        val desc = radioAlphabeticalStations(source, descending = true, locale = Locale.GERMAN)
        assertEquals(listOf("#", "#", "A", "A", "B", "Z"),
            asc.map { radioStationLetter(it).toString() })
        assertEquals(asc.map { it.id }.reversed(), desc.map { it.id })
        assertEquals('#', radioStationLetter(asc.first()))
        assertEquals('#', radioStationLetter(desc.last()))
    }

    @Test fun jumpIndicesAreExactlyTheDisplayedSortedRowPositions() {
        val items = radioAlphabeticalStations(listOf(
            station("rock", "Rock"), station("digit", "42 FM"),
            station("a1", "Ambient"), station("a2", "Another A"),
            station("jazz", "Jazz"),
        ), locale = Locale.GERMAN)
        val jumps = radioStationAlphabetJumps(items)
        assertEquals(
            listOf(RadioAlphabetJump("#", 0), RadioAlphabetJump("A", 1),
                RadioAlphabetJump("J", 3), RadioAlphabetJump("R", 4)),
            jumps,
        )
        assertTrue(jumps.all { jump ->
            items.getOrNull(jump.index)?.let { radioStationLetter(it).toString() == jump.letter } == true
        })
        val reverse = radioAlphabeticalStations(items, descending = true, locale = Locale.GERMAN)
        assertEquals(
            listOf(RadioAlphabetJump("R", 0), RadioAlphabetJump("J", 1),
                RadioAlphabetJump("A", 2), RadioAlphabetJump("#", 4)),
            radioStationAlphabetJumps(reverse),
        )
    }

    @Test fun aSecondPageExtendsTheIndexWithoutBreakingEarlierOffsets() {
        val first = radioAlphabeticalStations(listOf(station("a","Alpha"), station("b","Blues")), locale = Locale.GERMAN)
        val extended = radioAlphabeticalStations(first + listOf(
            station("n", "News"), station("z", "Zoo"),
        ), locale = Locale.GERMAN)
        assertEquals(listOf(RadioAlphabetJump("A",0),RadioAlphabetJump("B",1)), radioStationAlphabetJumps(first))
        assertEquals(listOf(RadioAlphabetJump("A",0),RadioAlphabetJump("B",1),
            RadioAlphabetJump("N",2),RadioAlphabetJump("Z",3)), radioStationAlphabetJumps(extended))
    }

    @Test fun pagingAppendsServerOrderedItemsInsteadOfLocallyResortingThem() {
        val first = listOf(station("last", "Zulu"), station("early", "Alpha"))
        val page = listOf(station("middle", "Middle"), station("early", "Duplicate"))
        val combined = radioAppendPagedStations(first, page)
        assertEquals(listOf("last", "early", "middle"), combined.map { it.id })
        assertEquals("Alpha", combined[1].name)
    }

    @Test fun removingFavoritePrefersNextThenPreviousAndNeverLosesTheWholeList() {
        val items = listOf(station("a"), station("b"), station("c"))
        assertEquals("c", radioFocusAfterRemovingFavorite(items, "b"))
        assertEquals("b", radioFocusAfterRemovingFavorite(items, "a"))
        assertEquals("b", radioFocusAfterRemovingFavorite(items, "c"))
        assertNull(radioFocusAfterRemovingFavorite(listOf(station("a")), "a"))
        assertNull(radioFocusAfterRemovingFavorite(items, "not-present"))
    }
}
