package com.ambient.tv.ui.detail

import com.ambient.tv.R
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.notifyResult

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.browse.PlaybackAvailability
import com.ambient.tv.data.browse.PlaybackAccessProbe
import com.ambient.tv.data.browse.mediaProvider
import com.ambient.tv.data.emby.EmbyBackend
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.jellyfin.JellyfinBackend
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.ThemeMusic
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.ui.player.ThemeMusicPlayer
import com.ambient.tv.util.suspendResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.ambient.tv.domain.model.dedupeCastMembers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Loaded(
        val item: MediaItem,
        val children: List<MediaItem> = emptyList(),
        val childrenLoading: Boolean = false,
        val childrenLoadFailed: Boolean = false,
        /** Other seasons of this episode or season. Empty when the show has only one. */
        val seasons: List<MediaItem> = emptyList(),
        val nextEpisode: MediaItem? = null,
        val similar: List<MediaItem> = emptyList(),
        val similarLoading: Boolean = false,
        val actorRows: List<ActorRow> = emptyList(),
        val actionInProgress: Boolean = false,
        val actionMessage: String? = null,
        val autoSelectDisplayVersion: Boolean = true,
        val themeMusic: ThemeMusic? = null,
        /** Cached metadata alone never grants playback, people navigation or edit actions. */
        val connectionVerified: Boolean = false,
        val connectionChecking: Boolean = false,
        val offline: Boolean = false,
        val removed: Boolean = false,
    ) : DetailUiState
    data class Error(val message: String, val messageRes: Int? = null) : DetailUiState
}

/** Plex-style "More with <actor>" row for one of the top-billed cast members. */
data class ActorRow(val actorId: String, val name: String, val items: List<MediaItem>)

private const val ACTOR_ROW_ACTORS = 2
private const val METADATA_EDIT_POLLS = 6

/** Whether an edit shows in the item yet (new title, match or artwork URL). */
internal fun metadataEditVisible(before: MediaItem, after: MediaItem): Boolean =
    before.title != after.title || before.year != after.year || before.guid != after.guid ||
        before.summary != after.summary || before.thumbUrl != after.thumbUrl || before.artUrl != after.artUrl
private const val ACTOR_ROW_MIN_ITEMS = 2

