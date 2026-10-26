package com.ambient.tv.data.shared

import com.ambient.tv.domain.model.*
import com.ambient.tv.domain.repository.MediaRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Ephemeral direct-play items. No provider credentials, server API, disk history or transcoder. */
@Singleton
class SharedMediaRepository @Inject constructor() : MediaRepository {
    private val links = LinkedHashMap<String, SharedMediaLink>()

    @Synchronized fun register(url: String): String =
        register(requireNotNull(SharedMediaLink.parse(url)) { "Unsupported shared media link" })

    /** A link already validated elsewhere (the DLNA renderer's [SharedMediaLink.parseRendered]). */
    @Synchronized fun register(link: SharedMediaLink): String {
        val key = "shared:${UUID.randomUUID()}"
        links[key] = link
        while (links.size > 32) links.remove(links.keys.first())
        return key
    }

    @Synchronized fun link(key: String): SharedMediaLink? = links[key]

    override fun cachedMediaItem(ratingKey: String): MediaItem? = link(ratingKey)?.let { link ->
        MediaItem(
            ratingKey = ratingKey, title = link.title, type = if (link.audioOnly) "track" else "video",
            index = null, thumbUrl = null, artUrl = null, year = null, summary = null, tagline = null,
            durationMs = null, viewOffsetMs = null, parentTitle = null, parentRatingKey = null,
            grandparentTitle = null, grandparentRatingKey = null, contentRating = null, rating = null, studio = null,
            mediaVersions = listOf(MediaVersion(streamUrl = link.url, resolution = null, videoCodec = null,
                audioCodec = null, container = link.extension.ifBlank { null }, sourceRatingKey = ratingKey)),
        )
    }

    override suspend fun getMetadata(ratingKey: String): Result<MediaItem?> = cachedMediaItem(ratingKey)?.let { Result.success(it) }
        ?: Result.failure(IllegalStateException("Shared link expired; send it again from your phone"))
    override suspend fun getMetadataFast(ratingKey: String) = getMetadata(ratingKey)
    override fun cachedLibrarySections(): List<LibrarySection>? = null
    override fun cachedOnDeck(): List<MediaItem>? = null
    override fun cachedSectionItems(sectionKey: String): List<MediaItem>? = null
    override fun invalidateCaches() = Unit // Library refresh must not invalidate an active shared item.
    override fun invalidateSectionCache(sectionKey: String) = Unit
    override suspend fun getIdentity(): Result<PlexServerIdentity> = Result.failure(UnsupportedOperationException("No media server"))
    override suspend fun getLibrarySections() = Result.success(emptyList<LibrarySection>())
    override suspend fun getOnDeck() = Result.success(emptyList<MediaItem>())
    override suspend fun getSectionItems(sectionKey: String, onPage: suspend (List<MediaItem>) -> Unit) = Result.success(emptyList<MediaItem>())
    override suspend fun getSectionPreview(sectionKey: String, sort: String, limit: Int) = Result.success(emptyList<MediaItem>())
    override suspend fun getChildren(ratingKey: String) = Result.success(emptyList<MediaItem>())
    override suspend fun getSimilar(ratingKey: String) = Result.success(emptyList<MediaItem>())
    override suspend fun getNextEpisode(ratingKey: String) = Result.success<MediaItem?>(null)
    override suspend fun getNextTrack(ratingKey: String) = Result.success<MediaItem?>(null)
    override suspend fun getNextUnwatchedEpisode(ratingKey: String) = Result.success<MediaItem?>(null)
    override suspend fun search(sectionKeys: List<String>, query: String) = Result.success(emptyList<MediaItem>())
    override suspend fun getItemsByActor(actorId: String) = Result.success(emptyList<MediaItem>())
    override suspend fun getItemsByCollection(collectionId: String) = Result.success(emptyList<MediaItem>())
    override suspend fun reportProgress(ratingKey: String, positionMs: Long, state: String) = Result.success(Unit)
    override suspend fun markWatched(ratingKey: String) = Result.success(Unit)
    override suspend fun markUnwatched(ratingKey: String) = Result.success(Unit)
    override suspend fun removeFromContinueWatching(ratingKey: String) = Result.success(Unit)
    override suspend fun getAudioCompatibleStreamUrl(ratingKey: String, mediaIndex: Int, startPositionMs: Long, audioCodec: String, audioStreamId: Long?, audioChannels: Int) = noTranscoder()
    override suspend fun getVideoCompatibleStreamUrl(ratingKey: String, mediaIndex: Int, startPositionMs: Long, audioChannels: Int) = noTranscoder()
    private fun noTranscoder(): Result<String> = Result.failure(UnsupportedOperationException("Shared links require local decoding"))

    companion object { fun owns(key: String) = key.startsWith("shared:") }
}
