package com.ambient.tv.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.data.dlna.DlnaServer
import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.connectivity.UnreachableServerGate
import com.ambient.tv.connectivity.HostConnectivity
import com.ambient.tv.data.browse.BrowseLoadCoordinator
import com.ambient.tv.data.browse.BrowseLoadPriority
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.telegram.TelegramRepository
import com.ambient.tv.data.local.SystemNameOverrides
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.data.jellyfin.JellyfinRepository
import com.ambient.tv.server.JellyfinConnectionManager
import com.ambient.tv.data.emby.EmbyRepository
import com.ambient.tv.server.EmbyConnectionManager
import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.util.suspendResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Bundled sidebar inputs so the rail observes one StateFlow instead of ~18 collects. */
data class SidebarUiState(
    val trailersEnabled: Boolean = true,
    val trailersConfigured: Boolean = false,
    val liveTvSources: List<com.ambient.tv.livetv.AvailableLiveTvSource> = emptyList(),
    val systemOrder: List<String> = emptyList(),
    val filesEnabled: Boolean = false,
    val gamesAvailable: Boolean = false,
    val gameSystemIds: List<String> = emptyList(),
    val networkFileSources: List<FileSource> = emptyList(),
    val dlnaServers: List<DlnaServer> = emptyList(),
    val plexEnabled: Boolean = false,
    val jellyfinEnabled: Boolean = false,
    val embyEnabled: Boolean = false,
    val youtubeEnabled: Boolean = false,
    val spotifyEnabled: Boolean = false,
    val radioEnabled: Boolean = false,
    /** RomM signed in and switched on; its platforms come from the device's catalog copy. */
    val rommEnabled: Boolean = false,
    val moonlightEnabled: Boolean = false,
    val moonlightHosts: List<com.ambient.tv.moonlight.MoonlightHost> = emptyList(),
    val rommPlatforms: List<com.ambient.tv.romm.RommMenuPlatform> = emptyList(),
    val weatherEnabled: Boolean = false,
    val telegramEnabled: Boolean = false,
    val telegramAuthorized: Boolean = false,
    val telegramHomeEnabled: Boolean = false,
    val telegramSubmenuKeys: List<String> = AppPreferences.DEFAULT_TELEGRAM_SUBMENU_KEYS,
    val radioHomeEnabled: Boolean = false,
    /** Address entered; TVHeadend stays in the menu even when its login was rejected. */
    val tvheadendEnabled: Boolean = false,
    /** Login rejected: the menu shows only "check sign-in", Home and search leave it out. */
    val tvheadendLoginRejected: Boolean = false,
    /** Only show the HbbTV browse entry after discovering TVHeadend applications without tuning. */
    val tvheadendHbbtvAvailable: Boolean = false,
    val youtubeFeedKeys: List<String> = listOf("home"),
    /** Section behind SmartTube's root route (see [Destinations.youtubeSection]). */
    val youtubeStartFeedKey: String = "home",
    val spotifySubmenuKeys: List<String> = emptyList(),
    val radioSubmenuKeys: List<String> = AppPreferences.DEFAULT_RADIO_SUBMENU_KEYS,
    val tvheadendSubmenuKeys: List<String> = emptyList(),
    val settingsShortcutHidden: Set<String> = emptySet(),
    /** Per-system entry order / dividers from the level editor (system id -> entry ids). */
    val entryOrder: Map<String, List<String>> = emptyMap(),
    val entryDividers: Map<String, List<String>> = emptyMap(),
    val dividersAfter: Set<String> = emptySet(),
    val spotifyAuthorized: Boolean = false,
    val spotifyNativeAvailable: Boolean = false,
    val sections: List<LibrarySection> = emptyList(),
    val jellyfinSections: List<LibrarySection> = emptyList(),
    val embySections: List<LibrarySection> = emptyList(),
    /** Confirmed host outages and unavailable public services; entries and focus IDs stay stable. */
    val offlineSystems: Set<String> = emptySet(),
)

