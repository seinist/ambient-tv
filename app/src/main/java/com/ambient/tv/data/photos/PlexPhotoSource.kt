package com.ambient.tv.data.photos

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.remote.PlexUrlBuilder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

/**
 * Plex photo libraries. Keys: "px:s:<sectionKey>" for a library, "px:a:<ratingKey>" for an album.
 * Full-size images go through Plex's photo transcoder, which also converts HEIC/RAW and applies
 * the EXIF orientation server-side.
 */
@Singleton
class PlexPhotoSource @Inject constructor(
    @Named("plexPhotos") private val api: PhotoJsonApi,
    private val urls: PlexUrlBuilder,
    private val preferences: AppPreferences,
) : PhotoSource {
    override val system = PhotoSystem.PLEX
    override fun owns(key: String) = key.startsWith(PREFIX)
    override fun available() = preferences.isConfigured && preferences.plexEnabled

    override suspend fun libraries(): List<PhotoLibrary> =
        container("library/sections").array("Directory")
            .filter { it.str("type") == "photo" }
            .mapNotNull { dir -> dir.str("key")?.let { PhotoLibrary(sectionKey(it), dir.str("title").orEmpty(), system) } }

    override suspend fun list(albumKey: String): List<PhotoEntry> = entries(container(listPath(albumKey)))

    override suspend fun listRecursive(albumKey: String, limit: Int): List<PhotoEntry> {
        if (albumKey.startsWith(SECTION)) {
            // type=13 flattens a whole photo library in one request.
            return entries(container("library/sections/${albumKey.removePrefix(SECTION)}/all?type=13&X-Plex-Container-Start=0&X-Plex-Container-Size=$limit"))
                .filter { it.kind == PhotoKind.PHOTO }
        }
        val result = mutableListOf<PhotoEntry>()
        val pending = ArrayDeque(listOf(albumKey))
        var visited = 0
        while (pending.isNotEmpty() && result.size < limit && visited++ < MAX_ALBUMS) {
            list(pending.removeFirst()).forEach { entry ->
                when (entry.kind) {
                    PhotoKind.FOLDER -> pending += entry.id
                    PhotoKind.PHOTO -> if (result.size < limit) result += entry
                    PhotoKind.VIDEO -> Unit
                }
            }
        }
        return result
    }

    override suspend fun details(entry: PhotoEntry): PhotoExif? {
        val ratingKey = entry.id.removePrefix(ITEM)
        val item = container("library/metadata/$ratingKey").array("Metadata").firstOrNull() ?: return entry.exif
        return exifOf(item)?.merge(entry.exif) ?: entry.exif
    }

    override suspend fun recent(limit: Int): List<PhotoEntry> = libraries().flatMap { library ->
        runCatching {
            entries(container("library/sections/${library.key.removePrefix(SECTION)}/all?type=13&sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=$limit"))
        }.getOrDefault(emptyList())
    }.sortedByDescending { it.addedAt ?: 0 }.take(limit)

    private suspend fun container(path: String): JsonObject = api.get(path).obj("MediaContainer") ?: JsonObject(emptyMap())

    private fun listPath(albumKey: String): String = when {
        albumKey.startsWith(SECTION) -> "library/sections/${albumKey.removePrefix(SECTION)}/all"
        albumKey.startsWith(ALBUM) -> "library/metadata/${albumKey.removePrefix(ALBUM)}/children"
        else -> error("Not a Plex photo key")
    }

    private fun entries(container: JsonObject): List<PhotoEntry> =
        (container.array("Metadata") + container.array("Directory")).mapNotNull(::entry)

    private fun entry(item: JsonObject): PhotoEntry? {
        val ratingKey = item.str("ratingKey") ?: return null
        val media = item.array("Media").firstOrNull()
        val part = media?.array("Part")?.firstOrNull()
        val title = item.str("title").orEmpty()
        val takenAt = isoToEpochMs(item.str("originallyAvailableAt"))
        val addedAt = item.long("addedAt")?.times(1000)
        val thumb = item.str("thumb") ?: item.str("composite")
        return when {
            // Albums have no media part; their key points at the children listing.
            media == null || item.str("key")?.endsWith("/children") == true -> PhotoEntry(
                id = ALBUM + ratingKey, title = title, kind = PhotoKind.FOLDER, takenAt = takenAt, addedAt = addedAt,
                image = { w, h -> urls.artworkUrl(thumb, w, h) },
            )
            item.str("type") == "clip" -> PhotoEntry(
                id = ITEM + ratingKey, title = title, kind = PhotoKind.VIDEO, takenAt = takenAt, addedAt = addedAt,
                playKey = ratingKey, image = { w, h -> urls.artworkUrl(thumb, w, h) },
            )
            else -> {
                val source = part?.str("key")
                PhotoEntry(
                    id = ITEM + ratingKey, title = title, kind = PhotoKind.PHOTO, takenAt = takenAt, addedAt = addedAt,
                    width = media.int("width"), height = media.int("height"), exif = exifOf(item),
                    image = { w, h ->
                        // Thumbnails reuse the small server thumb; the viewer asks for display size.
                        if (w <= PhotoEntry.THUMB_WIDTH && thumb != null) urls.artworkUrl(thumb, w, h)
                        else urls.photoUrl(source ?: thumb, w, h)
                    },
                )
            }
        }
    }

    private fun exifOf(item: JsonObject): PhotoExif? {
        val media = item.array("Media").firstOrNull() ?: return null
        val make = media.str("make")
        val model = media.str("model")
        return PhotoExif(
            takenAt = isoToEpochMs(item.str("originallyAvailableAt")),
            camera = listOfNotNull(make, model?.removePrefix(make.orEmpty())?.trim()).joinToString(" ").ifBlank { null },
            lens = media.str("lens"),
            aperture = media.str("aperture")?.let { if (it.startsWith("f/")) it else "f/$it" },
            exposure = media.str("exposure")?.let { if (it.endsWith("s")) it else "$it s" },
            iso = media.str("iso")?.let { "ISO $it" },
            width = media.int("width"),
            height = media.int("height"),
            fileName = media.array("Part").firstOrNull()?.str("file")?.substringAfterLast('/'),
        )
    }

    companion object {
        const val PREFIX = "px:"
        private const val SECTION = "px:s:"
        private const val ALBUM = "px:a:"
        private const val ITEM = "px:i:"
        private const val MAX_ALBUMS = 200
        fun sectionKey(plexSectionKey: String) = SECTION + plexSectionKey
    }
}
