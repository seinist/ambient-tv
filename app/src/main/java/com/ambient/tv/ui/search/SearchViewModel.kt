package com.ambient.tv.ui.search

import com.ambient.tv.util.suspendResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.connectivity.UnreachableServerGate
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.radio.RadioBrowserRepository
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.radio.RadioPlaybackLauncher
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.data.jellyfin.JellyfinRepository
import com.ambient.tv.data.emby.EmbyRepository
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.spotify.SpotifyBrowseItem
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.tvheadend.data.TvheadendRepository
import com.ambient.tv.tvheadend.domain.TvhSearchResults
import com.ambient.tv.trailers.TmdbTrailerRepository
import com.ambient.tv.trailers.toMediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchViewMode { ROWS, LIST }
enum class SearchSourceState { IDLE, DISABLED, LOADING, READY, PARTIAL, ERROR }
enum class SearchSource { PLEX, JELLYFIN, EMBY, LIVE_TV, TRAILERS, YOUTUBE, SPOTIFY, RADIO }

private val LOCAL_SEARCH_SOURCES = setOf(SearchSource.PLEX, SearchSource.JELLYFIN, SearchSource.EMBY, SearchSource.LIVE_TV)
internal fun defaultSearchSources(available: Set<SearchSource>): Set<SearchSource> =
    (available intersect LOCAL_SEARCH_SOURCES).ifEmpty { available }
internal fun reconcileSearchSources(selected: Set<SearchSource>, previous: Set<SearchSource>, available: Set<SearchSource>): Set<SearchSource> =
    if (previous.isEmpty()) defaultSearchSources(available)
    else (selected intersect available) + ((available - previous) intersect LOCAL_SEARCH_SOURCES)
internal fun liveTvSearchSource(system: String): SearchSource? = when (system) {
    "plex" -> SearchSource.PLEX
    "jellyfin" -> SearchSource.JELLYFIN
    "emby" -> SearchSource.EMBY
    "tvheadend", "iptv" -> SearchSource.LIVE_TV
    else -> null
}

internal suspend fun searchLiveTvRepositories(
    repositories: List<com.ambient.tv.livetv.LiveTvRepository>,
    query: String,
): Pair<TvhSearchResults, Boolean> = coroutineScope {
    val found = repositories.map { repo -> async {
        suspendResult {
            check(repo.capabilities().available) { "Live TV source unavailable" }
            repo.search(query)
        }
    } }.map { it.await() }
    val successes = found.mapNotNull { it.getOrNull() }
    TvhSearchResults(successes.flatMap { it.channels }, successes.flatMap { it.programs }, successes.flatMap { it.recordings },
        incomplete = successes.isNotEmpty() && found.any { it.isFailure || it.getOrNull()?.incomplete == true }) to
        found.any { it.isFailure || it.getOrNull()?.incomplete == true }
}

internal fun liveTvSearchState(results: TvhSearchResults, failed: Boolean): SearchSourceState = when {
    results.incomplete -> SearchSourceState.PARTIAL
    failed -> SearchSourceState.ERROR
    else -> SearchSourceState.READY
}

