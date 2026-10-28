package com.ambient.tv.ui.home

import com.ambient.tv.launcher.toLauncherTitle
import com.ambient.tv.ui.common.displayTitle
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.data.local.genre
import com.ambient.tv.data.local.homeRowType
import com.ambient.tv.data.local.homeSystemReady
import com.ambient.tv.domain.model.LibraryHub
import com.ambient.tv.domain.model.LibraryRecommendation
import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.recommendationScore
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.data.jellyfin.JellyfinRepository
import com.ambient.tv.data.jellyfin.JellyfinBackend
import com.ambient.tv.data.emby.EmbyRepository
import com.ambient.tv.data.emby.EmbyBackend
import com.ambient.tv.server.ServerConnectionManager
import com.ambient.tv.server.ServerConnectionState
import com.ambient.tv.server.JellyfinConnectionManager
import com.ambient.tv.server.EmbyConnectionManager
import com.ambient.tv.ui.common.ContentRowData
import com.ambient.tv.ui.common.MediaTileStyle
import com.ambient.tv.ui.common.PlexRefreshBus
import com.ambient.tv.tvheadend.data.TvheadendRepository
import com.ambient.tv.tvheadend.domain.TvhChannel
import com.ambient.tv.tvheadend.domain.TvhEpgEvent
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import com.ambient.tv.connectivity.UnreachableServerGate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.ambient.tv.ui.performance.PerformanceProfileResolver
import com.ambient.tv.util.suspendResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class HomeLiveTvItem(val channel: TvhChannel, val event: TvhEpgEvent?, val imageHeaders: Map<String, String> = emptyMap(), val sourceName: String = "")
data class HomeTvRow(val items: List<HomeLiveTvItem>, val title: String)

/** Provider-neutral content for the pinned Home banner. Home contains more than Plex rows, so
 * keeping the banner tied to [MediaItem] would leave TV and provider shortcuts invisible there. */
data class HomeHeroContent(
    val key: String,
    val title: String,
    val artworkUrl: String? = null,
    val thumbnailUrl: String? = null,
    /** The artwork is a transparent logo (TV channel icon), not a full-bleed backdrop. */
    val artworkIsLogo: Boolean = false,
    /** Transparent title/channel logo for the right-hand artwork slot. */
    val titleLogoUrl: String? = null,
    val providerLabel: String? = null,
    val metadata: List<String> = emptyList(),
    val imageHeaders: Map<String, String> = emptyMap(),
    val mediaItem: MediaItem? = null,
    /** Bold line under the title (a live programme's episode title or its channel). */
    val subtitle: String? = null,
    /** Summary for content without a [mediaItem], such as a live programme. */
    val summary: String? = null,
    val live: HomeHeroLive? = null,
)

/** Airing window of a live programme, for the hero's LIVE marker and remaining time. */
data class HomeHeroLive(val timeLabel: String, val startEpochSeconds: Long, val stopEpochSeconds: Long)

internal fun MediaItem.toHomeHeroContent() = HomeHeroContent(
    key = "${when {
        ratingKey.startsWith("trailer-video:") -> "trailers"
        EmbyBackend.owns(ratingKey) -> "emby"
        JellyfinBackend.owns(ratingKey) -> "jellyfin"
        else -> "plex"
    }}:$ratingKey",
    title = title,
    artworkUrl = artUrl,
    thumbnailUrl = thumbUrl,
    titleLogoUrl = logoUrl,
    providerLabel = when {
        ratingKey.startsWith("trailer-video:") -> "trailers"
        EmbyBackend.owns(ratingKey) -> "Emby"
        JellyfinBackend.owns(ratingKey) -> "Jellyfin"
        else -> "Plex"
    },
    mediaItem = this,
)

data class HomeFocusSnapshot(val ratingKey: String?, val rowId: String?)

enum class HomeInitialFocusTarget { RESOLVING, LIVE_TV, RECENT_TV, SHORTCUT, CONTENT, NONE }

/** Lists a media server ranks itself, as just fetched or as last cached. */
private class RankedData(
    val lists: Map<LibraryRecommendation, List<MediaItem>> = emptyMap(),
    /** Similar titles by the rating key they were looked up for. */
    val similar: Map<String, List<MediaItem>> = emptyMap(),
    /** Library key to one of that library's hubs, in the server's order. */
    val hubs: List<Pair<String, LibraryHub>> = emptyList(),
)

private data class HomeTvPreview(val live: List<HomeLiveTvItem> = emptyList(), val recent: List<HomeLiveTvItem> = emptyList())

