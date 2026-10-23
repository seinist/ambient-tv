package com.ambient.tv.data.repository

import com.ambient.tv.util.suspendResult
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.data.remote.PlexApiService
import com.ambient.tv.data.remote.PlexUrlBuilder
import com.ambient.tv.data.remote.dto.toDomain
import com.ambient.tv.domain.model.LibraryHub
import com.ambient.tv.domain.model.LibraryRecommendation
import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaTrackType
import com.ambient.tv.domain.model.PlexServerIdentity
import com.ambient.tv.domain.model.ThemeMusic
import com.ambient.tv.domain.repository.MediaRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlexRepository @Inject constructor(
    private val api: PlexApiService,
    private val urlBuilder: PlexUrlBuilder,
    private val cache: PlexMediaCache,
    private val preferences: AppPreferences,
    disk: com.ambient.tv.data.browse.BrowseDiskStore,
    scopes: com.ambient.tv.data.browse.BrowseScopes,
) : MediaRepository {
    init { cache.configurePersistence(disk) { scopes.forProvider("plex") } }
    override suspend fun restoreCache() = cache.restore()
    override fun refreshCaches() = cache.expire()
    override fun cachedSectionPreview(sectionKey: String, sort: String, limit: Int) = cache.sectionPreview("$sectionKey|$sort|$limit", Long.MAX_VALUE)
    override fun cachedChildren(ratingKey: String) = cache.children(ratingKey, Long.MAX_VALUE)
    private val libraryMutex = Mutex()
    private val onDeckMutex = Mutex()
    private val keyedMutexes = Array(KEYED_MUTEX_COUNT) { Mutex() }

    private fun mutexFor(key: String): Mutex = keyedMutexes[(key.hashCode() and Int.MAX_VALUE) % keyedMutexes.size]
    override fun cachedLibrarySections(): List<LibrarySection>? = cache.librarySections()
    override fun cachedOnDeck(): List<MediaItem>? = cache.onDeck()
    override fun cachedSectionItems(sectionKey: String): List<MediaItem>? = cache.sectionItems(sectionKey) ?: cache.sectionPreview("$sectionKey|titleSort:asc|100", Long.MAX_VALUE)
    override fun cachedMediaItem(ratingKey: String): MediaItem? = cache.mediaItem(ratingKey)

    override fun invalidateCaches() = cache.clear()
    override fun invalidateSectionCache(sectionKey: String) = cache.clearSection(sectionKey)

    private fun mergeAndRegister(items: List<MediaItem>): List<MediaItem> = cache.mergeAndRegister(items)
    private fun register(item: MediaItem): MediaItem = cache.register(item)
    private fun linkedKeys(ratingKey: String): List<String> = cache.linkedKeys(ratingKey)

    /**
     * Runs [action] for every linked version of [ratingKey]. The cache is patched for the
     * versions the server accepted even when a later one fails, so it never shows a stale state.
     */
    private suspend inline fun forEachLinked(
        ratingKey: String,
        action: (String) -> Unit,
        patch: (Set<String>) -> Unit,
    ): Result<Unit> {
        val done = linkedSetOf<String>()
        val result = suspendResult { linkedKeys(ratingKey).forEach { key -> action(key); done += key } }
        if (done.isNotEmpty()) patch(done)
        return result
    }

    override suspend fun getIdentity(): Result<PlexServerIdentity> = cache.withGeneration {
        suspendResult { api.getIdentity().container.toDomain() }
    }

    override suspend fun getLibrarySections(): Result<List<LibrarySection>> = cache.withGeneration {
        cache.freshLibrarySections(LIBRARY_SECTIONS_TTL_MS)?.let { return@withGeneration Result.success(it) }
        libraryMutex.withLock {
            cache.freshLibrarySections(LIBRARY_SECTIONS_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult { api.getLibrarySections().container.directories.map { it.toDomain(urlBuilder) } }
                .onSuccess(cache::setLibrarySections)
        }
    }

    override suspend fun getOnDeck(): Result<List<MediaItem>> = cache.withGeneration {
        cache.freshOnDeck(ON_DECK_TTL_MS)?.let { return@withGeneration Result.success(it) }
        onDeckMutex.withLock {
            cache.freshOnDeck(ON_DECK_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult { mergeAndRegister(api.getOnDeck().container.metadata.map { it.toDomain(urlBuilder) }) }
                .onSuccess(cache::setOnDeck)
        }
    }


    override suspend fun getPlaybackTimeline(ratingKey: String, mediaIndex: Int) = suspendResult {
        val item = api.getPlaybackTimeline(ratingKey, mediaIndex).container.metadata.firstOrNull()
        val part = item?.media?.getOrNull(mediaIndex)?.parts?.firstOrNull()
        val preview = if (part?.id != null && part.indexes?.lowercase() == "sd")
            urlBuilder.artworkUrl("/library/parts/${part.id}/indexes/sd/__TIME__", 320, 180)?.let(com.ambient.tv.domain.model.TimelinePreview::Plex)
        else null
        com.ambient.tv.domain.model.PlaybackTimeline(
            chapters = item?.chapters.orEmpty().map { chapter -> com.ambient.tv.domain.model.VideoChapter(
                chapter.startTimeOffset, chapter.tag ?: chapter.title,
                urlBuilder.absoluteUrl(chapter.thumb), chapter.endTimeOffset,
            ) }, preview = preview,
            segments = item?.markers.orEmpty().map {
                com.ambient.tv.domain.model.PlaybackSegment(it.type, it.startTimeOffset, it.endTimeOffset)
            },
        ).normalized(item?.duration ?: 0)
    }

    override suspend fun getMetadata(ratingKey: String): Result<MediaItem?> = cache.withGeneration {
        suspendResult {
            val raw = api.getMetadata(ratingKey).container.metadata.firstOrNull()?.toDomain(urlBuilder) ?: return@suspendResult null
            resolveCanonicalDuplicates(raw)
        }
    }

    override suspend fun getThemeMusic(ratingKey: String): Result<ThemeMusic?> = suspendResult {
        // Prefer the already-parsed metadata theme URL; if list/preview cache omitted it,
        // fetch metadata once so Detail can still play theme music.
        val cachedUrl = cachedMediaItem(ratingKey)?.themeMusicUrl
        val url = cachedUrl
            ?: getMetadataFast(ratingKey).getOrNull()?.themeMusicUrl
            ?: return@suspendResult null
        // The path ends in a change stamp that differs between a show, its seasons and its
        // episodes, so the song is identified by the path without it (and without the token).
        ThemeMusic(id = url.substringBefore('?').substringBeforeLast('/'), url = url)
    }

    /**
     * Fast metadata path for detail screens. It performs only the metadata request and merges it
     * with versions that are already known from library/home caches. Expensive cross-section
     * duplicate discovery is intentionally left to [getMetadata], which playback still uses.
     */
    override suspend fun getMetadataFast(ratingKey: String): Result<MediaItem?> = cache.withGeneration {
        suspendResult {
            api.getMetadata(ratingKey).container.metadata.firstOrNull()?.toDomain(urlBuilder)?.let(::register)
        }
    }

    /**
     * Home intentionally uses bounded previews. Resolve duplicate physical entries lazily when a
     * title is opened so a 4K/1080p pair still becomes one logical item even when only one copy
     * was present in the preview window.
     */
    private suspend fun resolveCanonicalDuplicates(item: MediaItem): MediaItem {
        val cached = cache.mergedFor(item.ratingKey)
        if (cache.isCanonicalResolved(item.canonicalContentKey) && cached != null) return register(item)
        val candidates = mutableListOf(item)
        if (cached != null && cached.canonicalContentKey == item.canonicalContentKey) candidates += cached

        // Duplicate discovery is an enhancement: one broken library must not fail metadata
        // (and with it playback). Failed sections are skipped and retried on the next open.
        val sections = getLibrarySections().getOrElse { return register(item) }
        val lookups = coroutineScope {
            sections.map { section ->
                async {
                    suspendResult {
                        val local = cache.sectionItems(section.key)
                        if (local != null) {
                            local.filter { it.canonicalContentKey == item.canonicalContentKey }
                        } else {
                            api.getSectionItems(
                                sectionKey = section.key,
                                titleFilter = item.title,
                                start = 0,
                                size = DUPLICATE_LOOKUP_LIMIT_PER_SECTION,
                            ).container.metadata.map { it.toDomain(urlBuilder) }
                                .filter { it.canonicalContentKey == item.canonicalContentKey }
                        }
                    }
                }
            }.awaitAll()
        }
        candidates += lookups.flatMap { it.getOrDefault(emptyList()) }
        val merged = register(ContentDeduplicator.mergeGroup(candidates))
        if (lookups.all { it.isSuccess }) cache.markCanonicalResolved(item.canonicalContentKey)
        return merged
    }

    override suspend fun getSectionItems(
        sectionKey: String,
        onPage: suspend (List<MediaItem>) -> Unit,
    ): Result<List<MediaItem>> = cache.withGeneration {
        cache.freshSectionItems(sectionKey, SECTION_ITEMS_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("section:$sectionKey").withLock {
            cache.freshSectionItems(sectionKey, SECTION_ITEMS_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                val all = fetchAllMediaPages(
                    SECTION_PAGE_SIZE, key = { it.ratingKey }, maxItems = MAX_SECTION_ITEMS, truncateAtLimit = true,
                    onPage = { onPage(mergeAndRegister(it.map { item -> item.toDomain(urlBuilder) })) },
                ) { start, size ->
                    val container = api.getSectionItems(sectionKey, start = start,
                        size = if (start == 0) SECTION_FIRST_PAGE_SIZE else size).container
                    MediaItemsPage(container.metadata, container.totalSize)
                }
                mergeAndRegister(all.map { it.toDomain(urlBuilder) })
            }.onSuccess { cache.setSectionItems(sectionKey, it) }
        }
    }

    override suspend fun getSectionPreview(sectionKey: String, sort: String, limit: Int): Result<List<MediaItem>> = cache.withGeneration {
        val boundedLimit = limit.coerceIn(1, HOME_PREVIEW_MAX)
        val cacheKey = "$sectionKey|$sort|$boundedLimit"
        cache.sectionPreview(cacheKey, PREVIEW_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("preview:$cacheKey").withLock {
            cache.sectionPreview(cacheKey, PREVIEW_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                mergeAndRegister(
                    api.getSectionItems(sectionKey, sort = sort, start = 0, size = boundedLimit)
                        .container.metadata.map { it.toDomain(urlBuilder) },
                )
            }.onSuccess { cache.setSectionPreview(cacheKey, it) }
        }
    }

    private fun recentEpisodesKey(sectionKey: String, limit: Int) = "$sectionKey|episodes|${limit.coerceIn(1, HOME_PREVIEW_MAX)}"

    override fun cachedRecentEpisodes(sectionKey: String, limit: Int): List<MediaItem>? =
        cache.sectionPreview(recentEpisodesKey(sectionKey, limit), Long.MAX_VALUE)

    override suspend fun getRecentEpisodes(sectionKey: String, limit: Int): Result<List<MediaItem>> = cache.withGeneration {
        val cacheKey = recentEpisodesKey(sectionKey, limit)
        cache.sectionPreview(cacheKey, PREVIEW_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("preview:$cacheKey").withLock {
            cache.sectionPreview(cacheKey, PREVIEW_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                mergeAndRegister(
                    api.getSectionItems(
                        sectionKey,
                        sort = "addedAt:desc",
                        start = 0,
                        size = limit.coerceIn(1, HOME_PREVIEW_MAX),
                        type = PLEX_TYPE_EPISODE,
                    ).container.metadata.map { it.toDomain(urlBuilder) },
                )
            }.onSuccess { cache.setSectionPreview(cacheKey, it) }
        }
    }

    private fun recommendationKey(sectionKey: String, recommendation: LibraryRecommendation, limit: Int) =
        "$sectionKey|rec:${recommendation.cacheKey}|${limit.coerceIn(1, HOME_PREVIEW_MAX)}"

    override fun cachedRecommendation(sectionKey: String, recommendation: LibraryRecommendation, limit: Int): List<MediaItem>? =
        cache.sectionPreview(recommendationKey(sectionKey, recommendation, limit), Long.MAX_VALUE)

    override suspend fun getRecommendation(sectionKey: String, recommendation: LibraryRecommendation, limit: Int): Result<List<MediaItem>> = cache.withGeneration {
        val cacheKey = recommendationKey(sectionKey, recommendation, limit)
        cache.sectionPreview(cacheKey, PREVIEW_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("preview:$cacheKey").withLock {
            cache.sectionPreview(cacheKey, PREVIEW_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult { fetchRecommendation(sectionKey, recommendation, limit.coerceIn(1, HOME_PREVIEW_MAX)) }
                .onSuccess { cache.setSectionPreview(cacheKey, it) }
        }
    }

    private suspend fun fetchRecommendation(sectionKey: String, recommendation: LibraryRecommendation, size: Int): List<MediaItem> {
        val shows = when (getLibrarySections().getOrThrow().firstOrNull { it.key == sectionKey }?.type) {
            "movie" -> false
            "show" -> true
            else -> return emptyList()
        }
        val metadata = when (recommendation) {
            // A show has no play date of its own that survives a rewatch; its episodes do.
            LibraryRecommendation.RecentlyWatched -> api.getSectionItems(
                sectionKey, sort = "lastViewedAt:desc", start = 0, size = size,
                type = PLEX_TYPE_EPISODE.takeIf { shows },
            )
            LibraryRecommendation.TopUnwatched -> api.getSectionItems(
                sectionKey, sort = RATING_SORT, start = 0, size = size,
                unwatched = 1.takeUnless { shows }, unwatchedLeaves = 1.takeIf { shows },
            )
            is LibraryRecommendation.TopGenre -> {
                val genreId = genreChoices(sectionKey).firstOrNull { it.second.equals(recommendation.genre, ignoreCase = true) }?.first
                    ?: return emptyList()
                api.getSectionItems(sectionKey, sort = RATING_SORT, start = 0, size = size, genreFilter = genreId)
            }
        }.container.metadata
        val items = mergeAndRegister(metadata.map { it.toDomain(urlBuilder) })
        return when (recommendation) {
            // The sort lists never played titles last instead of leaving them out.
            LibraryRecommendation.RecentlyWatched -> items.filter { it.lastViewedAt != null && it.isWatched }
            LibraryRecommendation.TopUnwatched -> items.filterNot { it.isWatched }
            is LibraryRecommendation.TopGenre -> items
        }
    }

    private suspend fun genreChoices(sectionKey: String): List<Pair<String, String>> {
        cache.genres(sectionKey, GENRES_TTL_MS)?.let { return it }
        return api.getSectionGenres(sectionKey).container.choices
            .map { it.key.substringAfterLast('/') to it.title }
            .filter { (id, title) -> id.isNotBlank() && title.isNotBlank() }
            .also { cache.setGenres(sectionKey, it) }
    }

    override suspend fun getGenres(sectionKey: String): Result<List<String>> = cache.withGeneration {
        suspendResult { genreChoices(sectionKey).map { it.second } }
    }

    private fun hubsKey(sectionKey: String, limit: Int) = "$sectionKey|hubs|${limit.coerceIn(1, HOME_PREVIEW_MAX)}"

    override fun cachedHubs(sectionKey: String, limit: Int): List<LibraryHub>? = cache.hubs(hubsKey(sectionKey, limit), Long.MAX_VALUE)

    override suspend fun getHubs(sectionKey: String, limit: Int): Result<List<LibraryHub>> = cache.withGeneration {
        val cacheKey = hubsKey(sectionKey, limit)
        cache.hubs(cacheKey, HUBS_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("hubs:$cacheKey").withLock {
            cache.hubs(cacheKey, HUBS_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                api.getSectionHubs(sectionKey, count = limit.coerceIn(1, HOME_PREVIEW_MAX)).container.hubs
                    .filter { hub -> hub.hubIdentifier.isNotBlank() && OWN_ROW_HUBS.none { it in hub.hubIdentifier.lowercase() } }
                    .map { hub ->
                        val items = hub.mediaItems().filter { it.type in HUB_ITEM_TYPES }.map { it.toDomain(urlBuilder) }
                        LibraryHub(hub.hubIdentifier, hub.title, mergeAndRegister(items))
                    }
                    .filter { it.title.isNotBlank() && it.items.isNotEmpty() }
            }.onSuccess { cache.setHubs(cacheKey, it) }
        }
    }

    /** Returns all direct children, including collection members, in server order. */
    override suspend fun getChildren(ratingKey: String): Result<List<MediaItem>> = cache.withGeneration {
        cache.children(ratingKey, CHILDREN_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("children:$ratingKey").withLock {
            cache.children(ratingKey, CHILDREN_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                val items = fetchAllMediaPages(FILTERED_PAGE_SIZE, key = { it.ratingKey }) { start, size ->
                    val container = api.getChildren(ratingKey, start = start, size = size).container
                    MediaItemsPage(container.metadata, container.totalSize)
                }
                mergeAndRegister(items.map { it.toDomain(urlBuilder) })
            }
                .onSuccess { cache.setChildren(ratingKey, it) }
        }
    }

    override suspend fun getSimilar(ratingKey: String): Result<List<MediaItem>> = cache.withGeneration {
        cache.similar(ratingKey, SIMILAR_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("similar:$ratingKey").withLock {
            cache.similar(ratingKey, SIMILAR_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                api.getSimilar(ratingKey).container.metadata
                    .map { it.toDomain(urlBuilder) }
                    .let(::mergeAndRegister)
                    .filterNot { ratingKey in it.allRatingKeys }
            }.onSuccess { cache.setSimilar(ratingKey, it) }
        }
    }

    override fun cachedSimilar(ratingKey: String): List<MediaItem>? = cache.similar(ratingKey, Long.MAX_VALUE)

    override suspend fun getServerTrailers(ratingKey: String): Result<List<MediaItem>> = cache.withGeneration {
        suspendResult {
            api.getExtras(ratingKey).container.metadata
                .filter(::isPlexTrailerExtra)
                .map { register(it.toDomain(urlBuilder)) }
        }
    }

    override suspend fun getNextEpisode(ratingKey: String): Result<MediaItem?> = cache.withGeneration {
        suspendResult {
            val current = cache.mediaItem(ratingKey)
                ?: getMetadataFast(ratingKey).getOrThrow()
                ?: return@suspendResult null
            if (current.type != "episode") return@suspendResult null
            val seasonKey = current.parentRatingKey ?: return@suspendResult null
            val episodes = getChildren(seasonKey).getOrThrow()
                .filter { it.type == "episode" }
                .sortedWith(compareBy<MediaItem> { it.index ?: Int.MAX_VALUE }.thenBy { it.ratingKey })
            val currentPosition = episodes.indexOfFirst { ratingKey in it.allRatingKeys }
            if (currentPosition >= 0) episodes.getOrNull(currentPosition + 1)
            else current.index?.let { idx -> episodes.firstOrNull { (it.index ?: Int.MIN_VALUE) > idx } }
        }
    }

    override suspend fun getNextTrack(ratingKey: String): Result<MediaItem?> = cache.withGeneration {
        suspendResult {
            val current = cache.mediaItem(ratingKey)
                ?: getMetadataFast(ratingKey).getOrThrow()
                ?: return@suspendResult null
            if (current.type !in setOf("track", "audio")) return@suspendResult null
            val albumKey = current.parentRatingKey ?: return@suspendResult null
            val tracks = getChildren(albumKey).getOrThrow()
                .filter { it.type in setOf("track", "audio") }
                .sortedWith(compareBy<MediaItem> { it.index ?: Int.MAX_VALUE }.thenBy { it.ratingKey })
            val currentPosition = tracks.indexOfFirst { ratingKey in it.allRatingKeys }
            if (currentPosition >= 0) tracks.getOrNull(currentPosition + 1)
            else current.index?.let { index -> tracks.firstOrNull { (it.index ?: Int.MIN_VALUE) > index } }
        }
    }

    override suspend fun getNextUnwatchedEpisode(ratingKey: String): Result<MediaItem?> = cache.withGeneration {
        suspendResult {
            val root = cache.mediaItem(ratingKey)
                ?: getMetadataFast(ratingKey).getOrThrow()
                ?: return@suspendResult null
            val episodes = when (root.type) {
                "show" -> {
                    val seasons = getChildren(ratingKey).getOrDefault(emptyList())
                        .filter { it.type == "season" }
                        .sortedBy { it.index ?: Int.MAX_VALUE }
                    coroutineScope {
                        seasons.map { season -> async { getChildren(season.ratingKey).getOrDefault(emptyList()) } }
                            .awaitAll().flatten()
                    }
                }
                "season" -> getChildren(ratingKey).getOrDefault(emptyList())
                else -> emptyList()
            }.filter { it.type == "episode" }

            EpisodeOrdering.nextUnwatched(episodes)
        }
    }

    override suspend fun search(sectionKeys: List<String>, query: String): Result<List<MediaItem>> = cache.withGeneration {
        val normalized = query.trim().lowercase()
        val cacheKey = sectionKeys.sorted().joinToString(",") + "|" + normalized
        cache.search(cacheKey, SEARCH_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("search:$cacheKey").withLock {
            cache.search(cacheKey, SEARCH_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                coroutineScope {
                    sectionKeys.map { sectionKey ->
                        async {
                            suspendResult {
                                api.getSectionItems(sectionKey, titleFilter = query, start = 0, size = SEARCH_LIMIT_PER_SECTION)
                                    .container.metadata
                            }
                        }
                    }.awaitAll().successfulOrThrow()
                }.map { it.toDomain(urlBuilder) }.let(::mergeAndRegister)
            }.onSuccess { cache.setSearch(cacheKey, it) }
        }
    }

    override suspend fun getItemsByActor(actorId: String): Result<List<MediaItem>> =
        getFilteredItems(
            cacheKey = "actor:$actorId",
            localPredicate = { item -> item.cast.any { it.id == actorId } },
            remoteFetch = { sectionKey -> fetchFilteredPages(sectionKey, actorId = actorId.toLongOrNull()) },
            // Section list responses carry only the first few Role tags, so a cached section would
            // silently miss titles where the actor is not top-billed. Ask the server instead, and
            // only in sections that have actors (music/photo sections may ignore the filter).
            useSectionCache = false,
            sectionTypes = ACTOR_SECTION_TYPES,
        )

    override suspend fun getItemsByCollection(collectionId: String): Result<List<MediaItem>> =
        getFilteredItems(
            cacheKey = "collection:$collectionId",
            localPredicate = { item -> item.collections.any { it.id == collectionId } },
            remoteFetch = { sectionKey -> fetchFilteredPages(sectionKey, collectionId = collectionId.toLongOrNull()) },
            // Lightweight section entries can omit collection memberships. Query every page.
            useSectionCache = false,
        )

    private suspend fun getFilteredItems(
        cacheKey: String,
        localPredicate: (MediaItem) -> Boolean,
        remoteFetch: suspend (String) -> List<com.ambient.tv.data.remote.dto.MetadataDto>,
        useSectionCache: Boolean = true,
        sectionTypes: Set<String>? = null,
    ): Result<List<MediaItem>> = cache.withGeneration {
        cache.filtered(cacheKey, FILTERED_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("filtered:$cacheKey").withLock {
            cache.filtered(cacheKey, FILTERED_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                val sections = getLibrarySections().getOrThrow()
                    .filter { sectionTypes == null || it.type in sectionTypes }
                coroutineScope {
                    sections.map { section ->
                        async {
                            val cachedSection = if (useSectionCache) cache.sectionItems(section.key) else null
                            if (cachedSection != null) Result.success(cachedSection.filter(localPredicate))
                            else suspendResult { remoteFetch(section.key).map { it.toDomain(urlBuilder) } }
                        }
                    }.awaitAll().successfulOrThrow()
                }.let(::mergeAndRegister)
            }.onSuccess { cache.setFiltered(cacheKey, it) }
        }
    }

    private suspend fun fetchFilteredPages(
        sectionKey: String,
        actorId: Long? = null,
        collectionId: Long? = null,
    ): List<com.ambient.tv.data.remote.dto.MetadataDto> {
        return fetchAllMediaPages(FILTERED_PAGE_SIZE, key = { it.ratingKey }, maxItems = MAX_FILTERED_ITEMS) { start, size ->
            val container = api.getSectionItems(sectionKey = sectionKey, actorFilter = actorId,
                collectionFilter = collectionId, start = start, size = size).container
            MediaItemsPage(container.metadata, container.totalSize)
        }
    }

    /** [state] must be one of "playing", "paused" or "stopped" (Plex's `/:/progress` vocabulary). */
    override suspend fun reportProgress(ratingKey: String, positionMs: Long, state: String): Result<Unit> =
        cache.withGeneration {
            if (state !in VALID_PROGRESS_STATES) {
                return@withGeneration Result.failure(IllegalArgumentException("Unsupported Plex progress state: $state"))
            }
            forEachLinked(ratingKey, { key ->
                if (key == ratingKey) {
                    reportPlexPlayback(api, key, positionMs, state, cachedMediaItem(key)?.durationMs)
                } else {
                    api.reportProgress(ratingKey = key, timeMs = positionMs, state = state)
                }
            }) { done ->
                cache.patchProgress(done, positionMs)
                // On Deck membership changes when playback pauses or stops. Periodic "playing"
                // ticks keep the cached row so Home does not refetch it every few seconds.
                if (state != "playing") cache.setOnDeck(null)
            }
        }

    override suspend fun markWatched(ratingKey: String): Result<Unit> = cache.withGeneration {
        forEachLinked(ratingKey, { key -> api.markWatched(ratingKey = key) }) { done ->
            cache.patchWatched(done)
            cache.setOnDeck(null)
        }
    }

    override suspend fun markUnwatched(ratingKey: String): Result<Unit> = cache.withGeneration {
        forEachLinked(ratingKey, { key -> api.markUnwatched(ratingKey = key) }) { done ->
            cache.patchUnwatched(done)
            cache.setOnDeck(null)
        }
    }

    override suspend fun removeFromContinueWatching(ratingKey: String): Result<Unit> = cache.withGeneration {
        forEachLinked(ratingKey, { key -> api.removeFromContinueWatching(key) }) { done ->
            cache.removeFromOnDeck(done)
        }
    }

    override suspend fun getAudioCompatibleStreamUrl(
        ratingKey: String,
        mediaIndex: Int,
        startPositionMs: Long,
        audioCodec: String,
        audioStreamId: Long?,
    ): Result<String> = cache.withGeneration {
        suspendResult {
            val (audioId, forcedSubtitleId) = preferredServerTracks(ratingKey, mediaIndex, audioStreamId)
            requireNotNull(urlBuilder.audioFallbackUrl(ratingKey, mediaIndex, startPositionMs, audioId, forcedSubtitleId, audioCodec)) {
                "Server address is not configured"
            }
        }
    }

    override suspend fun getVideoCompatibleStreamUrl(
        ratingKey: String,
        mediaIndex: Int,
        startPositionMs: Long,
    ): Result<String> = cache.withGeneration {
        suspendResult {
            val (audioId, forcedSubtitleId) = preferredServerTracks(ratingKey, mediaIndex)
            requireNotNull(urlBuilder.videoFallbackUrl(ratingKey, mediaIndex, startPositionMs, audioId, forcedSubtitleId)) {
                "Server address is not configured"
            }
        }
    }

    private suspend fun preferredServerTracks(ratingKey: String, mediaIndex: Int, audioStreamId: Long? = null): Pair<Long?, Long?> {
        val item = cachedMediaItem(ratingKey) ?: getMetadataFast(ratingKey).getOrNull()
        val version = item?.mediaVersions?.getOrNull(mediaIndex)
        val requestedAudioLanguage = preferences.playbackAudioLanguage
        val audio = com.ambient.tv.data.local.audioTrackForConversion(
            version?.tracks.orEmpty(), requestedAudioLanguage, preferences.audioSettings.tracks, audioStreamId,
        )
        val activeAudioLanguage = audio?.languageCode ?: audio?.language ?: requestedAudioLanguage
        val forcedSubtitle = com.ambient.tv.data.local.SubtitleSelectionPolicy.chooseMediaTrack(
            version?.tracks.orEmpty(), preferences.subtitlePreferences, activeAudioLanguage, LanguagePreferences.systemLanguageTag(),
        )
        return audio?.id to forcedSubtitle?.id
    }

    private companion object {
        const val SECTION_FIRST_PAGE_SIZE = 100
        const val SECTION_PAGE_SIZE = 500
        const val MAX_SECTION_ITEMS = 50_000
        const val KEYED_MUTEX_COUNT = 64
        const val HOME_PREVIEW_MAX = 300
        const val PLEX_TYPE_EPISODE = 4
        // Which rating a library carries depends on its metadata agent; the second one breaks ties.
        const val RATING_SORT = "audienceRating:desc,rating:desc"
        // Hubs that repeat rows Home already builds itself.
        val OWN_ROW_HUBS = listOf("recentlyadded", "ondeck", "inprogress", "continuewatching", "recentlyviewed", "recentlyreleased", "topunwatched")
        val HUB_ITEM_TYPES = setOf("movie", "show", "episode")
        const val GENRES_TTL_MS = 10 * 60_000L
        const val HUBS_TTL_MS = 5 * 60_000L
        const val SEARCH_LIMIT_PER_SECTION = 100
        const val FILTERED_PAGE_SIZE = 250
        const val MAX_FILTERED_ITEMS = 10_000
        const val DUPLICATE_LOOKUP_LIMIT_PER_SECTION = 50
        const val LIBRARY_SECTIONS_TTL_MS = 5 * 60_000L
        const val ON_DECK_TTL_MS = 30_000L
        const val SECTION_ITEMS_TTL_MS = 2 * 60_000L
        const val PREVIEW_TTL_MS = 60_000L
        const val CHILDREN_TTL_MS = 2 * 60_000L
        const val SIMILAR_TTL_MS = 5 * 60_000L
        const val SEARCH_TTL_MS = 45_000L
        const val FILTERED_TTL_MS = 2 * 60_000L
        /** Plex library types whose items carry Role (actor) tags. */
        val ACTOR_SECTION_TYPES = setOf("movie", "show")
        val VALID_PROGRESS_STATES = setOf("playing", "paused", "stopped")
    }

}

/** Plex marks trailer extras with subtype "trailer" and extraType 1. */
internal fun isPlexTrailerExtra(extra: com.ambient.tv.data.remote.dto.MetadataDto): Boolean =
    extra.subtype.equals("trailer", ignoreCase = true) || extra.extraType == 1
