package com.ambient.tv.data.emby

import com.ambient.tv.util.suspendResult
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.data.repository.continueWatching
import com.ambient.tv.data.repository.MediaItemsPage
import com.ambient.tv.data.repository.fetchAllMediaPages
import com.ambient.tv.domain.model.LibraryRecommendation
import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaTag
import com.ambient.tv.domain.model.MediaTrackType
import com.ambient.tv.domain.model.PlexServerIdentity
import com.ambient.tv.domain.model.ThemeMusic
import com.ambient.tv.domain.repository.MediaRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class EmbyRepository @Inject constructor(
    private val api: EmbyApiService,
    private val urls: EmbyUrlBuilder,
    private val cache: EmbyMediaCache,
    private val preferences: AppPreferences,
    disk: com.ambient.tv.data.browse.BrowseDiskStore,
    scopes: com.ambient.tv.data.browse.BrowseScopes,
) : MediaRepository {
    init { cache.configurePersistence(disk) { scopes.forProvider("emby") } }
    override suspend fun restoreCache() = cache.restore()
    override fun refreshCaches() = cache.expire()
    override fun cachedSectionPreview(sectionKey: String, sort: String, limit: Int) = cache.sectionPreview("$sectionKey|$sort|$limit", Long.MAX_VALUE)
    override fun cachedChildren(ratingKey: String) = cache.children(ratingKey, Long.MAX_VALUE)
    private data class Session(
        val mediaSourceId: String?,
        val playSessionId: String?,
        var started: Boolean = false,
        val playMethod: String = PLAY_METHOD_DIRECT,
    )
    private val sessions = ConcurrentHashMap<String, Session>()
    private val libraryMutex = Mutex()
    private val onDeckMutex = Mutex()
    private val keyedMutexes = Array(KEYED_MUTEX_COUNT) { Mutex() }
    private val collectionMutex = Mutex()
    @Volatile private var collectionMemberships: Map<String, List<MediaTag>>? = null
    @Volatile private var collectionMembershipsAt = 0L

    private fun freshCollectionMemberships(): Map<String, List<MediaTag>>? =
        collectionMemberships?.takeIf { System.nanoTime() / 1_000_000L - collectionMembershipsAt < COLLECTIONS_TTL_MS }

    private fun mutexFor(key: String): Mutex = keyedMutexes[(key.hashCode() and Int.MAX_VALUE) % keyedMutexes.size]
    private fun userId(): String = requireNotNull(preferences.embyUserId) { "Emby user is not configured" }
    private fun raw(id: String) = EmbyBackend.raw(id)
    private fun map(items: List<EmbyItem>) = cache.mergeAndRegister(items.map { it.toDomain(urls) })
    private fun mediaTypesForSection(sectionKey: String): String = when (
        cache.librarySections()?.firstOrNull { it.key == sectionKey }?.type
    ) {
        // Music-video libraries are typed "movie"; their entries are MusicVideo items.
        "movie" -> "Movie,MusicVideo"
        "show" -> "Series"
        "artist" -> "MusicArtist"
        "collection" -> "BoxSet"
        "track" -> "Audio"
        else -> MEDIA_TYPES
    }

    override fun cachedLibrarySections() = cache.librarySections()
    override fun cachedOnDeck() = cache.onDeck()
    override fun cachedSectionItems(sectionKey: String) = cache.sectionItems(sectionKey) ?: cache.sectionPreview("$sectionKey|titleSort:asc|100", Long.MAX_VALUE)
    override fun cachedMediaItem(ratingKey: String) = cache.mediaItem(ratingKey)
    override fun invalidateCaches() { cache.clear(); collectionMemberships = null; sessions.clear() }
    override fun invalidateSectionCache(sectionKey: String) {
        cache.clearSection(sectionKey)
        collectionMemberships = null
    }

    override suspend fun getIdentity(): Result<PlexServerIdentity> = cache.withGeneration {
        suspendResult {
            api.getPublicSystemInfo().let { PlexServerIdentity(machineIdentifier = it.id, version = it.version, claimed = true) }
        }
    }

    override suspend fun getLibrarySections(): Result<List<LibrarySection>> = cache.withGeneration {
        cache.freshLibrarySections(LIBRARY_SECTIONS_TTL_MS)?.let { return@withGeneration Result.success(it) }
        libraryMutex.withLock {
            cache.freshLibrarySections(LIBRARY_SECTIONS_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                api.getUserViews(userId()).items
                    .filter { it.collectionType?.lowercase() in SUPPORTED_COLLECTION_TYPES }
                    .map { it.toLibrarySection(urls) }
            }.onSuccess(cache::setLibrarySections)
        }
    }

    override suspend fun getOnDeck(): Result<List<MediaItem>> = cache.withGeneration {
        cache.freshOnDeck(ON_DECK_TTL_MS)?.let { return@withGeneration Result.success(it) }
        onDeckMutex.withLock {
            cache.freshOnDeck(ON_DECK_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                coroutineScope {
                    val resume = async { api.getResumeItems(userId(), fields = HOME_ITEM_FIELDS).items }
                    // Both are extras: a server that refuses them still has its resumable titles.
                    val nextUp = async { suspendResult { api.getNextUp(userId(), fields = HOME_ITEM_FIELDS).items }.getOrDefault(emptyList()) }
                    val played = async {
                        suspendResult {
                            api.getItems(
                                userId = userId(), recursive = true, includeItemTypes = "Episode",
                                fields = NEXT_EPISODE_FIELDS, sortBy = "DatePlayed", sortOrder = "Descending",
                                limit = PLAYED_EPISODE_SCAN_LIMIT, enableImages = false,
                                enableTotalRecordCount = false, isPlayed = true,
                            ).items.map { it.toDomain(urls) }
                        }.getOrDefault(emptyList())
                    }
                    continueWatching(map(resume.await()), map(nextUp.await()), played.await())
                }
            }.onSuccess(cache::setOnDeck)
        }
    }

    override suspend fun getPlaybackTimeline(ratingKey: String, mediaIndex: Int) = suspendResult {
        val item = api.getItem(raw(ratingKey), userId())
        val sourceId = item.mediaSources.getOrNull(mediaIndex)?.id
        val trick = sourceId?.let { item.trickplay[it] }?.values?.filterNotNull()?.filter { it.width in 1..320 && it.intervalMs > 0 }
            ?.maxByOrNull { it.width }
        val preview = if (trick != null) com.ambient.tv.domain.model.TimelinePreview.Sprite(
            trick.width, trick.height, trick.columns, trick.rows, trick.intervalMs, trick.count,
            urlTemplate = urls.trickplayUrl(ratingKey, sourceId, trick.width),
        ) else null
        com.ambient.tv.domain.model.PlaybackTimeline(
            item.chapters.mapIndexed { index, chapter -> com.ambient.tv.domain.model.VideoChapter(
                if (chapter.startTicks < 0) -1 else chapter.startTicks / 10_000L, chapter.name,
                chapter.imageTag?.let { urls.imageUrl(ratingKey, "Chapter", index, it, 320) },
            ) }.filterIndexed { index, _ -> item.chapters[index].markerType.let { it == null || it == "Chapter" } }, preview,
            segments = com.ambient.tv.domain.model.playbackSegmentsFromMarkers(item.chapters.map {
                it.markerType to if (it.startTicks < 0) -1L else it.startTicks / 10_000L
            }),
        ).normalized((item.runTimeTicks ?: 0) / 10_000L)
    }

    override suspend fun getMetadata(ratingKey: String): Result<MediaItem?> = getMetadataFast(ratingKey)

    override suspend fun getMetadataFast(ratingKey: String): Result<MediaItem?> = cache.withGeneration {
        // Collection membership requires one request per BoxSet on Emby. It must not delay
        // opening the detail page; collections are enriched only during the full library load.
        suspendResult { cache.register(api.getItem(raw(ratingKey), userId()).toDomain(urls)) }
    }

    override suspend fun getSectionItems(sectionKey: String, onPage: suspend (List<MediaItem>) -> Unit): Result<List<MediaItem>> =
        cache.withGeneration {
            cache.freshSectionItems(sectionKey, SECTION_ITEMS_TTL_MS)?.let { return@withGeneration Result.success(it) }
            mutexFor("section:$sectionKey").withLock {
                cache.freshSectionItems(sectionKey, SECTION_ITEMS_TTL_MS)?.let { return@withLock Result.success(it) }
                suspendResult {
                    val all = fetchAllMediaPages(
                        PAGE_SIZE, key = { it.id }, maxItems = MAX_ITEMS, truncateAtLimit = true,
                        onPage = { onPage(map(it)) },
                    ) { start, size ->
                        val response = api.getItems(
                            userId = userId(), parentId = raw(sectionKey), recursive = true,
                            includeItemTypes = mediaTypesForSection(sectionKey), fields = LIBRARY_ITEM_FIELDS,
                            sortBy = "SortName", sortOrder = "Ascending", startIndex = start, limit = size,
                        )
                        MediaItemsPage(response.items, response.totalRecordCount)
                    }
                    map(all).also { cache.setSectionItems(sectionKey, it) }
                }
            }
        }

    override suspend fun enrichCollections(items: List<MediaItem>): Result<List<MediaItem>> = cache.withGeneration {
        suspendResult { cache.mergeAndRegister(enrichCollectionsInternal(items)) }
    }

    override suspend fun getSectionPreview(sectionKey: String, sort: String, limit: Int): Result<List<MediaItem>> = cache.withGeneration {
        val boundedLimit = limit.coerceIn(1, 100)
        val cacheKey = "$sectionKey|$sort|$boundedLimit"
        cache.sectionPreview(cacheKey, PREVIEW_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("preview:$cacheKey").withLock {
            cache.sectionPreview(cacheKey, PREVIEW_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                val sortField = when (sort.substringBefore(':')) {
                    "originallyAvailableAt" -> "PremiereDate"
                    "titleSort" -> "SortName"
                    else -> "DateCreated"
                }
                val order = if (sort.endsWith(":asc")) "Ascending" else "Descending"
                // Home previews need to become visible immediately. Full collection enrichment
                // can fan out into many requests and belongs to the progressive library path.
                map(
                    api.getItems(
                        userId = userId(),
                        parentId = raw(sectionKey),
                        recursive = true,
                        includeItemTypes = mediaTypesForSection(sectionKey),
                        fields = HOME_ITEM_FIELDS,
                        sortBy = sortField,
                        sortOrder = order,
                        limit = boundedLimit,
                        enableTotalRecordCount = false,
                    ).items,
                )
            }.onSuccess { cache.setSectionPreview(cacheKey, it) }
        }
    }

    private fun recentEpisodesKey(sectionKey: String, limit: Int) = "$sectionKey|episodes|${limit.coerceIn(1, 100)}"

    override fun cachedRecentEpisodes(sectionKey: String, limit: Int): List<MediaItem>? =
        cache.sectionPreview(recentEpisodesKey(sectionKey, limit), Long.MAX_VALUE)

    override suspend fun getRecentEpisodes(sectionKey: String, limit: Int): Result<List<MediaItem>> = cache.withGeneration {
        val cacheKey = recentEpisodesKey(sectionKey, limit)
        cache.sectionPreview(cacheKey, PREVIEW_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("preview:$cacheKey").withLock {
            cache.sectionPreview(cacheKey, PREVIEW_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                map(
                    api.getItems(
                        userId = userId(),
                        parentId = raw(sectionKey),
                        recursive = true,
                        includeItemTypes = "Episode",
                        fields = HOME_ITEM_FIELDS,
                        sortBy = "DateCreated",
                        sortOrder = "Descending",
                        limit = limit.coerceIn(1, 100),
                        enableTotalRecordCount = false,
                    ).items,
                )
            }.onSuccess { cache.setSectionPreview(cacheKey, it) }
        }
    }

    private fun recommendationKey(sectionKey: String, recommendation: LibraryRecommendation, limit: Int) =
        "$sectionKey|rec:${recommendation.cacheKey}|${limit.coerceIn(1, 100)}"

    override fun cachedRecommendation(sectionKey: String, recommendation: LibraryRecommendation, limit: Int): List<MediaItem>? =
        cache.sectionPreview(recommendationKey(sectionKey, recommendation, limit), Long.MAX_VALUE)

    override suspend fun getRecommendation(sectionKey: String, recommendation: LibraryRecommendation, limit: Int): Result<List<MediaItem>> = cache.withGeneration {
        val cacheKey = recommendationKey(sectionKey, recommendation, limit)
        cache.sectionPreview(cacheKey, PREVIEW_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("preview:$cacheKey").withLock {
            cache.sectionPreview(cacheKey, PREVIEW_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                // A series has no play date of its own that survives a rewatch; its episodes do.
                val watched = recommendation == LibraryRecommendation.RecentlyWatched
                val itemTypes = when (getLibrarySections().getOrThrow().firstOrNull { it.key == sectionKey }?.type) {
                    "movie" -> "Movie"
                    "show" -> if (watched) "Episode" else "Series"
                    "mixed" -> if (watched) "Movie,Episode" else "Movie,Series"
                    else -> return@suspendResult emptyList()
                }
                val items = map(
                    api.getItems(
                        userId = userId(),
                        parentId = raw(sectionKey),
                        recursive = true,
                        includeItemTypes = itemTypes,
                        fields = HOME_ITEM_FIELDS,
                        sortBy = if (watched) "DatePlayed" else "CommunityRating",
                        sortOrder = "Descending",
                        limit = limit.coerceIn(1, 100),
                        enableTotalRecordCount = false,
                        isPlayed = when (recommendation) {
                            LibraryRecommendation.RecentlyWatched -> true
                            LibraryRecommendation.TopUnwatched -> false
                            is LibraryRecommendation.TopGenre -> null
                        },
                        genres = (recommendation as? LibraryRecommendation.TopGenre)?.genre,
                    ).items,
                )
                if (recommendation == LibraryRecommendation.TopUnwatched) items.filterNot { it.isWatched } else items
            }.onSuccess { cache.setSectionPreview(cacheKey, it) }
        }
    }

    override suspend fun getGenres(sectionKey: String): Result<List<String>> = cache.withGeneration {
        cache.genres(sectionKey, GENRES_TTL_MS)?.let { genres -> return@withGeneration Result.success(genres.map { it.second }) }
        suspendResult {
            api.getGenres(userId(), raw(sectionKey)).items.map { it.name }.filter(String::isNotBlank)
                .also { names -> cache.setGenres(sectionKey, names.map { it to it }) }
        }
    }

    override suspend fun getChildren(ratingKey: String): Result<List<MediaItem>> = cache.withGeneration {
        cache.children(ratingKey, CHILDREN_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("children:$ratingKey").withLock {
            cache.children(ratingKey, CHILDREN_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                val items = fetchAllMediaPages(PAGE_SIZE, key = { it.id }) { start, size ->
                    val response = api.getItems(
                        userId = userId(), parentId = raw(ratingKey), recursive = false,
                        fields = EmbyApiService.ITEM_FIELDS, sortBy = "SortName", sortOrder = "Ascending",
                        startIndex = start, limit = size,
                    )
                    MediaItemsPage(response.items, response.totalRecordCount)
                }
                map(items)
            }.onSuccess { cache.setChildren(ratingKey, it) }
        }
    }

    override suspend fun getSimilar(ratingKey: String): Result<List<MediaItem>> = cache.withGeneration {
        cache.similar(ratingKey, SIMILAR_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("similar:$ratingKey").withLock {
            cache.similar(ratingKey, SIMILAR_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                map(api.getSimilar(raw(ratingKey), userId(), fields = HOME_ITEM_FIELDS).items)
                    .filterNot { it.ratingKey == ratingKey }
            }.onSuccess { cache.setSimilar(ratingKey, it) }
        }
    }

    override fun cachedSimilar(ratingKey: String): List<MediaItem>? = cache.similar(ratingKey, Long.MAX_VALUE)

    override suspend fun getServerTrailers(ratingKey: String): Result<List<MediaItem>> = cache.withGeneration {
        suspendResult { map(api.getLocalTrailers(raw(ratingKey), userId())) }
    }

    override suspend fun getAlternativeBackdrop(ratingKey: String): Result<String?> = cache.withGeneration {
        // Image providers may be limited to administrators: a refusal simply means "none".
        suspendResult { api.getRemoteImages(raw(ratingKey)).images.firstNotNullOfOrNull { it.url?.takeIf(String::isNotBlank) } }
    }

    override suspend fun getThemeMusic(ratingKey: String): Result<ThemeMusic?> = cache.withGeneration {
        suspendResult {
            api.getThemeSongs(raw(ratingKey), userId()).items.firstOrNull()?.let { ThemeMusic(EmbyBackend.id(it.id), urls.themeSongUrl(it.id)) }
        }
    }

    override suspend fun getNextEpisode(ratingKey: String): Result<MediaItem?> = nextSibling(ratingKey, "episode")
    override suspend fun getNextTrack(ratingKey: String): Result<MediaItem?> = nextSibling(ratingKey, "track")

    private suspend fun nextSibling(ratingKey: String, type: String): Result<MediaItem?> = suspendResult {
        val current = cachedMediaItem(ratingKey) ?: getMetadataFast(ratingKey).getOrThrow() ?: return@suspendResult null
        val parent = current.parentRatingKey ?: return@suspendResult null
        val siblings = getChildren(parent).getOrThrow().filter { it.type == type }
            .sortedWith(compareBy<MediaItem> { it.index ?: Int.MAX_VALUE }.thenBy { it.ratingKey })
        val position = siblings.indexOfFirst { it.ratingKey == ratingKey }
        if (position >= 0) siblings.getOrNull(position + 1) else null
    }

    override suspend fun getNextUnwatchedEpisode(ratingKey: String): Result<MediaItem?> = suspendResult {
        val root = cachedMediaItem(ratingKey) ?: getMetadataFast(ratingKey).getOrThrow() ?: return@suspendResult null
        if (root.type !in setOf("show", "season")) return@suspendResult null
        // Let Emby filter played episodes server-side and return a deliberately tiny
        // projection. Walking every season previously produced one full MediaSources/People
        // payload per season. Only the selected candidate needs full playback metadata.
        val episodes = map(
            api.getItems(
                userId = userId(), parentId = raw(ratingKey), recursive = true,
                includeItemTypes = "Episode", fields = NEXT_EPISODE_FIELDS,
                sortBy = "SortName", sortOrder = "Ascending", limit = NEXT_EPISODE_SCAN_LIMIT,
                enableImages = false, enableTotalRecordCount = false, isPlayed = false,
            ).items,
        )
        // The server already filtered played episodes; specials (season 0) come last.
        val candidate = com.ambient.tv.data.repository.EpisodeOrdering.nextUnwatched(episodes, assumeUnwatched = true)
            ?: return@suspendResult null
        getMetadataFast(candidate.ratingKey).getOrThrow() ?: candidate
    }

    override suspend fun search(sectionKeys: List<String>, query: String): Result<List<MediaItem>> = cache.withGeneration {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank() || sectionKeys.isEmpty()) return@withGeneration Result.success(emptyList())
        val cacheKey = sectionKeys.sorted().joinToString(",") + "|" + normalized
        cache.search(cacheKey, SEARCH_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("search:$cacheKey").withLock {
            cache.search(cacheKey, SEARCH_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                sectionKeys.chunked(SEARCH_CONCURRENCY).flatMap { chunk ->
                    coroutineScope {
                        chunk.map { sectionKey ->
                            async {
                                api.getItems(
                                    userId = userId(), parentId = raw(sectionKey), recursive = true,
                                    includeItemTypes = mediaTypesForSection(sectionKey), fields = HOME_ITEM_FIELDS,
                                    searchTerm = query.trim(), limit = SEARCH_LIMIT_PER_SECTION,
                                    enableTotalRecordCount = false,
                                ).items
                            }
                        }.awaitAll().flatten()
                    }
                }.let(::map).take(SEARCH_RESULT_LIMIT)
            }.onSuccess { cache.setSearch(cacheKey, it) }
        }
    }

    override suspend fun getItemsByActor(actorId: String): Result<List<MediaItem>> = cache.withGeneration {
        val cacheKey = "actor:$actorId"
        cache.filtered(cacheKey, FILTERED_TTL_MS)?.let { return@withGeneration Result.success(it) }
        mutexFor("filtered:$cacheKey").withLock {
            cache.filtered(cacheKey, FILTERED_TTL_MS)?.let { return@withLock Result.success(it) }
            suspendResult {
                map(api.getItems(userId(), recursive = true, includeItemTypes = MEDIA_TYPES, fields = HOME_ITEM_FIELDS, personIds = raw(actorId), limit = 500).items)
            }.onSuccess { cache.setFiltered(cacheKey, it) }
        }
    }

    /** Collection IDs are container IDs; list members without flattening series or albums. */
    override suspend fun getItemsByCollection(collectionId: String): Result<List<MediaItem>> =
        getChildren(collectionId)

    override fun preparePlayback(ratingKey: String, mediaIndex: Int) {
        val sourceId = cachedMediaItem(ratingKey)?.mediaVersions?.getOrNull(mediaIndex)?.sourceId
        sessions[ratingKey] = Session(sourceId, null)
    }

    override suspend fun reportProgress(ratingKey: String, positionMs: Long, state: String): Result<Unit> = cache.withGeneration {
        suspendResult {
            val rawId = raw(ratingKey)
            val session = sessions.getOrPut(ratingKey) {
                Session(cachedMediaItem(ratingKey)?.mediaVersions?.firstOrNull()?.sourceId, null)
            }
            val body = EmbyPlaybackProgress(
                itemId = rawId, mediaSourceId = session.mediaSourceId, playSessionId = session.playSessionId, playMethod = session.playMethod,
                positionTicks = positionMs.coerceAtLeast(0) * 10_000L, isPaused = state == "paused",
            )
            when (state) {
                "stopped" -> try {
                    api.reportPlaybackStopped(body)
                } finally {
                    sessions.remove(ratingKey, session)
                }
                else -> {
                    if (!session.started) { api.reportPlaybackStart(body); session.started = true }
                    api.reportPlaybackProgress(body)
                }
            }
            cache.patchProgress(setOf(ratingKey), positionMs)
            // Periodic "playing" ticks keep the cached Continue Watching row.
            if (state != "playing") cache.setOnDeck(null)
        }
    }

    override suspend fun markWatched(ratingKey: String): Result<Unit> = cache.withGeneration {
        suspendResult { api.markPlayed(userId(), raw(ratingKey)); cache.patchWatched(setOf(ratingKey)); cache.setOnDeck(null) }
    }

    override suspend fun markUnwatched(ratingKey: String): Result<Unit> = cache.withGeneration {
        suspendResult { api.markUnplayed(userId(), raw(ratingKey)); cache.patchUnwatched(setOf(ratingKey)); cache.setOnDeck(null) }
    }

    override suspend fun removeFromContinueWatching(ratingKey: String): Result<Unit> = cache.withGeneration {
        suspendResult {
            val item = cachedMediaItem(ratingKey) ?: getMetadataFast(ratingKey).getOrThrow()
            api.updateUserData(userId(), raw(ratingKey), EmbyUserDataUpdate(0, item?.isWatched == true, item?.viewCount ?: 0))
            cache.patchProgress(setOf(ratingKey), 0L)
            cache.removeFromOnDeck(setOf(ratingKey))
        }
    }

    override suspend fun getAudioCompatibleStreamUrl(ratingKey: String, mediaIndex: Int, startPositionMs: Long, audioCodec: String, audioStreamId: Long?, audioChannels: Int): Result<String> =
        compatibilityUrl(ratingKey, mediaIndex, startPositionMs, video = false, audioCodec = audioCodec, audioStreamId = audioStreamId, audioChannels = audioChannels)

    override suspend fun getVideoCompatibleStreamUrl(ratingKey: String, mediaIndex: Int, startPositionMs: Long): Result<String> =
        compatibilityUrl(ratingKey, mediaIndex, startPositionMs, video = true)

    private suspend fun compatibilityUrl(ratingKey: String, mediaIndex: Int, startPositionMs: Long, video: Boolean, audioCodec: String = "aac", audioStreamId: Long? = null, audioChannels: Int = 2): Result<String> = cache.withGeneration {
        suspendResult {
            val item = cachedMediaItem(ratingKey) ?: getMetadataFast(ratingKey).getOrThrow() ?: error("Emby item not found")
            val source = item.mediaVersions.getOrNull(mediaIndex)
            val requestedAudioLanguage = preferences.playbackAudioLanguage
            val preferredAudio = com.ambient.tv.data.local.audioTrackForConversion(
                source?.tracks.orEmpty(), requestedAudioLanguage, preferences.audioSettings.tracks, audioStreamId,
            )
            val activeAudioLanguage = preferredAudio?.languageCode ?: preferredAudio?.language ?: requestedAudioLanguage
            val forcedSubtitle = com.ambient.tv.data.local.SubtitleSelectionPolicy.chooseMediaTrack(
                source?.tracks.orEmpty(), preferences.subtitlePreferences, activeAudioLanguage, LanguagePreferences.systemLanguageTag(),
            )
            val rawId = raw(ratingKey)
            val info = api.getPlaybackInfo(
                rawId,
                EmbyPlaybackInfoRequest(
                    userId = userId(),
                    startTimeTicks = startPositionMs * 10_000L,
                    mediaSourceId = source?.sourceId,
                    audioStreamIndex = preferredAudio?.id?.toInt(),
                    subtitleStreamIndex = forcedSubtitle?.id?.toInt() ?: -1,
                    allowVideoStreamCopy = !video,
                    allowAudioStreamCopy = false,
                    enableDirectPlay = false,
                    enableDirectStream = false,
                ),
            )
            val remoteSource = info.mediaSources.firstOrNull { it.id == source?.sourceId }
                ?: info.mediaSources.getOrNull(mediaIndex) ?: info.mediaSources.firstOrNull()
            sessions[ratingKey] = Session(remoteSource?.id, info.playSessionId, playMethod = PLAY_METHOD_TRANSCODE)
            if (!video) urls.compatibilityUrl(ratingKey, remoteSource?.id ?: source?.sourceId,
                startPositionMs, false, preferredAudio?.id?.toInt(), forcedSubtitle?.id?.toInt() ?: -1,
                audioCodec, info.playSessionId, audioChannels)
            else remoteSource?.transcodingUrl?.let(urls::absolute)
                ?: urls.compatibilityUrl(ratingKey, remoteSource?.id, startPositionMs, true,
                    preferredAudio?.id?.toInt(), forcedSubtitle?.id?.toInt() ?: -1)

        }
    }

    private suspend fun enrichCollectionsInternal(items: List<MediaItem>): List<MediaItem> {
        if (items.none { it.type == "movie" || it.type == "show" }) return items
        val memberships = freshCollectionMemberships() ?: collectionMutex.withLock {
            freshCollectionMemberships() ?: run {
                val boxSets = fetchAllMediaPages(PAGE_SIZE, key = { it.id }, maxItems = MAX_COLLECTIONS, truncateAtLimit = true) { start, size ->
                    val response = api.getItems(
                        userId = userId(), recursive = true, includeItemTypes = "BoxSet",
                        fields = COLLECTION_ITEM_FIELDS, startIndex = start, limit = size,
                        enableUserData = false, enableImages = false,
                    )
                    MediaItemsPage(response.items, response.totalRecordCount)
                }
                boxSets.chunked(COLLECTION_FETCH_CONCURRENCY).flatMap { chunk ->
                    coroutineScope {
                        chunk.map { boxSet ->
                            async {
                                val tag = MediaTag(EmbyBackend.id(boxSet.id), boxSet.name)
                                suspendResult {
                                    api.getItems(
                                        userId = userId(), parentId = boxSet.id, recursive = true,
                                        includeItemTypes = "Movie,Series", fields = COLLECTION_ITEM_FIELDS, limit = 1000,
                                        enableUserData = false, enableImages = false, enableTotalRecordCount = false,
                                    ).items.map { EmbyBackend.id(it.id) to tag }
                                }.getOrDefault(emptyList())
                            }
                        }.awaitAll().flatten()
                    }
                }.groupBy({ it.first }, { it.second })
            }.also { collectionMemberships = it; collectionMembershipsAt = System.nanoTime() / 1_000_000L }
        }
        return items.map { item ->
            val collections = memberships[item.ratingKey].orEmpty()
            if (collections.isEmpty()) item else item.copy(collections = collections)
        }
    }

    private companion object {
        // Home cards do not need playback streams, cast lists or full detail metadata. Asking
        // Emby for those fields made a small preview take tens of seconds on larger servers.
        const val HOME_ITEM_FIELDS = "Overview,Genres,ProviderIds,DateCreated,PremiereDate,Studios,ChildCount,RecursiveItemCount,${EmbyApiService.CARD_FIELDS}"
        // Cast and stream details are fetched on demand by the detail screen. Keeping those
        // fields out of library pages prevents large local libraries timing out before page 1.
        const val LIBRARY_ITEM_FIELDS = "$HOME_ITEM_FIELDS,Width,Height"
        const val COLLECTION_ITEM_FIELDS = "ProviderIds"
        const val NEXT_EPISODE_FIELDS = "ProviderIds"
        val SUPPORTED_COLLECTION_TYPES = setOf("movies", "tvshows", "music", "mixed", "audiobooks", "musicvideos", "boxsets")
        const val MEDIA_TYPES = "Movie,Series,Season,Episode,Audio,MusicAlbum,MusicArtist,BoxSet"
        const val KEYED_MUTEX_COUNT = 32
        const val COLLECTION_FETCH_CONCURRENCY = 4
        const val SEARCH_CONCURRENCY = 4
        const val SEARCH_LIMIT_PER_SECTION = 40
        const val SEARCH_RESULT_LIMIT = 100
        const val NEXT_EPISODE_SCAN_LIMIT = 2_000
        const val PLAYED_EPISODE_SCAN_LIMIT = 100
        const val GENRES_TTL_MS = 10 * 60_000L
        const val LIBRARY_SECTIONS_TTL_MS = 5 * 60_000L
        const val ON_DECK_TTL_MS = 30_000L
        const val PREVIEW_TTL_MS = 60_000L
        const val SECTION_ITEMS_TTL_MS = 5 * 60_000L
        const val CHILDREN_TTL_MS = 2 * 60_000L
        const val SIMILAR_TTL_MS = 10 * 60_000L
        const val FILTERED_TTL_MS = 5 * 60_000L
        const val SEARCH_TTL_MS = 60_000L
        const val PAGE_SIZE = 100
        const val MAX_ITEMS = 50_000
        const val MAX_COLLECTIONS = 5_000
        const val COLLECTIONS_TTL_MS = 10 * 60_000L
        const val PLAY_METHOD_DIRECT = "DirectPlay"
        const val PLAY_METHOD_TRANSCODE = "Transcode"
    }
}
