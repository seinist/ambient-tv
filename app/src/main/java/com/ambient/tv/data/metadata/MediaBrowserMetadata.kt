package com.ambient.tv.data.metadata

import com.ambient.tv.domain.model.ArtworkChoice
import com.ambient.tv.domain.model.ArtworkKind
import com.ambient.tv.domain.model.MetadataField
import com.ambient.tv.domain.model.MetadataFields
import com.ambient.tv.domain.model.MetadataMatch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/* Jellyfin and Emby share these admin endpoints (common MediaBrowser origin). */

@Serializable
data class MediaBrowserUserDto(@SerialName("Policy") val policy: MediaBrowserUserPolicy? = null)

@Serializable
data class MediaBrowserUserPolicy(@SerialName("IsAdministrator") val isAdministrator: Boolean = false)

@Serializable
data class MediaBrowserRemoteSearchQuery(
    @SerialName("SearchInfo") val searchInfo: MediaBrowserSearchInfo,
    @SerialName("ItemId") val itemId: String,
    @SerialName("IncludeDisabledProviders") val includeDisabledProviders: Boolean = false,
)

@Serializable
data class MediaBrowserSearchInfo(
    @SerialName("Name") val name: String? = null,
    @SerialName("Year") val year: Int? = null,
)

@Serializable
data class MediaBrowserRemoteImageList(@SerialName("Images") val images: List<MediaBrowserRemoteImage> = emptyList())

@Serializable
data class MediaBrowserRemoteImage(
    @SerialName("Url") val url: String? = null,
    @SerialName("ThumbnailUrl") val thumbnailUrl: String? = null,
    @SerialName("ProviderName") val providerName: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
)

@Serializable
data class MediaBrowserImageInfo(
    @SerialName("ImageType") val imageType: String? = null,
    @SerialName("ImageIndex") val imageIndex: Int? = null,
)

/** RemoteSearch path segment for an item type, null where the server cannot re-identify it. */
internal fun mediaBrowserSearchType(type: String): String? = when (type) {
    "movie" -> "Movie"
    "show" -> "Series"
    "collection" -> "BoxSet"
    "artist" -> "MusicArtist"
    "album" -> "MusicAlbum"
    else -> null
}

internal fun mediaBrowserImageType(kind: ArtworkKind): String = when (kind) {
    ArtworkKind.POSTER -> "Primary"
    ArtworkKind.BACKDROP -> "Backdrop"
}

/** The server's search result is passed back unchanged on apply, so it is kept as JSON. */
internal fun JsonObject.toMetadataMatch(): MetadataMatch? {
    val name = string("Name") ?: return null
    return MetadataMatch(
        title = name,
        year = (this["ProductionYear"] as? JsonPrimitive)?.intOrNull
            ?: string("PremiereDate")?.take(4)?.toIntOrNull(),
        thumbUrl = string("ImageUrl"),
        source = string("SearchProviderName"),
        score = null,
        overview = string("Overview"),
        handle = toString(),
    )
}

internal fun metadataMatchBody(json: Json, match: MetadataMatch): JsonObject =
    json.parseToJsonElement(match.handle) as JsonObject

internal fun MediaBrowserRemoteImage.toArtworkChoice(): ArtworkChoice? {
    val full = url?.takeIf(String::isNotBlank) ?: return null
    return ArtworkChoice(
        previewUrl = thumbnailUrl?.takeIf(String::isNotBlank) ?: full,
        handle = full,
        provider = providerName,
        language = language,
        width = width,
        height = height,
        rating = communityRating,
    )
}

private val FIELD_KEYS = mapOf(
    MetadataField.TITLE to "Name",
    MetadataField.SORT_TITLE to "ForcedSortName",
    MetadataField.ORIGINAL_TITLE to "OriginalTitle",
    MetadataField.YEAR to "ProductionYear",
    MetadataField.SUMMARY to "Overview",
)
/** The server's LockedFields names; it has none for sort title, original title and year. */
private val LOCK_NAMES = mapOf(MetadataField.TITLE to "Name", MetadataField.SUMMARY to "Overview")

internal fun JsonObject.toMetadataFields(): MetadataFields {
    val lockedNames = (this["LockedFields"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
    return MetadataFields(
        values = FIELD_KEYS.mapValues { (field, key) ->
            when (field) {
                MetadataField.YEAR -> (this[key] as? JsonPrimitive)?.intOrNull?.toString().orEmpty()
                MetadataField.SORT_TITLE -> string(key) ?: string("SortName").orEmpty()
                else -> string(key).orEmpty()
            }
        },
        locked = LOCK_NAMES.filterValues { it in lockedNames }.keys,
        lockable = LOCK_NAMES.keys,
    )
}

/** [this] item with the fields and locks that changed from [current] to [edited]. */
internal fun JsonObject.withMetadataFields(current: MetadataFields, edited: MetadataFields): JsonObject {
    val updated = toMutableMap()
    FIELD_KEYS.forEach { (field, key) ->
        val value = edited[field].trim()
        if (value == current[field].trim()) return@forEach
        updated[key] = when {
            value.isEmpty() -> JsonNull
            field == MetadataField.YEAR -> value.toIntOrNull()?.let(::JsonPrimitive) ?: return@forEach
            else -> JsonPrimitive(value)
        }
    }
    if (edited.locked != current.locked) {
        val others = (this["LockedFields"] as? JsonArray).orEmpty().filter { (it as? JsonPrimitive)?.content !in LOCK_NAMES.values }
        updated["LockedFields"] = JsonArray(others + edited.locked.mapNotNull(LOCK_NAMES::get).map(::JsonPrimitive))
    }
    return JsonObject(updated)
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