sealed interface HomeUiState {
    data object NotConfigured : HomeUiState
    data class Waking(val elapsedSeconds: Int) : HomeUiState
    data object LoadingLibrary : HomeUiState
    data class Loaded(
        val rows: List<ContentRowData>,
        val focusedRatingKey: String? = null,
        val focusedRowId: String? = null,
        val hero: HomeHeroContent? = null,
        val liveTvItems: List<HomeLiveTvItem> = emptyList(),
        val shortcuts: List<HomeRowDefinition> = emptyList(),
        val recentTvItems: List<HomeLiveTvItem> = emptyList(),
        val rowKeys: List<String> = emptyList(),
        val tvRows: Map<String, HomeTvRow> = emptyMap(),
        val liveTvTitle: String = "",
        val recentTvTitle: String = "",
        val initialFocusTarget: HomeInitialFocusTarget = HomeInitialFocusTarget.NONE,
        /** A library load is still running: empty rows are not yet "no libraries". */
        val loading: Boolean = false,
        val focusRecovery: HomeFocusSnapshot? = null,
    ) : HomeUiState
    data class Offline(val serverName: String) : HomeUiState
    data class Error(val message: String) : HomeUiState
    data class WakeTimedOut(val serverName: String) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    private val mediaRepository: MediaRepository,
    val metadataPrefetcher: com.ambient.tv.data.browse.MetadataPrefetcher,
    val availability: com.ambient.tv.data.browse.SourceAvailability,
    private val jellyfinRepository: JellyfinRepository,
    private val embyRepository: EmbyRepository,
    private val connectionManager: ServerConnectionManager,
    private val jellyfinConnectionManager: JellyfinConnectionManager,
    private val embyConnectionManager: EmbyConnectionManager,
    private val tvheadendRepository: TvheadendRepository,
    private val tvheadendConnectionManager: com.ambient.tv.server.TvheadendConnectionManager,
    private val liveTvRegistry: com.ambient.tv.livetv.LiveTvRegistry,
    private val plexRefreshBus: PlexRefreshBus,
    private val spotifyRepository: SpotifyRepository,
    private val photoRepository: com.ambient.tv.data.photos.PhotoRepository,
    private val trailerRepository: com.ambient.tv.trailers.TmdbTrailerRepository,
    private val resumeConnectivityMonitor: ResumeConnectivityMonitor,
    private val performanceProfileResolver: PerformanceProfileResolver,
    private val unreachableServers: UnreachableServerGate,
    private val launcherPublisher: com.ambient.tv.launcher.LauncherPublisher,
) : ViewModel() {

    val homeHeroStyleKey: String get() = appPreferences.homeHeroStyleKey
    val homeHeroIntensityPercent: Int get() = appPreferences.homeHeroIntensityPercent
    val homeHeroSpreadKey: String get() = appPreferences.homeHeroSpreadKey

    private var plexOnly = false
    private var jellyfinOnly = false
    private var embyOnly = false
    private val _overviewViewMode = MutableStateFlow(HomeRowDefinition.DISPLAY_POSTER)
    /** Poster, landscape or list for the open system overview. Unused on the app home screen. */
    val overviewViewMode: StateFlow<String> = _overviewViewMode.asStateFlow()
    private var homeEntranceAnimationConsumed = false
    private var jellyfinCollectionJob: Job? = null
    private var embyCollectionJob: Job? = null
    private var loadJob: Job? = null
    private var loadGeneration = 0
    private var libraryLoading = false
    private var tvPreviewJob: Job? = null
    private var photoJob: Job? = null
    private var trailerJob: Job? = null
    private var trailerRows: List<ContentRowData> = emptyList()
    /** "Recently added photos" row; merged into every publish so provider reloads keep it. */
    private var photoRow: ContentRowData? = null
    private var tvPreview = HomeTvPreview()
    /** Last successful continue-watching list per media server, for the cross-system row. */
    private val continueBySystem = java.util.concurrent.ConcurrentHashMap<String, List<MediaItem>>()
    /** The live TV preview of this load has finished (successfully or not). */
    private var tvPreviewComplete = false
    private var tvPreviewScope: String? = null
    private var activeFocusKnown = false
    private var activeFocusedRatingKey: String? = null
    private var activeFocusedRowId: String? = null
    private var homeFocusStateKey = HOME_FOCUS_STATE_KEY
    private var persistedHomeFocusKey: String? = appPreferences.sectionFocus(homeFocusStateKey)
    private var persistedHomeFocusRowId: String? = appPreferences.sectionFocusRow(homeFocusStateKey)
    private var homeFocusPersistenceJob: Job? = null

    // Focus moves update only the pinned banner. Keeping it out of uiState prevents every D-pad
    // step from recomposing the Home LazyColumn and all currently visible rows.
    private val _heroContent = MutableStateFlow<HomeHeroContent?>(null)
    val heroContent: StateFlow<HomeHeroContent?> = _heroContent.asStateFlow()

    /** Caps parallel Home metadata fetches across section/ranked/hubs waves. */
    private val homeFetchLimiter = Semaphore(HOME_FETCH_CONCURRENCY)

    private val _uiState = MutableStateFlow<HomeUiState>(
        if (appPreferences.isConfigured || appPreferences.isJellyfinConfigured || appPreferences.isEmbyConfigured || appPreferences.isTvheadendConfigured)
            HomeUiState.LoadingLibrary else HomeUiState.NotConfigured,
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Channel icon URLs point at the TVHeadend server and need the same Basic Auth credentials
     * as the API calls - Coil doesn't share those with [tvheadendRepository]'s own client. */
    val tvheadendImageHeaders: Map<String, String> get() = tvheadendRepository.authHeaders()
    val autoRefreshIntervalSeconds: Int get() = appPreferences.plexAutoRefreshIntervalSeconds
    val refreshGeneration = plexRefreshBus.generation

    fun focusSnapshot(): HomeFocusSnapshot {
        if (activeFocusKnown) return HomeFocusSnapshot(activeFocusedRatingKey, activeFocusedRowId)
        val loaded = _uiState.value as? HomeUiState.Loaded
        return HomeFocusSnapshot(loaded?.focusedRatingKey, loaded?.focusedRowId)
    }

    /** Commit the most recent D-pad target when leaving Home, without publishing on every step. */
    fun captureFocusForReturn() {
        val current = _uiState.value as? HomeUiState.Loaded ?: return
        if (!activeFocusKnown) return
        homeFocusPersistenceJob?.cancel()
        activeFocusedRatingKey?.takeIf {
            it != persistedHomeFocusKey || activeFocusedRowId != persistedHomeFocusRowId
        }?.let { ratingKey ->
            appPreferences.setSectionFocusSnapshot(homeFocusStateKey, ratingKey, activeFocusedRowId)
            persistedHomeFocusKey = ratingKey
            persistedHomeFocusRowId = activeFocusedRowId
        }
        val hero = _heroContent.value
        if (current.focusedRatingKey == activeFocusedRatingKey &&
            current.focusedRowId == activeFocusedRowId && current.hero == hero
        ) return
        _uiState.value = current.copy(
            focusedRatingKey = activeFocusedRatingKey,
            focusedRowId = activeFocusedRowId,
            hero = hero,
        )
    }

    init {
        // A system whose login was just confirmed or rejected joins or leaves Home.
        viewModelScope.launch {
            com.ambient.tv.data.local.LoginStateSignal.generation.drop(1).collect { refresh() }
        }
        // TVHeadend woke up after Home showed its cached channels: reload only the TV rows.
        viewModelScope.launch {
            tvheadendRepository.revalidated.collect {
                if (tvPreviewJob?.isActive != true && !plexOnly && !jellyfinOnly && !embyOnly && liveTvRegistry.configured().isNotEmpty()) startTvPreview()
            }
        }
        // Address/login saved elsewhere (backup import, companion transfer) but not yet confirmed:
        // confirm it once here, so a working server appears without a manual connection test.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (appPreferences.hasPlexEndpoint && !appPreferences.plexLoginVerified) connectionManager.testCurrentServer()
            if (appPreferences.hasTvheadendEndpoint && !appPreferences.tvheadendLoginVerified) tvheadendRepository.testConnection()
        }
    }

    init {
        viewModelScope.launch {
            var previousHidden = emptySet<String>()
            unreachableServers.state.collect { gate ->
                val hidden = gate.offline
                if (hidden == previousHidden) return@collect
                val restored = previousHidden - hidden
                previousHidden = hidden
                if (restored.isNotEmpty()) {
                    retry()
                    return@collect
                }
                if (hidden.isEmpty()) return@collect
                when (val current = _uiState.value) {
                    is HomeUiState.Loaded -> publishRows(current.rows)
                    is HomeUiState.Offline, is HomeUiState.Error, is HomeUiState.WakeTimedOut ->
                        publishRows(emptyList())
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            var seen = resumeConnectivityMonitor.recheckGeneration.value
            resumeConnectivityMonitor.recheckGeneration.collect { generation ->
                if (generation == seen) return@collect
                seen = generation
                onResumeRecheckFinished()
            }
        }
    }

    /** After a ProcessLifecycle resume recheck, soft-retry offline/error Home without clearing Loaded. */
    private fun onResumeRecheckFinished() {
        when (_uiState.value) {
            is HomeUiState.Offline, is HomeUiState.Error, is HomeUiState.WakeTimedOut -> retry()
            else -> Unit
        }
    }


    fun consumeHomeEntranceAnimation(): Boolean {
        if (homeEntranceAnimationConsumed) return false
        homeEntranceAnimationConsumed = true
        return true
    }

    fun start(
        plexOnly: Boolean = false,
        jellyfinOnly: Boolean = false,
        embyOnly: Boolean = false,
        focusStateKey: String = HOME_FOCUS_STATE_KEY,
    ) {
        val samePage = this.plexOnly == plexOnly && this.jellyfinOnly == jellyfinOnly &&
            this.embyOnly == embyOnly && homeFocusStateKey == focusStateKey
        // Re-entering the destination must not cancel an initial/background load in flight.
        if (samePage && listOf(loadJob, tvPreviewJob, photoJob, trailerJob).any { it?.isActive == true }) return
        start(plexOnly, jellyfinOnly, embyOnly, focusStateKey, refreshing = false)
    }

    private fun start(
        plexOnly: Boolean,
        jellyfinOnly: Boolean,
        embyOnly: Boolean,
        focusStateKey: String = homeFocusStateKey,
        refreshing: Boolean,
    ) {
        val keepSnapshot = _uiState.value is HomeUiState.Loaded &&
            this.plexOnly == plexOnly && this.jellyfinOnly == jellyfinOnly &&
            this.embyOnly == embyOnly && homeFocusStateKey == focusStateKey
        loadJob?.cancel()
        jellyfinCollectionJob?.cancel()
        jellyfinCollectionJob = null
        embyCollectionJob?.cancel()
        embyCollectionJob = null
        this.plexOnly = plexOnly
        this.jellyfinOnly = jellyfinOnly
        this.embyOnly = embyOnly
        _overviewViewMode.value = systemOverviewPageMode() ?: HomeRowDefinition.DISPLAY_POSTER
        if (homeFocusStateKey != focusStateKey) {
            homeFocusStateKey = focusStateKey
            persistedHomeFocusKey = appPreferences.sectionFocus(focusStateKey)
            persistedHomeFocusRowId = appPreferences.sectionFocusRow(focusStateKey)
            activeFocusKnown = false
            activeFocusedRatingKey = null
            activeFocusedRowId = null
        }
        loadGeneration++
        libraryLoading = false
        tvPreviewJob?.cancel()
        val nextTvScope = liveTvRegistry.configured().joinToString { it.source.id }
        if (!keepSnapshot || tvPreviewScope != nextTvScope) tvPreview = HomeTvPreview()
        tvPreviewComplete = false
        // Retain each provider's last answer until its asynchronous replacement arrives.
        if (!keepSnapshot) continueBySystem.clear()
        tvPreviewScope = nextTvScope
        loadPhotoRow()
        loadTrailerRows()
        if (!plexOnly && !jellyfinOnly && !embyOnly && liveTvRegistry.configured().isNotEmpty()) startTvPreview()
        if ((jellyfinOnly || embyOnly) && !refreshing && _uiState.value !is HomeUiState.Loaded) {
            libraryLoading = true
            // Hydration and cached row construction run in launchLoad, off the UI thread.
        }
        if (jellyfinOnly) {
            if (!appPreferences.isJellyfinConfigured) { _uiState.value = HomeUiState.NotConfigured; return }
            launchLoad {
                if (jellyfinConnectionManager.ensureConnected()) loadJellyfinLibrary()
                else { availability.unreachable("jellyfin"); failOrContinue(HomeUiState.Offline(activeServerName())) }
            }
            return
        }
        if (embyOnly) {
            if (!appPreferences.isEmbyConfigured) { _uiState.value = HomeUiState.NotConfigured; return }
            launchLoad {
                if (embyConnectionManager.ensureConnected()) loadEmbyLibrary()
                else { availability.unreachable("emby"); failOrContinue(HomeUiState.Offline(activeServerName())) }
            }
            return
        }
        if (!appPreferences.isConfigured && !appPreferences.isJellyfinConfigured && !appPreferences.isEmbyConfigured) {
            if (appPreferences.isTvheadendConfigured || trailerJob?.isActive == true) {
                // The independent TV job publishes as soon as channels are ready.
                if (_uiState.value !is HomeUiState.Loaded) _uiState.value = HomeUiState.LoadingLibrary
            } else {
                _uiState.value = HomeUiState.NotConfigured
            }
            return
        }
        // A refresh has just emptied the caches: publishing them would blank the rows on screen
        // (and drop focus) until the reload lands. Keep what is shown and swap it in place.
        // The same holds when returning from Detail: partially evicted caches would briefly
        // drop rows, and the row the user came from would lose its restored selection.
        val showingRows = (_uiState.value as? HomeUiState.Loaded)?.rows?.isNotEmpty() == true
        libraryLoading = true
        // Keep existing rows until asynchronous cache hydration completes.
        // A retry from an error screen shows the startup progress, not the old error, while it runs.
        if (_uiState.value !is HomeUiState.Loaded) _uiState.value = HomeUiState.LoadingLibrary
        // TVHeadend connects (and wakes) like the media servers; its rows load independently of this result.
        if (appPreferences.isTvheadendConfigured) viewModelScope.launch { tvheadendConnectionManager.ensureConnected() }
        launchLoad {
            if (!appPreferences.isConfigured && !appPreferences.isJellyfinConfigured && appPreferences.isEmbyConfigured) {
                if (embyConnectionManager.ensureConnected()) loadEmbyLibrary()
                else { availability.unreachable("emby"); failOrContinue(HomeUiState.Offline(activeServerName())) }
                return@launchLoad
            }
            if (!appPreferences.isConfigured && appPreferences.isJellyfinConfigured) {
                val connected = listOf(
                    async { jellyfinConnectionManager.ensureConnected().also { if (it) loadJellyfinLibrary() else availability.unreachable("jellyfin") } },
                    async {
                        if (!appPreferences.isEmbyConfigured) false
                        else embyConnectionManager.ensureConnected()
                            .also { if (it) loadEmbyLibrary() else availability.unreachable("emby") }
                    },
                ).awaitAll()
                if (connected.none { it }) failOrContinue(HomeUiState.Offline(activeServerName()))
                return@launchLoad
            }
            // While Jellyfin/Emby may still connect, a Plex failure must not take over the screen.
            val othersPending = !plexOnly && (appPreferences.isJellyfinConfigured || appPreferences.isEmbyConfigured)
            var plexFailure: HomeUiState? = null
            fun plexFailed(failure: HomeUiState) {
                availability.unreachable("plex")
                if (_uiState.value is HomeUiState.Loaded) return
                if (othersPending) plexFailure = failure else failOrContinue(failure)
            }
            val stateJob = launch {
                // Skip the replayed state: it belongs to an earlier attempt (e.g. a stale error).
                connectionManager.state.drop(1).collect { state ->
                    when (state) {
                        ServerConnectionState.NotConfigured -> if (!appPreferences.isConfigured && _uiState.value !is HomeUiState.Loaded) {
                            _uiState.value = HomeUiState.NotConfigured
                        }
                        ServerConnectionState.Checking,
                        ServerConnectionState.Connecting -> if (_uiState.value !is HomeUiState.Loaded) _uiState.value = HomeUiState.LoadingLibrary
                        ServerConnectionState.SendingWakePacket -> if (_uiState.value !is HomeUiState.Loaded) _uiState.value = HomeUiState.Waking(0)
                        is ServerConnectionState.Waking -> if (_uiState.value !is HomeUiState.Loaded) _uiState.value = HomeUiState.Waking(state.elapsedSeconds)
                        ServerConnectionState.WakeTimedOut -> plexFailed(HomeUiState.WakeTimedOut(activeServerName()))
                        ServerConnectionState.Offline -> plexFailed(HomeUiState.Offline(activeServerName()))
                        is ServerConnectionState.Error -> plexFailed(HomeUiState.Error(state.message))
                        is ServerConnectionState.Online -> Unit
                    }
                }
            }
            val plexConnected = async { connectionManager.ensureConnected() }
            val jellyfinConnected = async {
                !plexOnly && appPreferences.isJellyfinConfigured && jellyfinConnectionManager.ensureConnected()
                    .also { if (!it) availability.unreachable("jellyfin") }
            }
            val embyConnected = async {
                !plexOnly && appPreferences.isEmbyConfigured && embyConnectionManager.ensureConnected()
                    .also { if (!it) availability.unreachable("emby") }
            }
            val providerLoads = listOf(
                async {
                    plexConnected.await() && loadLibrary { failure ->
                        // Same rules as a connection failure: other servers may still fill Home.
                        if (_uiState.value !is HomeUiState.Loaded) {
                            plexFailure = failure
                            if (!othersPending) failOrContinue(failure)
                        }
                    }
                },
                async { jellyfinConnected.await().also { if (it) appendJellyfinRows() } },
                async { embyConnected.await().also { if (it) appendEmbyRows() } },
            )
            if (providerLoads.awaitAll().none { it }) {
                failOrContinue(plexFailure ?: HomeUiState.Offline(activeServerName()))
            }
            stateJob.cancel()
        }
    }

    /** Runs a library load; until it ends, an empty Home shows loading instead of "no libraries". */
    private fun launchLoad(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        val generation = ++loadGeneration
        libraryLoading = true
        loadJob = viewModelScope.launch {
            mediaRepository.restoreCache()
            if (_uiState.value !is HomeUiState.Loaded) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { cachedRows() }?.let { libraryLoading = false; publishRows(it) }

            try {
                if (!availability.hasNetwork()) {
                    failOrContinue(HomeUiState.Offline(activeServerName()))
                    return@launch
                }
                block()
            } finally {
                if (generation == loadGeneration) {
                    libraryLoading = false
                    (_uiState.value as? HomeUiState.Loaded)?.takeIf { it.loading }?.let { _uiState.value = it.copy(loading = false) }
                }
            }
        }
    }

    val browseProviders: List<String>
        get() = homeBrowseProviders(
            appPreferences.isConfigured, appPreferences.isJellyfinConfigured, appPreferences.isEmbyConfigured,
            plexOnly, jellyfinOnly, embyOnly,
        )

    fun retryOffline() {
        if (loadJob?.isActive == true || !availability.hasNetwork()) return
        if (browseProviders.any { availability.state(it) == com.ambient.tv.data.browse.SourceHealth.OFFLINE }) {
            start(plexOnly, jellyfinOnly, embyOnly, homeFocusStateKey, refreshing = false)
        }
    }

    fun refresh() {
        loadJob?.cancel()
        jellyfinCollectionJob?.cancel()
        embyCollectionJob?.cancel()
        mediaRepository.refreshCaches()
        start(
            plexOnly = plexOnly,
            jellyfinOnly = jellyfinOnly,
            embyOnly = embyOnly,
            focusStateKey = homeFocusStateKey,
            refreshing = true,
        )
    }

    /** Collection rows only return with the later enrichment pass; keep the shown ones until then. */
    private fun keepPendingCollectionRows(system: String, rows: List<ContentRowData>, enrichment: Job?): List<ContentRowData> {
        val prefix = "$system.collection."
        if (enrichment?.isActive != true || rows.any { it.id.startsWith(prefix) }) return rows
        val shown = (_uiState.value as? HomeUiState.Loaded)?.rows.orEmpty().filter { it.id.startsWith(prefix) }
        if (shown.isEmpty()) return rows
        val at = rows.indexOfFirst { it.id.startsWith("$system.library.") }.takeIf { it >= 0 } ?: rows.size
        return rows.subList(0, at) + shown + rows.subList(at, rows.size)
    }

    /**
     * A dead media server must not blank the library when something else still works.
     * Confirming the dialog hides that server; with nothing else reachable the blocking state stays.
     */
    private fun failOrContinue(failure: HomeUiState) {
        val loaded = _uiState.value as? HomeUiState.Loaded
        if (loaded != null && !loaded.onlyTrailers()) return
        unreachableServers.refresh()
        val now = unreachableServers.current()
        if (now.canDismiss || now.offline.isNotEmpty()) publishRows(emptyList())
        else if (now.pending) _uiState.value = HomeUiState.LoadingLibrary
        else _uiState.value = failure
    }

    private fun activeServerName(): String = when {
        embyOnly || (!appPreferences.isConfigured && !appPreferences.isJellyfinConfigured && appPreferences.isEmbyConfigured) ->
            appPreferences.activeEmbyServerProfile()?.name?.takeIf { it.isNotBlank() }
                ?: appPreferences.embyHost.takeIf { it.isNotBlank() }
        jellyfinOnly || !appPreferences.isConfigured ->
            appPreferences.activeJellyfinServerProfile()?.name?.takeIf { it.isNotBlank() }
                ?: appPreferences.jellyfinHost.takeIf { it.isNotBlank() }
        else -> appPreferences.activeServerProfile()?.name?.takeIf { it.isNotBlank() }
            ?: appPreferences.host.takeIf { it.isNotBlank() }
    } ?: context.getString(R.string.home_server_generic_name)

    private fun cachedRows(): List<ContentRowData>? {
        val includePlex = !jellyfinOnly && !embyOnly && (plexOnly || appPreferences.isConfigured)
        val includeJellyfin = !plexOnly && !embyOnly && appPreferences.isJellyfinConfigured
        val includeEmby = !plexOnly && !jellyfinOnly && appPreferences.isEmbyConfigured
        if (!includePlex && !includeJellyfin && !includeEmby) return null
        val plexRows = if (includePlex) {
            val sections = mediaRepository.cachedLibrarySections()?.filter { it.type != "photo" }
            if (sections == null) {
                if (!includeJellyfin && !includeEmby) return null
                emptyList()
            } else {
                val allSectionItems = sections.mapNotNull { section ->
                    (mediaRepository.cachedSectionPreview(section.key, "addedAt:desc", HOME_SOURCE_PREVIEW_LIMIT) ?: mediaRepository.cachedSectionItems(section.key))?.let { section to it }
                }
                buildLocalRows(mediaRepository.cachedOnDeck().orEmpty(), allSectionItems, allSectionItems.flatMap { it.second }, HomeRowDefinition.SYSTEM_PLEX, cachedRecentEpisodes(mediaRepository, sections), cachedRanked(mediaRepository, sections, HomeRowDefinition.SYSTEM_PLEX))
            }
        } else emptyList()
        val extra = buildList {
            if (includeJellyfin) {
                val hiddenJellyfinSections = appPreferences.jellyfinHiddenSectionKeys
                val jellyfinSections = jellyfinRepository.cachedLibrarySections().orEmpty()
                    .filterNot { it.key in hiddenJellyfinSections }
                val jellyfinItems = jellyfinSections.mapNotNull { section -> (jellyfinRepository.cachedSectionPreview(section.key, "addedAt:desc", JELLYFIN_HOME_PREVIEW_LIMIT) ?: jellyfinRepository.cachedSectionItems(section.key))?.let { section to it } }
                addAll(buildLocalRows(jellyfinRepository.cachedOnDeck().orEmpty(), jellyfinItems, jellyfinItems.flatMap { it.second }, HomeRowDefinition.SYSTEM_JELLYFIN, cachedRecentEpisodes(jellyfinRepository, jellyfinSections), cachedRanked(jellyfinRepository, jellyfinSections, HomeRowDefinition.SYSTEM_JELLYFIN)))
            }
            if (includeEmby) {
                val hiddenEmbySections = appPreferences.embyHiddenSectionKeys
                val embySections = embyRepository.cachedLibrarySections().orEmpty()
                    .filterNot { it.key in hiddenEmbySections }
                val embyItems = embySections.mapNotNull { section -> (embyRepository.cachedSectionPreview(section.key, "addedAt:desc", EMBY_HOME_PREVIEW_LIMIT) ?: embyRepository.cachedSectionItems(section.key))?.let { section to it } }
                addAll(buildLocalRows(embyRepository.cachedOnDeck().orEmpty(), embyItems, embyItems.flatMap { it.second }, HomeRowDefinition.SYSTEM_EMBY, cachedRecentEpisodes(embyRepository, embySections), cachedRanked(embyRepository, embySections, HomeRowDefinition.SYSTEM_EMBY)))
            }
        }
        return if (extra.isEmpty()) plexRows else sortCombinedRows(plexRows + extra)
    }

    /** False when the library list could not be loaded; [onFailure] decides what Home shows. */
    private suspend fun loadLibrary(onFailure: (HomeUiState) -> Unit): Boolean {
        if (_uiState.value !is HomeUiState.Loaded) _uiState.value = HomeUiState.LoadingLibrary

        val sections = mediaRepository.getLibrarySections().getOrElse { error ->
            onFailure(HomeUiState.Error(error.message ?: context.getString(R.string.error_generic)))
            return false
        }.filter { it.type != "photo" }

        loadProviderRows(mediaRepository, sections, HomeRowDefinition.SYSTEM_PLEX, HOME_SOURCE_PREVIEW_LIMIT, HOME_RELEASE_PREVIEW_LIMIT)
        return true
    }

    private suspend fun appendJellyfinRows() { loadJellyfinRows() }

    private suspend fun loadJellyfinLibrary() {
        if (_uiState.value !is HomeUiState.Loaded) _uiState.value = HomeUiState.LoadingLibrary
        loadJellyfinRows()
    }

    private suspend fun appendEmbyRows() { loadEmbyRows() }

    private suspend fun loadEmbyLibrary() {
        if (_uiState.value !is HomeUiState.Loaded) _uiState.value = HomeUiState.LoadingLibrary
        loadEmbyRows()
    }

    /** Each response updates its rows immediately; slow recommendations never hold back titles. */
    private suspend fun loadProviderRows(
        repository: MediaRepository,
        sections: List<LibrarySection>,
        system: String,
        previewLimit: Int,
        releaseLimit: Int,
    ): List<ContentRowData> = coroutineScope {
        var onDeck = emptyList<MediaItem>()
        val previews = mutableMapOf<String, List<MediaItem>>()
        val releases = mutableMapOf<String, List<MediaItem>>()
        var episodes = emptyList<MediaItem>()
        var ranked = RankedData()
        // Partial API failures (server reachable, one request failing) would otherwise just drop
        // a row. A complete failure is left to the connection notice.
        val requested = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val firstFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        fun Result<List<MediaItem>>.counted(fallback: List<MediaItem> = emptyList()): List<MediaItem> {
            requested.incrementAndGet()
            return getOrElse { failed.incrementAndGet(); firstFailure.compareAndSet(null, it); fallback }
        }
        fun sectionItems() = sections.mapNotNull { section -> previews[section.key]?.let { section to it } }
        fun rows() = buildLocalRows(onDeck, sectionItems(), sections.flatMap { releases[it.key].orEmpty() }, system, episodes, ranked)
        fun publishPartial() {
            val incoming = rows()
            if (incoming.isEmpty()) return
            // Keep cached/visible rows whose requests are still pending during a refresh.
            val current = (_uiState.value as? HomeUiState.Loaded)?.rows.orEmpty()
            publishRows(sortCombinedRows(mergePartialHomeRows(
                current = current,
                incoming = incoming,
                system = system,
                previewsComplete = previews.size == sections.size,
                releasesComplete = releases.size == sections.size,
            )))
        }
        coroutineScope {
            if (hasEnabledRow(system, "continue") || (allContinueRow() != null && system in appPreferences.allContinueSystems)) launch {
                val result = repository.getOnDeck()
                onDeck = result.counted(repository.cachedOnDeck().orEmpty())
                // Feeds the cross-system row; a failed answer keeps the last list of that server.
                if (result.isSuccess) continueBySystem[system] = onDeck
                // A successful answer is the server's truth; Watch Next follows it (videos only).
                if (result.isSuccess) homeMediaSystem(system)?.let { mediaSystem ->
                    launcherPublisher.syncWatchNextFromServer(mediaSystem,
                        onDeck.filter { it.type == "movie" || it.type == "episode" }
                            .map { it.toLauncherTitle(com.ambient.tv.ui.common.MediaTileStyle.CONTINUE) })
                }
                publishPartial()
            }
            if (listOf("recent", "collections", "libraries").any { hasEnabledRow(system, it) }) {
                sections.forEach { section -> launch {
                    previews[section.key] = limitedHomeFetch {
                        repository.getSectionPreview(section.key, "addedAt:desc", previewLimit).counted(repository.cachedSectionPreview(section.key, "addedAt:desc", previewLimit).orEmpty())
                    }
                    publishPartial()
                } }
            }
            if (hasEnabledRow(system, "recent")) {
                sections.forEach { section -> launch {
                    releases[section.key] = limitedHomeFetch {
                        repository.getSectionPreview(section.key, "originallyAvailableAt:desc", releaseLimit).counted(repository.cachedSectionPreview(section.key, "originallyAvailableAt:desc", releaseLimit).orEmpty())
                    }
                    publishPartial()
                } }
            }
            launch {
                episodes = loadRecentEpisodes(repository, sections, system)
                publishPartial()
            }
            launch {
                ranked = loadRanked(repository, sections, system)
                publishPartial()
            }
        }
        if (failed.get() in 1 until requested.get()) {
            val reason = firstFailure.get()?.let { com.ambient.tv.notifications.failureReasonText(context, it) }
            com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
                "home:rows:$system", if (reason != null) com.ambient.tv.R.string.notice_home_rows_failed_reason else com.ambient.tv.R.string.notice_home_rows_failed,
                systems = listOf(system), args = listOfNotNull(reason),
                phase = com.ambient.tv.notifications.NoticePhase.ERROR, kind = com.ambient.tv.notifications.NoticeKind.LIBRARY,
                interruptPlayback = false,
            ))
        }
        val released = sections.flatMap { releases[it.key].orEmpty() }
        val enrichment = when (system) {
            HomeRowDefinition.SYSTEM_JELLYFIN -> {
                scheduleJellyfinCollectionRows(onDeck, sectionItems(), released, episodes, ranked)
                jellyfinCollectionJob
            }
            HomeRowDefinition.SYSTEM_EMBY -> {
                scheduleEmbyCollectionRows(onDeck, sectionItems(), released, episodes, ranked)
                embyCollectionJob
            }
            else -> null
        }
        sections.take(2).forEach { metadataPrefetcher.section(it.key) }
        val fetchedRows = keepPendingCollectionRows(system, rows(), enrichment)
        val completed = if (failed.get() == 0) fetchedRows else {
            val oldRows = (_uiState.value as? HomeUiState.Loaded)?.rows.orEmpty().filter { it.id.startsWith("$system.") }
            val ids = fetchedRows.mapTo(hashSetOf()) { it.id }
            fetchedRows + oldRows.filterNot { it.id in ids }
        }
        val retained = (_uiState.value as? HomeUiState.Loaded)?.rows.orEmpty().filterNot { it.id.startsWith("$system.") }
        publishRows(sortCombinedRows(completed + retained))
        completed
    }

    private suspend fun loadEmbyRows(): List<ContentRowData> {

        val sections = embyRepository.getLibrarySections().getOrElse { error ->

            if (_uiState.value !is HomeUiState.Loaded) _uiState.value = HomeUiState.Error(error.message ?: context.getString(R.string.error_generic))
            return emptyList()
        }
            .filterNot { it.key in appPreferences.embyHiddenSectionKeys }
        return loadProviderRows(
            embyRepository, sections, HomeRowDefinition.SYSTEM_EMBY, EMBY_HOME_PREVIEW_LIMIT, EMBY_HOME_RELEASE_PREVIEW_LIMIT,
        )
    }

    private fun scheduleEmbyCollectionRows(
        onDeck: List<MediaItem>,
        sectionItems: List<Pair<com.ambient.tv.domain.model.LibrarySection, List<MediaItem>>>,
        released: List<MediaItem>,
        recentEpisodes: List<MediaItem>,
        ranked: RankedData,
    ) {
        if (configuredRow(HomeRowDefinition.SYSTEM_EMBY, "collections") == null ||
            sectionItems.isEmpty() || embyCollectionJob?.isActive == true
        ) return
        embyCollectionJob = viewModelScope.launch {
            embyRepository.enrichCollections(sectionItems.flatMap { it.second }).onSuccess { enriched ->
                val byId = enriched.associateBy(MediaItem::ratingKey)
                val enrichedSections = sectionItems.map { (section, items) ->
                    section to items.map { byId[it.ratingKey] ?: it }
                }
                val updated = buildLocalRows(onDeck, enrichedSections, released, HomeRowDefinition.SYSTEM_EMBY, recentEpisodes, ranked)
                val current = _uiState.value as? HomeUiState.Loaded ?: return@onSuccess
                val retained = current.rows.filterNot { it.id.startsWith("${HomeRowDefinition.SYSTEM_EMBY}.") }
                publishRows(sortCombinedRows(retained + updated))
            }
        }
    }

    private suspend fun loadJellyfinRows(): List<ContentRowData> {

        val sections = jellyfinRepository.getLibrarySections().getOrElse { error ->

            if (_uiState.value !is HomeUiState.Loaded) _uiState.value = HomeUiState.Error(error.message ?: context.getString(R.string.error_generic))
            return emptyList()
        }
            .filterNot { it.key in appPreferences.jellyfinHiddenSectionKeys }
        return loadProviderRows(
            jellyfinRepository, sections, HomeRowDefinition.SYSTEM_JELLYFIN, JELLYFIN_HOME_PREVIEW_LIMIT, JELLYFIN_HOME_RELEASE_PREVIEW_LIMIT,
        )
    }

    private fun scheduleJellyfinCollectionRows(
        onDeck: List<MediaItem>,
        sectionItems: List<Pair<com.ambient.tv.domain.model.LibrarySection, List<MediaItem>>>,
        released: List<MediaItem>,
        recentEpisodes: List<MediaItem>,
        ranked: RankedData,
    ) {
        if (configuredRow(HomeRowDefinition.SYSTEM_JELLYFIN, "collections") == null ||
            sectionItems.isEmpty() || jellyfinCollectionJob?.isActive == true
        ) return
        jellyfinCollectionJob = viewModelScope.launch {
            jellyfinRepository.enrichCollections(sectionItems.flatMap { it.second }).onSuccess { enriched ->
                val byId = enriched.associateBy(MediaItem::ratingKey)
                val enrichedSections = sectionItems.map { (section, items) ->
                    section to items.map { byId[it.ratingKey] ?: it }
                }
                val updated = buildLocalRows(onDeck, enrichedSections, released, HomeRowDefinition.SYSTEM_JELLYFIN, recentEpisodes, ranked)
                val current = _uiState.value as? HomeUiState.Loaded ?: return@onSuccess
                val retained = current.rows.filterNot { it.id.startsWith("${HomeRowDefinition.SYSTEM_JELLYFIN}.") }
                publishRows(sortCombinedRows(retained + updated))
            }
        }
    }

    private fun sortCombinedRows(rows: List<ContentRowData>): List<ContentRowData> {
        // Provider landing pages use their own feed order; only Home follows the editor.
        if (plexOnly || jellyfinOnly || embyOnly) return rows
        val order = appPreferences.homeRows.withIndex().associate { indexed ->
            "${indexed.value.system}.${indexed.value.contentKey}" to indexed.index
        }
        return rows.sortedBy { order[homeRowSource(it.id)] ?: Int.MAX_VALUE }
    }

    /** Newest episodes of every show library; only the "recent" widget's episode row needs them. */
    private suspend fun loadRecentEpisodes(
        repository: MediaRepository,
        sections: List<com.ambient.tv.domain.model.LibrarySection>,
        system: String,
    ): List<MediaItem> {
        if (configuredRow(system, "recent") == null) return emptyList()
        return coroutineScope {
            sections.filter { it.type in EPISODE_SECTION_TYPES }.map { section ->
                async {
                    limitedHomeFetch {
                        repository.getRecentEpisodes(section.key, HOME_EPISODE_PREVIEW_LIMIT).getOrElse { repository.cachedRecentEpisodes(section.key, HOME_EPISODE_PREVIEW_LIMIT).orEmpty() }
                    }
                }
            }.awaitAll().flatten()
        }
    }

    private fun cachedRecentEpisodes(
        repository: MediaRepository,
        sections: List<com.ambient.tv.domain.model.LibrarySection>,
    ): List<MediaItem> = sections.filter { it.type in EPISODE_SECTION_TYPES }
        .flatMap { repository.cachedRecentEpisodes(it.key, HOME_EPISODE_PREVIEW_LIMIT).orEmpty() }

    /** "Top in genre" rows of [system]: one per genre the Home editor lists for it. */
    private fun genreRows(system: String): List<HomeRowDefinition> =
        appPreferences.homeRows.filter { it.system == system && it.genre != null }
            .distinctBy { it.contentKey.lowercase() }
            .mapNotNull { configuredRow(system, it.contentKey) }

    private fun wantedRecommendations(system: String): List<LibraryRecommendation> = buildList {
        // "Because you watched" starts from what was watched last.
        if (hasEnabledRow(system, HomeRowDefinition.CONTENT_WATCHED) || hasEnabledRow(system, HomeRowDefinition.CONTENT_BECAUSE_WATCHED)) {
            add(LibraryRecommendation.RecentlyWatched)
        }
        if (hasEnabledRow(system, HomeRowDefinition.CONTENT_TOP_UNWATCHED)) add(LibraryRecommendation.TopUnwatched)
        genreRows(system).forEach { row -> row.genre?.let { add(LibraryRecommendation.TopGenre(it)) } }
    }

    private fun wantsHubs(system: String) =
        system == HomeRowDefinition.SYSTEM_PLEX && hasEnabledRow(system, HomeRowDefinition.CONTENT_HUBS)

    /** One list per recommendation across all libraries of a system. */
    private fun rankAcrossLibraries(recommendation: LibraryRecommendation, items: List<MediaItem>): List<MediaItem> = when (recommendation) {
        LibraryRecommendation.RecentlyWatched -> items.sortedByDescending { it.lastViewedAt ?: 0L }
            // One tile per series, as in "recently added episodes".
            .distinctBy { it.grandparentRatingKey ?: it.canonicalContentKey }
        // A title that was started belongs to "continue watching", not to the picks.
        LibraryRecommendation.TopUnwatched -> items
            .filter { !it.isWatched && (it.viewedLeafCount ?: 0) == 0 && (it.viewOffsetMs ?: 0L) == 0L }
            .sortedByDescending { it.recommendationScore }
            .distinctBy { it.canonicalContentKey }
        is LibraryRecommendation.TopGenre -> items.sortedByDescending { it.recommendationScore }
            .distinctBy { it.canonicalContentKey }
    }

    /** Titles to look up similar ones for: the series of an episode, otherwise the title itself. */
    private fun becauseSeeds(watched: List<MediaItem>): List<Pair<String, String>> =
        watched.map { (it.grandparentRatingKey ?: it.ratingKey) to it.posterTitle }
            .distinctBy { it.first }
            .take(BECAUSE_SEED_LIMIT)

    private suspend fun loadRanked(repository: MediaRepository, sections: List<LibrarySection>, system: String): RankedData = coroutineScope {
        val libraries = sections.filter { it.type in VIDEO_SECTION_TYPES }
        val lists = wantedRecommendations(system).map { recommendation ->
            async {
                val items = libraries.map { section ->
                    async {
                        limitedHomeFetch {
                            repository.getRecommendation(section.key, recommendation, HOME_RANKED_LIMIT).getOrElse { repository.cachedRecommendation(section.key, recommendation, HOME_RANKED_LIMIT).orEmpty() }
                        }
                    }
                }.awaitAll().flatten()
                recommendation to rankAcrossLibraries(recommendation, items)
            }
        }
        val hubs = if (wantsHubs(system)) libraries.map { section ->
            async {
                limitedHomeFetch {
                    repository.getHubs(section.key, HOME_ROW_LIMIT).getOrElse { repository.cachedHubs(section.key, HOME_ROW_LIMIT).orEmpty() }.take(HUB_ROWS_PER_LIBRARY).map { section.key to it }
                }
            }
        } else emptyList()
        val ranked = lists.awaitAll().toMap()
        val similar = if (hasEnabledRow(system, HomeRowDefinition.CONTENT_BECAUSE_WATCHED)) {
            becauseSeeds(ranked[LibraryRecommendation.RecentlyWatched].orEmpty()).map { (key, _) ->
                async {
                    limitedHomeFetch { key to repository.getSimilar(key).getOrElse { repository.cachedSimilar(key).orEmpty() } }
                }
            }.awaitAll().toMap()
        } else emptyMap()
        RankedData(ranked, similar, hubs.awaitAll().flatten())
    }

    private fun cachedRanked(repository: MediaRepository, sections: List<LibrarySection>, system: String): RankedData {
        val libraries = sections.filter { it.type in VIDEO_SECTION_TYPES }
        val ranked = wantedRecommendations(system).associateWith { recommendation ->
            rankAcrossLibraries(recommendation, libraries.flatMap { repository.cachedRecommendation(it.key, recommendation, HOME_RANKED_LIMIT).orEmpty() })
        }
        val similar = becauseSeeds(ranked[LibraryRecommendation.RecentlyWatched].orEmpty())
            .mapNotNull { (key, _) -> repository.cachedSimilar(key)?.let { key to it } }.toMap()
        val hubs = if (wantsHubs(system)) libraries.flatMap { section ->
            repository.cachedHubs(section.key, HOME_ROW_LIMIT).orEmpty().take(HUB_ROWS_PER_LIBRARY).map { section.key to it }
        } else emptyList()
        return RankedData(ranked, similar, hubs)
    }

    private fun rankedRows(system: String, ranked: RankedData): List<ContentRowData> = buildList {
        fun row(definition: HomeRowDefinition, title: String, items: List<MediaItem>, id: String = "$system.${definition.contentKey}") {
            if (items.isNotEmpty()) add(ContentRowData(title, items.take(HOME_ROW_LIMIT), definition.tileStyle(), id, showDescription = false))
        }
        configuredRow(system, HomeRowDefinition.CONTENT_TOP_UNWATCHED)?.let { definition ->
            row(definition, definition.title.ifBlank { context.getString(R.string.home_top_unwatched) }, ranked.lists[LibraryRecommendation.TopUnwatched].orEmpty())
        }
        genreRows(system).forEach { definition ->
            val genre = definition.genre ?: return@forEach
            row(definition, definition.title.ifBlank { context.getString(R.string.home_top_genre, genre) }, ranked.lists[LibraryRecommendation.TopGenre(genre)].orEmpty())
        }
        val watched = ranked.lists[LibraryRecommendation.RecentlyWatched].orEmpty()
        configuredRow(system, HomeRowDefinition.CONTENT_BECAUSE_WATCHED)?.let { definition ->
            // The most recently watched title the server knows something similar and unseen for.
            becauseSeeds(watched).firstNotNullOfOrNull { (key, title) ->
                ranked.similar[key]?.filterNot { it.isWatched }?.takeIf { it.isNotEmpty() }?.let { title to it }
            }?.let { (title, items) ->
                row(definition, definition.title.ifBlank { context.getString(R.string.home_because_watched, title) }, items)
            }
        }
        configuredRow(system, HomeRowDefinition.CONTENT_WATCHED)?.let { definition ->
            row(definition, definition.title.ifBlank { context.getString(R.string.home_recently_watched) }, watched)
        }
        configuredRow(system, HomeRowDefinition.CONTENT_HUBS)?.let { definition ->
            ranked.hubs.distinctBy { (sectionKey, hub) -> "$sectionKey.${hub.id}" }.take(MAX_HUB_ROWS).forEach { (sectionKey, hub) ->
                row(definition, hub.title, hub.items, "$system.hub.$sectionKey.${hub.id}")
            }
        }
    }

    private fun buildLocalRows(
        onDeck: List<MediaItem>,
        sectionItems: List<Pair<com.ambient.tv.domain.model.LibrarySection, List<MediaItem>>>,
        releasedSource: List<MediaItem>,
        system: String,
        recentEpisodeSource: List<MediaItem> = emptyList(),
        ranked: RankedData = RankedData(),
    ): List<ContentRowData> {
        val rows = mutableListOf<ContentRowData>()
        val allItems = sectionItems.flatMap { it.second }
            .distinctBy { it.canonicalContentKey }

        val continueRow = configuredRow(system, "continue")
        if (continueRow != null && onDeck.isNotEmpty()) {
            rows += ContentRowData(
                continueRow.title.ifBlank { context.getString(R.string.home_continue_watching) },
                onDeck.distinctBy { it.canonicalContentKey },
                continueRow.tileStyle(default = MediaTileStyle.CONTINUE),
                "$system.continue",
                showDescription = false,
            )
        }

        val recentRow = configuredRow(system, "recent")
        if (recentRow != null) {
            // Like Plex, "recently added" is one row of movies and one of single episodes.
            fun recentTitle(default: Int, kind: Int) = recentRow.title.takeIf { it.isNotBlank() }
                ?.let { "$it · ${context.getString(kind)}" } ?: context.getString(default)
            val recentMovies = allItems.filter { it.type == "movie" && it.addedAt != null }
                .sortedByDescending { it.addedAt }
                .take(HOME_ROW_LIMIT)
            if (recentMovies.isNotEmpty()) {
                rows += ContentRowData(recentTitle(R.string.home_recently_added_movies, R.string.search_movies), recentMovies, recentRow.tileStyle(), "$system.recentlyAddedMovies", showDescription = false)
            }

            // A freshly added season would otherwise fill the whole row with a single series.
            val recentEpisodes = recentEpisodeSource.filter { it.type == "episode" && it.addedAt != null }
                .sortedWith(compareByDescending<MediaItem> { it.addedAt }.thenByDescending { it.parentIndex ?: -1 }.thenByDescending { it.index ?: -1 })
                .distinctBy { it.grandparentRatingKey ?: it.grandparentTitle ?: it.ratingKey }
                .take(HOME_ROW_LIMIT)
            if (recentEpisodes.isNotEmpty()) {
                rows += ContentRowData(recentTitle(R.string.home_recently_added_episodes, R.string.search_episodes), recentEpisodes, recentRow.tileStyle(), "$system.recentlyAddedEpisodes", showDescription = false)
            }

            val recentlyReleased = releasedSource.distinctBy { it.canonicalContentKey }
                .filter { !it.originallyAvailableAt.isNullOrBlank() }
                .sortedByDescending { it.originallyAvailableAt }
                .take(HOME_ROW_LIMIT)
            if (recentlyReleased.isNotEmpty()) {
                rows += ContentRowData(context.getString(R.string.home_recently_released), recentlyReleased, recentRow.tileStyle(), "$system.recentlyReleased", showDescription = false)
            }
        }

        rows += rankedRows(system, ranked)

        val collectionRow = configuredRow(system, "collections")
        if (collectionRow != null) {
            val collections = allItems.flatMap { item -> item.collections.map { it to item } }
                .groupBy({ it.first.id ?: it.first.name.lowercase() }, { it.second })
                .mapValues { (_, items) -> items.distinctBy { it.canonicalContentKey } }
                .filterValues { it.size >= 2 }
                .entries
                .sortedByDescending { it.value.size }
                .take(MAX_COLLECTION_ROWS)
            collections.forEach { (key, items) ->
                val name = items.firstNotNullOfOrNull { item ->
                    item.collections.firstOrNull { (it.id ?: it.name.lowercase()) == key }?.name
                } ?: return@forEach
                rows += ContentRowData(
                    name,
                    items.take(HOME_ROW_LIMIT),
                    collectionRow.tileStyle(),
                    "$system.collection.$key",
                    showDescription = false,
                )
            }
        }

        val libraryRow = configuredRow(system, "libraries")
        if (libraryRow != null) {
            sectionItems.filter { it.second.isNotEmpty() }.forEach { (section, items) ->
                rows += ContentRowData(
                    section.title,
                    items.take(HOME_ROW_LIMIT),
                    libraryRow.tileStyle(),
                    "$system.library.${section.key}",
                    showDescription = false,
                )
            }
        }

        val order = appPreferences.homeRows.withIndex().associate { indexed ->
            "${indexed.value.system}.${indexed.value.contentKey}" to indexed.index
        }
        return if (plexOnly || jellyfinOnly || embyOnly) rows else rows.sortedBy { order[homeRowSource(it.id)] ?: Int.MAX_VALUE }
    }

    /** Live TV rows; TVHeadend answers from its cache at once while the server sleeps. */
    private fun startTvPreview() {
        tvPreviewJob?.cancel()
        tvPreviewJob = viewModelScope.launch {
            tvPreview = loadLiveTvPreview()
            tvPreviewComplete = true
            val current = _uiState.value as? HomeUiState.Loaded
            if (current != null) publishRows(current.rows)
            else if (tvPreview.live.isNotEmpty() || tvPreview.recent.isNotEmpty() ||
                (!appPreferences.isConfigured && !appPreferences.isJellyfinConfigured && !appPreferences.isEmbyConfigured)) publishRows(emptyList())
        }
    }

    private suspend fun loadLiveTvPreview(): HomeTvPreview = kotlinx.coroutines.coroutineScope {
        val repositories = liveTvRegistry.configured().filter {
            hasEnabledRow(it.source.system, "live") || hasEnabledRow(it.source.system, "recent_channels")
        }
        val previews = repositories.map { repo -> async {
            suspendResult {
                if (!repo.capabilities().available) return@suspendResult HomeTvPreview()
                val channels = repo.channels()
                val historyScope = if (repo.source.system == "tvheadend") appPreferences.tvheadendHistoryScope else repo.source.id
                val recent = appPreferences.recentTvChannelIds(historyScope)
                val showLive = hasEnabledRow(repo.source.system, "live")
                val showRecent = hasEnabledRow(repo.source.system, "recent_channels")
                val headers = repo.authHeaders()
                fun item(channel: TvhChannel) = HomeLiveTvItem(channel, null, headers, repo.source.name)
                val preview = HomeTvPreview(
                    if (showLive) channels.take(HOME_LIVE_TV_LIMIT).map(::item) else emptyList(),
                    if (showRecent) recent.mapNotNull { id -> channels.firstOrNull { it.uuid == id }?.let(::item) } else emptyList())
                // Publish usable channel cards before waiting for EPG.
                tvPreview = HomeTvPreview(
                    tvPreview.live.filterNot { it.channel.sourceId == repo.source.id } + preview.live,
                    tvPreview.recent.filterNot { it.channel.sourceId == repo.source.id } + preview.recent,
                )
                (_uiState.value as? HomeUiState.Loaded)?.let { publishRows(it.rows) }
                val events = suspendResult { repo.nowEvents() }.getOrDefault(emptyList()).associateBy { it.channelUuid }
                HomeTvPreview(preview.live.map { it.copy(event = events[it.channel.uuid]) },
                    preview.recent.map { it.copy(event = events[it.channel.uuid]) })
            }.getOrDefault(HomeTvPreview())
        } }.map { it.await() }
        HomeTvPreview(previews.flatMap { it.live }, previews.flatMap { it.recent })
    }

    private fun loadPhotoRow() {
        photoJob?.cancel()
        val definition = if (plexOnly || jellyfinOnly || embyOnly) null else configuredRow(HomeRowDefinition.SYSTEM_PHOTOS, "recent")
        if (definition == null) { photoRow = null; return }
        photoJob = viewModelScope.launch {
            val photos = photoRepository.recent()
            photoRow = photos.takeIf { it.isNotEmpty() }?.let { entries ->
                ContentRowData(
                    title = definition.displayTitle(context),
                    items = entries.map { it.toHomeItem() },
                    style = definition.tileStyle(MediaTileStyle.LANDSCAPE),
                    id = PHOTO_ROW_ID,
                    showDescription = false,
                )
            }
            val current = _uiState.value as? HomeUiState.Loaded
            if (current != null) publishRows(current.rows)
        }
    }

    private fun loadTrailerRows() {
        trailerJob?.cancel()
        if (plexOnly || jellyfinOnly || embyOnly || !appPreferences.trailersEnabled || !trailerRepository.configured) {
            trailerRows = emptyList()
            return
        }
        val definitions = appPreferences.homeRows.filter { it.enabled && it.system == HomeRowDefinition.SYSTEM_TRAILERS }
            .distinctBy { it.contentKey }
        val enabledIds = definitions.mapTo(HashSet()) { "trailers.${it.contentKey}" }
        trailerRows = trailerRows.filter { it.id in enabledIds }
        if (definitions.isEmpty()) return
        trailerJob = viewModelScope.launch {
            val locale = context.resources.configuration.locales[0]
            for (definition in definitions) {
                val feed = com.ambient.tv.trailers.TrailerFeed.entries.firstOrNull { it.name == definition.contentKey } ?: continue
                val language = when (appPreferences.trailerLanguage) { "de" -> "de-DE"; "en" -> "en-US"; else -> locale.toLanguageTag() }
                val result = trailerRepository.feed(feed, language, locale.country.ifBlank { "US" })
                if (result.isFailure) continue
                val entries = result.getOrThrow()
                trailerRows = trailerRows.filterNot { it.id == "trailers.${feed.name}" }
                if (entries.isNotEmpty()) trailerRows = trailerRows + ContentRowData(
                    title = definition.displayTitle(context),
                    items = entries.distinctBy { it.videos.first().key }.map { entry ->
                        MediaItem(ratingKey = "trailer-video:${entry.videos.first().key}", title = entry.title.displayTitle,
                            type = "trailer", index = null, thumbUrl = entry.title.posterUrl ?: entry.title.imageUrl, artUrl = entry.title.imageUrl,
                            year = entry.title.year, summary = entry.title.overview, tagline = null, durationMs = null,
                            viewOffsetMs = null, parentTitle = null, parentRatingKey = null, grandparentTitle = null,
                            grandparentRatingKey = null, contentRating = null, rating = null, studio = "TMDB")
                    }, style = homeRowTileStyle(definition.displayMode, MediaTileStyle.LANDSCAPE), id = "trailers.${feed.name}", showDescription = false)
                // Trailers are an extra row: they must not cover an offline or error page.
                if (!_uiState.value.isFailurePage()) publishRows((_uiState.value as? HomeUiState.Loaded)?.rows.orEmpty())
            }
        }
    }

    private fun HomeUiState.isFailurePage() =
        this is HomeUiState.Offline || this is HomeUiState.Error || this is HomeUiState.WakeTimedOut

    /** Loaded only with trailer rows: no server content yet, so a server failure still shows. */
    private fun HomeUiState.Loaded.onlyTrailers() = rows.isNotEmpty() && rows.all { it.id.startsWith("trailers.") } &&
        liveTvItems.isEmpty() && recentTvItems.isEmpty() && tvRows.isEmpty()

    private fun com.ambient.tv.data.photos.PhotoEntry.toHomeItem() = MediaItem(
        ratingKey = com.ambient.tv.ui.photos.PhotoLaunch.HOME_PREFIX + id,
        title = title, type = "photo", index = null,
        thumbUrl = imageModel(640, 480) as? String, artUrl = imageModel(1920, 1080) as? String,
        year = null, summary = null, tagline = null, durationMs = null, viewOffsetMs = null,
        parentTitle = null, parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
        contentRating = null, rating = null, studio = null, addedAt = addedAt?.div(1000),
    )

    // Whether there is anything to show is decided before the cross-system row is added, so an
    // offline Home (no rows) still leaves the launcher's channels alone.
    private fun publishRows(rows: List<ContentRowData>) =
        publishRowsInternal(withAllContinueRow(applyOverviewStyle(rows)), hasRows = rows.isNotEmpty())

    /** Re-applies the system overview's page view. Rows already carry a resolved tile style. */
    private fun applyOverviewStyle(rows: List<ContentRowData>): List<ContentRowData> {
        if (systemOverviewPageMode() == null) return rows
        return rows.map { row ->
            val source = homeRowSource(row.id)
            val dot = source.indexOf('.')
            if (dot <= 0) return@map row
            val definition = configuredRow(source.substring(0, dot), source.substring(dot + 1)) ?: return@map row
            val default = if (source.substring(dot + 1) == "continue") MediaTileStyle.CONTINUE else MediaTileStyle.POSTER
            val style = definition.tileStyle(default)
            if (style == row.style) row else row.copy(style = style)
        }
    }

    fun setOverviewViewMode(mode: String) {
        if (systemOverviewPageMode() == null || mode !in com.ambient.tv.data.local.OVERVIEW_VIEW_MODES) return
        val system = when {
            plexOnly -> HomeRowDefinition.SYSTEM_PLEX
            jellyfinOnly -> HomeRowDefinition.SYSTEM_JELLYFIN
            else -> HomeRowDefinition.SYSTEM_EMBY
        }
        if (mode == appPreferences.overviewViewMode(system)) return
        appPreferences.setOverviewViewMode(system, mode)
        _overviewViewMode.value = mode
        (_uiState.value as? HomeUiState.Loaded)?.let { publishRows(it.rows) }
    }

    /** Null away from a system overview, so the app home keeps each row's own display mode. */
    private fun systemOverviewPageMode(): String? {
        val system = when {
            plexOnly -> HomeRowDefinition.SYSTEM_PLEX
            jellyfinOnly -> HomeRowDefinition.SYSTEM_JELLYFIN
            embyOnly -> HomeRowDefinition.SYSTEM_EMBY
            else -> return null
        }
        return appPreferences.overviewViewMode(system)
    }

    /** The Home editor's "Continue watching · all systems" row, when enabled (not on a system's own page). */
    private fun allContinueRow(): HomeRowDefinition? =
        if (plexOnly || jellyfinOnly || embyOnly) null else configuredRow(HomeRowDefinition.SYSTEM_ALL, "continue")

    /**
     * Adds (or refreshes) the cross-system continue row: every server's in-progress titles in one
     * row, most recently watched first, the same title from two servers once. Servers hidden as
     * unreachable are left out; until a server answered, its cached list stands in.
     */
    private fun withAllContinueRow(rows: List<ContentRowData>): List<ContentRowData> {
        val others = rows.filterNot { it.id == ALL_CONTINUE_ROW_ID }
        val definition = allContinueRow() ?: return others
        val sources = listOf(
            HomeRowDefinition.SYSTEM_PLEX to mediaRepository,
            HomeRowDefinition.SYSTEM_JELLYFIN to jellyfinRepository,
            HomeRowDefinition.SYSTEM_EMBY to embyRepository,
        ).filter { (system, _) -> system in appPreferences.allContinueSystems && homeSystemActive(system) }
        val items = sources.flatMap { (system, repository) ->
            // The cached list only while that server's own rows are on screen (not when offline).
            continueBySystem[system]
                ?: if (rows.any { it.id.startsWith("$system.") }) runCatching { repository.cachedOnDeck() }.getOrNull().orEmpty() else emptyList()
        }
            .sortedByDescending { it.lastViewedAt ?: 0L }
            .distinctBy { it.canonicalContentKey }
            .take(HOME_ROW_LIMIT)
        if (items.isEmpty()) return others
        val row = ContentRowData(
            definition.displayTitle(context),
            items,
            definition.tileStyle(default = MediaTileStyle.CONTINUE),
            ALL_CONTINUE_ROW_ID,
            showDescription = false,
        )
        return sortCombinedRows(others + row)
    }

    private fun publishRowsInternal(rows: List<ContentRowData>, hasRows: Boolean) {
        // The launcher's rows follow the server rows (enabled rows, their titles and display
        // setting; servers hidden as unreachable left out). The publisher waits until they settle.
        val launcherRows = rows.filterNot { homeRowHidden(it.id) }
        fun channel(selected: List<ContentRowData>, newestFirst: Boolean) = com.ambient.tv.launcher.LauncherChannelContent(
            name = selected.singleOrNull()?.title,
            titles = selected.flatMap { row -> row.items.map { it to row.style } }
                .let { pairs -> if (newestFirst) pairs.sortedByDescending { it.first.addedAt ?: 0L } else pairs }
                .map { (item, style) -> item.toLauncherTitle(style) },
        )
        // Offline, asleep or failed loads publish no rows; the launcher keeps the last ones, which
        // are exactly what wakes the server from the home screen.
        val mediaConfigured = appPreferences.isConfigured || appPreferences.isJellyfinConfigured || appPreferences.isEmbyConfigured
        if (hasRows || !mediaConfigured) launcherPublisher.scheduleChannels(
            // The cross-system row is already ordered by last watched; otherwise each server's row.
            continueWatching = channel(launcherRows.filter { it.id == ALL_CONTINUE_ROW_ID }
                .ifEmpty { launcherRows.filter { it.id.endsWith(".continue") } }, newestFirst = false),
            recentlyAdded = channel(launcherRows.filter { ".recentlyAdded" in it.id }, newestFirst = true),
            liveTv = launcherLiveTv().takeIf { live ->
                // Still loading, or empty because the live TV server is asleep/unreachable: keep the
                // launcher's last channels. Empty with no live rows or no live TV clears it.
                val liveWanted = !plexOnly && !jellyfinOnly && !embyOnly && liveTvRegistry.configured().isNotEmpty() &&
                    appPreferences.homeRows.any { it.enabled && it.contentKey in setOf("live", "recent_channels") }
                !liveWanted || (tvPreviewComplete && live.titles.isNotEmpty())
            },
        )
        @Suppress("NAME_SHADOWING")
        val rows = rows.filterNot { it.id == PHOTO_ROW_ID || it.id.startsWith("trailers.") || homeRowHidden(it.id) } +
            listOfNotNull(photoRow?.takeUnless { homeRowHidden(it.id) }) +
            trailerRows
        val liveTvItems = tvPreview.live
        val recentTvItems = tvPreview.recent
        val previous = _uiState.value as? HomeUiState.Loaded
        val shortcuts = if (plexOnly || jellyfinOnly || embyOnly) emptyList() else appPreferences.homeRows.filter { row ->
            row.enabled && homeSystemActive(row.system) && when (row.system) {
                HomeRowDefinition.SYSTEM_YOUTUBE -> true
                HomeRowDefinition.SYSTEM_SPOTIFY -> true
                HomeRowDefinition.SYSTEM_TVHEADEND -> row.contentKey !in setOf("live", "recent_channels")
                else -> false
            }
        }
        // A system's own page keeps the order its rows were built in; only Home follows the editor.
        val tvRows = appPreferences.homeRows.filter { it.enabled && it.contentKey in setOf("live", "recent_channels") }.mapNotNull { row ->
            val recent = row.contentKey == "recent_channels"
            val items = (if (recent) recentTvItems else liveTvItems).filter { it.channel.sourceId.substringBefore(':') == row.system }
            val key = (if (recent) "recent-tv" else "live-tv") + if (row.system == "tvheadend") "" else ":${row.system}"
            if (items.isEmpty()) null else key to HomeTvRow(items, row.displayTitle(context))
        }.toMap()
        val contentRowKeys = orderedHomeRowKeys(if (plexOnly || jellyfinOnly || embyOnly) emptyList() else appPreferences.homeRows, rows.map { it.id },
            shortcuts, tvRows.containsKey("live-tv"), tvRows.containsKey("recent-tv"), tvRows.keys.filter { ':' in it }.associateWith { key -> "${key.substringAfter(':')}.${if (key.startsWith("recent")) "recent_channels" else "live"}" })
        // An offline server whose rows are on Home keeps one entry there that wakes it. With no
        // other rows Home shows the full offline page instead.
        val rowKeys = if (contentRowKeys.isEmpty() || plexOnly || jellyfinOnly || embyOnly) contentRowKeys
        else contentRowKeys + unreachableServers.current().offline
            .filter { system -> appPreferences.homeRows.any { it.enabled && it.system == system } }
            .map { HOME_OFFLINE_ROW_PREFIX + it }
        val defaultItem = rows.firstNotNullOfOrNull { it.items.firstOrNull() }
        // Initial focus belongs to the first row on screen, which follows rowKeys (the editor
        // order), not the provider order of `rows`.
        val firstContentRow = rows.firstOrNull { it.id == rowKeys.firstOrNull() && it.items.isNotEmpty() }
        var savedFocus = if (activeFocusKnown) {
            HomeFocusSnapshot(activeFocusedRatingKey, activeFocusedRowId)
        } else {
            HomeFocusSnapshot(
                previous?.focusedRatingKey ?: persistedHomeFocusKey,
                previous?.focusedRowId ?: persistedHomeFocusRowId,
            )
        }
        var focusRecovery = previous?.focusRecovery
        if (previous != null && savedFocus.ratingKey != null && rows.none { row -> (savedFocus.rowId == null || row.id == savedFocus.rowId) && row.items.any { savedFocus.ratingKey in it.allRatingKeys } }) {
            val oldRow = previous.rows.firstOrNull { it.id == savedFocus.rowId }
            val newRow = rows.firstOrNull { it.id == savedFocus.rowId && it.items.isNotEmpty() }
                ?: rows.getOrNull(previous.rows.indexOf(oldRow).coerceIn(0, rows.lastIndex.coerceAtLeast(0)))
            val neighbour = com.ambient.tv.data.browse.survivingFocus(oldRow?.items.orEmpty().map { it.ratingKey }, newRow?.items.orEmpty().map { it.ratingKey }, savedFocus.ratingKey)
            savedFocus = HomeFocusSnapshot(neighbour, newRow?.id)
            focusRecovery = savedFocus
            activeFocusedRatingKey = neighbour
            activeFocusedRowId = newRow?.id
        }
        val savedContentFocusExists = savedFocus.ratingKey != null && rows.any { row ->
            row.items.any { savedFocus.ratingKey in it.allRatingKeys }
        }
        // A view with no snapshot starts at the top-left item. Once that concrete view has its
        // own snapshot, recreating its destination restores it instead of sharing Home's focus.
        val needsInitialTopLeft = (previous == null || previous.rowKeys.isEmpty()) && !savedContentFocusExists
        // Resolve the initial target while loading; the first actual focus event consumes it.
        val initialFocusTarget = if (needsInitialTopLeft || previous?.initialFocusTarget?.let { it != HomeInitialFocusTarget.NONE } == true) {
            when {
                rowKeys.firstOrNull()?.startsWith("live-tv") == true -> HomeInitialFocusTarget.LIVE_TV
                rowKeys.firstOrNull()?.startsWith("recent-tv") == true -> HomeInitialFocusTarget.RECENT_TV
                rowKeys.firstOrNull() == null -> HomeInitialFocusTarget.NONE
                else -> if (rowKeys.first().startsWith("shortcut:")) HomeInitialFocusTarget.SHORTCUT else HomeInitialFocusTarget.CONTENT
            }
        } else {
            HomeInitialFocusTarget.NONE
        }
        val focusedKey = when (initialFocusTarget) {
            HomeInitialFocusTarget.RESOLVING, HomeInitialFocusTarget.LIVE_TV, HomeInitialFocusTarget.RECENT_TV, HomeInitialFocusTarget.SHORTCUT -> null
            HomeInitialFocusTarget.CONTENT -> firstContentRow?.items?.first()?.ratingKey ?: defaultItem?.ratingKey
            HomeInitialFocusTarget.NONE -> savedFocus.ratingKey ?: defaultItem?.ratingKey
        }
        val focusedRowId = firstContentRow?.id?.takeIf { initialFocusTarget == HomeInitialFocusTarget.CONTENT }
            ?: savedFocus.rowId
            ?.takeIf { rowId -> rows.any { row -> row.id == rowId && focusedKey != null && row.items.any { focusedKey in it.allRatingKeys } } }
            ?: rows.firstOrNull { row -> focusedKey != null && row.items.any { focusedKey in it.allRatingKeys } }?.id
        val plexHero = focusedRowId?.let { rowId -> rows.firstOrNull { it.id == rowId } }
            ?.items?.firstOrNull { focusedKey in it.allRatingKeys }
            ?: rows.firstNotNullOfOrNull { it.items.firstOrNull() }
        val refreshedProviderHero = (_heroContent.value?.key ?: previous?.hero?.key)?.let { previousHeroKey ->
            when {
                previousHeroKey.startsWith("livetv:") -> (liveTvItems + recentTvItems).firstOrNull { item ->
                    previousHeroKey.startsWith("livetv:${item.channel.sourceId}:${item.channel.uuid}:")
                }?.toHeroContent()
                previousHeroKey.startsWith("shortcut:") -> shortcuts.firstOrNull { row ->
                    previousHeroKey == "shortcut:${row.id}"
                }?.toHeroContent()
                else -> null
            }
        }
        val hero = when {
            initialFocusTarget == HomeInitialFocusTarget.LIVE_TV -> tvRows[rowKeys.firstOrNull()]?.items?.firstOrNull()?.toHeroContent()
            initialFocusTarget == HomeInitialFocusTarget.RECENT_TV -> tvRows[rowKeys.firstOrNull()]?.items?.firstOrNull()?.toHeroContent()
            initialFocusTarget == HomeInitialFocusTarget.SHORTCUT -> shortcuts.firstOrNull()?.toHeroContent()
            initialFocusTarget == HomeInitialFocusTarget.NONE && refreshedProviderHero != null -> refreshedProviderHero
            plexHero != null -> plexHero.toHeroContent()
            liveTvItems.isNotEmpty() -> liveTvItems.first().toHeroContent()
            recentTvItems.isNotEmpty() -> recentTvItems.first().toHeroContent()
            else -> shortcuts.firstOrNull()?.toHeroContent()
        }
        activeFocusKnown = true
        activeFocusedRatingKey = focusedKey
        activeFocusedRowId = focusedRowId
        _heroContent.value = hero
        _uiState.value = HomeUiState.Loaded(
            rows = rows,
            focusRecovery = focusRecovery, focusedRatingKey = focusedKey, focusedRowId = focusedRowId, hero = hero,
            tvRows = tvRows, liveTvItems = liveTvItems, shortcuts = shortcuts, recentTvItems = recentTvItems, rowKeys = rowKeys,
            liveTvTitle = configuredRow(HomeRowDefinition.SYSTEM_TVHEADEND, "live")?.displayTitle(context).orEmpty(),
            recentTvTitle = configuredRow(HomeRowDefinition.SYSTEM_TVHEADEND, "recent_channels")?.displayTitle(context).orEmpty(),
            initialFocusTarget = initialFocusTarget,
            loading = libraryLoading && previous?.loading != false,
        )
        // Artwork is decoded only by visible cards. Speculation warms metadata, never bitmaps.
    }

    private suspend fun <T> limitedHomeFetch(block: suspend () -> T): T =
        homeFetchLimiter.withPermit { block() }

    fun onItemFocused(rowId: String, item: MediaItem) {
        metadataPrefetcher.detail(item.ratingKey)
        val current = _uiState.value as? HomeUiState.Loaded ?: return
        val focusChanged = !activeFocusKnown || activeFocusedRatingKey != item.ratingKey || activeFocusedRowId != rowId
        val mustReleaseInitialFocus = current.initialFocusTarget != HomeInitialFocusTarget.NONE
        if (!focusChanged && !mustReleaseInitialFocus) return
        activeFocusKnown = true
        activeFocusedRatingKey = item.ratingKey
        activeFocusedRowId = rowId
        if (current.focusRecovery != null) _uiState.value = current.copy(focusRecovery = null)
        queueHomeFocusPersistence(item.ratingKey, rowId)
        _heroContent.value = item.toHeroContent()
        if (mustReleaseInitialFocus) {
            // Consume the initial request even when publishRows already selected this item.
            // Later provider updates must not move focus to a newly inserted row.
            _uiState.value = current.copy(
                focusedRatingKey = item.ratingKey,
                focusedRowId = rowId,
                hero = _heroContent.value,
                initialFocusTarget = HomeInitialFocusTarget.NONE,
            )
        }
    }

    fun onLiveTvFocused(item: HomeLiveTvItem) {
        val current = _uiState.value as? HomeUiState.Loaded ?: return
        val nextHero = item.toHeroContent()
        val mustReleaseInitialFocus = current.initialFocusTarget != HomeInitialFocusTarget.NONE
        val focusChanged = !activeFocusKnown || activeFocusedRatingKey != null || activeFocusedRowId != null || _heroContent.value?.key != nextHero.key
        if (!focusChanged && !mustReleaseInitialFocus) return
        activeFocusKnown = true
        activeFocusedRatingKey = null
        activeFocusedRowId = null
        homeFocusPersistenceJob?.cancel()
        _heroContent.value = nextHero
        if (mustReleaseInitialFocus) {
            _uiState.value = current.copy(
                focusedRatingKey = null,
                focusedRowId = null,
                hero = nextHero,
                initialFocusTarget = HomeInitialFocusTarget.NONE,
            )
        }
    }

    fun onShortcutFocused(row: HomeRowDefinition) {
        val current = _uiState.value as? HomeUiState.Loaded ?: return
        val nextHero = row.toHeroContent()
        val mustReleaseInitialFocus = current.initialFocusTarget != HomeInitialFocusTarget.NONE
        val focusChanged = !activeFocusKnown || activeFocusedRatingKey != null || activeFocusedRowId != null || _heroContent.value?.key != nextHero.key
        if (!focusChanged && !mustReleaseInitialFocus) return
        activeFocusKnown = true
        activeFocusedRatingKey = null
        activeFocusedRowId = null
        homeFocusPersistenceJob?.cancel()
        _heroContent.value = nextHero
        if (mustReleaseInitialFocus) {
            _uiState.value = current.copy(
                focusedRatingKey = null,
                focusedRowId = null,
                hero = nextHero,
                initialFocusTarget = HomeInitialFocusTarget.NONE,
            )
        }
    }

    private fun queueHomeFocusPersistence(ratingKey: String, rowId: String) {
        homeFocusPersistenceJob?.cancel()
        if (ratingKey == persistedHomeFocusKey && rowId == persistedHomeFocusRowId) return
        homeFocusPersistenceJob = viewModelScope.launch {
            delay(HOME_FOCUS_PERSIST_DEBOUNCE_MS)
            if (activeFocusedRatingKey == ratingKey && activeFocusedRowId == rowId) {
                appPreferences.setSectionFocusSnapshot(homeFocusStateKey, ratingKey, rowId)
                persistedHomeFocusKey = ratingKey
                persistedHomeFocusRowId = rowId
            }
        }
    }

    fun retry() = start(
        plexOnly = plexOnly,
        jellyfinOnly = jellyfinOnly,
        embyOnly = embyOnly,
        focusStateKey = homeFocusStateKey,
    )

    /**
     * The launcher's Live TV channel: recently watched channels first, then the live row, only
     * from systems whose Home TV rows are enabled and whose server is not hidden as unreachable.
     */
    private fun launcherLiveTv(): com.ambient.tv.launcher.LauncherChannelContent {
        val enabled = appPreferences.homeRows.filter { it.enabled && it.contentKey in setOf("live", "recent_channels") }
        fun allowed(item: HomeLiveTvItem, key: String): Boolean {
            val system = item.channel.sourceId.substringBefore(':').ifBlank { HomeRowDefinition.SYSTEM_TVHEADEND }
            return enabled.any { it.system == system && it.contentKey == key } && !homeRowHidden("$system.$key")
        }
        val items = (tvPreview.recent.filter { allowed(it, "recent_channels") } + tvPreview.live.filter { allowed(it, "live") })
            .distinctBy { it.channel.sourceId to it.channel.uuid }
        return com.ambient.tv.launcher.LauncherChannelContent(
            name = null,
            titles = items.map { item ->
                val channel = item.channel
                com.ambient.tv.launcher.LauncherTitle(
                    key = "${channel.sourceId}:${channel.uuid}",
                    title = listOfNotNull(channel.numberLabel, channel.name).joinToString(" "),
                    subtitle = item.event?.title,
                    description = item.event?.let { it.subtitle ?: it.summary ?: it.description },
                    artworkUrl = channel.iconUrl ?: item.event?.imageUrl,
                    artworkHeaders = item.imageHeaders,
                    landscapeArtwork = true,
                    liveChannel = com.ambient.tv.launcher.LiveChannelRef(channel.uuid, channel.sourceId),
                )
            },
        )
    }

    private fun homeMediaSystem(system: String): com.ambient.tv.ui.common.MediaSystem? = when (system) {
        HomeRowDefinition.SYSTEM_PLEX -> com.ambient.tv.ui.common.MediaSystem.PLEX
        HomeRowDefinition.SYSTEM_JELLYFIN -> com.ambient.tv.ui.common.MediaSystem.JELLYFIN
        HomeRowDefinition.SYSTEM_EMBY -> com.ambient.tv.ui.common.MediaSystem.EMBY
        else -> null
    }

    private fun homeRowHidden(id: String): Boolean {
        val hidden = unreachableServers.current().offline
        if (hidden.isEmpty()) return false
        return id.substringBefore('.') in hidden
    }

    private fun homeSystemActive(system: String): Boolean {
        if (system in unreachableServers.current().offline) return false
        return homeSystemReady(
        system,
        plexReady = plexOnly || appPreferences.isConfigured,
        jellyfinReady = jellyfinOnly || appPreferences.isJellyfinConfigured,
        tvheadendReady = appPreferences.isTvheadendConfigured,
        youtubeReady = appPreferences.youtubeEnabled,
        spotifyReady = appPreferences.spotifyEnabled && spotifyRepository.nativeAvailable && spotifyRepository.webAuthorized,
        embyReady = embyOnly || appPreferences.isEmbyConfigured,
        photosReady = photoRepository.hasAnyPhotoSource(),
        trailersReady = appPreferences.trailersEnabled && trailerRepository.configured,
        )
    }

    private fun configuredRow(system: String, contentKey: String): HomeRowDefinition? {
        if (!homeSystemActive(system)) return null
        val ownPage = (plexOnly && system == HomeRowDefinition.SYSTEM_PLEX) ||
            (jellyfinOnly && system == HomeRowDefinition.SYSTEM_JELLYFIN) ||
            (embyOnly && system == HomeRowDefinition.SYSTEM_EMBY)
        // A system's own page shows every kind of row unless it was switched off for that system.
        if (ownPage && "$system.${homeRowType(contentKey)}" in appPreferences.systemHomeHiddenRows) return null
        val configured = appPreferences.homeRows.firstOrNull {
            it.enabled && it.system == system && it.contentKey == contentKey
        }
        if (configured != null) return configured
        return if (ownPage) {
            HomeRowDefinition(
                system = system,
                contentKey = contentKey,
                displayMode = HomeRowDefinition.DISPLAY_POSTER,
            )
        } else {
            null
        }
    }

    private fun hasEnabledRow(system: String, contentKey: String): Boolean = configuredRow(system, contentKey) != null

    private fun MediaItem.toHeroContent() = toHomeHeroContent()

    private fun HomeLiveTvItem.toHeroContent(): HomeHeroContent {
        val currentEvent = event
        return HomeHeroContent(
            key = "livetv:${channel.sourceId}:${channel.uuid}:${currentEvent?.eventId ?: "channel"}",
            title = currentEvent?.title ?: channel.name,
            artworkUrl = currentEvent?.imageUrl ?: channel.iconUrl,
            thumbnailUrl = channel.iconUrl,
            titleLogoUrl = channel.iconUrl,
            artworkIsLogo = currentEvent?.imageUrl == null,
            providerLabel = sourceName,
            // Without guide data the channel name is already the title.
            metadata = if (currentEvent == null) listOf(sourceName).filter { it.isNotBlank() } else emptyList(),
            imageHeaders = imageHeaders,
            subtitle = currentEvent?.let { it.subtitle?.takeIf { sub -> sub.isNotBlank() } ?: channel.name },
            // DVB guide data often repeats the subtitle as the short summary; skip the echo.
            summary = currentEvent?.let { event ->
                listOfNotNull(event.summary, event.description)
                    .firstOrNull { it.isNotBlank() && it.trim() != event.subtitle?.trim() }
            },
            live = currentEvent?.let {
                HomeHeroLive("${formatClock(it.startEpochSeconds)}–${formatClock(it.stopEpochSeconds)}", it.startEpochSeconds, it.stopEpochSeconds)
            },
        )
    }

    private fun HomeRowDefinition.toHeroContent() = HomeHeroContent(
        key = "shortcut:$id",
        title = displayTitle(context),
        providerLabel = when (system) {
            HomeRowDefinition.SYSTEM_YOUTUBE -> "SmartTube"
            HomeRowDefinition.SYSTEM_SPOTIFY -> "Spotify"
            HomeRowDefinition.SYSTEM_TVHEADEND -> "TV"
            HomeRowDefinition.SYSTEM_PLEX -> "Plex"
            HomeRowDefinition.SYSTEM_EMBY -> "Emby"
            else -> system.replaceFirstChar(Char::uppercase)
        },
    )

    private fun HomeRowDefinition.tileStyle(default: MediaTileStyle = MediaTileStyle.POSTER): MediaTileStyle =
        homeRowTileStyle(displayMode, default, overviewPageMode = systemOverviewPageMode())

    private companion object {
        const val ALL_CONTINUE_ROW_ID = "all.continue"
        const val HOME_FOCUS_STATE_KEY = "home"
        private const val HOME_FOCUS_PERSIST_DEBOUNCE_MS = 320L
        const val HOME_ROW_LIMIT = 24
        const val HOME_SOURCE_PREVIEW_LIMIT = 72
        const val HOME_RELEASE_PREVIEW_LIMIT = 32
        const val HOME_EPISODE_PREVIEW_LIMIT = 96
        const val HOME_RANKED_LIMIT = 48
        val VIDEO_SECTION_TYPES = setOf("movie", "show", "mixed")
        const val BECAUSE_SEED_LIMIT = 3
        // A library offers around ten hubs; several libraries would bury every row below them.
        const val HUB_ROWS_PER_LIBRARY = 5
        const val MAX_HUB_ROWS = 10
        val EPISODE_SECTION_TYPES = setOf("show", "mixed")
        const val JELLYFIN_HOME_PREVIEW_LIMIT = 48
        const val JELLYFIN_HOME_RELEASE_PREVIEW_LIMIT = 32
        const val EMBY_HOME_PREVIEW_LIMIT = 48
        const val EMBY_HOME_RELEASE_PREVIEW_LIMIT = 32
        const val MAX_COLLECTION_ROWS = 6
        const val PREFETCH_ITEMS_PER_ROW = 4
        const val PREFETCH_TOTAL_LIMIT = 12
        const val LIVE_TV_PREFETCH_TOTAL_LIMIT = 12
        const val LIVE_TV_PREFETCH_SIZE = 160
        /** Parallel Home metadata fetches (section/ranked/hubs) across backends. */
        const val HOME_FETCH_CONCURRENCY = 6
        const val HOME_LIVE_TV_LIMIT = 12
        val HOME_CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
    }

    private fun formatClock(epochSeconds: Long): String =
        HOME_CLOCK_FORMATTER.format(Instant.ofEpochSecond(epochSeconds))
}