data class SearchUiState(
    val query: String = "",
    val availableSources: Set<SearchSource> = emptySet(),
    val selectedSources: Set<SearchSource> = emptySet(),
    val results: List<MediaItem> = emptyList(),
    val jellyfinResults: List<MediaItem> = emptyList(),
    val embyResults: List<MediaItem> = emptyList(),
    val trailerResults: List<MediaItem> = emptyList(),
    val youtubeResults: List<YouTubeItem> = emptyList(),
    val spotifyResults: List<SpotifyBrowseItem> = emptyList(),
    val radioResults: List<RadioStation> = emptyList(),
    val tvheadendResults: TvhSearchResults = TvhSearchResults(),
    val liveTvSources: Map<String, com.ambient.tv.livetv.LiveTvSource> = emptyMap(),
    val liveTvHeaders: Map<String, Map<String, String>> = emptyMap(),
    val recentSearches: List<String> = emptyList(),
    val hasSearched: Boolean = false,
    val plexState: SearchSourceState = SearchSourceState.IDLE,
    val jellyfinState: SearchSourceState = SearchSourceState.IDLE,
    val embyState: SearchSourceState = SearchSourceState.IDLE,
    val trailerState: SearchSourceState = SearchSourceState.IDLE,
    val youtubeState: SearchSourceState = SearchSourceState.IDLE,
    val spotifyState: SearchSourceState = SearchSourceState.IDLE,
    val radioState: SearchSourceState = SearchSourceState.IDLE,
    val tvheadendState: SearchSourceState = SearchSourceState.IDLE,
    val tvheadendImageHeaders: Map<String, String> = emptyMap(),
    val viewMode: SearchViewMode = SearchViewMode.ROWS,
    val focusedRatingKey: String? = null,
    val restoreResultFocus: Boolean = false,
) {
    val resultCount: Int get() = results.size + jellyfinResults.size + embyResults.size + trailerResults.size + youtubeResults.size + spotifyResults.size + radioResults.size + tvheadendResults.size
    val hasResults: Boolean get() = resultCount > 0
    val isSearching: Boolean get() = listOf(plexState, jellyfinState, embyState, trailerState, youtubeState, spotifyState, radioState, tvheadendState).any { it == SearchSourceState.LOADING }
}

