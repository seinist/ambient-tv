package com.ambient.tv.domain.repository

import com.ambient.tv.domain.model.LibraryHub
import com.ambient.tv.domain.model.LibraryRecommendation
import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.PlexServerIdentity
import com.ambient.tv.domain.model.ThemeMusic

/**
 * Backend-neutral media access used by the TV UI.
 *
 * Plex-specific transport details stay in the data layer. This keeps the UI and playback
 * surfaces independent from the current server protocol while retaining full Plex support.
 */
interface MediaRepository {
    suspend fun restoreCache() = Unit
    fun refreshCaches() = invalidateCaches()
    fun cachedChildren(ratingKey: String): List<MediaItem>? = null
    fun cachedSectionPreview(sectionKey: String, sort: String, limit: Int): List<MediaItem>? = null
    fun cachedLibrarySections(): List<LibrarySection>?
    fun cachedLibrarySection(sectionKey: String): LibrarySection? = cachedLibrarySections()?.firstOrNull { it.key == sectionKey }
    fun cachedOnDeck(): List<MediaItem>?
    fun cachedSectionItems(sectionKey: String): List<MediaItem>?
    fun cachedMediaItem(ratingKey: String): MediaItem?
    fun invalidateCaches()
    fun invalidateSectionCache(sectionKey: String)

    suspend fun getIdentity(): Result<PlexServerIdentity>
    suspend fun getLibrarySections(): Result<List<LibrarySection>>
    /** Provider-wide feed selected by library; callers must restrict it to library members. */
    suspend fun getProviderOnDeck(sectionKey: String): Result<List<MediaItem>> = getOnDeck()
    fun cachedProviderOnDeck(sectionKey: String): List<MediaItem>? = cachedOnDeck()
    suspend fun getOnDeck(): Result<List<MediaItem>>
    suspend fun getMetadata(ratingKey: String): Result<MediaItem?>
    suspend fun getMetadataFast(ratingKey: String): Result<MediaItem?>
    suspend fun getPlaybackTimeline(ratingKey: String, mediaIndex: Int): Result<com.ambient.tv.domain.model.PlaybackTimeline> =
        Result.success(com.ambient.tv.domain.model.PlaybackTimeline())
    suspend fun getSectionItems(
        sectionKey: String,
        onPage: suspend (List<MediaItem>) -> Unit = {},
    ): Result<List<MediaItem>>
    /** Optional provider-specific enrichment deferred until collection UI is actually opened. */
    suspend fun enrichCollections(items: List<MediaItem>): Result<List<MediaItem>> = Result.success(items)
    suspend fun getSectionPreview(sectionKey: String, sort: String, limit: Int = 120): Result<List<MediaItem>>
    /** A show library's most recently added episodes, newest first; other libraries have none. */
    suspend fun getRecentEpisodes(sectionKey: String, limit: Int = 96): Result<List<MediaItem>> = Result.success(emptyList())
    /** Last [getRecentEpisodes] result regardless of age, for the instantly published cached Home. */
    fun cachedRecentEpisodes(sectionKey: String, limit: Int = 96): List<MediaItem>? = null
    /** A server-ranked list of one movie or show library; other libraries have none. */
    suspend fun getRecommendation(sectionKey: String, recommendation: LibraryRecommendation, limit: Int = 48): Result<List<MediaItem>> =
        Result.success(emptyList())
    /** Last [getRecommendation] result regardless of age, for the instantly published cached Home. */
    fun cachedRecommendation(sectionKey: String, recommendation: LibraryRecommendation, limit: Int = 48): List<MediaItem>? = null
    /** Genre names of a movie or show library, as [LibraryRecommendation.TopGenre] expects them. */
    suspend fun getGenres(sectionKey: String): Result<List<String>> = Result.success(emptyList())
    /** Rows the server itself recommends for a library; only Plex has them. */
    suspend fun getHubs(sectionKey: String, limit: Int = 24): Result<List<LibraryHub>> = Result.success(emptyList())
    fun cachedHubs(sectionKey: String, limit: Int = 24): List<LibraryHub>? = null
    suspend fun getChildren(ratingKey: String): Result<List<MediaItem>>
    suspend fun getSimilar(ratingKey: String): Result<List<MediaItem>>
    /** Trailers the server stores with the title (playable like any item). Empty where unsupported. */
    suspend fun getServerTrailers(ratingKey: String): Result<List<MediaItem>> = Result.success(emptyList())
    /** Last [getSimilar] result regardless of age. */
    fun cachedSimilar(ratingKey: String): List<MediaItem>? = null
    /** The item's theme song, or null when the provider or the item has none. */
    suspend fun getThemeMusic(ratingKey: String): Result<ThemeMusic?> = Result.success(null)
    suspend fun getNextEpisode(ratingKey: String): Result<MediaItem?>
    suspend fun getNextTrack(ratingKey: String): Result<MediaItem?>
    suspend fun getNextUnwatchedEpisode(ratingKey: String): Result<MediaItem?>
    suspend fun search(sectionKeys: List<String>, query: String): Result<List<MediaItem>>
    suspend fun getItemsByActor(actorId: String): Result<List<MediaItem>>
    suspend fun getItemsByCollection(collectionId: String): Result<List<MediaItem>>
    /** Selects the physical source that subsequent playback progress belongs to. */
    fun preparePlayback(ratingKey: String, mediaIndex: Int) = Unit
    suspend fun reportProgress(ratingKey: String, positionMs: Long, state: String): Result<Unit>
    suspend fun markWatched(ratingKey: String): Result<Unit>
    suspend fun markUnwatched(ratingKey: String): Result<Unit>
    suspend fun removeFromContinueWatching(ratingKey: String): Result<Unit>
    /** Video stays stream-copied; only audio is converted to [audioCodec] where the server lets the client pick it. */
    suspend fun getAudioCompatibleStreamUrl(ratingKey: String, mediaIndex: Int, startPositionMs: Long, audioCodec: String = "aac", audioStreamId: Long? = null): Result<String>
    suspend fun getVideoCompatibleStreamUrl(ratingKey: String, mediaIndex: Int, startPositionMs: Long): Result<String>
}
