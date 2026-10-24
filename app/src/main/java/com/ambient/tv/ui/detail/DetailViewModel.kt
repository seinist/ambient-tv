package com.ambient.tv.ui.detail

import com.ambient.tv.R
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.notifyResult

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.emby.EmbyBackend
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.jellyfin.JellyfinBackend
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.ThemeMusic
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.ui.player.ThemeMusicPlayer
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

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Loaded(
        val item: MediaItem,
        val children: List<MediaItem> = emptyList(),
        val childrenLoading: Boolean = false,
        val childrenLoadFailed: Boolean = false,
        val nextEpisode: MediaItem? = null,
        val similar: List<MediaItem> = emptyList(),
        val actionInProgress: Boolean = false,
        val actionMessage: String? = null,
        val autoSelectDisplayVersion: Boolean = true,
        val themeMusic: ThemeMusic? = null,
        val offline: Boolean = false,
        val removed: Boolean = false,
    ) : DetailUiState
    data class Error(val message: String, val messageRes: Int? = null) : DetailUiState
}

private val BROWSABLE_TYPES = setOf("show", "season", "collection")
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
) : ViewModel() {
    private val ratingKey: String = checkNotNull(savedStateHandle["ratingKey"])
    private val cachedItem get() = mediaRepository.cachedMediaItem(ratingKey)
    private val _uiState = MutableStateFlow<DetailUiState>(
        cachedItem?.let {
            DetailUiState.Loaded(
                item = it,
                childrenLoading = it.type in BROWSABLE_TYPES || it.type == "episode",
                autoSelectDisplayVersion = appPreferences.autoSelectDisplayVersion,
                offline = sourceOffline(),
            )
        } ?: DetailUiState.Loading,
    )
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var themeMusicJob: Job? = null

    private val _tmdbBackdrop = MutableStateFlow<String?>(null)
    /** TMDb's wide backdrop for a movie or show whose media server has only a poster (needs a TMDb key). */
    val tmdbBackdrop: StateFlow<String?> = _tmdbBackdrop.asStateFlow()

    private fun sourceOffline() = availability.mediaState(ratingKey) in setOf(
        com.ambient.tv.data.browse.SourceHealth.OFFLINE,
        com.ambient.tv.data.browse.SourceHealth.AUTH_REQUIRED,
    )

    fun retryOffline() {
        val state = _uiState.value
        val failed = state is DetailUiState.Error || (state as? DetailUiState.Loaded)?.offline == true
        if (availability.hasNetwork() && failed && loadJob?.isActive != true) load()
    }

    init {
        load()
        viewModelScope.launch {
            _uiState.map { (it as? DetailUiState.Loaded)?.item }.filterNotNull()
                .map { item -> item.takeIf { it.wideBackdropUrl == null && it.type in setOf("movie", "show") } }
                .distinctUntilChanged { a, b -> a?.ratingKey == b?.ratingKey }
                .collect { item ->
                    if (item == null || !tmdb.configured) return@collect
                    val language = java.util.Locale.getDefault().let { if (it.language == "de") "de-DE" else it.toLanguageTag() }
                    tmdb.backdropFor(item, language).getOrNull()?.let { _tmdbBackdrop.value = it }
                }
        }
        viewModelScope.launch {
            availability.changes.collect {
                // A failed first load has nothing on screen to patch: try again once the source is back.
                if (_uiState.value is DetailUiState.Error) {
                    val health = availability.mediaState(ratingKey)
                    val offline = health == com.ambient.tv.data.browse.SourceHealth.OFFLINE || health == com.ambient.tv.data.browse.SourceHealth.AUTH_REQUIRED
                    if (!offline && loadJob?.isActive != true) load()
                    return@collect
                }
                val current = _uiState.value as? DetailUiState.Loaded ?: return@collect
                val health = availability.mediaState(ratingKey)
                val offline = health == com.ambient.tv.data.browse.SourceHealth.OFFLINE || health == com.ambient.tv.data.browse.SourceHealth.AUTH_REQUIRED
                _uiState.value = current.copy(offline = offline, themeMusic = current.themeMusic.takeUnless { offline })
                if (current.offline && !offline && loadJob?.isActive != true) load()
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
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mediaRepository.restoreCache()
            if (_uiState.value !is DetailUiState.Loaded) cachedItem?.let { item ->
                _uiState.value = DetailUiState.Loaded(
                    item = item, children = mediaRepository.cachedChildren(if (item.type == "episode") item.parentRatingKey ?: ratingKey else ratingKey).orEmpty(),
                    similar = mediaRepository.cachedSimilar(ratingKey).orEmpty(),
                    autoSelectDisplayVersion = appPreferences.autoSelectDisplayVersion,
                    offline = sourceOffline(),
                )
            }
            if (_uiState.value !is DetailUiState.Loaded) _uiState.value = DetailUiState.Loading

            mediaRepository.getMetadataFast(ratingKey).fold(
                onSuccess = { freshItem ->
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
                        childrenLoading = item.type in BROWSABLE_TYPES || item.type == "episode",
                        nextEpisode = previous?.nextEpisode,
                        similar = previous?.similar.orEmpty(),
                        actionInProgress = previous?.actionInProgress ?: false,
                        actionMessage = previous?.actionMessage,
                        autoSelectDisplayVersion = appPreferences.autoSelectDisplayVersion,
                        themeMusic = previous?.themeMusic.takeUnless { sourceOffline() },
                        offline = sourceOffline(),
                    )
                    refreshThemeMusic()
                    loadSecondaryContent(item)
                },
                onFailure = { error ->
                    // A cached card is still much more useful than replacing the whole detail
                    // page with an error because a background refresh failed temporarily.
                    if (_uiState.value !is DetailUiState.Loaded) {
                        _uiState.value = DetailUiState.Error(error.message ?: "")
                    } else {
                        val current = _uiState.value as DetailUiState.Loaded
                        val removed = (error as? retrofit2.HttpException)?.code() in setOf(404, 410)
                        _uiState.value = current.copy(removed = removed, offline = !removed,
                            childrenLoading = false, themeMusic = null)
                    }
                },
            )
        }
    }

    private suspend fun loadSecondaryContent(item: MediaItem) = coroutineScope {
        launch {
            val childKey = when {
                item.type in BROWSABLE_TYPES -> ratingKey
                item.type == "episode" -> item.parentRatingKey
                else -> null
            }
            if (childKey != null) {
                updateLoaded { it.copy(childrenLoading = true, childrenLoadFailed = false) }
                val initialChildren = mediaRepository.getChildren(childKey)
                val onlySeason = initialChildren.getOrNull()?.singleOrNull()?.takeIf { item.type == "show" && it.type == "season" }
                val result = if (onlySeason != null) mediaRepository.getChildren(onlySeason.ratingKey) else initialChildren
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
                updateLoaded { current ->
                    current.copy(
                        children = if (result.isSuccess) children else current.children,
                        childrenLoading = false,
                        childrenLoadFailed = result.isFailure,
                        nextEpisode = derivedNext ?: current.nextEpisode,
                    )
                }
            }
        }

        if (item.type == "movie" && !sourceOffline()) {
            launch {
                mediaRepository.getMetadata(ratingKey).onSuccess { enriched ->
                    if (enriched != null) updateLoaded { it.copy(item = enriched, offline = sourceOffline()) }
                }
            }
        }

        // A show's "next unwatched" can require walking all seasons. Keep that expensive Plex
        // work completely off the first paint and independent of the seasons/recommendations rows.
        if (item.type == "show") {
            launch {
                val next = mediaRepository.getNextUnwatchedEpisode(ratingKey).getOrNull()
                if (next != null) updateLoaded { it.copy(nextEpisode = next) }
            }
        }

        if (item.type in RELATED_TYPES) {
            launch {
                mediaRepository.getSimilar(ratingKey).onSuccess { similar ->
                    updateLoaded { it.copy(similar = similar.take(20)) }
                }
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

    private fun runAction(message: Int, block: suspend () -> Result<Unit>) {
        val current = _uiState.value as? DetailUiState.Loaded ?: return
        if (current.actionInProgress) return
        viewModelScope.launch {
            _uiState.value = current.copy(actionInProgress = true, actionMessage = null)
            block().fold(
                onSuccess = {
                    notifyResult("detail:$ratingKey", detailSystem(), current.item.title, message, kind = NoticeKind.LIBRARY)
                    // Actions only need a fast metadata refresh; cross-library duplicate discovery
                    // must never block a watch-state UI update.
                    mediaRepository.getMetadataFast(ratingKey).fold(
                        onSuccess = { refreshed ->
                            val latest = (_uiState.value as? DetailUiState.Loaded) ?: current
                            _uiState.value = latest.copy(
                                item = refreshed ?: latest.item,
                                actionInProgress = false,
                                actionMessage = "ok",
                            )
                        },
                        onFailure = {
                            val latest = (_uiState.value as? DetailUiState.Loaded) ?: current
                            _uiState.value = latest.copy(actionInProgress = false, actionMessage = "ok")
                        },
                    )
                },
                onFailure = { error ->
                    notifyResult("detail:$ratingKey", detailSystem(), current.item.title, success = false, kind = NoticeKind.LIBRARY)
                    val latest = (_uiState.value as? DetailUiState.Loaded) ?: current
                    _uiState.value = latest.copy(
                        actionInProgress = false,
                        actionMessage = error.message ?: "error",
                    )
                },
            )
        }
    }

    private fun detailSystem(): String = when {
        com.ambient.tv.data.files.FileKeys.owns(ratingKey) -> "files"
        com.ambient.tv.data.jellyfin.JellyfinBackend.owns(ratingKey) -> "jellyfin"
        com.ambient.tv.data.emby.EmbyBackend.owns(ratingKey) -> "emby"
        else -> "plex"
    }
}
