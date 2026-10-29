package com.ambient.tv.data.radio

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioBrowserRepositoryTest {
    @Test fun searchNormalizesFiltersAndPagesByStableUuid() = runBlocking {
        val api = FakeRadioBrowserApi().apply {
            stations = listOf(
                station("one", "One"),
                station("two", "Two"),
            )
        }
        val repository = RadioBrowserRepository(api)

        val page = repository.search(
            query = "  rock ",
            countryCode = " de ",
            language = " German ",
            tag = " pop ",
            offset = 4,
            limit = 2,
        )

        assertEquals(listOf("one", "two"), page.items.map { it.id })
        assertEquals(4, page.offset)
        assertEquals(2, page.limit)
        assertTrue(page.hasMore)
        assertEquals("rock", api.lastName)
        assertEquals("DE", api.lastCountryCode)
        assertEquals("German", api.lastLanguage)
        assertEquals("pop", api.lastTag)
        assertFalse(api.lastLanguageExact)
        assertFalse(api.lastTagExact)
        assertEquals(4, api.lastOffset)
        assertEquals(2, api.lastLimit)
    }

    @Test fun invalidCatalogRowsAreDroppedAndResolvedUrlWins() = runBlocking {
        val api = FakeRadioBrowserApi().apply {
            stations = listOf(
                station("", "Missing id"),
                station("blank-name", " "),
                station("valid", "Valid", url = "https://old.example/stream", resolved = "https://new.example/stream"),
            )
        }
        val station = RadioBrowserRepository(api).search(limit = 10).items.single()

        assertEquals("valid", station.id)
        assertEquals("https://new.example/stream", station.streamUrl)
        assertEquals(listOf("Rock", "Pop"), station.tags)
        assertEquals(listOf("German", "English"), station.languages)
        assertEquals("DE", station.countryCode)
        assertEquals(128, station.bitrateKbps)
        assertFalse(station.isHls)
    }

    @Test fun facetListsAreNormalizedAndExactFiltersReachSearch() = runBlocking {
        val api = FakeRadioBrowserApi().apply {
            facets = listOf(
                RadioBrowserFacetDto("rock", JsonPrimitive("12")),
                RadioBrowserFacetDto("", JsonPrimitive(99)),
            )
            stations = listOf(station("one", "One"))
        }
        val repository = RadioBrowserRepository(api)

        assertEquals(listOf(RadioFacet("rock", "rock", 12)), repository.genres())
        api.facets = listOf(RadioBrowserFacetDto("DE", JsonPrimitive(42)))
        val country = repository.countries().single()
        assertEquals("DE", country.value)
        assertEquals(42, country.stationCount)
        repository.search(language = "German", tag = "rock", exactFilters = true)

        assertTrue(api.lastLanguageExact)
        assertTrue(api.lastTagExact)
    }

    @Test fun playbackUsesClickUrlButFallsBackToCatalogStream() = runBlocking {
        val station = station("one", "One").toRadioStation()!!
        val api = FakeRadioBrowserApi().apply {
            click = RadioBrowserClickDto(stationUuid = "one", url = "https://fresh.example/stream")
        }
        val repository = RadioBrowserRepository(api)
        assertEquals("https://fresh.example/stream", repository.playbackUrl(station))

        api.throwOnClick = true
        assertEquals(station.streamUrl, repository.playbackUrl(station))
    }

    @Test fun stationLookupUsesUuidAndCanReturnNull() = runBlocking {
        val api = FakeRadioBrowserApi().apply { stations = emptyList() }
        assertNull(RadioBrowserRepository(api).station("missing"))
        assertEquals("missing", api.lastUuid)
    }

    private fun station(
        uuid: String,
        name: String,
        url: String = "https://example.test/$uuid",
        resolved: String = "",
    ) = RadioBrowserStationDto(
        stationUuid = uuid,
        name = name,
        url = url,
        resolvedUrl = resolved,
        country = "Germany",
        countryCode = "de",
        language = "German, English, German",
        tags = "Rock, Pop, Rock",
        codec = "mp3",
        bitrate = 128,
        hls = 0,
    )

    private class FakeRadioBrowserApi : RadioBrowserApiService {
        var stations: List<RadioBrowserStationDto> = emptyList()
        var click = RadioBrowserClickDto()
        var throwOnClick = false
        var lastName: String? = null
        var lastCountryCode: String? = null
        var lastLanguage: String? = null
        var lastTag: String? = null
        var lastLanguageExact = false
        var lastTagExact = false
        var facets: List<RadioBrowserFacetDto> = emptyList()
        var lastOffset = -1
        var lastLimit = -1
        var lastUuid: String? = null

        override suspend fun searchStations(
            name: String?,
            countryCode: String?,
            language: String?,
            languageExact: Boolean,
            tag: String?,
            tagExact: Boolean,
            order: String,
            reverse: Boolean,
            offset: Int,
            limit: Int,
            hideBroken: Boolean,
        ): List<RadioBrowserStationDto> {
            lastName = name
            lastCountryCode = countryCode
            lastLanguage = language
            lastLanguageExact = languageExact
            lastTag = tag
            lastTagExact = tagExact
            lastOffset = offset
            lastLimit = limit
            return stations
        }

        override suspend fun tags(order: String, reverse: Boolean, offset: Int, limit: Int, hideBroken: Boolean): List<RadioBrowserFacetDto> = facets
        override suspend fun countryCodes(order: String, reverse: Boolean, offset: Int, limit: Int, hideBroken: Boolean): List<RadioBrowserFacetDto> = facets
        override suspend fun languages(order: String, reverse: Boolean, offset: Int, limit: Int, hideBroken: Boolean): List<RadioBrowserFacetDto> = facets

        override suspend fun topClicked(limit: Int, hideBroken: Boolean): List<RadioBrowserStationDto> = stations

        override suspend fun stationByUuid(
            stationUuid: String,
            hideBroken: Boolean,
        ): List<RadioBrowserStationDto> {
            lastUuid = stationUuid
            return stations
        }

        override suspend fun resolveAndCountClick(stationUuid: String): RadioBrowserClickDto {
            if (throwOnClick) error("offline")
            return click
        }
    }
}