@HiltViewModel
class SidebarViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val tokens: com.ambient.tv.data.local.TokenStore,
    private val liveTv: com.ambient.tv.livetv.LiveTvRegistry,
    private val tvheadendRepository: com.ambient.tv.tvheadend.data.TvheadendRepository,
    private val fileStore: FileStore,
    private val gameLibrary: com.ambient.tv.games.library.GameLibraryRepository,
    private val dlnaStore: DlnaStore,
    private val mediaRepository: MediaRepository,
    val metadataPrefetcher: com.ambient.tv.data.browse.MetadataPrefetcher,
    private val jellyfinRepository: JellyfinRepository,
    private val jellyfinConnectionManager: JellyfinConnectionManager,
    private val embyRepository: EmbyRepository,
    private val embyConnectionManager: EmbyConnectionManager,
    private val smartTubeEngine: SmartTubeEngine,
    private val spotifyProvider: javax.inject.Provider<SpotifyRepository>,
    private val telegramRepository: TelegramRepository,
    private val photoRepository: com.ambient.tv.data.photos.PhotoRepository,
    private val unreachableServers: UnreachableServerGate,
    private val hosts: HostConnectivity,
    private val onlineServices: com.ambient.tv.data.browse.OnlineServiceAvailability,
    private val loadCoordinator: BrowseLoadCoordinator,
    private val rommLibrary: com.ambient.tv.romm.RommLibrary,
    private val rommAccounts: com.ambient.tv.romm.RommAccountStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SidebarUiState(
            trailersEnabled = appPreferences.trailersEnabled,
            trailersConfigured = appPreferences.trailersEnabled && !tokens.tmdbApiKey.isNullOrBlank(),
            systemOrder = appPreferences.sidebarSystemOrder,
            filesEnabled = appPreferences.filesEnabled,
            gamesAvailable = gameLibrary.hasConfiguredFolders(),
            gameSystemIds = configuredGameSystemIds(),
            networkFileSources = networkFileSourcesFromStore(),
            dlnaServers = dlnaStore.servers(),
            plexEnabled = appPreferences.isConfigured,
            jellyfinEnabled = appPreferences.isJellyfinConfigured,
            embyEnabled = appPreferences.isEmbyConfigured,
            youtubeEnabled = appPreferences.youtubeEnabled,
            youtubeFeedKeys = appPreferences.youtubeFeedKeys.ifEmpty { listOf("home") },
            youtubeStartFeedKey = appPreferences.youtubeStartFeedKey,
            spotifyEnabled = appPreferences.spotifyEnabled,
            radioEnabled = appPreferences.radioEnabled,
            moonlightEnabled = com.ambient.tv.BuildConfig.MOONLIGHT_NATIVE && appPreferences.hasMoonlightHosts,
            moonlightHosts = if (com.ambient.tv.BuildConfig.MOONLIGHT_NATIVE)
                appPreferences.moonlightHosts else emptyList(),
            weatherEnabled = appPreferences.weatherSettings.let { it.enabled && it.showInSidebar },
            telegramEnabled = appPreferences.telegramEnabled,
            telegramAuthorized = telegramRepository.available,
            telegramHomeEnabled = appPreferences.homeRows.any { it.enabled && it.system == com.ambient.tv.data.local.HomeRowDefinition.SYSTEM_TELEGRAM },
            telegramSubmenuKeys = telegramUsableSections(appPreferences.telegramSubmenuKeys,
                appPreferences.youtubeEnabled && smartTubeEngine.available),
            radioHomeEnabled = appPreferences.homeRows.any { it.enabled && it.system == com.ambient.tv.data.local.HomeRowDefinition.SYSTEM_RADIO },
            tvheadendEnabled = appPreferences.hasTvheadendEndpoint,
            tvheadendLoginRejected = appPreferences.tvheadendLoginRejected,
            spotifySubmenuKeys = appPreferences.spotifySubmenuKeys,
            radioSubmenuKeys = appPreferences.radioSubmenuKeys,
            tvheadendSubmenuKeys = appPreferences.tvheadendSubmenuKeys,
            settingsShortcutHidden = appPreferences.sidebarSettingsShortcutHidden,
            dividersAfter = appPreferences.sidebarDividersAfter,
            entryOrder = appPreferences.sidebarEntryOrder,
            entryDividers = appPreferences.sidebarEntryDividers,
        ),
    )
    val uiState: StateFlow<SidebarUiState> = _uiState.asStateFlow()

    fun moveSystem(id: String, delta: Int, visible: List<String>) {
        val order = reorderedSidebarSystems(_uiState.value.systemOrder, id, delta, visible)
        appPreferences.sidebarSystemOrder = order
        _uiState.update { it.copy(systemOrder = order) }
    }

    /** Applies visible system order from the customize dialog (Fertig); disabled systems stay after. */
    fun applySystemOrder(order: List<String>) {
        val current = _uiState.value.systemOrder
        val merged = order + current.filterNot { it in order }
        if (merged == current) return
        appPreferences.sidebarSystemOrder = merged
        _uiState.update { it.copy(systemOrder = merged) }
    }

    /**
     * Saves the names chosen in the customize dialog; a blank name restores the provider's own.
     * [edited] names the systems the dialog could change, so overrides of systems it did not list
     * (a server currently hidden as unreachable) survive.
     */
    /** Per-level editor: order of one system's entries; saved immediately. */
    fun applyEntryOrder(systemId: String, order: List<String>) {
        val next = _uiState.value.entryOrder + (systemId to order)
        appPreferences.sidebarEntryOrder = next
        _uiState.update { it.copy(entryOrder = next) }
    }

    /** Per-level editor: divider below one of a system's entries; saved immediately. */
    fun toggleEntryDivider(systemId: String, id: String) {
        val current = _uiState.value.entryDividers[systemId].orEmpty().toSet()
        val next = _uiState.value.entryDividers + (systemId to sidebarToggleDivider(current, id).sorted())
        appPreferences.sidebarEntryDividers = next
        _uiState.update { it.copy(entryDividers = next) }
    }

    /** Menu editor: insert/remove the divider below [id]; saved immediately. */
    fun toggleDivider(id: String) {
        val next = sidebarToggleDivider(_uiState.value.dividersAfter, id)
        appPreferences.sidebarDividersAfter = next
        _uiState.update { it.copy(dividersAfter = next) }
    }

    fun renameSystems(names: Map<String, String>, edited: Set<String>) {
        val merged = appPreferences.systemNames.filterKeys { it !in edited } + names
        if (SystemNameOverrides.normalized(merged) != appPreferences.systemNames) appPreferences.systemNames = merged
    }

    private var refreshJob: Job? = null
    private var menuJob: Job? = null
    private var menuLoadingSystem: String? = null
    private var spotifyAuthJob: Job? = null
    private var gameSystemsJob: Job? = null

    val reconnectSources: Set<String>
        get() = buildSet {
            if (appPreferences.isConfigured) add("plex")
            if (appPreferences.isJellyfinConfigured) add("jellyfin")
            if (appPreferences.isEmbyConfigured) add("emby")
            if (appPreferences.hasTvheadendEndpoint) add("tvheadend")
            addAll(liveTv.configured().map { it.source.system })
            if (appPreferences.youtubeEnabled) add("youtube")
            if (appPreferences.spotifyEnabled) add("spotify")
            if (appPreferences.radioEnabled) add("radio")
            if (com.ambient.tv.BuildConfig.MONOGRAM_NATIVE && appPreferences.telegramEnabled) add("telegram")
            if (appPreferences.filesEnabled) add("files")
            if (gameLibrary.hasConfiguredFolders()) add("games")
            if (dlnaStore.servers().isNotEmpty()) add("dlna")
            if (appPreferences.isRommConfigured) add("romm")
            if (com.ambient.tv.BuildConfig.MOONLIGHT_NATIVE && appPreferences.hasMoonlightHosts) add("moonlight")
        }

    init {
        viewModelScope.launch {
            tvheadendRepository.hbbtvChanges.collect {
                _uiState.update { it.copy(tvheadendHbbtvAvailable = tvheadendRepository.hasCachedHbbtvApps()) }
            }
        }
        viewModelScope.launch {
            combine(rommLibrary.state, rommAccounts.changes, appPreferences.changes().onStart { emit(Unit) }) { _, _, _ -> Unit }
                .collect { refreshRomm() }
        }
        viewModelScope.launch {
            telegramRepository.authorized.collect { allowed ->
                _uiState.update { it.copy(telegramAuthorized = allowed) }
            }
        }
        viewModelScope.launch {
            observeSidebarAvailability(
                hosts.states,
                onlineServices.state,
                appPreferences.changes(),
                fileStore.revision,
                dlnaStore.serverRevision,
                com.ambient.tv.data.local.LoginStateSignal.generation,
                snapshot = ::currentOfflineSources,
            ).collect { offline -> _uiState.update { it.copy(offlineSystems = offline) } }
        }
        viewModelScope.launch {
            combine(gameLibrary.revisions, fileStore.revision) { _, _ -> gameLibrary.revisionSnapshot(includeOverrides = false) }
                .distinctUntilChanged().collect {
                    refreshGameSystems()
                }
        }
        viewModelScope.launch { dlnaStore.serverRevision.collect { _uiState.update { it.copy(dlnaServers = dlnaStore.servers()) } } }
        // Include the current snapshot: login may finish before these collectors start.
        viewModelScope.launch {
            observeSidebarAvailability(
                appPreferences.changes(),
                tokens.tmdbCredentialVersion,
                com.ambient.tv.data.local.LoginStateSignal.generation,
                snapshot = ::sidebarRefreshSnapshot,
            ).collect { refresh() }
        }
    }

    /**
     * Everything that can change the visible sidebar or one of its provider pages. Preference
     * writes are deliberately de-duplicated here so unrelated settings never trigger expensive
     * provider refreshes, while menu configuration changes can no longer get stuck until the next
     * system activation.
     */
    private fun sidebarRefreshSnapshot(): List<Any?> = listOf(
        appPreferences.isConfigured,
        appPreferences.isJellyfinConfigured,
        appPreferences.isEmbyConfigured,
        appPreferences.isTvheadendConfigured,
        appPreferences.hasTvheadendEndpoint,
        appPreferences.tvheadendHistoryScope,
        appPreferences.tvheadendUseHttps,
        appPreferences.tvheadendHtspPort,
        appPreferences.youtubeEnabled,
        appPreferences.spotifyEnabled,
        appPreferences.radioEnabled,
        appPreferences.moonlightEnabled,
        appPreferences.moonlightHosts,
        appPreferences.weatherSettings.let { it.enabled && it.showInSidebar },
        appPreferences.telegramEnabled,
        appPreferences.telegramSubmenuKeys,
        appPreferences.homeRows.any { it.enabled && it.system == com.ambient.tv.data.local.HomeRowDefinition.SYSTEM_RADIO },
        appPreferences.filesEnabled,
        gameLibrary.folders(),
        appPreferences.trailersEnabled && !tokens.tmdbApiKey.isNullOrBlank(),
        // Keep explicit auth completions observable even when every preference-derived boolean
        // stayed the same (for example SmartTube signing in while it was already enabled).
        com.ambient.tv.data.local.LoginStateSignal.generation.value,
        appPreferences.sidebarSystemOrder,
        appPreferences.sidebarSubmenuSnapshot(),
        appPreferences.sidebarSettingsShortcutHidden,
        appPreferences.sidebarDividersAfter,
        appPreferences.sidebarEntryOrder,
        appPreferences.sidebarEntryDividers,
        appPreferences.plexHiddenSectionKeys,
        appPreferences.jellyfinHiddenSectionKeys,
        appPreferences.embyHiddenSectionKeys,
    )

    /** Explicit assignments are available immediately; SQLite opens/migrations run on IO. */
    private fun configuredGameSystemIds(): List<String> =
        com.ambient.tv.games.GameSystems.sortIdsByName(gameLibrary.folders().filter { folder ->
            fileStore.sources().any { it.id == folder.sourceId }
        }.map { it.systemId }.filter { it != "auto" })

    private fun refreshGameSystems() {
        gameSystemsJob?.cancel()
        gameSystemsJob = viewModelScope.launch {
            val systems = try {
                kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                    com.ambient.tv.games.GameSystems.sortIdsByName(configuredGameSystemIds() + gameLibrary.systemIds())
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { configuredGameSystemIds() }
            _uiState.update { state -> state.copy(
                networkFileSources = networkFileSourcesFromStore(),
                gamesAvailable = gameLibrary.hasConfiguredFolders(),
                gameSystemIds = systems,
            ) }
        }
    }

    private var rommJob: Job? = null

    private fun refreshRomm() {
        rommJob?.cancel()
        rommJob = viewModelScope.launch {
            val enabled = rommAccounts.account() != null
            val platforms = if (!enabled) emptyList() else try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { rommLibrary.menuPlatforms() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
            _uiState.update { it.copy(rommEnabled = enabled, rommPlatforms = platforms) }
        }
    }

    private fun networkFileSourcesFromStore(): List<FileSource> =
        fileStore.fileBrowserSources().filter { it.network }

    private fun currentOfflineSources(): Set<String> {
        val configured = buildSet {
            if (appPreferences.isConfigured) add("plex")
            if (appPreferences.isJellyfinConfigured) add("jellyfin")
            if (appPreferences.isEmbyConfigured) add("emby")
            if (appPreferences.hasTvheadendEndpoint) add("tvheadend")
            if (appPreferences.isRommConfigured) add("romm")
            if (com.ambient.tv.BuildConfig.MOONLIGHT_NATIVE && appPreferences.hasMoonlightHosts)
                addAll(appPreferences.moonlightHosts.map { "moonlight:" + it.id })
            if (appPreferences.filesEnabled) addAll(networkFileSourcesFromStore().map { "files:${it.id}" })
            addAll(dlnaStore.servers().map { "dlna:${it.id}" })
        }
        val remote = buildSet {
            if (appPreferences.youtubeEnabled) add("youtube")
            if (appPreferences.radioEnabled) add("radio")
            if (appPreferences.weatherSettings.enabled && appPreferences.weatherSettings.showInSidebar) add("weather")
            if (appPreferences.telegramEnabled && telegramRepository.authorized.value) add("telegram")
            if (appPreferences.trailersEnabled && !tokens.tmdbApiKey.isNullOrBlank()) add("trailers")
            if (appPreferences.spotifyEnabled) add("spotify")
        }
        val offline = sidebarOfflineSources(configured, hosts::health) +
            onlineServices.state.value.unavailableServices(remote)
        val moonlight = configured.filter { it.startsWith("moonlight:") }
        return if (moonlight.isNotEmpty() && moonlight.all { it in offline }) offline + "moonlight" else offline
    }

    private fun observeSpotifyAuthorization() {
        if (appPreferences.spotifyEnabled && spotifyAuthJob == null) {
            val spotify = spotifyProvider.get()
            _uiState.update { it.copy(spotifyNativeAvailable = spotify.nativeAvailable) }
            spotifyAuthJob = viewModelScope.launch {
                spotify.authState.collect { auth ->
                    _uiState.update { it.copy(spotifyAuthorized = auth.authorized) }
                }
            }
        } else if (!appPreferences.spotifyEnabled) {
            spotifyAuthJob?.cancel()
            spotifyAuthJob = null
            _uiState.update { it.copy(spotifyAuthorized = false, spotifyNativeAvailable = false) }
        }
    }

    fun closeSystemMenu() {
        menuJob?.cancel()
        menuJob = null
        menuLoadingSystem = null
    }

    /**
     * Entering a sidebar system page is an explicit demand for its navigation data. A normal
     * startup/route refresh only restores persisted lists; do not fetch every server merely to
     * draw a collapsed sidebar. An abandoned menu cancels its unused request.
     */
    fun openSystemMenu(system: String) {
        if (menuLoadingSystem == system && menuJob?.isActive == true) return
        menuJob?.cancel()
        menuLoadingSystem = system
        menuJob = viewModelScope.launch {
            when (system) {
                "plex" -> if (appPreferences.isConfigured) {
                    mediaRepository.restoreCache()
                    val sections = loadCoordinator.librarySections("plex", BrowseLoadPriority.VISIBLE) {
                        mediaRepository.getLibrarySections()
                    }.getOrElse { mediaRepository.cachedLibrarySections().orEmpty() }
                        .filterNot { it.key in appPreferences.plexHiddenSectionKeys }
                    photoRepository.registerPhotoSections(
                        sections.filter { it.type == "photo" }.associate {
                            it.key to com.ambient.tv.data.photos.PhotoLibrary(
                                com.ambient.tv.data.photos.PlexPhotoSource.sectionKey(it.key),
                                it.title, com.ambient.tv.data.photos.PhotoSystem.PLEX,
                            )
                        },
                        com.ambient.tv.data.photos.PhotoSystem.PLEX,
                    )
                    _uiState.update { it.copy(sections = sections) }
                }
                "jellyfin" -> if (appPreferences.isJellyfinConfigured && jellyfinConnectionManager.ensureConnected()) {
                    val sections = loadCoordinator.librarySections("jellyfin", BrowseLoadPriority.VISIBLE) {
                        jellyfinRepository.getLibrarySections()
                    }.getOrElse { jellyfinRepository.cachedLibrarySections().orEmpty() }
                    _uiState.update { it.copy(jellyfinSections = withPhotoLibraries(
                        sections, com.ambient.tv.data.photos.PhotoSystem.JELLYFIN
                    ).filterNot { section -> section.key in appPreferences.jellyfinHiddenSectionKeys }) }
                }
                "emby" -> if (appPreferences.isEmbyConfigured && embyConnectionManager.ensureConnected()) {
                    val sections = loadCoordinator.librarySections("emby", BrowseLoadPriority.VISIBLE) {
                        embyRepository.getLibrarySections()
                    }.getOrElse { embyRepository.cachedLibrarySections().orEmpty() }
                    _uiState.update { it.copy(embySections = withPhotoLibraries(
                        sections, com.ambient.tv.data.photos.PhotoSystem.EMBY
                    ).filterNot { section -> section.key in appPreferences.embyHiddenSectionKeys }) }
                }
                "youtube" -> if (appPreferences.youtubeEnabled && smartTubeEngine.available) {
                    val supported = suspendResult {
                        loadCoordinator.request("youtube", "supported-feeds", BrowseLoadPriority.VISIBLE) {
                            smartTubeEngine.supportedFeedKeys()
                        }
                    }.getOrDefault(setOf("home"))
                    val keys = appPreferences.youtubeFeedKeys.filter { it in supported }
                        .ifEmpty { AppPreferences.YOUTUBE_FEED_KEYS.filter { it in supported } }
                        .ifEmpty { listOf("home") }
                    val start = appPreferences.youtubeStartFeedKey.takeIf { it in keys } ?: keys.first()
                    _uiState.update { it.copy(youtubeFeedKeys = keys, youtubeStartFeedKey = start) }
                }
                "tvheadend" -> if (appPreferences.isTvheadendConfigured) {
                    suspendResult {
                        loadCoordinator.request("tvheadend", "sidebar-hbbtv", BrowseLoadPriority.VISIBLE) {
                            tvheadendRepository.prepareHbbtvDiscovery()
                        }
                    }
                    _uiState.update { it.copy(tvheadendHbbtvAvailable = tvheadendRepository.hasCachedHbbtvApps()) }
                }
                "romm" -> if (rommAccounts.account() != null) {
                    // Opening RomM is a request for its menu: read the server again when the copy is old.
                    val stale = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { rommLibrary.catalog() }
                        ?.let { System.currentTimeMillis() - it.fetchedAt > 10 * 60 * 1000L } ?: true
                    if (stale) suspendResult { rommLibrary.refresh() }
                }
                "iptv" -> {
                    val sources = loadCoordinator.request("iptv", "sidebar-capabilities", BrowseLoadPriority.VISIBLE) {
                        liveTv.discover()
                    }
                    _uiState.update { it.copy(liveTvSources = com.ambient.tv.livetv.reconcileSidebarLiveTvSources(sources, liveTv.sidebarSnapshot())) }
                }
            }
        }
    }

    fun refreshAfterReconnect() = refresh(forceLiveTv = true)

    fun refresh(deferExpensiveWorkMs: Long = 0L, forceLiveTv: Boolean = false) {
        observeSpotifyAuthorization()
        refreshGameSystems()
        _uiState.update {
            it.copy(
                filesEnabled = appPreferences.filesEnabled,
                gamesAvailable = gameLibrary.hasConfiguredFolders(),
                networkFileSources = networkFileSourcesFromStore(),
                dlnaServers = dlnaStore.servers(),
                systemOrder = appPreferences.sidebarSystemOrder,
                trailersEnabled = appPreferences.trailersEnabled,
                trailersConfigured = appPreferences.trailersEnabled && !tokens.tmdbApiKey.isNullOrBlank(),
                plexEnabled = appPreferences.isConfigured,
                jellyfinEnabled = appPreferences.isJellyfinConfigured,
                embyEnabled = appPreferences.isEmbyConfigured,
                youtubeEnabled = appPreferences.youtubeEnabled,
                youtubeFeedKeys = appPreferences.youtubeFeedKeys.ifEmpty { listOf("home") },
                youtubeStartFeedKey = appPreferences.youtubeStartFeedKey,
                spotifyEnabled = appPreferences.spotifyEnabled,
                radioEnabled = appPreferences.radioEnabled,
                moonlightEnabled = com.ambient.tv.BuildConfig.MOONLIGHT_NATIVE && appPreferences.hasMoonlightHosts,
                moonlightHosts = if (com.ambient.tv.BuildConfig.MOONLIGHT_NATIVE)
                    appPreferences.moonlightHosts else emptyList(),
                weatherEnabled = appPreferences.weatherSettings.let { it.enabled && it.showInSidebar },
            telegramEnabled = appPreferences.telegramEnabled,
            telegramAuthorized = telegramRepository.available,
            telegramHomeEnabled = appPreferences.homeRows.any { it.enabled && it.system == com.ambient.tv.data.local.HomeRowDefinition.SYSTEM_TELEGRAM },
            telegramSubmenuKeys = telegramUsableSections(appPreferences.telegramSubmenuKeys,
                appPreferences.youtubeEnabled && smartTubeEngine.available),
                radioHomeEnabled = appPreferences.homeRows.any { it.enabled && it.system == com.ambient.tv.data.local.HomeRowDefinition.SYSTEM_RADIO },
                tvheadendEnabled = appPreferences.hasTvheadendEndpoint,
                tvheadendLoginRejected = appPreferences.tvheadendLoginRejected,
                tvheadendHbbtvAvailable = tvheadendRepository.hasCachedHbbtvApps(),
                spotifySubmenuKeys = appPreferences.spotifySubmenuKeys,
                radioSubmenuKeys = appPreferences.radioSubmenuKeys,
                tvheadendSubmenuKeys = appPreferences.tvheadendSubmenuKeys,
                settingsShortcutHidden = appPreferences.sidebarSettingsShortcutHidden,
                dividersAfter = appPreferences.sidebarDividersAfter,
                entryOrder = appPreferences.sidebarEntryOrder,
                entryDividers = appPreferences.sidebarEntryDividers,
            )
        }
        unreachableServers.refresh()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mediaRepository.restoreCache()
            _uiState.update { it.copy(
                sections = mediaRepository.cachedLibrarySections().orEmpty().filterNot { section -> section.key in appPreferences.plexHiddenSectionKeys },
                jellyfinSections = jellyfinRepository.cachedLibrarySections().orEmpty().filterNot { section -> section.key in appPreferences.jellyfinHiddenSectionKeys },
                embySections = embyRepository.cachedLibrarySections().orEmpty().filterNot { section -> section.key in appPreferences.embyHiddenSectionKeys },
            ) }
            // Saved IPTV sources belong to initial navigation, not the delayed network phase.
            // Restore before any intentional Home/intro deferral so menu entries never pop in later.
            val savedSources = liveTv.sidebarSnapshot()
            _uiState.update { it.copy(liveTvSources = savedSources) }
            if (appPreferences.isTvheadendConfigured) launch {
                suspendResult { tvheadendRepository.restoreCachedHbbtv() }
                _uiState.update { it.copy(tvheadendHbbtvAvailable = tvheadendRepository.hasCachedHbbtvApps()) }
                tvheadendRepository.requestHbbtvRefresh()
            }
            if (deferExpensiveWorkMs > 0L && forceLiveTv) delay(deferExpensiveWorkMs)
            if (forceLiveTv) launch {
                val sources = loadCoordinator.request("iptv", "sidebar-capabilities:force", BrowseLoadPriority.HOME) {
                    liveTv.discover(force = true)
                }
                _uiState.update { it.copy(liveTvSources = com.ambient.tv.livetv.reconcileSidebarLiveTvSources(sources, liveTv.sidebarSnapshot())) }
            }
            if (forceLiveTv && appPreferences.isTvheadendConfigured) launch {
                suspendResult {
                    loadCoordinator.request("tvheadend", "sidebar-hbbtv", BrowseLoadPriority.HOME) {
                        tvheadendRepository.prepareHbbtvDiscovery()
                    }
                }
                _uiState.update { it.copy(tvheadendHbbtvAvailable = tvheadendRepository.hasCachedHbbtvApps()) }
            }
            val youtubeOn = _uiState.value.youtubeEnabled
            if (forceLiveTv && youtubeOn) launch {
                val supported = suspendResult {
                    loadCoordinator.request("youtube", "supported-feeds", BrowseLoadPriority.HOME) {
                        smartTubeEngine.supportedFeedKeys()
                    }
                }.getOrDefault(setOf("home"))
                // Signed out, none of the configured feeds may be available. Falling back to Home
                // would offer the one feed that cannot load anything then, so offer what works.
                val keys = appPreferences.youtubeFeedKeys.filter { it in supported }
                    .ifEmpty { AppPreferences.YOUTUBE_FEED_KEYS.filter { it in supported } }
                    .ifEmpty { listOf("home") }
                val start = appPreferences.youtubeStartFeedKey.takeIf { it in keys } ?: "home"
                _uiState.update { it.copy(youtubeFeedKeys = keys, youtubeStartFeedKey = start) }
            }
            if (!appPreferences.isConfigured) {
                _uiState.update { it.copy(sections = emptyList()) }
            } else launch {
                val hidden = appPreferences.plexHiddenSectionKeys
                val sections = (if (forceLiveTv) loadCoordinator.librarySections("plex", BrowseLoadPriority.HOME) {
                    mediaRepository.getLibrarySections()
                }.getOrElse { mediaRepository.cachedLibrarySections().orEmpty() }
                else mediaRepository.cachedLibrarySections().orEmpty()).filterNot { it.key in hidden }
                // Plex photo libraries open the photo browser instead of the media grid.
                photoRepository.registerPhotoSections(
                    sections.filter { it.type == "photo" }.associate {
                        it.key to com.ambient.tv.data.photos.PhotoLibrary(
                            com.ambient.tv.data.photos.PlexPhotoSource.sectionKey(it.key), it.title, com.ambient.tv.data.photos.PhotoSystem.PLEX,
                        )
                    },
                    com.ambient.tv.data.photos.PhotoSystem.PLEX,
                )
                _uiState.update { it.copy(sections = sections) }
            }
            if (!appPreferences.isJellyfinConfigured) {
                _uiState.update { it.copy(jellyfinSections = emptyList()) }
            } else launch {
                val hidden = appPreferences.jellyfinHiddenSectionKeys
                val jellyfinSections = if (forceLiveTv && jellyfinConnectionManager.ensureConnected()) {
                    withPhotoLibraries(
                        loadCoordinator.librarySections("jellyfin", BrowseLoadPriority.HOME) {
                            jellyfinRepository.getLibrarySections()
                        }.getOrElse { jellyfinRepository.cachedLibrarySections().orEmpty() },
                        com.ambient.tv.data.photos.PhotoSystem.JELLYFIN,
                    ).filterNot { it.key in hidden }
                } else jellyfinRepository.cachedLibrarySections().orEmpty().filterNot { it.key in hidden }
                _uiState.update { it.copy(jellyfinSections = jellyfinSections) }
            }
            if (!appPreferences.isEmbyConfigured) {
                _uiState.update { it.copy(embySections = emptyList()) }
            } else launch {
                val hidden = appPreferences.embyHiddenSectionKeys
                val embySections = if (forceLiveTv && embyConnectionManager.ensureConnected()) {
                    withPhotoLibraries(
                        loadCoordinator.librarySections("emby", BrowseLoadPriority.HOME) {
                            embyRepository.getLibrarySections()
                        }.getOrElse { embyRepository.cachedLibrarySections().orEmpty() },
                        com.ambient.tv.data.photos.PhotoSystem.EMBY,
                    ).filterNot { it.key in hidden }
                } else embyRepository.cachedLibrarySections().orEmpty().filterNot { it.key in hidden }
                _uiState.update { it.copy(embySections = embySections) }
            }
        }
    }

    /**
     * Jellyfin/Emby media repositories skip photo views; the photo module lists them separately
     * and they are appended as "photo" sections that open the photo browser.
     */
    private suspend fun withPhotoLibraries(
        sections: List<LibrarySection>,
        system: com.ambient.tv.data.photos.PhotoSystem,
    ): List<LibrarySection> {
        val photos = photoRepository.libraries(system)
        photoRepository.registerPhotoSections(photos.associateBy { it.key }, system)
        return sections + photos.filter { photo -> sections.none { it.key == photo.key } }
            .map { LibrarySection(it.key, it.title, "photo", null, null) }
    }
}
