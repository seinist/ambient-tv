package com.ambient.tv.ui.detail

import com.ambient.tv.R
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.notifyResult

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.browse.BrowseScopes
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.ambient.tv.domain.model.dedupeCastMembers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

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
    private val browseScopes: BrowseScopes,
    private val tmdb: com.ambient.tv.trailers.TmdbTrailerRepository,
    private val refreshBus: com.ambient.tv.ui.common.PlexRefreshBus,
) : ViewModel() {
    private val ratingKey: String = checkNotNull(savedStateHandle["ratingKey"])
    /** Match the cache's server/account identity without retaining credentials in focus memory. */
    val focusScope: String get() = browseScopes.forProvider(mediaProvider(ratingKey))
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

    fun retryOffline() {
        val state = _uiState.value
        val failed = state is DetailUiState.Error || (state as? DetailUiState.Loaded)?.offline == true
        if (availability.hasNetwork() && failed && loadJob?.isActive != true) load()
    }

    /** Reloads after a temporary failure with backoff (2, 4, 8 s …) instead of hiding Play. */
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
            _uiState.map { (it as? DetailUiState.Loaded)?.item }.filterNotNull()
                .map { item -> item.takeIf { it.wideBackdropUrl == null } }
                // A new match (guid) needs a new fallback as well, not only another item.
                .distinctUntilChanged { a, b -> a?.ratingKey == b?.ratingKey && a?.guid == b?.guid }
                .collect { item ->
                    if (item == null) return@collect
                    val fromServer = mediaRepository.getAlternativeBackdrop(item.ratingKey).getOrNull()
                    if (fromServer != null) { _fallbackBackdrop.value = fromServer; return@collect }
                    if (!tmdb.configured || item.type !in setOf("movie", "show")) return@collect
                    val language = java.util.Locale.getDefault().let { if (it.language == "de") "de-DE" else it.toLanguageTag() }
                    tmdb.backdropFor(item, language).getOrNull()?.let { _fallbackBackdrop.value = it }
                }
        }
        viewModelScope.launch {
            // The stored rights decide at once; the background re-check only corrects them.
            _uiState.map { (it as? DetailUiState.Loaded)?.item }.filterNotNull()
                .distinctUntilChanged { a, b -> a.ratingKey == b.ratingKey && a.type == b.type }
                .collect { item ->
                    _editSupport.value = mediaRepository.metadataEditSupport(item)
                    if (mediaRepository.refreshMetadataEditRights(ratingKey)) _editSupport.value = mediaRepository.metadataEditSupport(item)
                }
        }
        viewModelScope.launch {
            availability.changes.collect {
                // Recovery refreshes through the visible screen after cache expiry. Health updates
                // only patch availability, so background pages do not start duplicate loads.
                val current = _uiState.value as? DetailUiState.Loaded ?: return@collect
                val health = availability.mediaState(ratingKey)
                val offline = health == com.ambient.tv.data.browse.SourceHealth.OFFLINE || health == com.ambient.tv.data.browse.SourceHealth.AUTH_REQUIRED
                _uiState.value = current.copy(offline = offline, themeMusic = current.themeMusic.takeUnless { offline })
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
    fun load() = load(automatic = false)

    private fun load(automatic: Boolean) {
        val generation = refreshPolicy.beginLoad() ?: return
        if (!automatic) autoReloads = 0
        reloadJob?.cancel()
        loadJob?.cancel()
        refreshThemeMusic()
        loadJob = viewModelScope.launch {
            mediaRepository.restoreCache()
            if (_uiState.value !is DetailUiState.Loaded) cachedItem?.let { item ->
                _uiState.value = DetailUiState.Loaded(
                    item = item,
                    children = cachedDetailChildren(item, ratingKey, mediaRepository::cachedChildren),
                    similar = mediaRepository.cachedSimilar(ratingKey).orEmpty(),
                    childrenLoading = detailChildrenKey(item, ratingKey) != null,
                    seasons = cachedSeasonStrip(item),
                    autoSelectDisplayVersion = appPreferences.autoSelectDisplayVersion,
                    offline = sourceOffline(),
                )
            }
            if (_uiState.value !is DetailUiState.Loaded) _uiState.value = DetailUiState.Loading

            val result = mediaRepository.getMetadataFast(ratingKey)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!refreshPolicy.isCurrent(generation)) return@launch
            result.fold(
                onSuccess = { freshItem ->
                    autoReloads = 0
                    val item = freshItem
                    if (item == null) {
                        val previous = _uiState.value as? DetailUiState.Loaded
                        _uiState.value = previous?.copy(removed = true, childrenLoading = false, themeMusic = null)
                            ?: DetailUiState.Error("", com.ambient.tv.R.string.browse_item_removed)
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
                        themeMusic = previous?.themeMusic.takeUnless { sourceOffline() },
                        offline = sourceOffline(),
                    )
                    refreshThemeMusic()
                    loadSecondaryContent(item, generation)
                },
                onFailure = { error ->
                    val removed = detailItemRemoved(error)
                    // A starting server (503, timeout) is retried with backoff; Play stays visible.
                    val retrying = !removed && detailFailureTransient(error) && !sourceOffline() && scheduleReload()
                    // A cached card is still much more useful than replacing the whole detail
                    // page with an error because a background refresh failed temporarily.
                    if (_uiState.value !is DetailUiState.Loaded) {
                        if (!retrying) _uiState.value = DetailUiState.Error(error.message ?: "")
                    } else {
                        val current = _uiState.value as DetailUiState.Loaded
                        val offline = detailOfflineAfterFailure(removed, availability.mediaState(ratingKey), retrying)
                        _uiState.value = current.copy(removed = removed, offline = offline,
                            childrenLoading = false,
                            childrenLoadFailed = current.childrenLoadFailed ||
                                (!removed && current.childrenLoading && current.children.isEmpty()),
                            themeMusic = current.themeMusic.takeUnless { offline || removed })
                    }
                },
            )
        }
    }

    private fun cachedSeasonStrip(item: MediaItem): List<MediaItem> {
        val showKey = seriesKeyForSeasonStrip(item.type, item.parentRatingKey, item.grandparentRatingKey) ?: return emptyList()
        return seasonStripItems(mediaRepository.cachedChildren(showKey).orEmpty())
    }

    private suspend fun loadSecondaryContent(item: MediaItem, generation: Long) = coroutineScope {
        fun updateCurrent(transform: (DetailUiState.Loaded) -> DetailUiState.Loaded) {
            if (refreshPolicy.isCurrent(generation)) updateLoaded(transform)
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
                if (!refreshPolicy.isCurrent(generation)) return@launch
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
        if (current.offline || current.removed || current.item.type !in THEME_MUSIC_TYPES || !themeMusicEnabled()) {
            themeMusicJob?.cancel()
            if (current.themeMusic != null) updateLoaded { it.copy(themeMusic = null) }
            return
        }
        if (current.themeMusic != null || themeMusicJob?.isActive == true) return
        themeMusicJob = viewModelScope.launch {
            val music = mediaRepository.getThemeMusic(ratingKey).getOrNull() ?: return@launch
            updateLoaded { it.copy(themeMusic = music) }
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
        val before = (_uiState.value as? DetailUiState.Loaded)?.item ?: return
        _editSupport.value = mediaRepository.metadataEditSupport(before)
        notifyResult("detail:$ratingKey", detailSystem(), before.title, R.string.notice_metadata_updated, success = success, kind = NoticeKind.LIBRARY)
        if (!success) return
        viewModelScope.launch {
            for (attempt in 0 until METADATA_EDIT_POLLS) {
                kotlinx.coroutines.delay(if (attempt == 0) 400L else 1_500L)
                val refreshed = mediaRepository.getMetadataFast(ratingKey).getOrNull() ?: continue
                updateLoaded { it.copy(item = refreshed) }
                if (metadataEditVisible(before, refreshed)) break
            }
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
        if (!refreshPolicy.beginAction(loadJob?.isActive == true || reloadJob?.isActive == true)) return
        // Old progressive reads must not replace the action's watch-state update afterwards.
        loadJob?.cancel()
        reloadJob?.cancel()
        _uiState.value = current.copy(actionInProgress = true, actionMessage = null)
        viewModelScope.launch {
            try {
                suspendResult { block().getOrThrow() }.fold(
                    onSuccess = {
                        notifyResult("detail:$ratingKey", detailSystem(), current.item.title, message, kind = NoticeKind.LIBRARY)
                        // Keep the action guard through the fast metadata read. A queued entry
                        // refresh may then replace the remaining rows without racing this update.
                        val refreshed = suspendResult { mediaRepository.getMetadataFast(ratingKey).getOrThrow() }.getOrNull()
                        updateLoaded { it.copy(item = refreshed ?: it.item, actionMessage = "ok") }
                    },
                    onFailure = { error ->
                        notifyResult("detail:$ratingKey", detailSystem(), current.item.title, success = false, kind = NoticeKind.LIBRARY)
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
        else -> "plex"
    }
}