internal fun homeRowTileStyle(
    displayMode: String,
    default: MediaTileStyle = MediaTileStyle.POSTER,
    overviewPageMode: String? = null,
): MediaTileStyle {
    // A system overview's page view replaces the poster default. A row the Home editor set to
    // landscape or list keeps that choice.
    val mode = when {
        overviewPageMode == null -> displayMode
        displayMode == HomeRowDefinition.DISPLAY_LANDSCAPE || displayMode == HomeRowDefinition.DISPLAY_LIST -> displayMode
        overviewPageMode in HomeRowDefinition.DISPLAY_MODES -> overviewPageMode
        else -> displayMode
    }
    return when (mode) {
        HomeRowDefinition.DISPLAY_POSTER -> MediaTileStyle.POSTER
        HomeRowDefinition.DISPLAY_LIST -> MediaTileStyle.LANDSCAPE
        HomeRowDefinition.DISPLAY_LANDSCAPE ->
            if (default == MediaTileStyle.CONTINUE) MediaTileStyle.CONTINUE else MediaTileStyle.LANDSCAPE
        else -> default
    }
}

private const val PHOTO_ROW_ID = "photos.recent"

/** Home row key of an offline server's wake entry: prefix + system key. */
const val HOME_OFFLINE_ROW_PREFIX = "offline:"