private val RELATED_TYPES = setOf("movie", "show")
private val THEME_MUSIC_TYPES = setOf("movie", "show", "season", "episode")

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
    private val appPreferences: AppPreferences,
    val themeMusicPlayer: ThemeMusicPlayer,
    val availability: com.ambient.tv.data.browse.SourceAvailability,
    private val tmdb: com.ambient.tv.trailers.TmdbTrailerRepository,
    private val refreshBus: com.ambient.tv.ui.common.PlexRefreshBus,
    private val artworkRefresh: com.ambient.tv.network.MediaArtworkRefresh,
    private val playbackAvailability: PlaybackAvailability,
) : ViewModel() {
    private val ratingKey: String = checkNotNull(savedStateHandle["ratingKey"])
    /** Match the cache's server/account identity without retaining credentials in focus memory. */
    val focusScope: String get() = playbackAvailability.mediaIdentity(ratingKey)
    val reconnectSources: Set<String> = setOf(when {
        FileKeys.owns(ratingKey) -> runCatching { "files:${FileKeys.parse(ratingKey).first}" }.getOrDefault("files")
        com.ambient.tv.data.dlna.DlnaKeys.owns(ratingKey) -> runCatching {
            "dlna:${com.ambient.tv.data.dlna.DlnaKeys.parse(ratingKey).first}"
        }.getOrDefault("dlna")
        else -> com.ambient.tv.data.browse.mediaProvider(ratingKey)
    })
    private val cachedItem get() = mediaRepository.cachedMediaItem(ratingKey)
    private val _uiState = MutableStateFlow<DetailUiState>(
        cachedItem?.let {
            DetailUiState.Loaded(
                item = it,
                children = cachedDetailChildren(it, ratingKey, mediaRepository::cachedChildren),
                childrenLoading = detailChildrenKey(it, ratingKey) != null,
                seasons = cachedSeasonStrip(it),
                autoSelectDisplayVersion = appPreferences.autoSelectDisplayVersion,
                offline = sourceOffline(),
            )
        } ?: DetailUiState.Loading,
    )
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private val refreshPolicy = DetailRefreshPolicy()
    private val connectionPolicy = DetailConnectionPolicy()
    private var contentScope = focusScope
    private var connectionJob: Job? = null
    private var connectionAttempt = 0L
    private val _connectionRetrying = MutableStateFlow(false)
    val connectionRetrying: StateFlow<Boolean> = _connectionRetrying.asStateFlow()
    private var reloadJob: Job? = null
    private var autoReloads = 0
    private var themeMusicJob: Job? = null

    private val _fallbackBackdrop = MutableStateFlow<String?>(null)
    /**
     * A wide background for an item that has none selected: first what the media server itself
     * offers (Plex's available backgrounds, Jellyfin/Emby image providers), then TMDb's (needs a key).
     */
    val fallbackBackdrop: StateFlow<String?> = _fallbackBackdrop.asStateFlow()

    private val _editSupport = MutableStateFlow(com.ambient.tv.domain.model.MetadataEditSupport.NONE)
    /** Edit options of the item; [MetadataEditSupport.NONE] unless the server confirmed admin rights. */
    val editSupport: StateFlow<com.ambient.tv.domain.model.MetadataEditSupport> = _editSupport.asStateFlow()

    private fun sourceOffline() = availability.mediaState(ratingKey) in setOf(
        com.ambient.tv.data.browse.SourceHealth.OFFLINE,
        com.ambient.tv.data.browse.SourceHealth.AUTH_REQUIRED,
    )

    private fun connectionVerified(): Boolean = connectionPolicy.isVerified(
        playbackAvailability.mediaScope(ratingKey), playbackAvailability.isMediaVerified(ratingKey),
    )

    private fun canUseDetails(): Boolean = (_uiState.value as? DetailUiState.Loaded)?.let {
        it.connectionVerified && !it.offline && !it.removed && connectionVerified()
    } == true

    fun retryOffline() {
        val state = _uiState.value
        val failed = state is DetailUiState.Error || (state as? DetailUiState.Loaded)?.connectionVerified == false
        if (availability.hasNetwork() && failed && loadJob?.isActive != true && connectionJob?.isActive != true) load()
    }

    /** Explicit Retry checks the actual service before the shared recovery flow may wake it. */
    fun retryConnection() {
        if (connectionJob?.isActive == true) return
        loadJob?.cancel()
        reloadJob?.cancel()
        val attempt = ++connectionAttempt
        _connectionRetrying.value = true
        updateLoaded { it.copy(connectionChecking = true) }
        connectionJob = viewModelScope.launch {
            try {
                val access = playbackAvailability.retryMedia(ratingKey)
                currentCoroutineContext().ensureActive()
                if (access.allowed) load(automatic = false)
                else {
                    connectionPolicy.failed()
                    updateLoaded { it.copy(connectionVerified = false, offline = true, themeMusic = null) }
                }
            } finally {
                if (attempt == connectionAttempt) {
                    _connectionRetrying.value = false
                    // load() owns the spinner after reconnect, until fresh details arrive.
                    if (loadJob?.isActive != true) updateLoaded { it.copy(connectionChecking = false) }
                }
            }
        }
    }

    /** Temporary failures retry with backoff (2, 4, 8 s …), keeping the cached preview locked. */
    private fun scheduleReload(): Boolean {
        if (autoReloads >= DETAIL_MAX_AUTO_RELOADS) return false
        val wait = detailReloadDelayMs(autoReloads++)
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            kotlinx.coroutines.delay(wait)
            load(automatic = true)
        }
        return true
    }

    init {
        viewModelScope.launch {
            _uiState.map { (it as? DetailUiState.Loaded)?.takeIf { state -> state.connectionVerified && !state.offline && !state.removed }?.item }
                .map { item -> item?.takeIf { it.wideBackdropUrl == null } }
                // A new match (guid) needs a new fallback as well, not only another item.
                .distinctUntilChanged { a, b -> a?.ratingKey == b?.ratingKey && a?.guid == b?.guid }
                .collectLatest { item ->
                    if (item == null) return@collectLatest
                    val scope = playbackAvailability.mediaScope(ratingKey)
                    val fromServer = mediaRepository.getAlternativeBackdrop(item.ratingKey).getOrNull()
                    if (!canUseDetails() || scope != playbackAvailability.mediaScope(ratingKey)) return@collectLatest
                    if (fromServer != null) { _fallbackBackdrop.value = fromServer; return@collectLatest }
                    if (!tmdb.configured || item.type !in setOf("movie", "show")) return@collectLatest
                    val language = java.util.Locale.getDefault().let { if (it.language == "de") "de-DE" else it.toLanguageTag() }
                    tmdb.backdropFor(item, language).getOrNull()?.let {
                        if (canUseDetails() && scope == playbackAvailability.mediaScope(ratingKey)) _fallbackBackdrop.value = it
                    }
                }
        }
        viewModelScope.launch {
            _uiState.map { (it as? DetailUiState.Loaded)?.takeIf { state -> state.connectionVerified && !state.offline && !state.removed }?.item }
                .distinctUntilChanged { a, b -> a?.ratingKey == b?.ratingKey && a?.type == b?.type }
                .collectLatest { item ->
                    if (item == null) { _editSupport.value = com.ambient.tv.domain.model.MetadataEditSupport.NONE; return@collectLatest }
                    val scope = playbackAvailability.mediaScope(ratingKey)
                    _editSupport.value = mediaRepository.metadataEditSupport(item)
                    if (mediaRepository.refreshMetadataEditRights(ratingKey) && canUseDetails() && scope == playbackAvailability.mediaScope(ratingKey)) {
                        _editSupport.value = mediaRepository.metadataEditSupport(item)
                    }
                }
        }
        viewModelScope.launch {
            playbackAvailability.changes.collect {
                // A host probe or a request by another page cannot unlock cached details.
                // The shared proof is invalidated on source/account/network changes.
                if (contentScope != focusScope) {
                    contentScope = focusScope
                    connectionPolicy.failed()
                    loadJob?.cancel()
                    reloadJob?.cancel()
                    connectionJob?.cancel()
                    connectionAttempt++
                    _connectionRetrying.value = false
                    themeMusicJob?.cancel()
                    _fallbackBackdrop.value = null
                    _editSupport.value = com.ambient.tv.domain.model.MetadataEditSupport.NONE
                    _uiState.value = cachedItem?.let { DetailUiState.Loaded(item = it) }
                        ?: DetailUiState.Error("", R.string.offline_detail_connection_required)
                    return@collect
                }
                val current = _uiState.value as? DetailUiState.Loaded ?: return@collect
                val verified = connectionVerified()
                if (!verified) {
                    themeMusicJob?.cancel()
                    // Once the hero has finished, this job only owns secondary rows. Stop that
                    // work immediately when the source becomes unverified again.
                    if (current.connectionVerified && !current.connectionChecking) loadJob?.cancel()
                }
                _uiState.value = current.copy(
                    connectionVerified = verified, offline = sourceOffline(),
                    childrenLoading = current.childrenLoading && verified,
                    similarLoading = current.similarLoading && verified,
                    themeMusic = current.themeMusic.takeIf { verified },
                )
            }
        }
    }

    /**
     * Detail loading is deliberately progressive:
     *  1. a process-cache item is painted immediately when available;
     *  2. one lightweight metadata request refreshes the hero;
     *  3. children, next-episode and recommendations load in parallel and patch the page.
     *
     * None of the secondary rows can hold the hero behind a full-page loading state anymore.
     */
    fun load() {
        if (connectionJob?.isActive != true) load(automatic = false)
    }

    private fun load(automatic: Boolean) {
        val generation = refreshPolicy.beginLoad() ?: return
        if (!automatic) autoReloads = 0
        reloadJob?.cancel()
        loadJob?.cancel()
        val requestScope = playbackAvailability.mediaScope(ratingKey)
        val requestIdentity = playbackAvailability.mediaIdentity(ratingKey)
        // Cached rows from a different account/server must never survive a source switch.
        if (contentScope != focusScope) {
            contentScope = focusScope
            connectionPolicy.failed()
            _fallbackBackdrop.value = null
            _uiState.value = DetailUiState.Loading
        }
        updateLoaded { it.copy(connectionChecking = true, connectionVerified = connectionVerified()) }
        refreshThemeMusic()
        loadJob = viewModelScope.launch {
            try {
                mediaRepository.restoreCache()
                currentCoroutineContext().ensureActive()
                if (requestIdentity != playbackAvailability.mediaIdentity(ratingKey)) return@launch
                if (_uiState.value !is DetailUiState.Loaded) cachedItem?.let { item ->
                    _uiState.value = DetailUiState.Loaded(
                        item = item,
                        children = cachedDetailChildren(item, ratingKey, mediaRepository::cachedChildren),
                        similar = mediaRepository.cachedSimilar(ratingKey).orEmpty(),
                        childrenLoading = detailChildrenKey(item, ratingKey) != null,
                        seasons = cachedSeasonStrip(item),
                        autoSelectDisplayVersion = appPreferences.autoSelectDisplayVersion,
                        connectionChecking = true,
                        offline = sourceOffline(),
                    )
                }
                if (_uiState.value !is DetailUiState.Loaded) _uiState.value = DetailUiState.Loading

                val result = withContext(PlaybackAccessProbe) { mediaRepository.getMetadataFast(ratingKey) }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!refreshPolicy.isCurrent(generation)) return@launch
                if (requestIdentity != playbackAvailability.mediaIdentity(ratingKey)) {
                    connectionPolicy.failed()
                    // The provider cache already uses the new identity; never retain the old account's
                    // poster/title while waiting for the next entry or the user's Retry.
                    _uiState.value = cachedItem?.let { DetailUiState.Loaded(item = it) }
                        ?: DetailUiState.Error("", R.string.offline_detail_connection_required)
                    return@launch
                }
                result.fold(
                    onSuccess = { freshItem ->
                        autoReloads = 0
                        val item = freshItem
                        if (item == null) {
                            connectionPolicy.failed()
                            val previous = _uiState.value as? DetailUiState.Loaded
                            _uiState.value = previous?.copy(removed = true, connectionVerified = false, connectionChecking = false, childrenLoading = false, themeMusic = null)
                                ?: DetailUiState.Error("", com.ambient.tv.R.string.browse_item_removed)
                            return@fold
                        }
                        val verified = connectionPolicy.confirmMetadata(
                            requestScope, playbackAvailability.mediaScope(ratingKey),
                            playbackAvailability.confirmMedia(ratingKey, requestScope),
                        )
                        if (!verified) {
                            connectionPolicy.failed()
                            val previous = _uiState.value as? DetailUiState.Loaded
                            _uiState.value = previous?.copy(connectionVerified = false, connectionChecking = false, themeMusic = null)
                                ?: DetailUiState.Loaded(item = item, offline = sourceOffline())
                            return@fold
                        }
                        val previous = _uiState.value as? DetailUiState.Loaded
                        _uiState.value = DetailUiState.Loaded(
                            item = item,
                            children = previous?.children.orEmpty(),
                            childrenLoading = detailChildrenKey(item, ratingKey) != null,
                            seasons = previous?.seasons ?: cachedSeasonStrip(item),
                            nextEpisode = previous?.nextEpisode,
                            similar = previous?.similar.orEmpty(),
                            // Reserve the recommendations row while it loads so the cast below it
                            // doesn't jump down when the row pops in.
                            similarLoading = item.type in RELATED_TYPES && previous?.similar.isNullOrEmpty(),
                            actorRows = previous?.actorRows.orEmpty(),
                            actionInProgress = previous?.actionInProgress ?: false,
                            actionMessage = previous?.actionMessage,
                            autoSelectDisplayVersion = appPreferences.autoSelectDisplayVersion,
                            themeMusic = previous?.themeMusic,
                            connectionVerified = true,
                            connectionChecking = false,
                            offline = false,
                        )
                        refreshThemeMusic()
                        loadSecondaryContent(item, generation)
                    },
                    onFailure = { error ->
                        connectionPolicy.failed()
                        val removed = detailItemRemoved(error)
                        // A starting server (503, timeout) is retried while the preview stays locked.
                        val retrying = !removed && detailFailureTransient(error) && !sourceOffline() && scheduleReload()
                        // A cached card is still much more useful than replacing the whole detail
                        // page with an error because a background refresh failed temporarily.
                        if (_uiState.value !is DetailUiState.Loaded) {
                            if (!retrying) _uiState.value = DetailUiState.Error(error.message ?: "")
                        } else {
                            val current = _uiState.value as DetailUiState.Loaded
                            val offline = detailOfflineAfterFailure(removed, availability.mediaState(ratingKey), retrying)
                            _uiState.value = current.copy(removed = removed, offline = offline,
                                connectionVerified = false, connectionChecking = false,
                                childrenLoading = false,
                                childrenLoadFailed = current.childrenLoadFailed ||
                                    (!removed && current.childrenLoading && current.children.isEmpty()),
                                themeMusic = null)
                        }
                    },
                )
            } finally {
                if (refreshPolicy.isCurrent(generation) && !_connectionRetrying.value) updateLoaded { it.copy(connectionChecking = false) }
            }
        }
    }

    private fun cachedSeasonStrip(item: MediaItem): List<MediaItem> {
        val showKey = seriesKeyForSeasonStrip(item.type, item.parentRatingKey, item.grandparentRatingKey) ?: return emptyList()
        return seasonStripItems(mediaRepository.cachedChildren(showKey).orEmpty())
    }

    private suspend fun loadSecondaryContent(item: MediaItem, generation: Long) = coroutineScope {
        if (!canUseDetails()) return@coroutineScope
        val scope = playbackAvailability.mediaScope(ratingKey)
        fun updateCurrent(transform: (DetailUiState.Loaded) -> DetailUiState.Loaded) {
            if (refreshPolicy.isCurrent(generation) && scope == playbackAvailability.mediaScope(ratingKey) && canUseDetails()) updateLoaded(transform)
        }
        launch {
            val showKey = seriesKeyForSeasonStrip(item.type, item.parentRatingKey, item.grandparentRatingKey)
            if (showKey != null) {
                val result = mediaRepository.getChildren(showKey)
                val seasons = result.getOrNull()?.let(::seasonStripItems).orEmpty()
                updateCurrent { current ->
                    current.copy(seasons = if (result.isSuccess) seasons else current.seasons)
                }
            }
        }
        launch {
            val childKey = detailChildrenKey(item, ratingKey)
            if (childKey != null) {
                updateCurrent { it.copy(childrenLoading = true, childrenLoadFailed = false) }
                val result = loadDetailChildren(item, childKey, mediaRepository::getChildren)
                currentCoroutineContext().ensureActive()
                if (!refreshPolicy.isCurrent(generation) || !canUseDetails()) return@launch
                val children = result.getOrElse {
                    // Otherwise a show simply looks like it has no seasons.
                    com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
                        "detail:children:$childKey", R.string.notice_children_failed, subject = item.title,
                        phase = com.ambient.tv.notifications.NoticePhase.ERROR, kind = com.ambient.tv.notifications.NoticeKind.LIBRARY,
                    ))
                    emptyList()
                }
                val derivedNext = when (item.type) {
                    "episode" -> nextEpisodeFromSiblings(item, children)
                    "season" -> nextUnwatchedFromEpisodes(children)
                    else -> null
                }
                updateCurrent { current ->
                    current.copy(
                        children = if (result.isSuccess) children else current.children,
                        childrenLoading = false,
                        childrenLoadFailed = result.isFailure,
                        nextEpisode = if (result.isSuccess && item.type in setOf("episode", "season")) derivedNext else current.nextEpisode,
                    )
                }
            }
        }

        if (item.type == "movie" && !sourceOffline()) {
            launch {
                mediaRepository.getMetadata(ratingKey).onSuccess { enriched ->
                    if (enriched != null) updateCurrent { it.copy(item = enriched, offline = sourceOffline()) }
                }
            }
        }

        // A show's "next unwatched" can require walking all seasons. Keep that expensive Plex
        // work completely off the first paint and independent of the seasons/recommendations rows.
        if (item.type == "show") {
            launch {
                mediaRepository.getNextUnwatchedEpisode(ratingKey).onSuccess { next ->
                    updateCurrent { it.copy(nextEpisode = next) }
                }
            }
        }

        if (item.type in RELATED_TYPES) {
            launch {
                val result = mediaRepository.getSimilar(ratingKey)
                updateCurrent { current ->
                    current.copy(similar = result.getOrNull()?.take(20) ?: current.similar, similarLoading = false)
                }
            }
            launch {
                val actors = item.cast.dedupeCastMembers().filter { !it.id.isNullOrBlank() }.take(ACTOR_ROW_ACTORS)
                val rows = actors.map { actor ->
                    async {
                        val result = mediaRepository.getItemsByActor(actor.id!!)
                        val others = result.getOrNull()
                            ?: return@async (_uiState.value as? DetailUiState.Loaded)?.actorRows?.firstOrNull { it.actorId == actor.id }
                        val related = others
                            .filter { it.ratingKey != item.ratingKey && it.type in RELATED_TYPES }
                            .distinctBy { it.ratingKey }
                            .take(20)
                        ActorRow(actor.id, actor.name, related).takeIf { related.size >= ACTOR_ROW_MIN_ITEMS }
                    }
                }.awaitAll().filterNotNull()
                updateCurrent { it.copy(actorRows = rows) }
            }
        }
    }

    /**
     * Called on every return to the page, not only after loading: the system's theme music
     * switch may have changed in Settings while this page waited on the back stack.
     */
    fun refreshThemeMusic() {
        val current = _uiState.value as? DetailUiState.Loaded ?: return
        if (!canUseDetails() || current.item.type !in THEME_MUSIC_TYPES || !themeMusicEnabled()) {
            themeMusicJob?.cancel()
            if (current.themeMusic != null) updateLoaded { it.copy(themeMusic = null) }
            return
        }
        if (current.themeMusic != null || themeMusicJob?.isActive == true) return
        val scope = playbackAvailability.mediaScope(ratingKey)
        themeMusicJob = viewModelScope.launch {
            val music = mediaRepository.getThemeMusic(ratingKey).getOrNull() ?: return@launch
            if (canUseDetails() && scope == playbackAvailability.mediaScope(ratingKey)) updateLoaded { it.copy(themeMusic = music) }
        }
    }

    private fun themeMusicEnabled(): Boolean = when {
        FileKeys.owns(ratingKey) -> false
        EmbyBackend.owns(ratingKey) -> appPreferences.embyThemeMusicEnabled
        JellyfinBackend.owns(ratingKey) -> appPreferences.jellyfinThemeMusicEnabled
        else -> appPreferences.plexThemeMusicEnabled
    }

    private fun nextEpisodeFromSiblings(item: MediaItem, siblings: List<MediaItem>): MediaItem? {
        val episodes = siblings.filter { it.type == "episode" }
            .sortedWith(compareBy<MediaItem> { it.index ?: Int.MAX_VALUE }.thenBy { it.ratingKey })
        val currentPosition = episodes.indexOfFirst { item.ratingKey in it.allRatingKeys }
        return if (currentPosition >= 0) episodes.getOrNull(currentPosition + 1)
        else item.index?.let { index -> episodes.firstOrNull { (it.index ?: Int.MIN_VALUE) > index } }
    }

    private fun nextUnwatchedFromEpisodes(children: List<MediaItem>): MediaItem? {
        val episodes = children.filter { it.type == "episode" }
            .sortedWith(compareBy<MediaItem> { it.index ?: Int.MAX_VALUE }.thenBy { it.ratingKey })
        return episodes.firstOrNull { (it.progressFraction ?: 0f) in 0.01f..0.89f }
            ?: episodes.firstOrNull { !it.isWatched }
    }

    private inline fun updateLoaded(transform: (DetailUiState.Loaded) -> DetailUiState.Loaded) {
        val current = _uiState.value as? DetailUiState.Loaded ?: return
        _uiState.value = transform(current)
    }

    fun setWatched(watched: Boolean) {
        runAction(if (watched) R.string.notice_watched else R.string.notice_unwatched) {
            if (watched) mediaRepository.markWatched(ratingKey)
            else mediaRepository.markUnwatched(ratingKey)
        }
    }

    fun removeFromContinueWatching() {
        runAction(R.string.notice_continue_removed) { mediaRepository.removeFromContinueWatching(ratingKey) }
    }

    /**
     * The server accepted a new match or image. It applies a match in the background, so the
     * item is re-read a few times until it changed; children and rows follow with a full load.
     */
    fun onMetadataEdited(success: Boolean) {
        if (!canUseDetails()) return
        val before = (_uiState.value as? DetailUiState.Loaded)?.item ?: return
        _editSupport.value = mediaRepository.metadataEditSupport(before)
        notifyResult("detail:$ratingKey", detailSystem(), before.title, R.string.notice_metadata_updated, success = success, kind = NoticeKind.LIBRARY)
        if (!success) return
        val editedScope = focusScope
        viewModelScope.launch {
            for (attempt in 0 until METADATA_EDIT_POLLS) {
                kotlinx.coroutines.delay(if (attempt == 0) 400L else 1_500L)
                if (focusScope != editedScope) return@launch
                val refreshed = mediaRepository.getMetadataFast(ratingKey).getOrNull() ?: continue
                if (focusScope != editedScope) return@launch
                updateLoaded { it.copy(item = refreshed) }
                if (metadataEditVisible(before, refreshed)) break
            }
            // Some providers replace the bytes at the same URL. Re-reading metadata alone
            // cannot invalidate either Coil RAM budget, its disk image, or the crop analysis.
            val after = (_uiState.value as? DetailUiState.Loaded)?.item
            if (focusScope != editedScope) return@launch
            val persistentArtworkRefresh = artworkRefresh.refresh(listOfNotNull(before, after).flatMap { item -> listOf(
                item.thumbUrl, item.artUrl, item.logoUrl, item.parentThumbUrl, item.grandparentThumbUrl,
            ) }, valid = { focusScope == editedScope })
            if (focusScope != editedScope) return@launch
            if (!persistentArtworkRefresh) notifyResult("detail:$ratingKey:artwork", detailSystem(), before.title,
                R.string.notice_artwork_cache_retry, success = false, kind = NoticeKind.LIBRARY)
            // Also when the opened version looks unchanged: another version may have left the
            // merged title, which only the full load (duplicate resolution) shows.
            _fallbackBackdrop.value = null
            // Seasons, recommendations and the theme belong to the old match; their caches would
            // otherwise survive until their TTL ends.
            mediaRepository.invalidateCaches()
            themeMusicJob?.cancel()
            updateLoaded { it.copy(themeMusic = null) }
            load()
            // Libraries and Home re-query once they are shown again: order (sort title) and
            // split duplicates only come from the server, not from the item cache.
            refreshBus.requestRefresh()
        }
    }

    private fun runAction(message: Int, block: suspend () -> Result<Unit>) {
        val current = _uiState.value as? DetailUiState.Loaded ?: return
        if (!canUseDetails()) return
        val actionIdentity = playbackAvailability.mediaIdentity(ratingKey)
        val actionScope = playbackAvailability.mediaScope(ratingKey)
        if (!refreshPolicy.beginAction(loadJob?.isActive == true || reloadJob?.isActive == true)) return
        // Old progressive reads must not replace the action's watch-state update afterwards.
        loadJob?.cancel()
        reloadJob?.cancel()
        _uiState.value = current.copy(actionInProgress = true, actionMessage = null)
        viewModelScope.launch {
            try {
                suspendResult { block().getOrThrow() }.fold(
                    onSuccess = {
                        if (actionIdentity != playbackAvailability.mediaIdentity(ratingKey)) return@fold
                        notifyResult("detail:$ratingKey", detailSystem(), current.item.title, message, kind = NoticeKind.LIBRARY)
                        // Keep the action guard through the fast metadata read. A queued entry
                        // refresh may then replace the remaining rows without racing this update.
                        val refreshed = suspendResult { mediaRepository.getMetadataFast(ratingKey).getOrThrow() }.getOrNull()
                        if (actionScope == playbackAvailability.mediaScope(ratingKey)) updateLoaded { it.copy(item = refreshed ?: it.item, actionMessage = "ok") }
                    },
                    onFailure = { error ->
                        // The failed request itself invalidates accessGeneration. Still report
                        // its outcome for this account; only a different source discards it.
                        if (actionIdentity != playbackAvailability.mediaIdentity(ratingKey)) return@fold
                        notifyResult("detail:$ratingKey", detailSystem(), current.item.title, success = false,
                            kind = NoticeKind.LIBRARY, failureMessage = R.string.offline_browse_action_unconfirmed)
                        updateLoaded { it.copy(actionMessage = error.message ?: "error") }
                    },
                )
            } finally {
                val refresh = refreshPolicy.finishAction()
                updateLoaded { it.copy(actionInProgress = false) }
                if (refresh && currentCoroutineContext().isActive) load()
            }
        }
    }

    private fun detailSystem(): String = when {
        com.ambient.tv.data.files.FileKeys.owns(ratingKey) -> "files"
        com.ambient.tv.data.jellyfin.JellyfinBackend.owns(ratingKey) -> "jellyfin"
        com.ambient.tv.data.emby.EmbyBackend.owns(ratingKey) -> "emby"
        com.ambient.tv.data.dlna.DlnaKeys.owns(ratingKey) -> "dlna"
        else -> "plex"
    }
}
