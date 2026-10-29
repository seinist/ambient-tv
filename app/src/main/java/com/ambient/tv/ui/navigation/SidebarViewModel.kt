package com.ambient.tv.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.data.dlna.DlnaServer
import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.connectivity.UnreachableServerGate
import com.ambient.tv.connectivity.HostConnectivity
import com.ambient.tv.data.local.AppPreferences
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
    val networkFileSources: List<FileSource> = emptyList(),
    val dlnaServers: List<DlnaServer> = emptyList(),
    val plexEnabled: Boolean = false,
    val jellyfinEnabled: Boolean = false,
    val embyEnabled: Boolean = false,
    val youtubeEnabled: Boolean = false,
    val spotifyEnabled: Boolean = false,
    /** Address entered; TVHeadend stays in the menu even when its login was rejected. */
    val tvheadendEnabled: Boolean = false,
    /** Login rejected: the menu shows only "check sign-in", Home and search leave it out. */
    val tvheadendLoginRejected: Boolean = false,
    val youtubeFeedKeys: List<String> = listOf("home"),
    /** Section behind SmartTube's root route (see [Destinations.youtubeSection]). */
    val youtubeStartFeedKey: String = "home",
    val spotifySubmenuKeys: List<String> = emptyList(),
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
    /** Confirmed host outages, including concrete files:<id>/dlna:<id> sources; entries stay usable. */
    val offlineSystems: Set<String> = emptySet(),
)

