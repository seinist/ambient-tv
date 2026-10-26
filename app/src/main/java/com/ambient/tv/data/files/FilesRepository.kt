package com.ambient.tv.data.files

import com.ambient.tv.domain.model.*
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.util.suspendResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class FilesRepository @Inject constructor(val store: FileStore, private val access: FileAccess, private val server: MediaFileServer) : MediaRepository {
    private data class Cached(val item: MediaItem, val source: FileSource)
    private val cache = LinkedHashMap<String, Cached>()
    private data class PlaybackBinding(val source: FileSource, val entryId: String)
    // Keep active URL identities across ordinary UI cache refreshes. A delayed old-session
    // report must never borrow a newer source configuration for the same file key.
    private val playbackBindings = LinkedHashMap<String, PlaybackBinding>()
    @Volatile private var queue = emptyList<FileEntry>()
    fun setQueue(entries: List<FileEntry>) { queue = entries.filter { it.playable }.take(2000) }
    override fun cachedLibrarySections(): List<LibrarySection>? = null
    override fun cachedOnDeck(): List<MediaItem>? = null
    override fun cachedSectionItems(sectionKey: String): List<MediaItem>? = null
    override fun cachedMediaItem(ratingKey: String): MediaItem? = synchronized(cache) { cache[ratingKey] }?.takeIf { saved -> store.sources().any { it == saved.source } }?.item
    override fun invalidateCaches() { synchronized(cache) { cache.clear() } }
    override fun invalidateSectionCache(sectionKey: String) = Unit
    override suspend fun getIdentity(): Result<PlexServerIdentity> = Result.failure(UnsupportedOperationException())
    override suspend fun getLibrarySections() = Result.success(emptyList<LibrarySection>())
    override suspend fun getOnDeck() = Result.success(emptyList<MediaItem>())
    override suspend fun getMetadataFast(ratingKey: String) = getMetadata(ratingKey)
    override suspend fun getMetadata(ratingKey: String): Result<MediaItem?> = suspendResult {
        val (sourceId, path) = FileKeys.parse(ratingKey)
        val source = store.sources().firstOrNull { it.id == sourceId } ?: throw java.io.FileNotFoundException("Source removed")
        val entry = access.stat(source, path)
        require(entry.playable)
        // Network listing happens before the source lock; a failure only means no sidecar tracks.
        val sidecars = if (entry.audio) emptyList() else findSidecarSubtitles(source, path, entry.name)
        // Music: tags for title/artist/album and the folder cover for the player and mini player.
        val tags = if (entry.audio) withContext(Dispatchers.IO) { runCatching { access.open(source, path).use { AudioTagReader.read(it, entry.name) } }.getOrNull() } else null
        val cover = if (entry.audio) runCatching {
            MusicFolder.cover(access.list(source, parentFolder(source, path) { access.root(source) }, limit = 500, accept = { it.image }).entries)
        }.getOrNull() else null
        withContext(Dispatchers.IO) { store.withCurrentSource(source) {
            val old = store.history().firstOrNull { it.entry.id == ratingKey }
            val offset = if (old?.completed == true) 0 else old?.positionMs ?: 0
            val url = server.register(source.id, entry.mime.ifBlank { FilePaths.mime(entry.name) }) { access.open(source, path) }
            val coverUrl = cover?.let { c -> server.register(source.id, c.mime.ifBlank { FilePaths.mime(c.name) }) { access.open(source, c.path) } }
            val subtitleTracks = sidecars.map { subtitle ->
                val subtitleUrl = server.register(source.id, SUBTITLE_MIME[subtitle.codec] ?: "text/plain", "subtitle.${subtitle.codec}") {
                    SubtitleText.convertedMedia(access.open(source, subtitle.path))
                }
                MediaTrack(id = null, type = MediaTrackType.SUBTITLE, codec = subtitle.codec,
                    language = subtitle.languageCode?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { null } },
                    languageCode = subtitle.languageCode, title = subtitle.name, channels = null, selected = false,
                    forced = subtitle.forced, externalUrl = subtitleUrl)
            }
            val item = MediaItem(ratingKey = ratingKey, title = tags?.title ?: entry.name, type = if (entry.audio) "track" else "movie",
                index = tags?.track, thumbUrl = coverUrl, artUrl = coverUrl, year = tags?.year, summary = null, tagline = null,
                durationMs = old?.durationMs?.takeIf { it > 0 }, viewOffsetMs = offset, parentTitle = tags?.album ?: source.name,
                parentRatingKey = null, grandparentTitle = tags?.albumArtist ?: tags?.artist, grandparentRatingKey = null, contentRating = null,
                rating = null, studio = null, viewCount = if (old?.completed == true) 1 else 0,
                mediaVersions = listOf(MediaVersion(url, null, null, null, FilePaths.extension(entry.name), tracks = subtitleTracks, sourceRatingKey = ratingKey)))
            synchronized(cache) {
                cache[ratingKey] = Cached(item, source)
                while (cache.size > 24) cache.remove(cache.keys.first())
                playbackBindings[url] = PlaybackBinding(source, ratingKey)
                while (playbackBindings.size > 32) playbackBindings.remove(playbackBindings.keys.first())
            }
            store.record(entry, position = offset, completed = false)
            item
        } }
    }
    /** Same folder first, then a Subs/Subtitles folder; each listing is one request to the source. */
    private suspend fun findSidecarSubtitles(source: FileSource, path: String, videoName: String): List<SidecarSubtitle> = runCatching {
        val folder = parentFolder(source, path) { access.root(source) }
        val accept: (FileEntry) -> Boolean = SidecarSubtitles::isSubtitle
        val here = access.list(source, folder, limit = 2000, accept = accept).entries
        val found = SidecarSubtitles.match(videoName, here).toMutableList()
        here.firstOrNull { it.directory && it.name.lowercase() in SidecarSubtitles.folderNames }?.let { subs ->
            val inSubs = runCatching { access.list(source, subs.path, limit = 500, accept = accept).entries }.getOrDefault(emptyList())
            found += SidecarSubtitles.match(videoName, inSubs)
            inSubs.firstOrNull { it.directory && it.name.equals(SidecarSubtitles.baseName(videoName), ignoreCase = true) }?.let { own ->
                val perTitle = runCatching { access.list(source, own.path, limit = 200, accept = accept).entries }.getOrDefault(emptyList())
                found += SidecarSubtitles.match(videoName, perTitle, wholeFolder = true)
            }
        }
        found.distinctBy { it.path }.take(SidecarSubtitles.MAX_TRACKS)
    }.getOrDefault(emptyList())

    override suspend fun getSectionItems(sectionKey: String, onPage: suspend (List<MediaItem>) -> Unit) = Result.success(emptyList<MediaItem>())
    override suspend fun getSectionPreview(sectionKey: String, sort: String, limit: Int) = Result.success(emptyList<MediaItem>())
    override suspend fun getChildren(ratingKey: String) = Result.success(emptyList<MediaItem>())
    override suspend fun getSimilar(ratingKey: String) = Result.success(emptyList<MediaItem>())
    override suspend fun getNextEpisode(ratingKey: String) = Result.success<MediaItem?>(null)
    override suspend fun getNextUnwatchedEpisode(ratingKey: String) = Result.success<MediaItem?>(null)
    override suspend fun getNextTrack(ratingKey: String): Result<MediaItem?> {
        val list = queue; val index = list.indexOfFirst { it.id == ratingKey }
        val next = if (index < 0) null else list.drop(index + 1).firstOrNull { it.audio }
        // Metadata for Next Up must not record a file as played or open a network connection.
        return Result.success(next?.let { e -> MediaItem(e.id, e.name, "track", null, null, null, null, null, null, null, null,
            null, parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null, contentRating = null, rating = null, studio = null) })
    }
    override suspend fun search(sectionKeys: List<String>, query: String) = Result.success(emptyList<MediaItem>())
    override suspend fun getItemsByActor(actorId: String) = Result.success(emptyList<MediaItem>())
    override suspend fun getItemsByCollection(collectionId: String) = Result.success(emptyList<MediaItem>())
    suspend fun updateProgress(id: String, position: Long, duration: Long, ended: Boolean = false, streamUrl: String? = null): Result<Unit> = suspendResult {
        withContext(Dispatchers.IO) {
            val source = synchronized(cache) {
                if (streamUrl == null) cache[id]?.source
                else playbackBindings[streamUrl]?.takeIf { it.entryId == id }?.source
            } ?: return@withContext
            val old = store.history().firstOrNull { it.entry.id == id } ?: return@withContext
            val finished = ended || (duration > 0 && position >= duration * .94)
            store.record(old.entry, position, duration, if (finished) true else null, expectedSource = source)
        }
    }
    override suspend fun reportProgress(ratingKey: String, positionMs: Long, state: String) = updateProgress(ratingKey, positionMs, 0)
    override suspend fun markWatched(ratingKey: String): Result<Unit> = suspendResult { withContext(Dispatchers.IO) { store.clearProgress(ratingKey) } }
    override suspend fun markUnwatched(ratingKey: String): Result<Unit> = suspendResult {
        withContext(Dispatchers.IO) {
            val item = store.history().firstOrNull { it.entry.id == ratingKey }
            if (item != null) store.record(item.entry, 0, completed = false)
        }
    }
    override suspend fun removeFromContinueWatching(ratingKey: String) = markWatched(ratingKey)
    override suspend fun getAudioCompatibleStreamUrl(ratingKey: String, mediaIndex: Int, startPositionMs: Long, audioCodec: String, audioStreamId: Long?, audioChannels: Int) = Result.failure<String>(UnsupportedOperationException("Local files have no server transcoder"))
    override suspend fun getVideoCompatibleStreamUrl(ratingKey: String, mediaIndex: Int, startPositionMs: Long) = Result.failure<String>(UnsupportedOperationException("Local files have no server transcoder"))

    private companion object {
        val SUBTITLE_MIME = mapOf("srt" to "application/x-subrip", "ass" to "text/x-ssa", "ssa" to "text/x-ssa", "vtt" to "text/vtt")
    }
}
