package com.ambient.tv.data.repository

import com.ambient.tv.data.emby.EmbyBackend
import com.ambient.tv.data.emby.EmbyRepository
import com.ambient.tv.data.dlna.DlnaKeys
import com.ambient.tv.data.dlna.DlnaRepository
import com.ambient.tv.data.jellyfin.JellyfinBackend
import com.ambient.tv.data.jellyfin.JellyfinRepository
import com.ambient.tv.domain.model.ArtworkChoice
import com.ambient.tv.domain.model.ArtworkKind
import com.ambient.tv.domain.model.LibraryRecommendation
import com.ambient.tv.domain.model.MetadataMatch
import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.PlexServerIdentity
import com.ambient.tv.domain.repository.MediaRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Routes provider-owned ids while preserving the existing unprefixed Plex routes. */
@Singleton
class RoutingMediaRepository @Inject constructor(
    val files: com.ambient.tv.data.files.FilesRepository,
    val plex: PlexRepository,
    val jellyfin: JellyfinRepository,
    val emby: EmbyRepository,
    val dlna: DlnaRepository,
    val shared: com.ambient.tv.data.shared.SharedMediaRepository,
) : MediaRepository {
    private fun forId(id: String): MediaRepository = when {
        com.ambient.tv.data.shared.SharedMediaRepository.owns(id) -> shared
        DlnaKeys.owns(id) -> dlna
        com.ambient.tv.data.files.FileKeys.owns(id) -> files
        EmbyBackend.owns(id) -> emby
        JellyfinBackend.owns(id) -> jellyfin
        else -> plex
    }
    private fun forIds(ids: List<String>): MediaRepository = ids.firstOrNull()?.let(::forId) ?: plex

    override suspend fun restoreCache() { plex.restoreCache(); jellyfin.restoreCache(); emby.restoreCache() }
    override fun refreshCaches() { plex.refreshCaches(); jellyfin.refreshCaches(); emby.refreshCaches() }
    override fun cachedSectionPreview(sectionKey: String, sort: String, limit: Int) = forId(sectionKey).cachedSectionPreview(sectionKey, sort, limit)
    override fun cachedChildren(ratingKey: String) = forId(ratingKey).cachedChildren(ratingKey)
    override fun cachedLibrarySections() = plex.cachedLibrarySections()
    override fun cachedLibrarySection(sectionKey: String) = forId(sectionKey).cachedLibrarySection(sectionKey)
    override fun cachedOnDeck() = plex.cachedOnDeck()
    override fun cachedProviderOnDeck(sectionKey: String) = forId(sectionKey).cachedOnDeck()
    override suspend fun getProviderOnDeck(sectionKey: String) = forId(sectionKey).getOnDeck()
    override fun cachedSectionItems(sectionKey: String) = forId(sectionKey).cachedSectionItems(sectionKey)
    override fun cachedMediaItem(ratingKey: String) = forId(ratingKey).cachedMediaItem(ratingKey)
    override fun invalidateCaches() { plex.invalidateCaches(); jellyfin.invalidateCaches(); emby.invalidateCaches(); dlna.invalidateCaches(); files.invalidateCaches() }
    override fun invalidateSectionCache(sectionKey: String) = forId(sectionKey).invalidateSectionCache(sectionKey)
    override suspend fun getIdentity(): Result<PlexServerIdentity> = plex.getIdentity()
    override suspend fun getLibrarySections(): Result<List<LibrarySection>> = plex.getLibrarySections()
    override suspend fun getOnDeck(): Result<List<MediaItem>> = plex.getOnDeck()
    override suspend fun getMetadata(ratingKey: String) = forId(ratingKey).getMetadata(ratingKey)
    override suspend fun getPlaybackTimeline(ratingKey: String, mediaIndex: Int) = forId(ratingKey).getPlaybackTimeline(ratingKey, mediaIndex)
    override suspend fun getMetadataFast(ratingKey: String) = forId(ratingKey).getMetadataFast(ratingKey)
    override suspend fun getSectionItems(sectionKey: String, onPage: suspend (List<MediaItem>) -> Unit) = forId(sectionKey).getSectionItems(sectionKey, onPage)
    override suspend fun enrichCollections(items: List<MediaItem>) = items.firstOrNull()?.ratingKey?.let(::forId)?.enrichCollections(items)
        ?: Result.success(items)
    override suspend fun getSectionPreview(sectionKey: String, sort: String, limit: Int) = forId(sectionKey).getSectionPreview(sectionKey, sort, limit)
    override suspend fun getRecentEpisodes(sectionKey: String, limit: Int) = forId(sectionKey).getRecentEpisodes(sectionKey, limit)
    override fun cachedRecentEpisodes(sectionKey: String, limit: Int) = forId(sectionKey).cachedRecentEpisodes(sectionKey, limit)
    override suspend fun getRecommendation(sectionKey: String, recommendation: LibraryRecommendation, limit: Int) = forId(sectionKey).getRecommendation(sectionKey, recommendation, limit)
    override fun cachedRecommendation(sectionKey: String, recommendation: LibraryRecommendation, limit: Int) = forId(sectionKey).cachedRecommendation(sectionKey, recommendation, limit)
    override suspend fun getGenres(sectionKey: String) = forId(sectionKey).getGenres(sectionKey)
    override suspend fun getHubs(sectionKey: String, limit: Int) = forId(sectionKey).getHubs(sectionKey, limit)
    override fun cachedHubs(sectionKey: String, limit: Int) = forId(sectionKey).cachedHubs(sectionKey, limit)
    override suspend fun getChildren(ratingKey: String) = forId(ratingKey).getChildren(ratingKey)
    override suspend fun getSimilar(ratingKey: String) = forId(ratingKey).getSimilar(ratingKey)
    override suspend fun getServerTrailers(ratingKey: String) = forId(ratingKey).getServerTrailers(ratingKey)
    override suspend fun getAlternativeBackdrop(ratingKey: String) = forId(ratingKey).getAlternativeBackdrop(ratingKey)
    override fun cachedSimilar(ratingKey: String) = forId(ratingKey).cachedSimilar(ratingKey)
    override suspend fun getThemeMusic(ratingKey: String) = forId(ratingKey).getThemeMusic(ratingKey)
    override suspend fun getNextEpisode(ratingKey: String) = forId(ratingKey).getNextEpisode(ratingKey)
    override suspend fun getNextTrack(ratingKey: String) = forId(ratingKey).getNextTrack(ratingKey)
    override suspend fun getNextUnwatchedEpisode(ratingKey: String) = forId(ratingKey).getNextUnwatchedEpisode(ratingKey)
    override suspend fun search(sectionKeys: List<String>, query: String) = forIds(sectionKeys).search(sectionKeys, query)
    override suspend fun getItemsByActor(actorId: String) = forId(actorId).getItemsByActor(actorId)
    override suspend fun getItemsByCollection(collectionId: String) = forId(collectionId).getItemsByCollection(collectionId)
    override fun preparePlayback(ratingKey: String, mediaIndex: Int) = forId(ratingKey).preparePlayback(ratingKey, mediaIndex)
    override suspend fun reportProgress(ratingKey: String, positionMs: Long, state: String) = forId(ratingKey).reportProgress(ratingKey, positionMs, state)
    override suspend fun markWatched(ratingKey: String) = forId(ratingKey).markWatched(ratingKey)
    override suspend fun markUnwatched(ratingKey: String) = forId(ratingKey).markUnwatched(ratingKey)
    override suspend fun removeFromContinueWatching(ratingKey: String) = forId(ratingKey).removeFromContinueWatching(ratingKey)
    override fun metadataEditSupport(item: MediaItem) = forId(item.ratingKey).metadataEditSupport(item)
    override suspend fun refreshMetadataEditRights(ratingKey: String) = forId(ratingKey).refreshMetadataEditRights(ratingKey)
    override suspend fun searchMatches(ratingKey: String, title: String?, year: Int?) = forId(ratingKey).searchMatches(ratingKey, title, year)
    override suspend fun applyMatch(ratingKey: String, match: MetadataMatch) = forId(ratingKey).applyMatch(ratingKey, match)
    override suspend fun artworkChoices(ratingKey: String, kind: ArtworkKind) = forId(ratingKey).artworkChoices(ratingKey, kind)
    override suspend fun setArtwork(ratingKey: String, kind: ArtworkKind, choice: ArtworkChoice) = forId(ratingKey).setArtwork(ratingKey, kind, choice)
    override fun forgetMerged(ratingKeys: Set<String>) = ratingKeys.groupBy(::forId).forEach { (repository, keys) -> repository.forgetMerged(keys.toSet()) }
    override suspend fun metadataFields(ratingKey: String) = forId(ratingKey).metadataFields(ratingKey)
    override suspend fun updateMetadataFields(ratingKey: String, current: com.ambient.tv.domain.model.MetadataFields, edited: com.ambient.tv.domain.model.MetadataFields) =
        forId(ratingKey).updateMetadataFields(ratingKey, current, edited)
    override suspend fun getAudioCompatibleStreamUrl(ratingKey: String, mediaIndex: Int, startPositionMs: Long, audioCodec: String, audioStreamId: Long?, audioChannels: Int) = forId(ratingKey).getAudioCompatibleStreamUrl(ratingKey, mediaIndex, startPositionMs, audioCodec, audioStreamId, audioChannels)
    override suspend fun getVideoCompatibleStreamUrl(ratingKey: String, mediaIndex: Int, startPositionMs: Long, audioChannels: Int) = forId(ratingKey).getVideoCompatibleStreamUrl(ratingKey, mediaIndex, startPositionMs, audioChannels)
}
