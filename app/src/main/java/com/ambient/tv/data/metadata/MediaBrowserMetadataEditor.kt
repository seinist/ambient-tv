package com.ambient.tv.data.metadata

import com.ambient.tv.domain.model.ArtworkChoice
import com.ambient.tv.domain.model.ArtworkKind
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MetadataEditSupport
import com.ambient.tv.domain.model.MetadataFields
import com.ambient.tv.domain.model.MetadataMatch
import com.ambient.tv.util.suspendResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The admin endpoints Jellyfin and Emby share; each provider's Retrofit service backs it. */
interface MediaBrowserEditApi {
    suspend fun remoteSearch(type: String, body: MediaBrowserRemoteSearchQuery): List<JsonObject>
    suspend fun applyRemoteSearch(id: String, body: JsonObject)
    suspend fun remoteImages(id: String, type: String, includeAllLanguages: Boolean): MediaBrowserRemoteImageList
    suspend fun downloadRemoteImage(id: String, type: String, imageUrl: String)
    suspend fun imageInfos(id: String): List<MediaBrowserImageInfo>
    suspend fun moveImage(id: String, type: String, index: Int, newIndex: Int)
    suspend fun itemJson(id: String): JsonObject
    suspend fun updateItem(id: String, body: JsonObject)
}

/** Fix match and artwork selection for Jellyfin and Emby items ([raw] strips the provider prefix). */
class MediaBrowserMetadataEditor(
    private val api: MediaBrowserEditApi,
    private val json: Json,
    private val rights: MetadataEditRights,
    private val raw: (String) -> String,
) {
    fun support(item: MediaItem): MetadataEditSupport =
        if (rights.granted()) MetadataEditSupport.forType(item.type, matchCollections = true) else MetadataEditSupport.NONE

    suspend fun refreshRights(): Boolean = rights.refresh()

    suspend fun searchMatches(item: MediaItem, title: String?, year: Int?): Result<List<MetadataMatch>> = write {
        val type = requireNotNull(mediaBrowserSearchType(item.type)) { "${item.type} cannot be re-identified" }
        val query = MediaBrowserRemoteSearchQuery(
            searchInfo = MediaBrowserSearchInfo(name = title?.takeIf(String::isNotBlank) ?: item.title, year = year),
            itemId = raw(item.ratingKey),
        )
        api.remoteSearch(type, query).mapNotNull { it.toMetadataMatch() }
    }

    suspend fun applyMatch(ratingKey: String, match: MetadataMatch): Result<Unit> = write {
        api.applyRemoteSearch(raw(ratingKey), metadataMatchBody(json, match))
    }

    suspend fun artworkChoices(ratingKey: String, kind: ArtworkKind): Result<List<ArtworkChoice>> = write {
        val type = mediaBrowserImageType(kind)
        // The library's metadata language first; all languages only when that offers nothing.
        api.remoteImages(raw(ratingKey), type, includeAllLanguages = false).images.mapNotNull { it.toArtworkChoice() }
            .ifEmpty { api.remoteImages(raw(ratingKey), type, includeAllLanguages = true).images.mapNotNull { it.toArtworkChoice() } }
            .distinctBy { it.handle }
    }

    suspend fun setArtwork(ratingKey: String, kind: ArtworkKind, choice: ArtworkChoice): Result<Unit> = write {
        val id = raw(ratingKey)
        val type = mediaBrowserImageType(kind)
        api.downloadRemoteImage(id, type, choice.handle)
        if (kind == ArtworkKind.BACKDROP) {
            // A downloaded backdrop is appended; the first one is what every client shows.
            val newest = api.imageInfos(id)
                .filter { it.imageType.equals(type, ignoreCase = true) }
                .mapNotNull { it.imageIndex }
                .maxOrNull()
            if (newest != null && newest > 0) api.moveImage(id, type, newest, 0)
        }
    }

    suspend fun fields(ratingKey: String): Result<MetadataFields> = write { api.itemJson(raw(ratingKey)).toMetadataFields() }

    suspend fun updateFields(ratingKey: String, current: MetadataFields, edited: MetadataFields): Result<Unit> = write {
        val id = raw(ratingKey)
        // Re-read right before writing: the server replaces the whole item with what it receives.
        api.updateItem(id, api.itemJson(id).withMetadataFields(current, edited))
    }

    private suspend inline fun <T> write(block: () -> T): Result<T> =
        suspendResult(block).onFailure(rights::onWriteFailure)
}