@HiltViewModel
class SidebarViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val tokens: com.ambient.tv.data.local.TokenStore,
    private val liveTv: com.ambient.tv.livetv.LiveTvRegistry,
    private val fileStore: FileStore,
    private val dlnaStore: DlnaStore,
    private val mediaRepository: MediaRepository,
    val metadataPrefetcher: com.ambient.tv.data.browse.MetadataPrefetcher,
    private val jellyfinRepository: JellyfinRepository,
    private val jellyfinConnectionManager: JellyfinConnectionManager,
    private val embyRepository: EmbyRepository,
    private val embyConnectionManager: EmbyConnectionManager,
    private val smartTubeEngine: SmartTubeEngine,
    private val spotifyProvider: javax.inject.Provider<SpotifyRepository>,
    private val photoRepository: com.ambient.tv.data.photos.PhotoRepository,
    private val unreachableServers: UnreachableServerGate,
    private val hosts: HostConnectivity,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SidebarUiState(
            trailersEnabled = appPreferences.trailersEnabled,
            trailersConfigured = appPreferences.trailersEnabled && !tokens.tmdbApiKey.isNullOrBlank(),
            systemOrder = appPreferences.sidebarSystemOrder,
            filesEnabled = appPreferences.filesEnabled,
            networkFileSources = networkFileSourcesFromStore(),
            dlnaServers = dlnaStore.servers(),
            plexEnabled = appPreferences.isConfigured,
            jellyfinEnabled = appPreferences.isJellyfinConfigured,
            embyEnabled = appPreferences.isEmbyConfigured,
            youtubeEnabled = appPreferences.youtubeEnabled,
            spotifyEnabled = appPreferences.spotifyEnabled,
            tvheadendEnabled = appPreferences.hasTvheadendEndpoint,
            tvheadendLoginRejected = appPreferences.tvheadendLoginRejected,
            spotifySubmenuKeys = appPreferences.spotifySubmenuKeys,
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
    private var spotifyAuthJob: Job? = null

    val reconnectSources: Set<String>
        get() = buildSet {
            if (appPreferences.isConfigured) add("plex")
            if (appPreferences.isJellyfinConfigured) add("jellyfin")
            if (appPreferences.isEmbyConfigured) add("emby")
            if (appPreferences.hasTvheadendEndpoint) add("tvheadend")
            addAll(liveTv.configured().map { it.source.system })
            if (appPreferences.youtubeEnabled) add("youtube")
            if (appPreferences.spotifyEnabled) add("spotify")
            if (appPreferences.filesEnabled) add("files")
            if (dlnaStore.servers().isNotEmpty()) add("dlna")
        }

    init {
        viewModelScope.launch {
            observeSidebarAvailability(
                hosts.states,
                appPreferences.changes(),
                fileStore.revision,
                dlnaStore.serverRevision,
                com.ambient.tv.data.local.LoginStateSignal.generation,
                snapshot = ::currentOfflineSources,
            ).collect { offline -> _uiState.update { it.copy(offlineSystems = offline) } }
        }
        viewModelScope.launch {
            fileStore.revision.collect {
                _uiState.update { state -> state.copy(networkFileSources = networkFileSourcesFromStore()) }
            }
        }
        viewModelScope.launch { dlnaStore.serverRevision.collect { _uiState.update { it.copy(dlnaServers = dlnaStore.servers()) } } }
        // Include the current snapshot: login may finish before these collectors start.
        viewModelScope.launch {
            observeSidebarAvailability(
                appPreferences.changes(),
                tokens.tmdbCredentialVersion,
                com.ambient.tv.data.local.LoginStateSignal.generation,
                snapshot = ::systemAvailability,
            ).collect { refresh() }
        }
    }

    private fun systemAvailability() = listOf(
        appPreferences.isConfigured, appPreferences.isJellyfinConfigured, appPreferences.isEmbyConfigured,
        appPreferences.isTvheadendConfigured, appPreferences.hasTvheadendEndpoint, appPreferences.youtubeEnabled, appPreferences.spotifyEnabled,
        appPreferences.filesEnabled, appPreferences.trailersEnabled && !tokens.tmdbApiKey.isNullOrBlank(),
    )

    private fun networkFileSourcesFromStore(): List<FileSource> =
        fileStore.sources().filter { it.network }

    private fun currentOfflineSources(): Set<String> {
        val configured = buildSet {
            if (appPreferences.isConfigured) add("plex")
            if (appPreferences.isJellyfinConfigured) add("jellyfin")
            if (appPreferences.isEmbyConfigured) add("emby")
            if (appPreferences.hasTvheadendEndpoint) add("tvheadend")
            if (appPreferences.filesEnabled) addAll(networkFileSourcesFromStore().map { "files:${it.id}" })
            addAll(dlnaStore.servers().map { "dlna:${it.id}" })
        }
        return sidebarOfflineSources(configured, hosts::health)
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

    fun refreshAfterReconnect() = refresh(forceLiveTv = true)

    fun refresh(deferExpensiveWorkMs: Long = 0L, forceLiveTv: Boolean = false) {
        observeSpotifyAuthorization()
        _uiState.update {
            it.copy(
                filesEnabled = appPreferences.filesEnabled,
                networkFileSources = networkFileSourcesFromStore(),
                dlnaServers = dlnaStore.servers(),
                systemOrder = appPreferences.sidebarSystemOrder,
                trailersEnabled = appPreferences.trailersEnabled,
                trailersConfigured = appPreferences.trailersEnabled && !tokens.tmdbApiKey.isNullOrBlank(),
                plexEnabled = appPreferences.isConfigured,
                jellyfinEnabled = appPreferences.isJellyfinConfigured,
                embyEnabled = appPreferences.isEmbyConfigured,
                youtubeEnabled = appPreferences.youtubeEnabled,
                spotifyEnabled = appPreferences.spotifyEnabled,
                tvheadendEnabled = appPreferences.hasTvheadendEndpoint,
                tvheadendLoginRejected = appPreferences.tvheadendLoginRejected,
                spotifySubmenuKeys = appPreferences.spotifySubmenuKeys,
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
            if (deferExpensiveWorkMs > 0L) delay(deferExpensiveWorkMs)
            launch { val sources = liveTv.discover(force = forceLiveTv); _uiState.update { it.copy(liveTvSources = sources) } }
            val youtubeOn = _uiState.value.youtubeEnabled
            if (youtubeOn) launch {
                val supported = suspendResult { smartTubeEngine.supportedFeedKeys() }.getOrDefault(setOf("home"))
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
                val sections = mediaRepository.getLibrarySections().getOrElse { mediaRepository.cachedLibrarySections().orEmpty() }.filterNot { it.key in hidden }
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
                val jellyfinSections = if (jellyfinConnectionManager.ensureConnected()) {
                    withPhotoLibraries(
                        jellyfinRepository.getLibrarySections().getOrElse { jellyfinRepository.cachedLibrarySections().orEmpty() },
                        com.ambient.tv.data.photos.PhotoSystem.JELLYFIN,
                    ).filterNot { it.key in hidden }
                } else jellyfinRepository.cachedLibrarySections().orEmpty().filterNot { it.key in hidden }
                _uiState.update { it.copy(jellyfinSections = jellyfinSections) }
            }
            if (!appPreferences.isEmbyConfigured) {
                _uiState.update { it.copy(embySections = emptyList()) }
            } else launch {
                val hidden = appPreferences.embyHiddenSectionKeys
                val embySections = if (embyConnectionManager.ensureConnected()) {
                    withPhotoLibraries(
                        embyRepository.getLibrarySections().getOrElse { embyRepository.cachedLibrarySections().orEmpty() },
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
