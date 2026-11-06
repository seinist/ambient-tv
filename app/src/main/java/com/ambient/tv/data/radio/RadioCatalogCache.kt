package com.ambient.tv.data.radio

import com.ambient.tv.data.browse.BrowseDiskStore
import com.ambient.tv.data.browse.BrowseScopes
import com.ambient.tv.data.browse.displayArtworkUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Cache lifetime controls network revalidation, not offline retention (BrowseDiskStore: 30 days). */
internal val RADIO_FACET_TTL_MS = TimeUnit.HOURS.toMillis(24)
internal val RADIO_POPULAR_TTL_MS = TimeUnit.HOURS.toMillis(1)
internal val RADIO_STATION_TTL_MS = TimeUnit.HOURS.toMillis(4)
internal val RADIO_SEARCH_TTL_MS = TimeUnit.MINUTES.toMillis(20)

internal fun radioCatalogFresh(fetchedAtMs: Long, ttlMs: Long, nowMs: Long): Boolean =
    nowMs >= fetchedAtMs && nowMs - fetchedAtMs < ttlMs

/** Hash parameters: queries should not become visible SQLite row keys. Include the remote cursor and sorting. */
internal fun radioSearchCacheKey(
    query: String?, countryCode: String?, language: String?, tag: String?,
    offset: Int, limit: Int, order: String, reverse: Boolean,
): String {
    val values = listOf(
        query.orEmpty().trim().lowercase(Locale.ROOT),
        countryCode.orEmpty().trim().uppercase(Locale.ROOT),
        language.orEmpty().trim().lowercase(Locale.ROOT),
        tag.orEmpty().trim().lowercase(Locale.ROOT),
        offset.toString(), limit.toString(), order, reverse.toString(),
    ).joinToString("\u0000")
    val hash = MessageDigest.getInstance("SHA-256").digest(values.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "stations:$hash"
}

/** Radio Browser URLs can carry stream tokens; only display metadata is persisted to the shared browse DB. */
internal fun RadioStation.radioCatalogDisplayOnly(): RadioStation = copy(
    streamUrl = "",
    homepage = null,
    artworkUrl = displayArtworkUrl(artworkUrl),
)

data class RadioCacheHit<T>(val value: T, val fresh: Boolean)

@Serializable
internal data class RadioCatalogRecord(
    val fetchedAtMs: Long,
    val kind: String,
    val stations: List<RadioStation> = emptyList(),
    val facets: List<RadioFacet> = emptyList(),
    val offset: Int = 0,
    val limit: Int = 0,
    val hasMore: Boolean = false,
)

/**
 * Shared, bounded Radio Browser metadata cache. One singleton serves Home and all Radio routes.
 * Disk I/O and JSON decoding stay off the UI thread; writes use BrowseDiskStore's idle worker.
 * A failed request may use the last saved answer, but only a fresh entry suppresses network I/O.
 */
@Singleton
class RadioCatalogCache @Inject constructor(
    private val disk: BrowseDiskStore,
    private val scopes: BrowseScopes,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = object : LinkedHashMap<String, RadioCatalogRecord>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RadioCatalogRecord>?): Boolean =
            size > 48
    }

    private fun namespace(): String = "radio-catalog-v1:${scopes.forProvider("radio")}"

    private suspend fun record(key: String): RadioCatalogRecord? {
        val scope = namespace()
        val memoryKey = "$scope/$key"
        synchronized(memory) { memory[memoryKey] }?.let { return it }
        val raw = disk.read(scope, key)[key] ?: return null
        val decoded = withContext(Dispatchers.Default) {
            runCatching { json.decodeFromString<RadioCatalogRecord>(raw) }.getOrNull()
        } ?: return null
        return synchronized(memory) {
            // An API response may have replaced this entry while the disk read was pending.
            val existing = memory[memoryKey]
            if (existing == null || existing.fetchedAtMs < decoded.fetchedAtMs) {
                memory[memoryKey] = decoded
                decoded
            } else existing
        }
    }

    suspend fun stations(key: String, ttlMs: Long): RadioCacheHit<RadioSearchPage>? {
        val entry = record(key)?.takeIf { it.kind == "stations" } ?: return null
        return RadioCacheHit(
            RadioSearchPage(entry.stations, entry.offset, entry.limit, entry.hasMore),
            radioCatalogFresh(entry.fetchedAtMs, ttlMs, System.currentTimeMillis()),
        )
    }

    suspend fun facets(key: String, ttlMs: Long): RadioCacheHit<List<RadioFacet>>? {
        val entry = record(key)?.takeIf { it.kind == "facets" } ?: return null
        return RadioCacheHit(entry.facets, radioCatalogFresh(entry.fetchedAtMs, ttlMs, System.currentTimeMillis()))
    }

    fun saveStations(key: String, page: RadioSearchPage) {
        save(key, RadioCatalogRecord(
            fetchedAtMs = System.currentTimeMillis(), kind = "stations",
            stations = page.items, offset = page.offset, limit = page.limit, hasMore = page.hasMore,
        ))
    }

    fun saveFacets(key: String, facets: List<RadioFacet>) {
        save(key, RadioCatalogRecord(
            fetchedAtMs = System.currentTimeMillis(), kind = "facets", facets = facets,
        ))
    }

    private fun save(key: String, record: RadioCatalogRecord) {
        val scope = namespace()
        synchronized(memory) { memory["$scope/$key"] = record }
        // Never serialize stream URLs (or arbitrary remote homepages) to the shared display cache.
        disk.put(scope, key, { namespace() == scope }) {
            json.encodeToString(record.copy(stations = record.stations.map { it.radioCatalogDisplayOnly() }))
        }
    }
}
