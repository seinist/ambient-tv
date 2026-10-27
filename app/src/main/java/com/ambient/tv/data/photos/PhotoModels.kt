package com.ambient.tv.data.photos

import com.ambient.tv.data.files.FilePaths

enum class PhotoKind { FOLDER, PHOTO, VIDEO }

/** Which provider a photo key belongs to; drives badges and the screensaver album picker. */
enum class PhotoSystem { PLEX, JELLYFIN, EMBY, FILES }

/**
 * One entry of a photo album: a sub-album, a photo or a video clip.
 *
 * Image models are opaque Coil models (URL strings for servers, [FileImageRef] for Files), built
 * per requested size so a 4K TV never downloads originals for a thumbnail grid.
 */
data class PhotoEntry(
    val id: String,
    val title: String,
    val kind: PhotoKind,
    val takenAt: Long? = null,
    val addedAt: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Existing player route key for videos (Plex rating key, "j:"/"e:" ids, Files keys). */
    val playKey: String? = null,
    /** Metadata already present in the listing (servers); Files read theirs lazily. */
    val exif: PhotoExif? = null,
    private val image: (maxWidth: Int, maxHeight: Int) -> Any?,
) {
    fun imageModel(maxWidth: Int, maxHeight: Int): Any? = image(maxWidth, maxHeight)
    fun thumbModel(): Any? = image(THUMB_WIDTH, THUMB_HEIGHT)

    override fun equals(other: Any?): Boolean = other is PhotoEntry && other.id == id
    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val THUMB_WIDTH = 480
        const val THUMB_HEIGHT = 360
    }
}

data class PhotoExif(
    val takenAt: Long? = null,
    val camera: String? = null,
    val lens: String? = null,
    val aperture: String? = null,
    val exposure: String? = null,
    val iso: String? = null,
    val focalLength: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val location: String? = null,
    val fileName: String? = null,
) {
    fun merge(other: PhotoExif?): PhotoExif = if (other == null) this else PhotoExif(
        takenAt = takenAt ?: other.takenAt,
        camera = camera ?: other.camera,
        lens = lens ?: other.lens,
        aperture = aperture ?: other.aperture,
        exposure = exposure ?: other.exposure,
        iso = iso ?: other.iso,
        focalLength = focalLength ?: other.focalLength,
        width = width ?: other.width,
        height = height ?: other.height,
        location = location ?: other.location,
        fileName = fileName ?: other.fileName,
    )
}

/** A browsable photo root: a server photo library or a Files source. */
data class PhotoLibrary(val key: String, val title: String, val system: PhotoSystem)

/**
 * Coil model for an image inside a Files source (local tree, SMB or NFS). [version] (modification
 * time and size) invalidates cached thumbnails when a file is replaced.
 */
data class FileImageRef(val sourceId: String, val path: String, val version: Long = 0)

object PhotoFormats {
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    fun isImage(name: String, mime: String = ""): Boolean =
        mime.startsWith("image/") || FilePaths.extension(name) in imageExtensions
}

enum class PhotoSort(val key: String) {
    DATE_DESC("date_desc"), DATE_ASC("date_asc"), NAME("name"), NAME_DESC("name_desc");

    val byDate: Boolean get() = this == DATE_DESC || this == DATE_ASC
    val descending: Boolean get() = this == DATE_DESC || this == NAME_DESC

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: DATE_DESC
        fun of(byDate: Boolean, descending: Boolean): PhotoSort = when {
            byDate && descending -> DATE_DESC
            byDate -> DATE_ASC
            descending -> NAME_DESC
            else -> NAME
        }
    }
}

/** Folders first, then the chosen order; entries without a date fall back to their name. */
fun List<PhotoEntry>.sortedForDisplay(sort: PhotoSort): List<PhotoEntry> {
    val byName = compareBy<PhotoEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
    val content = when (sort) {
        PhotoSort.NAME -> byName
        PhotoSort.NAME_DESC -> compareByDescending<PhotoEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        PhotoSort.DATE_DESC -> compareByDescending<PhotoEntry> { it.takenAt ?: it.addedAt ?: Long.MIN_VALUE }.then(byName)
        PhotoSort.DATE_ASC -> compareBy<PhotoEntry> { it.takenAt ?: it.addedAt ?: Long.MAX_VALUE }.then(byName)
    }
    return sortedWith(compareBy<PhotoEntry> { it.kind != PhotoKind.FOLDER }.then(content))
}
