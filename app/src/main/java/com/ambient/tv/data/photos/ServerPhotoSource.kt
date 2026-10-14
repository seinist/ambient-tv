package com.ambient.tv.data.photos

import com.ambient.tv.data.emby.EmbyBackend
import com.ambient.tv.data.emby.EmbyUrlBuilder
import com.ambient.tv.data.jellyfin.JellyfinBackend
import com.ambient.tv.data.jellyfin.JellyfinUrlBuilder
import com.ambient.tv.data.local.AppPreferences
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

/**
 * Jellyfin and Emby share the item model; only the item endpoints, key prefix and image token
 * differ. Keys are the provider ids ("j:<id>" / "e:<id>") of views, folders and photos.
 */
abstract class ServerPhotoSource(
    private val api: PhotoJsonApi,
    private val prefix: String,
) : PhotoSource {
    protected abstract fun userId(): String?
    protected abstract fun viewsPath(userId: String): String
    protected abstract fun itemsPath(userId: String): String
    protected abstract fun imageUrl(rawId: String, tag: String?, maxWidth: Int): String?

    override fun owns(key: String) = key.startsWith(prefix)
    private fun raw(key: String) = key.removePrefix(prefix)

    override suspend fun libraries(): List<PhotoLibrary> {
        val user = userId() ?: return emptyList()
        return api.get(viewsPath(user)).array("Items")
            .filter { it.str("CollectionType").equals("photos", ignoreCase = true) }
            .mapNotNull { view -> view.str("Id")?.let { PhotoLibrary(prefix + it, view.str("Name").orEmpty(), system) } }
    }

    override suspend fun list(albumKey: String): List<PhotoEntry> {
        // Paged: a single request would silently cut large folders at the page limit.
        val result = mutableListOf<PhotoEntry>()
        var start = 0
        while (start < MAX_ITEMS) {
            val page = rawItems(
                "parentId" to raw(albumKey),
                "sortBy" to "IsFolder,SortName",
                "startIndex" to start.toString(),
                "limit" to PAGE_SIZE.toString(),
            )
            result += page.mapNotNull(::entry)
            if (page.size < PAGE_SIZE) break
            start += PAGE_SIZE
        }
        return result
    }

    override suspend fun listRecursive(albumKey: String, limit: Int): List<PhotoEntry> = items(
        "parentId" to raw(albumKey),
        "recursive" to "true",
        "includeItemTypes" to "Photo",
        "sortBy" to "PremiereDate,DateCreated",
        "sortOrder" to "Descending",
        "limit" to limit.toString(),
    )

    override suspend fun recent(limit: Int): List<PhotoEntry> = libraries().flatMap { library ->
        runCatching {
            items(
                "parentId" to raw(library.key),
                "recursive" to "true",
                "includeItemTypes" to "Photo",
                "sortBy" to "DateCreated",
                "sortOrder" to "Descending",
                "limit" to limit.toString(),
            )
        }.getOrDefault(emptyList())
    }.sortedByDescending { it.addedAt ?: 0 }.take(limit)

    private suspend fun items(vararg query: Pair<String, String>): List<PhotoEntry> = rawItems(*query).mapNotNull(::entry)

    private suspend fun rawItems(vararg query: Pair<String, String>): List<JsonObject> {
        val user = userId() ?: return emptyList()
        val params = (query.toList() + ("fields" to FIELDS) + ("enableImageTypes" to "Primary"))
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val path = itemsPath(user)
        return api.get(path + (if ('?' in path) "&" else "?") + params).array("Items")
    }

    private fun entry(item: JsonObject): PhotoEntry? {
        val id = item.str("Id") ?: return null
        val type = item.str("Type").orEmpty()
        val tag = item.obj("ImageTags")?.str("Primary")
        val title = item.str("Name").orEmpty()
        val takenAt = isoToEpochMs(item.str("PremiereDate"))
        val addedAt = isoToEpochMs(item.str("DateCreated"))
        val image: (Int, Int) -> Any? = { w, h ->
            if (tag == null && type != "Photo") null
            // Fit portrait photos by height as well; the servers only get maxWidth from the builder.
            else imageUrl(id, tag, w)?.let { url -> if (h > PhotoEntry.THUMB_HEIGHT) "$url&maxHeight=$h" else url }
        }
        return when {
            item.str("IsFolder") == "true" -> PhotoEntry(prefix + id, title, PhotoKind.FOLDER, takenAt, addedAt, image = image)
            type.equals("Photo", true) -> PhotoEntry(
                id = prefix + id, title = title, kind = PhotoKind.PHOTO, takenAt = takenAt, addedAt = addedAt,
                width = item.int("Width"), height = item.int("Height"), exif = exif(item), image = image,
            )
            type in VIDEO_TYPES -> PhotoEntry(prefix + id, title, PhotoKind.VIDEO, takenAt, addedAt, playKey = prefix + id, image = image)
            else -> null
        }
    }

    private fun exif(item: JsonObject): PhotoExif {
        val make = item.str("CameraMake")
        val model = item.str("CameraModel")
        return PhotoExif(
            takenAt = isoToEpochMs(item.str("PremiereDate")),
            camera = listOfNotNull(make, model?.removePrefix(make.orEmpty())?.trim()).joinToString(" ").ifBlank { null },
            aperture = formatAperture(item.double("Aperture")),
            exposure = formatExposure(item.double("ExposureTime")),
            iso = item.int("IsoSpeedRating")?.let { "ISO $it" },
            focalLength = formatFocal(item.double("FocalLength")),
            width = item.int("Width"),
            height = item.int("Height"),
            location = formatLocation(item.double("Latitude"), item.double("Longitude")),
            fileName = item.str("Path")?.substringAfterLast('/')?.substringAfterLast('\\'),
        )
    }

    private companion object {
        const val FIELDS = "DateCreated,PremiereDate,Width,Height,Path,ProductionYear"
        const val MAX_ITEMS = 10_000
        const val PAGE_SIZE = 500
        val VIDEO_TYPES = setOf("Video", "Movie", "Episode", "MusicVideo")
    }
}

@Singleton
class JellyfinPhotoSource @Inject constructor(
    @Named("jellyfinPhotos") api: PhotoJsonApi,
    private val urls: JellyfinUrlBuilder,
    private val preferences: AppPreferences,
) : ServerPhotoSource(api, JellyfinBackend.PREFIX) {
    override val system = PhotoSystem.JELLYFIN
    override fun available() = preferences.jellyfinEnabled && preferences.isJellyfinConfigured
    override fun userId() = preferences.jellyfinUserId
    override fun viewsPath(userId: String) = "UserViews?userId=$userId"
    override fun itemsPath(userId: String) = "Items?userId=$userId"
    override fun imageUrl(rawId: String, tag: String?, maxWidth: Int) = urls.imageUrl(rawId, "Primary", tag = tag, maxWidth = maxWidth)
}

@Singleton
class EmbyPhotoSource @Inject constructor(
    @Named("embyPhotos") api: PhotoJsonApi,
    private val urls: EmbyUrlBuilder,
    private val preferences: AppPreferences,
) : ServerPhotoSource(api, EmbyBackend.PREFIX) {
    override val system = PhotoSystem.EMBY
    override fun available() = preferences.embyEnabled && preferences.isEmbyConfigured
    override fun userId() = preferences.embyUserId
    override fun viewsPath(userId: String) = "Users/$userId/Views"
    override fun itemsPath(userId: String) = "Users/$userId/Items"
    override fun imageUrl(rawId: String, tag: String?, maxWidth: Int) = urls.imageUrl(rawId, "Primary", tag = tag, maxWidth = maxWidth)
}
