package com.ambient.tv.data.photos

import android.media.ExifInterface
import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.data.local.AppPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Images in device folders, USB drives and SMB/NFS shares. Keys are Files keys of folders. */
@Singleton
class FilesPhotoSource @Inject constructor(
    private val access: FileAccess,
    private val store: FileStore,
    private val preferences: AppPreferences,
) : PhotoSource {
    override val system = PhotoSystem.FILES
    override fun owns(key: String) = FileKeys.owns(key)
    override fun available() = preferences.filesEnabled && store.sources().isNotEmpty()

    override suspend fun libraries(): List<PhotoLibrary> = store.sources().map { source ->
        PhotoLibrary(FileKeys.key(source.id, access.root(source)), source.name, system)
    }

    override suspend fun list(albumKey: String): List<PhotoEntry> {
        val (sourceId, path) = FileKeys.parse(albumKey)
        val source = store.sources().firstOrNull { it.id == sourceId } ?: throw java.io.FileNotFoundException("Source removed")
        return access.list(source, path, MAX_ITEMS) { it.image || it.playable && !it.audio }.entries.map(::entry)
    }

    override suspend fun listRecursive(albumKey: String, limit: Int): List<PhotoEntry> {
        val result = mutableListOf<PhotoEntry>()
        val pending = ArrayDeque(listOf(albumKey to 0))
        var visited = 0
        while (pending.isNotEmpty() && result.size < limit && visited++ < MAX_FOLDERS) {
            val (key, depth) = pending.removeFirst()
            com.ambient.tv.util.suspendResult { list(key) }.getOrDefault(emptyList()).forEach { entry ->
                when (entry.kind) {
                    PhotoKind.FOLDER -> if (depth < MAX_DEPTH) pending += entry.id to depth + 1
                    PhotoKind.PHOTO -> if (result.size < limit) result += entry
                    PhotoKind.VIDEO -> Unit
                }
            }
        }
        return result
    }

    /** Files have no index; "recent" is left to the server libraries. */
    override suspend fun recent(limit: Int): List<PhotoEntry> = emptyList()

    override suspend fun details(entry: PhotoEntry): PhotoExif? {
        val (sourceId, path) = FileKeys.parse(entry.id)
        val bytes = com.ambient.tv.util.suspendResult { read(FileImageRef(sourceId, path), EXIF_READ_LIMIT) }.getOrNull()
        val exif = bytes?.let { runCatching { ExifInterface(ByteArrayInputStream(it)) }.getOrNull() }
        val base = PhotoExif(takenAt = entry.takenAt, fileName = entry.title)
        if (exif == null) return base
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
        val latLong = FloatArray(2).takeIf { exif.getLatLong(it) }
        return PhotoExif(
            takenAt = exifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)) ?: entry.takenAt,
            camera = listOfNotNull(make, model?.removePrefix(make.orEmpty())?.trim()).joinToString(" ").ifBlank { null },
            lens = exif.getAttribute("LensModel"),
            aperture = formatAperture(exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)),
            exposure = formatExposure(exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)),
            iso = (exif.getAttribute("PhotographicSensitivity") ?: exif.getAttribute("ISOSpeedRatings"))?.let { "ISO $it" },
            focalLength = formatFocal(exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)),
            // JPEGs carry their size in the EXIF sub-IFD (PixelX/YDimension), not in ImageWidth.
            width = (exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)).takeIf { it > 0 },
            height = (exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)).takeIf { it > 0 },
            location = latLong?.let { formatLocation(it[0].toDouble(), it[1].toDouble()) },
            fileName = entry.title,
        )
    }

    /** Whole file (bounded) for Coil decoding and EXIF parsing. */
    suspend fun read(ref: FileImageRef, maxBytes: Int = IMAGE_READ_LIMIT): ByteArray = withContext(Dispatchers.IO) {
        val source = store.sources().firstOrNull { it.id == ref.sourceId } ?: throw java.io.FileNotFoundException("Source removed")
        // Same preparation as browsing: WireGuard route and wake-on-LAN for a sleeping NAS.
        access.prepare(source)
        access.open(source, ref.path).use { media ->
            if (media.size > IMAGE_READ_LIMIT) throw java.io.IOException("Image too large")
            val total = minOf(media.size, maxBytes.toLong()).toInt()
            val out = ByteArrayOutputStream(total)
            val chunk = ByteArray(64 * 1024)
            var position = 0L
            while (position < total) {
                val read = media.read(position, chunk, minOf(chunk.size.toLong(), total - position).toInt())
                if (read <= 0) break
                out.write(chunk, 0, read); position += read
            }
            out.toByteArray()
        }
    }

    fun entry(file: FileEntry): PhotoEntry = when {
        file.directory -> PhotoEntry(file.id, file.name, PhotoKind.FOLDER, image = { _, _ -> null })
        file.image -> PhotoEntry(
            id = file.id, title = file.name, kind = PhotoKind.PHOTO,
            takenAt = file.modified.takeIf { it > 0 }, addedAt = file.modified.takeIf { it > 0 },
            image = { _, _ -> FileImageRef(file.sourceId, file.path, file.modified * 31 + file.size) },
        )
        else -> PhotoEntry(file.id, file.name, PhotoKind.VIDEO, takenAt = file.modified.takeIf { it > 0 }, playKey = file.id, image = { _, _ -> null })
    }

    private fun exifDate(value: String?): Long? = value?.let {
        runCatching {
            java.time.LocalDateTime.parse(it.trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private companion object {
        const val MAX_ITEMS = 2000
        const val MAX_FOLDERS = 300
        const val MAX_DEPTH = 6
        const val IMAGE_READ_LIMIT = 40 * 1024 * 1024
        const val EXIF_READ_LIMIT = 4 * 1024 * 1024
    }
}
