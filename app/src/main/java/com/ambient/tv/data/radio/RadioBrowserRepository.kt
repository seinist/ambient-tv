package com.ambient.tv.data.radio

import javax.inject.Inject
import javax.inject.Singleton

data class RadioSearchPage(
    val items: List<RadioStation>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
)

@Singleton
class RadioBrowserRepository @Inject constructor(
    private val api: RadioBrowserApiService,
) {
    suspend fun search(
        query: String? = null,
        countryCode: String? = null,
        language: String? = null,
        tag: String? = null,
        offset: Int = 0,
        limit: Int = 50,
    ): RadioSearchPage {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, 100)
        val dtos = api.searchStations(
            name = query?.trim()?.takeIf(String::isNotBlank),
            countryCode = countryCode?.trim()?.uppercase()?.takeIf(String::isNotBlank),
            language = language?.trim()?.takeIf(String::isNotBlank),
            tag = tag?.trim()?.takeIf(String::isNotBlank),
            offset = safeOffset,
            limit = safeLimit,
        )
        return RadioSearchPage(
            items = dtos.mapNotNull(RadioBrowserStationDto::toRadioStation),
            offset = safeOffset,
            limit = safeLimit,
            hasMore = dtos.size >= safeLimit,
        )
    }

    suspend fun countries(limit: Int = 100): List<RadioFacet> =
        api.countryCodes(limit = limit.coerceIn(1, 200))
            .toFacets { it.trim().uppercase() }

    suspend fun languages(limit: Int = 100): List<RadioFacet> =
        api.languages(limit = limit.coerceIn(1, 200))
            .mapNotNull { dto ->
                val id = dto.name.trim()
                val count = dto.count()
                if (id.isBlank() || count <= 0) null
                else RadioFacet(
                    id = id,
                    name = id,
                    stationCount = count,
                    languageCode = radioLanguageCode(dto.iso639, id),
                )
            }
            .distinctBy { it.id.lowercase() }

    suspend fun tags(limit: Int = 150): List<RadioFacet> =
        api.tags(limit = limit.coerceIn(1, 250))
            .toFacets { it.trim() }

    private fun List<RadioBrowserFacetDto>.toFacets(
        normalize: (String) -> String,
    ): List<RadioFacet> = mapNotNull { dto ->
        val id = normalize(dto.name)
        val count = dto.count()
        if (id.isBlank() || count <= 0) null else RadioFacet(id = id, name = id, stationCount = count)
    }.distinctBy { it.id.lowercase() }

    suspend fun popular(limit: Int = 50): List<RadioStation> =
        api.topClicked(limit.coerceIn(1, 100))
            .mapNotNull(RadioBrowserStationDto::toRadioStation)

    suspend fun station(stationUuid: String): RadioStation? =
        api.stationByUuid(stationUuid.trim())
            .firstNotNullOfOrNull(RadioBrowserStationDto::toRadioStation)

    suspend fun playbackUrl(station: RadioStation): String {
        val resolved = runCatching {
            api.resolveAndCountClick(station.id).url.trim()
        }.getOrDefault("")
        return resolved.ifBlank { station.streamUrl }
    }
}
