package com.ambient.tv.data.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        assertEquals(listOf("de", "en"), station.languageCodes)
        assertEquals("DE-BE", station.regionCode)
        assertEquals(4, station.clickTrend)
        assertEquals(52.52, station.latitude!!, 0.001)
        assertEquals(13.405, station.longitude!!, 0.001)
        assertEquals("2026-10-29T19:00:00Z", station.lastCheckOkTime)
        assertFalse(station.sslError)
        assertFalse(station.isHls)
    }

    @Test fun browseFacetsNormalizeCodesNamesAndFlexibleCounts() = runBlocking {
        val api = FakeRadioBrowserApi().apply {
            countryFacets = listOf(
                RadioBrowserFacetDto(" de ", JsonPrimitive(12)),
                RadioBrowserFacetDto("US", JsonPrimitive("9")),
                RadioBrowserFacetDto(" ", JsonPrimitive(4)),
            )
            languageFacets = listOf(
                RadioBrowserFacetDto(" german ", JsonPrimitive("7"), iso639 = "deu"),
                RadioBrowserFacetDto("English", JsonPrimitive(5), iso639 = "en"),
            )
            tagFacets = listOf(
                RadioBrowserFacetDto(" rock ", JsonPrimitive(8)),
                RadioBrowserFacetDto("pop", JsonPrimitive("6")),
            )
        }
        val repository = RadioBrowserRepository(api)

        assertEquals(listOf("DE", "US"), repository.countries().map { it.id })
        assertEquals(listOf(12, 9), repository.countries().map { it.stationCount })
        assertEquals(listOf("german", "English"), repository.languages().map { it.id })
        assertEquals(listOf("de", "en"), repository.languages().map { it.languageCode })
        assertEquals(listOf("rock", "pop"), repository.tags().map { it.id })
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

    @Test fun cancelledPlaybackResolutionDoesNotFallBackToCatalogUrl() = runBlocking {
        val station = station("one", "One").toRadioStation()!!
        val api = FakeRadioBrowserApi().apply { awaitClickCancellation = true }
        val pending = async { RadioBrowserRepository(api).playbackUrl(station) }

        api.clickStarted.await()
        pending.cancel()
        try {
            pending.await()
            fail("cancelled playback URL resolution returned a fallback URL")
        } catch (_: CancellationException) {
            // Cancellation is control flow: the superseded station must never start.
        }
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
        languageCodes = "de,en,de",
        regionCode = "de-be",
        tags = "Rock, Pop, Rock",
        codec = "mp3",
        bitrate = 128,
        hls = 0,
        clickTrend = 4,
        latitude = 52.52,
        longitude = 13.405,
        lastCheckOkTime = "2026-10-29T19:00:00Z",
        sslError = 0,
    )

    private class FakeRadioBrowserApi : RadioBrowserApiService {
        var stations: List<RadioBrowserStationDto> = emptyList()
        var countryFacets: List<RadioBrowserFacetDto> = emptyList()
        var languageFacets: List<RadioBrowserFacetDto> = emptyList()
        var tagFacets: List<RadioBrowserFacetDto> = emptyList()
        var click = RadioBrowserClickDto()
        var throwOnClick = false
        var awaitClickCancellation = false
        val clickStarted = CompletableDeferred<Unit>()
        var lastName: String? = null
        var lastCountryCode: String? = null
        var lastLanguage: String? = null
        var lastTag: String? = null
        var lastOffset = -1
        var lastLimit = -1
        var lastUuid: String? = null

        override suspend fun searchStations(
            name: String?,
            countryCode: String?,
            language: String?,
            tag: String?,
            order: String,
            reverse: Boolean,
            offset: Int,
            limit: Int,
            hideBroken: Boolean,
        ): List<RadioBrowserStationDto> {
            lastName = name
            lastCountryCode = countryCode
            lastLanguage = language
            lastTag = tag
            lastOffset = offset
            lastLimit = limit
            return stations
        }

        override suspend fun countryCodes(
            order: String,
            reverse: Boolean,
            hideBroken: Boolean,
            limit: Int,
        ): List<RadioBrowserFacetDto> = countryFacets.take(limit)

        override suspend fun languages(
            order: String,
            reverse: Boolean,
            hideBroken: Boolean,
            limit: Int,
        ): List<RadioBrowserFacetDto> = languageFacets.take(limit)

        override suspend fun tags(
            order: String,
            reverse: Boolean,
            hideBroken: Boolean,
            limit: Int,
        ): List<RadioBrowserFacetDto> = tagFacets.take(limit)

        override suspend fun topClicked(limit: Int, hideBroken: Boolean): List<RadioBrowserStationDto> = stations

        override suspend fun stationByUuid(
            stationUuid: String,
            hideBroken: Boolean,
        ): List<RadioBrowserStationDto> {
            lastUuid = stationUuid
            return stations
        }

        override suspend fun resolveAndCountClick(stationUuid: String): RadioBrowserClickDto {
            clickStarted.complete(Unit)
            if (awaitClickCancellation) awaitCancellation()
            if (throwOnClick) error("offline")
            return click
        }
    }
}
