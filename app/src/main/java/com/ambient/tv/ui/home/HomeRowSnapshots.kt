package com.ambient.tv.ui.home

import com.ambient.tv.data.browse.BrowseDiskStore
import com.ambient.tv.data.browse.BrowseScopes
import com.ambient.tv.data.browse.displayOnly
import com.ambient.tv.data.browse.encodeBrowseJson
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.ContentRowData
import com.ambient.tv.ui.common.MediaTileStyle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A small offline display copy. No stream URLs, bearer tokens, or signed image URLs. */
@Serializable
internal data class HomeRowDisplaySnapshot(
    val id: String,
    val title: String,
    val style: String,
    val showDescription: Boolean,
    val items: List<MediaItem>,
) {
    fun asRow(): ContentRowData? {
        if (items.isEmpty()) return null
        return ContentRowData(title, items, runCatching { MediaTileStyle.valueOf(style) }.getOrDefault(MediaTileStyle.LANDSCAPE),
            id, showDescription)
    }
}

/**
 * Radio Browser and TMDB Home feeds do not use a persistent media-server catalogue.
 * Keep their last successfully fetched display rows so a cold start never needs an API
 * response before showing the posters. Provider + language scopes prevent stale accounts
 * or languages sharing cached data. Writes use BrowseDiskStore's idle/background worker.
 */
@Singleton
class HomeRowSnapshots @Inject constructor(
    private val disk: BrowseDiskStore,
    private val scopes: BrowseScopes,
    private val preferences: AppPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun namespace(system: String): String = buildString {
        append("home-rows-v1:")
        append(scopes.forProvider(system))
        if (system == "trailers") {
            append(':')
            append(preferences.trailerLanguage)
        }
    }

    suspend fun read(system: String, allowedIds: Set<String>): List<ContentRowData> {
        if (allowedIds.isEmpty()) return emptyList()
        return disk.read(namespace(system)).filterKeys { it in allowedIds }.values.mapNotNull { raw ->
            runCatching { json.decodeFromString<HomeRowDisplaySnapshot>(raw).asRow() }.getOrNull()
        }
    }

    /** A successful empty response stores a tombstone; a failed fetch leaves the old row. */
    fun save(system: String, id: String, row: ContentRowData?) {
        val scope = namespace(system)
        val snapshot = HomeRowDisplaySnapshot(
            id = id, title = row?.title.orEmpty(), style = (row?.style ?: MediaTileStyle.LANDSCAPE).name,
            showDescription = row?.showDescription ?: false, items = row?.items.orEmpty().take(24),
        )
        disk.put(scope, id, { namespace(system) == scope }) {
            encodeBrowseJson(json, snapshot.copy(items = snapshot.items.map { it.displayOnly() }))
        }
    }
}
