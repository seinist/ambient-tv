package com.ambient.tv.data.photos

import com.ambient.tv.util.suspendResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Singleton
class PhotoRepository @Inject constructor(
    plex: PlexPhotoSource,
    jellyfin: JellyfinPhotoSource,
    emby: EmbyPhotoSource,
    private val files: FilesPhotoSource,
) {
    private val sources: List<PhotoSource> = listOf(plex, jellyfin, emby, files)
    private fun source(key: String) = sources.first { it.owns(key) }

    /**
     * Sidebar section route key -> photo album key. Library routes stay "section/<key>"; this map
     * tells the section destination to show the photo browser instead of the media grid.
     */
    private val _photoSections = MutableStateFlow<Map<String, PhotoLibrary>>(emptyMap())
    val photoSections: StateFlow<Map<String, PhotoLibrary>> = _photoSections.asStateFlow()

    fun registerPhotoSections(entries: Map<String, PhotoLibrary>, owner: PhotoSystem) {
        _photoSections.update { current -> current.filterValues { it.system != owner } + entries }
    }

    fun filesImageReader(): FilesPhotoSource = files

    suspend fun libraries(system: PhotoSystem? = null): List<PhotoLibrary> = sources
        .filter { it.available() && (system == null || it.system == system) }
        .flatMap { source -> suspendResult { source.libraries() }.getOrDefault(emptyList()) }

    suspend fun list(albumKey: String): Result<List<PhotoEntry>> = suspendResult { source(albumKey).list(albumKey) }

    suspend fun listRecursive(albumKey: String, limit: Int = RECURSIVE_LIMIT): Result<List<PhotoEntry>> =
        suspendResult { source(albumKey).listRecursive(albumKey, limit) }

    suspend fun details(entry: PhotoEntry): PhotoExif? = detailsResult(entry).getOrNull() ?: entry.exif

    /** EXIF details; a failure (not a cancellation, which is rethrown) is reported, not hidden. */
    suspend fun detailsResult(entry: PhotoEntry): Result<PhotoExif?> =
        suspendResult { source(entry.id).details(entry) ?: entry.exif }

    /** Last [recent] result; the home row opens the viewer on exactly this list. */
    @Volatile var lastRecent: List<PhotoEntry> = emptyList()
        private set

    /** Newest photos of all server libraries (Files have no index). */
    suspend fun recent(limit: Int = RECENT_LIMIT): List<PhotoEntry> = sources.filter { it.available() }
        .flatMap { source -> suspendResult { source.recent(limit) }.getOrDefault(emptyList()) }
        .sortedByDescending { it.addedAt ?: 0 }
        .take(limit)
        .also { lastRecent = it }

    fun hasAnyPhotoSource(): Boolean = sources.any { it.available() }

    companion object {
        const val RECURSIVE_LIMIT = 3000
        const val RECENT_LIMIT = 40
    }
}

/**
 * Hand-off from a browser/home row to the full-screen viewer route. Photo lists can be thousands
 * of entries, so they are not serialized into navigation arguments.
 */
@Singleton
class PhotoViewerSession @Inject constructor() {
    data class Request(
        val title: String,
        val entries: List<PhotoEntry>,
        val startIndex: Int,
        val startSlideshow: Boolean,
        /** Album to expand recursively when "include subfolders" is on for a slideshow. */
        val albumKey: String?,
    )

    @Volatile var request: Request? = null

    /** Photo shown last in the viewer; the browser/Files list refocuses it on return. */
    @Volatile var lastViewedId: String? = null

    /** A new browser session must not inherit the previous session's return selection. */
    fun clearReturnFocus() {
        lastViewedId = null
    }
}
