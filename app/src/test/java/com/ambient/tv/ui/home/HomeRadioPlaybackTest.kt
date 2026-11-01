package com.ambient.tv.ui.home

import com.ambient.tv.data.radio.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRadioPlaybackTest {
    private fun station(id: String) = RadioStation(
        id = id, name = id, streamUrl = "https://example.test/$id", homepage = null,
        artworkUrl = null, country = null, countryCode = null, state = null,
        languages = emptyList(), tags = emptyList(), codec = null, bitrateKbps = null,
        isHls = false, votes = 0, clickCount = 0,
    )

    @Test fun thirdHomeRadioTileKeepsItsFirstTwoPredecessors() {
        val selection = HomeRadioPlaybackSelection(
            rowId = "radio.popular", stationId = "third",
            stationIds = listOf("first", "second", "third", "fourth"),
            cachedStations = listOf(station("fourth"), station("second")),
        )
        val result = homeRadioPlaybackQueue(
            selection, fetched = listOf(station("third"), station("first")), clicked = station("third"),
        )
        assertEquals(listOf("first", "second", "third", "fourth"), result.map { it.id })
        assertEquals(2, result.indexOfFirst { it.id == selection.stationId })
    }

    @Test fun offlineHomeRadioSelectionKeepsAtLeastTheClickedStation() {
        val clicked = station("third")
        val selection = HomeRadioPlaybackSelection("radio.home", "third", listOf("third"), emptyList())
        assertEquals(listOf(clicked), homeRadioPlaybackQueue(selection, emptyList(), clicked))
    }
}