private const val MIN_QUERY_LENGTH = 2
private const val SEARCH_STATE_KEY = "search"

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val jellyfinRepository: JellyfinRepository,
    private val embyRepository: EmbyRepository,
    private val trailerRepository: TmdbTrailerRepository,
    private val preferences: AppPreferences,
    private val smartTubeEngine: SmartTubeEngine,
    private val spotifyRepository: SpotifyRepository,
    private val radioRepository: RadioBrowserRepository,
    private val radioPlaybackLauncher: RadioPlaybackLauncher,
    private val tvheadendRepository: TvheadendRepository,
    private val liveTvRegistry: com.ambient.tv.livetv.LiveTvRegistry,
    private val unreachableServers: UnreachableServerGate,
) : ViewModel() {
    private var youtubeSignedIn = false

    /** Include configured sources even while hidden as offline, so recovery restores their chips. */
    val reconnectSources: Set<String>
        get() = buildSet {
            if (preferences.isConfigured) add("plex")
            if (preferences.isJellyfinConfigured) add("jellyfin")
            if (preferences.isEmbyConfigured) add("emby")
            addAll(liveTvRegistry.configured().map { it.source.system })
            if (preferences.trailersEnabled && trailerRepository.configured) add("trailers")
            if (preferences.youtubeEnabled) add("youtube")
            if (preferences.spotifyEnabled) add("spotify")
            if (preferences.radioEnabled) add("radio")
        }

    private fun availableSearchSources(): Set<SearchSource> {
        val hidden = unreachableServers.current().offline
        return buildSet {
            if (preferences.isConfigured && "plex" !in hidden) add(SearchSource.PLEX)
            if (preferences.isJellyfinConfigured && "jellyfin" !in hidden) add(SearchSource.JELLYFIN)
            if (preferences.isEmbyConfigured && "emby" !in hidden) add(SearchSource.EMBY)
            if (liveTvRegistry.configured().any { liveTvSearchSource(it.source.system) == SearchSource.LIVE_TV &&
                    !(it.source.system == "tvheadend" && "tvheadend" in hidden) }) add(SearchSource.LIVE_TV)
            if (preferences.trailersEnabled && trailerRepository.configured) add(SearchSource.TRAILERS)
            if (preferences.youtubeEnabled && smartTubeEngine.available && youtubeSignedIn) add(SearchSource.YOUTUBE)
            if (preferences.spotifyEnabled && spotifyRepository.nativeAvailable && spotifyRepository.webAuthorized) add(SearchSource.SPOTIFY)
            if (preferences.radioEnabled) add(SearchSource.RADIO)
        }
    }

    private val initialSources = availableSearchSources()
    private val _uiState = MutableStateFlow(
        SearchUiState(
            availableSources = initialSources,
            selectedSources = defaultSearchSources(initialSources),
            recentSearches = preferences.recentSearches(),
            viewMode = preferences.sectionViewMode(SEARCH_STATE_KEY)?.let { runCatching { SearchViewMode.valueOf(it) }.getOrNull() } ?: SearchViewMode.ROWS,
            focusedRatingKey = preferences.sectionFocus(SEARCH_STATE_KEY),
            restoreResultFocus = preferences.sectionFocus(SEARCH_STATE_KEY) != null,
            tvheadendImageHeaders = tvheadendRepository.authHeaders(),
        ),
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var youtubeProbe: Job? = null
    private var searchRevision = 0L

    fun refreshSources() = refreshSources(preserveResults = false)

    private fun refreshSources(preserveResults: Boolean) {
        val available = availableSearchSources()
        if (available != _uiState.value.availableSources) {
            val selected = reconcileSearchSources(_uiState.value.selectedSources, _uiState.value.availableSources, available)
            if (selected != _uiState.value.selectedSources) {
                searchJob?.cancel()
                searchRevision++
            }
            _uiState.update { state ->
                val updated = state.copy(availableSources = available, selectedSources = selected)
                if (selected != state.selectedSources && !preserveResults) clearResults(updated) else updated
            }
        }
        if (preferences.youtubeEnabled && smartTubeEngine.available && youtubeProbe?.isActive != true) {
            youtubeProbe = viewModelScope.launch {
                val signed = suspendResult { smartTubeEngine.isSignedIn() }.getOrDefault(false)
                if (youtubeSignedIn != signed) {
                    val before = _uiState.value
                    youtubeSignedIn = signed
                    refreshSources(preserveResults = before.hasSearched)
                    // A late login result must not discard an already submitted search. Only
                    // changed selected sources need another request; an edited draft stays idle.
                    if (before.hasSearched && before.selectedSources != _uiState.value.selectedSources) {
                        startSearch(preserveResults = true)
                    }
                }
            }
        }
    }

    fun toggleSource(source: SearchSource) {
        if (source !in _uiState.value.availableSources) return
        searchJob?.cancel()
        searchRevision++
        _uiState.update { state -> clearResults(state.copy(selectedSources =
            if (source in state.selectedSources) state.selectedSources - source else state.selectedSources + source)) }
    }

    fun selectAllSources() {
        if (_uiState.value.selectedSources == _uiState.value.availableSources) return
        searchJob?.cancel()
        searchRevision++
        _uiState.update { state -> clearResults(state.copy(selectedSources = state.availableSources)) }
    }

    fun onQueryChanged(query: String) {
        searchJob?.cancel()
        searchRevision++
        _uiState.update { clearResults(it.copy(query = query, restoreResultFocus = false)) }
    }

    private fun clearResults(state: SearchUiState) = state.copy(
        results = emptyList(), jellyfinResults = emptyList(), embyResults = emptyList(),
        trailerResults = emptyList(), youtubeResults = emptyList(), spotifyResults = emptyList(), radioResults = emptyList(),
        tvheadendResults = TvhSearchResults(), hasSearched = false, restoreResultFocus = false,
        plexState = SearchSourceState.IDLE, jellyfinState = SearchSourceState.IDLE,
        embyState = SearchSourceState.IDLE, trailerState = SearchSourceState.IDLE,
        youtubeState = SearchSourceState.IDLE, spotifyState = SearchSourceState.IDLE, radioState = SearchSourceState.IDLE,
        tvheadendState = SearchSourceState.IDLE,
    )

    fun useRecentSearch(query: String) = onQueryChanged(query)

    fun submitSearch() = startSearch(preserveResults = false)

    fun refreshAfterReconnect() {
        if (_uiState.value.hasSearched) startSearch(preserveResults = true)
        else refreshSources()
    }

    private fun startSearch(preserveResults: Boolean) {
        refreshSources(preserveResults)
        val state = _uiState.value
        if (state.query.trim().length < MIN_QUERY_LENGTH || state.selectedSources.isEmpty()) return
        searchJob?.cancel()
        val revision = ++searchRevision
        searchJob = viewModelScope.launch { performSearch(state.query, state.selectedSources, revision, preserveResults) }
    }

    fun clearRecentSearches() {
        preferences.clearRecentSearches()
        _uiState.update { it.copy(recentSearches = emptyList()) }
    }

    fun setViewMode(mode: SearchViewMode) {
        if (_uiState.value.viewMode == mode) return
        preferences.setSectionViewMode(SEARCH_STATE_KEY, mode.name)
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun onItemFocused(item: MediaItem) {
        preferences.setSectionFocus(SEARCH_STATE_KEY, item.ratingKey)
        _uiState.update { it.copy(focusedRatingKey = item.ratingKey, restoreResultFocus = true) }
    }

    private suspend fun performSearch(rawQuery: String, selected: Set<SearchSource>, revision: Long, preserveResults: Boolean) {
        val query = rawQuery.trim()
        if (query.length < MIN_QUERY_LENGTH) return
        val enabled = selected intersect availableSearchSources()
        val plexEnabled = SearchSource.PLEX in enabled
        val jellyfinEnabled = SearchSource.JELLYFIN in enabled
        val embyEnabled = SearchSource.EMBY in enabled
        val trailersEnabled = SearchSource.TRAILERS in enabled
        val youtubeEnabled = SearchSource.YOUTUBE in enabled
        val spotifyEnabled = SearchSource.SPOTIFY in enabled
        val radioEnabled = SearchSource.RADIO in enabled
        val tvRepositories = liveTvRegistry.configured().filter { repo ->
            liveTvSearchSource(repo.source.system)?.let { it in enabled } == true
        }
        val tvheadendEnabled = tvRepositories.isNotEmpty()
        _uiState.update {
            it.copy(
                query = query,
                liveTvSources = tvRepositories.associate { it.source.id to it.source },
                liveTvHeaders = tvRepositories.associate { it.source.id to it.authHeaders() },
                results = if (preserveResults && plexEnabled) it.results else emptyList(),
                jellyfinResults = if (preserveResults && jellyfinEnabled) it.jellyfinResults else emptyList(),
                embyResults = if (preserveResults && embyEnabled) it.embyResults else emptyList(),
                trailerResults = if (preserveResults && trailersEnabled) it.trailerResults else emptyList(),
                youtubeResults = if (preserveResults && youtubeEnabled) it.youtubeResults else emptyList(),
                spotifyResults = if (preserveResults && spotifyEnabled) it.spotifyResults else emptyList(),
                radioResults = if (preserveResults && radioEnabled) it.radioResults else emptyList(),
                tvheadendResults = if (preserveResults && tvheadendEnabled) it.tvheadendResults else TvhSearchResults(),
                hasSearched = true,
                plexState = if (plexEnabled) SearchSourceState.LOADING else SearchSourceState.DISABLED,
                jellyfinState = if (jellyfinEnabled) SearchSourceState.LOADING else SearchSourceState.DISABLED,
                embyState = if (embyEnabled) SearchSourceState.LOADING else SearchSourceState.DISABLED,
                trailerState = if (trailersEnabled) SearchSourceState.LOADING else SearchSourceState.DISABLED,
                youtubeState = if (youtubeEnabled) SearchSourceState.LOADING else SearchSourceState.DISABLED,
                spotifyState = if (spotifyEnabled) SearchSourceState.LOADING else SearchSourceState.DISABLED,
                radioState = if (radioEnabled) SearchSourceState.LOADING else SearchSourceState.DISABLED,
                tvheadendState = if (tvheadendEnabled) SearchSourceState.LOADING else SearchSourceState.DISABLED,
                tvheadendImageHeaders = tvheadendRepository.authHeaders(),
            )
        }
        coroutineScope {
            if (plexEnabled) launch {
                val result = suspendResult {
                    val keys = mediaRepository.getLibrarySections().getOrThrow()
                        .filterNot { it.key in preferences.plexHiddenSectionKeys }.map { it.key }
                    mediaRepository.search(keys, query).getOrThrow().distinctBy { it.canonicalContentKey }
                }
                updateForQuery(query, revision) { state ->
                    result.fold(
                        onSuccess = { state.copy(results = it, plexState = SearchSourceState.READY) },
                        onFailure = { state.copy(plexState = SearchSourceState.ERROR) },
                    )
                }
            }
            if (jellyfinEnabled) launch {
                val result = suspendResult {
                    val hidden = preferences.jellyfinHiddenSectionKeys
                    val keys = jellyfinRepository.getLibrarySections().getOrThrow()
                        .filterNot { it.key in hidden }
                        .map { it.key }
                    jellyfinRepository.search(keys, query).getOrThrow().distinctBy { it.canonicalContentKey }
                }
                updateForQuery(query, revision) { state ->
                    result.fold(
                        onSuccess = { state.copy(jellyfinResults = it, jellyfinState = SearchSourceState.READY) },
                        onFailure = { state.copy(jellyfinState = SearchSourceState.ERROR) },
                    )
                }
            }
            if (embyEnabled) launch {
                val result = suspendResult {
                    val hidden = preferences.embyHiddenSectionKeys
                    val keys = embyRepository.getLibrarySections().getOrThrow()
                        .filterNot { it.key in hidden }
                        .map { it.key }
                    embyRepository.search(keys, query).getOrThrow().distinctBy { it.canonicalContentKey }
                }
                updateForQuery(query, revision) { state ->
                    result.fold(
                        onSuccess = { state.copy(embyResults = it, embyState = SearchSourceState.READY) },
                        onFailure = { state.copy(embyState = SearchSourceState.ERROR) },
                    )
                }
            }
            if (trailersEnabled) launch {
                val locale = java.util.Locale.getDefault()
                val language = when (preferences.trailerLanguage) {
                    "de" -> "de-DE"
                    "en" -> "en-US"
                    else -> locale.toLanguageTag()
                }
                val result = trailerRepository.search(query, language, locale.country.ifBlank { "US" })
                    .map { values -> values.map { it.toMediaItem() } }
                updateForQuery(query, revision) { state ->
                    result.fold(
                        onSuccess = { state.copy(trailerResults = it, trailerState = SearchSourceState.READY) },
                        onFailure = { state.copy(trailerState = SearchSourceState.ERROR) },
                    )
                }
            }
            if (youtubeEnabled) launch {
                val result = suspendResult { smartTubeEngine.search(query).flatMap { it.items }.distinctBy { it.videoId }.take(24) }
                updateForQuery(query, revision) { state ->
                    result.fold(
                        onSuccess = { state.copy(youtubeResults = it, youtubeState = SearchSourceState.READY) },
                        onFailure = { state.copy(youtubeState = SearchSourceState.ERROR) },
                    )
                }
            }
            if (spotifyEnabled) launch {
                val result = suspendResult { spotifyRepository.search(query).take(24) }
                updateForQuery(query, revision) { state ->
                    result.fold(
                        onSuccess = { state.copy(spotifyResults = it, spotifyState = SearchSourceState.READY) },
                        onFailure = { state.copy(spotifyState = SearchSourceState.ERROR) },
                    )
                }
            }
            if (radioEnabled) launch {
                val result = suspendResult { radioRepository.search(query = query, limit = 24).items }
                updateForQuery(query, revision) { state ->
                    result.fold(
                        onSuccess = { state.copy(radioResults = it, radioState = SearchSourceState.READY) },
                        onFailure = { state.copy(radioState = SearchSourceState.ERROR) },
                    )
                }
            }
            if (tvheadendEnabled) launch {
                val result = suspendResult { searchLiveTvRepositories(tvRepositories, query) }
                updateForQuery(query, revision) { state ->
                    result.fold(
                        onSuccess = { state.copy(tvheadendResults = it.first, tvheadendState = liveTvSearchState(it.first, it.second)) },
                        onFailure = { state.copy(tvheadendState = SearchSourceState.ERROR) },
                    )
                }
            }
        }
        if (_uiState.value.query.trim() == query && searchRevision == revision) {
            preferences.rememberSearch(query)
            _uiState.update { it.copy(recentSearches = preferences.recentSearches()) }
        }
    }

    fun playRadio(station: RadioStation, onStarted: () -> Unit = {}) {
        viewModelScope.launch {
            radioPlaybackLauncher.play(station, _uiState.value.radioResults)
                .onSuccess { onStarted() }
        }
    }

    private inline fun updateForQuery(query: String, revision: Long, transform: (SearchUiState) -> SearchUiState) {
        _uiState.update { state -> if (state.query.trim() == query && searchRevision == revision && state.hasSearched) transform(state) else state }
    }
}
