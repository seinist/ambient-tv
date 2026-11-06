package com.ambient.tv.data.radio

import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RadioSearchPage(
    val items: List<RadioStation>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
)

@Singleton
class RadioBrowserRepository @Inject constructor(
    private val api: RadioBrowserApiService,
    // Optional default keeps pure JVM repository tests independent of Android SQLite.
    private val cache: RadioCatalogCache? = null,
) {
    private data class StationQuery(
        val name: String?, val countryCode: String?, val language: String?, val tag: String?,
        val offset: Int, val limit: Int, val order: String, val reverse: Boolean,
    ) {
        val key: String get() = radioSearchCacheKey(
            name, countryCode, language, tag, offset, limit, order, reverse,
        )
        val ttlMs: Long get() = if (!name.isNullOrBlank()) RADIO_SEARCH_TTL_MS else RADIO_STATION_TTL_MS
    }

    private fun stationQuery(
        query: String?, countryCode: String?, language: String?, tag: String?,
        offset: Int, limit: Int, order: String, reverse: Boolean,
    ) = StationQuery(
        query?.trim()?.takeIf(String::isNotBlank),
        countryCode?.trim()?.uppercase()?.takeIf(String::isNotBlank),
        language?.trim()?.takeIf(String::isNotBlank),
        tag?.trim()?.takeIf(String::isNotBlank),
        offset.coerceAtLeast(0), limit.coerceIn(1, 500), order, reverse,
    )

    suspend fun cachedSearch(
        query: String? = null,
        countryCode: String? = null,
        language: String? = null,
        tag: String? = null,
        offset: Int = 0,
        limit: Int = 50,
        order: String = "clickcount",
        reverse: Boolean = true,
    ): RadioCacheHit<RadioSearchPage>? {
        val p = stationQuery(query, countryCode, language, tag, offset, limit, order, reverse)
        return cache?.stations(p.key, p.ttlMs)
    }

    suspend fun search(
        query: String? = null,
        countryCode: String? = null,
        language: String? = null,
        tag: String? = null,
        offset: Int = 0,
        limit: Int = 50,
        order: String = "clickcount",
        reverse: Boolean = true,
        forceRefresh: Boolean = false,
    ): RadioSearchPage {
        val p = stationQuery(query, countryCode, language, tag, offset, limit, order, reverse)
        val saved = cache?.stations(p.key, p.ttlMs)
        if (saved?.fresh == true && !forceRefresh) return saved.value
        return try {
            val dtos = api.searchStations(
                name = p.name, countryCode = p.countryCode, language = p.language, tag = p.tag,
                order = p.order, reverse = p.reverse, offset = p.offset, limit = p.limit,
            )
            val page = RadioSearchPage(
                items = dtos.mapNotNull(RadioBrowserStationDto::toRadioStation),
                offset = p.offset, limit = p.limit, hasMore = dtos.size >= p.limit,
            )
            cache?.saveStations(p.key, page)
            page
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            saved?.value ?: throw error
        }
    }

    suspend fun cachedCountries(limit: Int = 100): RadioCacheHit<List<RadioFacet>>? =
        cache?.facets("countries:${limit.coerceIn(1, 200)}", RADIO_FACET_TTL_MS)

    suspend fun cachedLanguages(limit: Int = 100): RadioCacheHit<List<RadioFacet>>? =
        cache?.facets("languages:${limit.coerceIn(1, 200)}", RADIO_FACET_TTL_MS)

    suspend fun cachedTags(limit: Int = 150): RadioCacheHit<List<RadioFacet>>? =
        cache?.facets("tags:${limit.coerceIn(1, 250)}", RADIO_FACET_TTL_MS)

    suspend fun countries(limit: Int = 100, forceRefresh: Boolean = false): List<RadioFacet> {
        val count = limit.coerceIn(1, 200)
        return fetchFacets("countries:$count", forceRefresh) {
            api.countryCodes(limit = count).toFacets { it.trim().uppercase() }
        }
    }

    suspend fun languages(limit: Int = 100, forceRefresh: Boolean = false): List<RadioFacet> {
        val count = limit.coerceIn(1, 200)
        return fetchFacets("languages:$count", forceRefresh) {
            api.languages(limit = count)
                .mapNotNull { dto ->
                    val id = dto.name.trim()
                    val stationCount = dto.count()
                    if (id.isBlank() || stationCount <= 0) null
                    else RadioFacet(
                        id = id, name = id, stationCount = stationCount,
                        languageCode = radioLanguageCode(dto.iso639, id),
                    )
                }
                .distinctBy { it.id.lowercase() }
        }
    }

    suspend fun tags(limit: Int = 150, forceRefresh: Boolean = false): List<RadioFacet> {
        val count = limit.coerceIn(1, 250)
        return fetchFacets("tags:$count", forceRefresh) {
            api.tags(limit = count).toFacets { it.trim() }
        }
    }

    private suspend fun fetchFacets(
        key: String, forceRefresh: Boolean, fetch: suspend () -> List<RadioFacet>,
    ): List<RadioFacet> {
        val saved = cache?.facets(key, RADIO_FACET_TTL_MS)
        if (saved?.fresh == true && !forceRefresh) return saved.value
        return try {
            fetch().also { cache?.saveFacets(key, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            saved?.value ?: throw error
        }
    }

    private fun List<RadioBrowserFacetDto>.toFacets(
        normalize: (String) -> String,
    ): List<RadioFacet> = mapNotNull { dto ->
        val id = normalize(dto.name)
        val count = dto.count()
        if (id.isBlank() || count <= 0) null else RadioFacet(id = id, name = id, stationCount = count)
    }.distinctBy { it.id.lowercase() }

    /** Popular is a bounded top-100 dataset, shared between Home's first 24 and Radio's 100. */
    suspend fun cachedPopular(limit: Int = 50): RadioCacheHit<List<RadioStation>>? =
        cache?.stations("popular-top100", RADIO_POPULAR_TTL_MS)?.let {
            RadioCacheHit(it.value.items.take(limit.coerceIn(1, 100)), it.fresh)
        }

    suspend fun popular(limit: Int = 50, forceRefresh: Boolean = false): List<RadioStation> {
        val requested = limit.coerceIn(1, 100)
        val saved = cache?.stations("popular-top100", RADIO_POPULAR_TTL_MS)
        if (saved?.fresh == true && !forceRefresh) return saved.value.items.take(requested)
        return try {
            val fetched = api.topClicked(if (cache != null) 100 else requested)
                .mapNotNull(RadioBrowserStationDto::toRadioStation)
            cache?.saveStations("popular-top100", RadioSearchPage(fetched, 0, 100, false))
            fetched.take(requested)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            saved?.value?.items?.take(requested) ?: throw error
        }
    }

    /** Station lookup and the click endpoint intentionally bypass the catalogue cache. */
    suspend fun station(stationUuid: String): RadioStation? =
        api.stationByUuid(stationUuid.trim())
            .firstNotNullOfOrNull(RadioBrowserStationDto::toRadioStation)

    suspend fun playbackUrl(station: RadioStation): String {
        // Network failures may fall back to the catalog URL, but coroutine cancellation is control
        // flow: a superseded station selection must never continue by starting its fallback stream.
        val resolved = suspendResult {
            api.resolveAndCountClick(station.id).url.trim()
        }.getOrDefault("")
        return resolved.ifBlank { station.streamUrl }
    }
}
