package com.ambient.tv.ui.settings

import com.ambient.tv.livetv.initializeTimeshiftStorage
import com.ambient.tv.notifications.*

import com.ambient.tv.util.suspendResult
import com.ambient.tv.network.NetworkEndpoint
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.BuildConfig
import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileListing
import com.ambient.tv.data.files.FilePaths
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.data.files.DeviceStorageRoot
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.data.local.HomeHeroStyle
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.data.local.MediaSelectionPreferences
import com.ambient.tv.data.local.SettingsBackupManager
import com.ambient.tv.data.local.SystemNameOverrides
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.AudioSettings
import com.ambient.tv.data.repository.PlexAuthRepository
import com.ambient.tv.data.remote.PlexAuthLink
import com.ambient.tv.data.jellyfin.JellyfinAuthRepository
import com.ambient.tv.data.jellyfin.JellyfinRepository
import com.ambient.tv.data.emby.EmbyAuthRepository
import com.ambient.tv.data.emby.EmbyRepository
import com.ambient.tv.domain.model.DiscoveredPlexServer
import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.domain.model.ServerProfile
import com.ambient.tv.domain.model.JellyfinServerProfile
import com.ambient.tv.domain.model.DiscoveredJellyfinServer
import com.ambient.tv.domain.model.EmbyServerProfile
import com.ambient.tv.domain.model.DiscoveredEmbyServer
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.server.PlexLanDiscovery
import com.ambient.tv.server.ServerConnectionManager
import com.ambient.tv.server.JellyfinConnectionManager
import com.ambient.tv.server.JellyfinLanDiscovery
import com.ambient.tv.server.EmbyConnectionManager
import com.ambient.tv.server.EmbyLanDiscovery
import com.ambient.tv.tvheadend.data.TvheadendRepository
import com.ambient.tv.wol.formatMacAddressInput
import com.ambient.tv.wol.MacAddressResolver
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubeContentCache
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.vpn.WireGuardRouteController
import com.ambient.tv.vpn.WireGuardProfile
import com.ambient.tv.vpn.hostCovered
import com.ambient.tv.vpn.parseWireGuardConfig
import android.net.VpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject


private const val LINK_POLL_INTERVAL_MS = 2_000L
private const val LINK_TIMEOUT_MS = 5 * 60 * 1_000L
private const val WIREGUARD_FILE_LIMIT = 64 * 1024

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val refreshBus: com.ambient.tv.ui.common.PlexRefreshBus,
    private val appPreferences: AppPreferences,
    private val tokenStore: TokenStore,
    private val mediaRepository: MediaRepository,
    private val plexAuthRepository: PlexAuthRepository,
    private val jellyfinAuthRepository: JellyfinAuthRepository,
    private val jellyfinRepository: JellyfinRepository,
    private val embyAuthRepository: EmbyAuthRepository,
    private val embyRepository: EmbyRepository,
    private val macAddressResolver: MacAddressResolver,
    private val lanDiscovery: PlexLanDiscovery,
    private val connectionManager: ServerConnectionManager,
    private val jellyfinConnectionManager: JellyfinConnectionManager,
    private val jellyfinLanDiscovery: JellyfinLanDiscovery,
    private val embyConnectionManager: EmbyConnectionManager,
    private val embyLanDiscovery: EmbyLanDiscovery,
    private val settingsBackupManager: SettingsBackupManager,
    private val tvheadendRepository: TvheadendRepository,
    private val smartTubeEngine: SmartTubeEngine,
    private val youtubeContentCache: YouTubeContentCache,
    private val spotifyRepository: SpotifyRepository,
    private val wireGuardRoute: WireGuardRouteController,
    private val fileStore: FileStore,
    private val textFileImporter: com.ambient.tv.data.files.TextFileImporter,
    private val fileAccess: FileAccess,
    private val photoRepository: com.ambient.tv.data.photos.PhotoRepository,
    private val launcherPublisher: com.ambient.tv.launcher.LauncherPublisher,
    private val dlnaRenderer: com.ambient.tv.renderer.DlnaRenderer,
    private val networkDlnaStore: com.ambient.tv.data.dlna.DlnaStore,
    private val iptvStore: com.ambient.tv.data.iptv.IptvStore,
    private val machineWake: com.ambient.tv.wol.MachineWake,
    private val youTubeTvLink: com.ambient.tv.youtube.YouTubeTvLink,
) : ViewModel() {
    val feedback = OperationFeedbackReporter()
    /** Fresh persisted snapshot; the settings form can contain changes awaiting Save. */
    internal fun savedPlaybackOverview() = savedPlaybackOverview(appPreferences)
    internal fun feedbackScope(page: SettingsCategory?) = page?.let { "settings:${it.name}" } ?: "settings"

    internal fun saveLiveResolutionProfile(resolution: String, profile: com.ambient.tv.data.local.ResolutionVideoProfile?) {
        appPreferences.resolutionVideoProfiles = com.ambient.tv.data.local.withLiveResolutionProfile(
            appPreferences.resolutionVideoProfiles, resolution, profile)
    }

    private fun beginFeedback(key: String): OperationFeedbackReporter.Ticket {
        _uiState.update { it.copy(operationFeedback = null, statusMessage = null) }
        return feedback.begin(key)
    }

    private fun finishFeedback(
        ticket: OperationFeedbackReporter.Ticket, page: SettingsCategory?, message: Int,
        success: Boolean, system: String? = null, subject: String? = null,
        error: Throwable? = null, delivery: FeedbackDelivery = FeedbackDelivery.ACTION,
        args: List<String> = emptyList(),
    ): OperationFeedback? {
        // A generic "failed" names its cause (login rejected, host:port unreachable, HTTPS, …).
        val withReason = !success && error != null && message == R.string.notice_failed && args.isEmpty()
        if (!success && error != null) timber.log.Timber.w(error, "Settings action %s failed", ticket.key)
        val result = feedback.complete(ticket, OperationFeedback(ticket.key, feedbackScope(page),
            if (withReason) R.string.notice_failed_reason else message,
            if (success) FeedbackPhase.SUCCESS else FeedbackPhase.ERROR,
            detail = error?.let(::feedbackFailureDetail), system = system, subject = subject,
            delivery = delivery, args = if (withReason) listOf(failureReasonText(context, error)) else args)) ?: return null
        _uiState.update { it.copy(statusMessage = null,
            operationFeedback = result.takeUnless { delivery == FeedbackDelivery.NOTIFICATION || (success && delivery == FeedbackDelivery.ACTION) }) }
        return result
    }

    private fun validation(message: Int, page: SettingsCategory? = null) {
        finishFeedback(beginFeedback("settings:validation"), page, message, false, delivery = FeedbackDelivery.INLINE)
    }

    private inline fun settingsAction(key: String, page: SettingsCategory?, message: Int,
        system: String? = null, subject: String? = null, block: () -> Unit) {
        val ticket = beginFeedback(key)
        runCatching(block).fold(
            onSuccess = { finishFeedback(ticket, page, message, true, system, subject) },
            onFailure = { finishFeedback(ticket, page, R.string.notice_failed, false, system, subject, it) },
        )
    }
    private val _photoLibraries = MutableStateFlow<List<com.ambient.tv.data.photos.PhotoLibrary>>(emptyList())
    val photoLibraries: StateFlow<List<com.ambient.tv.data.photos.PhotoLibrary>> = _photoLibraries.asStateFlow()

    /** Screensaver source picker: photo libraries of all configured systems. */
    fun loadPhotoLibraries() {
        viewModelScope.launch { _photoLibraries.value = photoRepository.libraries() }
    }

    fun onPhotoSlideshowIntervalChanged(value: Int) = _uiState.update {
        it.copy(photoSlideshowIntervalSeconds = value.coerceIn(3, 60))
    }
    fun onPhotoTransitionChanged(value: String) = _uiState.update { it.copy(photoTransitionKey = value) }
    fun onPhotoKenBurnsChanged(value: Boolean) = _uiState.update { it.copy(photoKenBurns = value) }
    fun onPhotoShuffleChanged(value: Boolean) = _uiState.update { it.copy(photoShuffle = value) }
    fun onPhotoIncludeSubfoldersChanged(value: Boolean) = _uiState.update { it.copy(photoIncludeSubfolders = value) }
    fun onPhotoSortChanged(value: String) = _uiState.update { it.copy(photoSortKey = value) }
    fun onPhotoShowInfoChanged(value: Boolean) = _uiState.update { it.copy(photoShowInfo = value) }
    fun onScreensaverPhotoAlbumChanged(key: String, title: String) = _uiState.update {
        it.copy(screensaverPhotoAlbumKey = key, screensaverPhotoAlbumTitle = title)
    }


    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private val savedState = MutableStateFlow(_uiState.value)
    val pendingChanges: StateFlow<Boolean> = combine(_uiState, savedState) { state, saved ->
        state.editableValues() != saved.editableValues()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun hasPendingChanges(): Boolean = _uiState.value.editableValues() != savedState.value.editableValues()
    private fun acceptSavedDraft() { savedState.value = _uiState.value }
    private fun refreshSavedValues() { savedState.value = loadInitialState() }

    fun refreshStoredSettings() {
        val stored = loadInitialState()
        _uiState.value = mergeStoredSettings(_uiState.value, savedState.value, stored)
        savedState.value = stored
        youtubeSettingsLoaded = false
        if (openedPage == SettingsCategory.YOUTUBE || openedPage == SettingsCategory.HOME) loadYouTubeSettings()
    }

    fun discardChanges() {
        cancelAccountLinking()
        youtubeSignInWatchJob?.cancel()
        pageJob?.cancel()
        _uiState.value = loadInitialState()
        youtubeSettingsLoaded = false
        acceptSavedDraft()
    }

    fun onAudioSettingsChanged(value: AudioSettings) = _uiState.update { it.copy(audioSettings = value.normalized()) }
    private var pageJob: Job? = null
    private var openedPage: SettingsCategory? = null

    internal fun refreshPageAfterPermissionGrant() {
        val page = openedPage
        openedPage = null
        onPageOpened(page)
    }

    internal fun onPageOpened(page: SettingsCategory?) {
        if (page == openedPage) return
        // Leaving a page applies its addresses and sign-in fields (validation shows inline).
        if (openedPage != null) commitPending()
        pageJob?.cancel()
        openedPage = page
        if (page != SettingsCategory.PLEX) cancelAccountLinking()
        if (page == SettingsCategory.YOUTUBE || page == SettingsCategory.HOME) loadYouTubeSettings()
        pageJob = viewModelScope.launch {
            when (page) {
                SettingsCategory.FILES -> _uiState.update { it.copy(fileSourceCount = appPreferences.fileSources.size) }
                SettingsCategory.PLEX -> if (appPreferences.hasPlexEndpoint) {
                    val sections = mediaRepository.getLibrarySections().getOrDefault(emptyList())
                    _uiState.update { it.copy(plexLibrarySections = sections) }
                }
                SettingsCategory.JELLYFIN -> if (appPreferences.isJellyfinConfigured) {
                    val sections = jellyfinRepository.getLibrarySections().getOrDefault(emptyList())
                    _uiState.update { it.copy(jellyfinLibrarySections = sections) }
                }
                SettingsCategory.EMBY -> if (appPreferences.isEmbyConfigured) {
                    val sections = embyRepository.getLibrarySections().getOrDefault(emptyList())
                    _uiState.update { it.copy(embyLibrarySections = sections) }
                }
                else -> Unit
            }
        }
    }

    val savedAppearance: SettingsUiState get() = loadInitialState()
    private var linkingJob: Job? = null
    private var youtubeSignInWatchJob: Job? = null

    private val mutableUiLanguageChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted after a saved change of the UI language; the screen recreates the activity. */
    val uiLanguageChanged: SharedFlow<Unit> = mutableUiLanguageChanged.asSharedFlow()

    /**
     * Every change is stored on its own, shortly after it is made. Addresses, ports, sign-in data,
     * keys and MAC addresses ([deferredSettingsFrom]) wait until the page or Settings is left
     * (or a connect/test action stores them), so half-typed values never reach the servers.
     */
    private fun autosave() {
        val state = _uiState.value
        if (state.isImportingSettings || state.isExportingSettings) return
        val saved = savedState.value
        val target = state.deferredSettingsFrom(saved)
        if (target.editableValues() == saved.editableValues()) return
        runCatching { saveDraft(target, deferred = true) }.onSuccess { changed ->
            if (changed == true) mutableUiLanguageChanged.tryEmit(Unit)
        }.onFailure { android.util.Log.w("Settings", "Autosave failed", it) }
    }

    /**
     * Stores everything including the deferred fields, without a "saved" message.
     * Null when a value is invalid (for example a MAC address); the draft is kept.
     */
    fun commitPending(): Boolean? {
        val state = _uiState.value
        if (state.isImportingSettings || state.isExportingSettings) return null
        if (!hasPendingChanges()) return false
        val deferredChanged = state.deferredSettingsFrom(savedState.value) != state
        return runCatching { saveDraft(state, deferred = false) }.fold(
            onSuccess = { changed ->
                if (changed != null && deferredChanged) verifyPendingLogins()
                if (changed == true) mutableUiLanguageChanged.tryEmit(Unit)
                changed
            },
            onFailure = {
                finishFeedback(beginFeedback("settings:save"), null, R.string.feedback_settings_save_failed, false, error = it)
                null
            },
        )
    }

    init {
        viewModelScope.launch {
            // collectLatest + delay debounces typing; toggles and choices land within ~0.4 s.
            _uiState.collectLatest {
                kotlinx.coroutines.delay(AUTOSAVE_DEBOUNCE_MS)
                autosave()
            }
        }
        viewModelScope.launch {
            networkDlnaStore.serverRevision.collect { _uiState.update { it.copy(dlnaServerCount = networkDlnaStore.servers().size) } }
        }
        viewModelScope.launch {
            iptvStore.revision.collect { _uiState.update { it.copy(iptvSourceCount = iptvStore.sources().size) } }
        }
        viewModelScope.launch {
            spotifyRepository.authState.collect { auth ->
                _uiState.update { it.copy(spotifyWebAuthorized = auth.authorized, spotifyAccountName = auth.accountName, spotifyAuthBusy = auth.busy, spotifyAuthError = auth.error) }
            }
        }
        viewModelScope.launch {
            wireGuardRoute.status.collect { status -> _uiState.update { it.copy(wireGuardStatus = status) } }
        }
    }

    private var youtubeSettingsLoaded = false
    fun loadYouTubeSettings() {
        if (youtubeSettingsLoaded) return
        youtubeSettingsLoaded = true
        viewModelScope.launch {
            if (smartTubeEngine.available) {
                val supported = suspendResult { smartTubeEngine.supportedFeedKeys() }.getOrDefault(setOf("home"))
                val signedIn = suspendResult { smartTubeEngine.isSignedIn() }.getOrDefault(false)
                _uiState.update { state ->
                    state.copy(
                        youtubeAvailable = true,
                        youtubeSignedIn = signedIn,
                        youtubeAuthKnown = true,
                        youtubeSupportedFeedKeys = supported,
                    )
                }
            } else {
                _uiState.update { it.copy(youtubeAvailable = false, youtubeSignedIn = false, youtubeAuthKnown = true) }
            }
        }
    }

    fun refreshTrailerConfiguration() {
        _uiState.update { it.copy(trailersConfigured = appPreferences.trailersEnabled && !tokenStore.tmdbApiKey.isNullOrBlank()) }
    }

    private fun loadInitialState() = SettingsUiState(
        trailersConfigured = appPreferences.trailersEnabled && !tokenStore.tmdbApiKey.isNullOrBlank(),
        mpvImageOptions = appPreferences.mpvImageOptions,
        vlcImageOptions = appPreferences.vlcImageOptions,
        media3ImageOptions = appPreferences.media3ImageOptions,
        audioSettings = appPreferences.audioSettings,
        plexConfigured = appPreferences.host.isNotBlank(),
        plexSignedIn = !tokenStore.token.isNullOrBlank(),
        plexEnabled = appPreferences.plexEnabled,
        jellyfinConfigured = appPreferences.isJellyfinConfigured,
        jellyfinEnabled = appPreferences.jellyfinEnabled,
        jellyfinHost = appPreferences.jellyfinHost,
        jellyfinPort = appPreferences.jellyfinPort.toString(),
        jellyfinUseHttps = appPreferences.jellyfinUseHttps,
        jellyfinBasePath = appPreferences.jellyfinBasePath,
        jellyfinUsername = appPreferences.jellyfinUsername.orEmpty(),
        jellyfinPassword = tokenStore.jellyfinPassword.orEmpty(),
        jellyfinMacAddress = appPreferences.jellyfinMacAddress.orEmpty(),
        jellyfinAutoWakeEnabled = appPreferences.jellyfinAutoWakeEnabled,
        jellyfinHiddenSectionKeys = appPreferences.jellyfinHiddenSectionKeys,
        savedJellyfinServers = appPreferences.jellyfinServerProfiles,
        activeJellyfinServerId = appPreferences.activeJellyfinServerId,
        embyConfigured = appPreferences.isEmbyConfigured,
        embyEnabled = appPreferences.embyEnabled,
        embyHost = appPreferences.embyHost,
        embyPort = appPreferences.embyPort.toString(),
        embyUseHttps = appPreferences.embyUseHttps,
        embyBasePath = appPreferences.embyBasePath,
        embyUsername = appPreferences.embyUsername.orEmpty(),
        embyPassword = tokenStore.embyPassword.orEmpty(),
        embyMacAddress = appPreferences.embyMacAddress.orEmpty(),
        embyAutoWakeEnabled = appPreferences.embyAutoWakeEnabled,
        embyHiddenSectionKeys = appPreferences.embyHiddenSectionKeys,
        savedEmbyServers = appPreferences.embyServerProfiles,
        activeEmbyServerId = appPreferences.activeEmbyServerId,
        filesEnabled = appPreferences.filesEnabled,
        fileSourceCount = appPreferences.fileSources.size,
        dlnaServerCount = networkDlnaStore.servers().size,
        iptvSourceCount = iptvStore.sources().size,
        systemNames = appPreferences.systemNames,
        youtubeEnabled = appPreferences.youtubeEnabled,
        youtubeAvailable = smartTubeEngine.available,
        spotifyEnabled = appPreferences.spotifyEnabled,
        spotifyClientId = appPreferences.spotifyClientId,
        spotifyWebAuthorized = spotifyRepository.authState.value.authorized,
        spotifyAccountName = spotifyRepository.authState.value.accountName,
        spotifyAuthBusy = spotifyRepository.authState.value.busy,
        spotifyAuthError = spotifyRepository.authState.value.error,
        spotifyDeviceName = spotifyRepository.deviceName(),
        spotifyBitrateKbps = appPreferences.spotifyBitrateKbps,
        spotifyVisualizerModeKey = appPreferences.spotifyVisualizerModeKey,
        spotifySubmenuKeys = appPreferences.spotifySubmenuKeys,
        host = appPreferences.host,
        port = appPreferences.port.toString(),
        plexUseHttps = appPreferences.plexUseHttps,
        token = tokenStore.token.orEmpty(),
        macAddress = appPreferences.macAddress.orEmpty(),
        autoWakeEnabled = appPreferences.autoWakeEnabled,
        chooseServerOnStartup = appPreferences.chooseServerOnStartup,
        localNetworkOnly = appPreferences.localNetworkOnly,
        plexThemeMusicEnabled = appPreferences.plexThemeMusicEnabled,
        jellyfinThemeMusicEnabled = appPreferences.jellyfinThemeMusicEnabled,
        embyThemeMusicEnabled = appPreferences.embyThemeMusicEnabled,
        themeMusicVolumePercent = (appPreferences.themeMusicVolume * 100f).toInt().coerceIn(0, 100),
        autoSelectDisplayVersion = appPreferences.autoSelectDisplayVersion,
        displayChangeNotificationsEnabled = appPreferences.displayChangeNotificationsEnabled,
        svpAvailable = appPreferences.svpAvailable,
        displayRefreshAdjustmentKey = appPreferences.displayRefreshAdjustmentKey,
        displayModeWhitelist = appPreferences.displayModeWhitelist,
        preferredDisplayModeKey = appPreferences.preferredDisplayModeKey,
        allowDoubleRefreshRate = appPreferences.allowDoubleRefreshRate,
        allowThreeTwoPulldown = appPreferences.allowThreeTwoPulldown,
        displaySwitchPauseMs = appPreferences.displaySwitchPauseMs,
        mediaSelectionBehaviorKey = appPreferences.mediaSelectionBehaviorKey,
        homeRows = appPreferences.homeRows,
        plexAutoRefreshIntervalSeconds = appPreferences.plexAutoRefreshIntervalSeconds,
        libraryGridColumns = appPreferences.libraryGridColumns,
        libraryListRows = appPreferences.libraryListRows,
        highQualityAssetsEnabled = appPreferences.highQualityAssetsEnabled,
        uiLanguageKey = appPreferences.uiLanguageKey,
        metadataLanguageKey = appPreferences.metadataLanguageKey,
        audioLanguageKey = appPreferences.audioLanguageKey,
        subtitleModeKey = appPreferences.subtitleModeKey,
        subtitleLanguageKey = appPreferences.subtitleLanguageKey,
        subtitleFallbackLanguageKey = appPreferences.subtitleFallbackLanguageKey,
        fullSubtitlesOnForeignAudio = appPreferences.fullSubtitlesOnForeignAudio,
        forcedSubtitlesInUiLanguage = appPreferences.forcedSubtitlesInUiLanguage,
        forcedSubtitlesEnabled = appPreferences.forcedSubtitlesEnabled,
        uiScale = appPreferences.uiScale,
        accentColorKey = appPreferences.accentColorKey,
        customAccentHex = appPreferences.customAccentHex,
        customPlaybackAccentHex = appPreferences.customPlaybackAccentHex,
        dualAccentEnabled = appPreferences.dualAccentEnabled,
        themeModeKey = appPreferences.themeModeKey,
        uiHdrModeKey = appPreferences.uiHdrModeKey,
        sdrToHdrEnabled = appPreferences.sdrToHdrEnabled,
        sdrToHdrPeakNits = appPreferences.sdrToHdrPeakNits,
        performanceModeKey = appPreferences.performanceModeKey,
        uiPerformanceModeKey = appPreferences.effectiveUiPerformanceModeKey(),
        playbackPerformanceModeKey = appPreferences.effectivePlaybackPerformanceModeKey(),
        frameDropTestSeconds = appPreferences.frameDropTestSeconds,
        homeBackgroundEverywhere = appPreferences.homeBackgroundEverywhere,
        focusArtworkBackdrop = appPreferences.focusArtworkBackdrop,
        homeHeroStyleKey = appPreferences.homeHeroStyleKey,
        homeHeroIntensityPercent = appPreferences.homeHeroIntensityPercent,
        homeHeroSpreadKey = appPreferences.homeHeroSpreadKey,
        notificationDisplayDurationKey = appPreferences.notificationDisplayDurationKey,
        navSoundsEnabled = appPreferences.navSoundsEnabled,
        navSoundsVolume = appPreferences.navSoundsVolume,
        navSoundBank = appPreferences.navSoundBank,
        playbackBackendKey = appPreferences.playbackBackendKey,
        tvheadendPlaybackBackendKey = appPreferences.tvheadendPlaybackBackendKey,
        mpvVideoOutputModeKey = appPreferences.mpvVideoOutputModeKey,
        mpvVideoDecoderKey = appPreferences.mpvVideoDecoderKey,
        mpvHdrGpuEnabled = appPreferences.mpvHdrGpuEnabled,
        vlcVideoOutputModeKey = appPreferences.vlcVideoOutputModeKey,
        visibleBackgroundVideoPlayback = appPreferences.visibleBackgroundVideoPlayback,
        backgroundVideoDimPercent = appPreferences.backgroundVideoDimPercent,
        mediaAudioPassthroughEnabled = appPreferences.mediaAudioPassthroughEnabled,
        audioVisualizerModeKey = appPreferences.audioVisualizerModeKey,
        projectmPresetIntervalSeconds = appPreferences.projectmPresetIntervalSeconds,
        projectmTransitionSeconds = appPreferences.projectmTransitionSeconds,
        projectmQualityKey = appPreferences.projectmQualityKey,
        projectmResolutionKey = appPreferences.projectmResolutionKey,
        projectmHdr4kAllowed = appPreferences.projectmHdr4kAllowed,
        projectmShowPresetName = appPreferences.projectmShowPresetName,
        projectmPresetCategoryKey = appPreferences.projectmPresetCategoryKey,
        bigSeekJumpSeconds = appPreferences.bigSeekJumpSeconds,
        creditsSkipMode = appPreferences.creditsSkipMode,
        introSkipMode = appPreferences.introSkipMode,
        nextUpEnabled = appPreferences.nextUpEnabled,
        nextUpLeadSeconds = appPreferences.nextUpLeadSeconds,
        nextUpAutoplayEpisodes = appPreferences.nextUpAutoplayEpisodes,
        nextUpAutoplayCollections = appPreferences.nextUpAutoplayCollections,
        nextUpOverlayEnabled = appPreferences.nextUpOverlayEnabled,
        nextUpStillWatchingEpisodes = appPreferences.nextUpStillWatchingEpisodes,
        sleepAutoStartKey = appPreferences.sleepAutoStartKey,
        sleepWarningMinutes = appPreferences.sleepWarningMinutes,
        sleepDailyStopMinute = appPreferences.sleepDailyStopMinute,
        wolRetryCount = appPreferences.wolRetryCount,
        youtubeSponsorBlockEnabled = appPreferences.youtubeSponsorBlockEnabled,
        youtubeAutoplayNextEnabled = appPreferences.youtubeAutoplayNextEnabled,
        youtubeFeedKeys = appPreferences.youtubeFeedKeys,
        youtubeMaxVideoHeight = appPreferences.youtubeMaxVideoHeight,
        plexHiddenSectionKeys = appPreferences.plexHiddenSectionKeys,
        sidebarSettingsShortcutHidden = appPreferences.sidebarSettingsShortcutHidden,
        systemHomeHiddenRows = appPreferences.systemHomeHiddenRows,
        screensaverEnabled = appPreferences.screensaverEnabled,
        screensaverTimeoutMinutes = appPreferences.screensaverTimeoutMinutes,
        screensaverModeKey = appPreferences.screensaverModeKey,
        screensaverShowClock = appPreferences.screensaverShowClock,
        screensaverShowDate = appPreferences.screensaverShowDate,
        screensaverUse24HourClock = appPreferences.screensaverUse24HourClock,
        screensaverUseArtwork = appPreferences.screensaverUseArtwork,
        screensaverArtworkIntervalSeconds = appPreferences.screensaverArtworkIntervalSeconds,
        screensaverDimPercent = appPreferences.screensaverDimPercent,
        screensaverQualityKey = appPreferences.screensaverQualityKey,
        tvheadendEnabled = appPreferences.tvheadendEnabled,
        tvheadendHost = appPreferences.tvheadendHost,
        tvheadendPort = appPreferences.tvheadendPort.toString(),
        tvheadendUseHttps = appPreferences.tvheadendUseHttps,
        tvheadendUsername = appPreferences.tvheadendUsername,
        tvheadendPassword = tokenStore.tvheadendPassword.orEmpty(),
        tvheadendStreamProfile = appPreferences.tvheadendStreamProfile,
        tvheadendDeinterlaceModeKey = appPreferences.tvheadendDeinterlaceModeKey,
        tvheadendDeinterlaceFilterKey = appPreferences.tvheadendDeinterlaceFilterKey,
        tvheadendMpvDeinterlaceFilterKey = appPreferences.tvheadendMpvDeinterlaceFilterKey,
        tvheadendMacAddress = appPreferences.tvheadendMacAddress.orEmpty(),
        tvheadendAutoWakeEnabled = appPreferences.tvheadendAutoWakeEnabled,
        tvheadendShowOnHome = appPreferences.homeRows.any { it.enabled && it.system == HomeRowDefinition.SYSTEM_TVHEADEND && it.contentKey == "live" },
        tvheadendSubmenuKeys = appPreferences.tvheadendSubmenuKeys,
        wireGuardSummary = appPreferences.wireGuardProfile()?.let { wireGuardSummary(it) }.orEmpty(),
        wireGuardPlexEnabled = appPreferences.wireGuardPlexEnabled,
        wireGuardJellyfinEnabled = appPreferences.wireGuardJellyfinEnabled,
        wireGuardEmbyEnabled = appPreferences.wireGuardEmbyEnabled,
        wireGuardTvheadendEnabled = appPreferences.wireGuardTvheadendEnabled,
        savedServers = appPreferences.serverProfiles,
        activeServerId = appPreferences.activeServerId,
        selectedMachineIdentifier = appPreferences.activeServerProfile()?.machineIdentifier,
        photoSlideshowIntervalSeconds = appPreferences.photoSlideshowIntervalSeconds,
        photoTransitionKey = appPreferences.photoTransitionKey,
        photoKenBurns = appPreferences.photoKenBurns,
        photoShuffle = appPreferences.photoShuffle,
        photoIncludeSubfolders = appPreferences.photoIncludeSubfolders,
        photoSortKey = appPreferences.photoSortKey,
        photoShowInfo = appPreferences.photoShowInfo,
        screensaverPhotoAlbumKey = appPreferences.screensaverPhotoAlbumKey,
        screensaverPhotoAlbumTitle = appPreferences.screensaverPhotoAlbumTitle,
    )

    val savedBackgroundVideoPreferences: Pair<Boolean, Int>
        get() = appPreferences.visibleBackgroundVideoPlayback to appPreferences.backgroundVideoDimPercent

    /** YouTube "Watch on TV": linking phones by code. */
    fun youtubeTvLinkEnabled(): Boolean = appPreferences.youtubeTvLinkEnabled
    fun setYouTubeTvLinkEnabled(enabled: Boolean) {
        appPreferences.youtubeTvLinkEnabled = enabled
        youTubeTvLink.refresh()
    }
    suspend fun youtubeLinkCode(): String? = runCatching { smartTubeEngine.loungePairingCode() }.getOrNull()
    /** All linked phones must link again with a new code. */
    fun unlinkYouTubeDevices() {
        viewModelScope.launch {
            runCatching { smartTubeEngine.resetLounge() }
            youTubeTvLink.refresh()
        }
    }

    /** DLNA renderer: other apps on the LAN may send media to this app. */
    fun diagnosticLogEnabled(): Boolean = appPreferences.diagnosticLogEnabled
    fun setDiagnosticLogEnabled(enabled: Boolean) {
        appPreferences.diagnosticLogEnabled = enabled
        com.ambient.tv.diagnostics.DiagnosticLog.setEnabled(enabled)
    }
    fun diagnosticLogSizeBytes(): Long = com.ambient.tv.diagnostics.DiagnosticLog.sizeBytes()
    private val diagnosticExportRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Writes the log ZIP to [uri] (a document the user picked) or, without one, to the app folder. */
    fun exportDiagnosticLog(uri: Uri?) {
        if (!diagnosticExportRunning.compareAndSet(false, true)) return
        val ticket = beginFeedback("diagnostics:export")
        viewModelScope.launch {
            val result = suspendResult {
                withContext(Dispatchers.IO) {
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use(com.ambient.tv.diagnostics.DiagnosticLog::export)
                            ?: throw java.io.IOException("No output stream")
                        null
                    } else {
                        // No document picker (common on TV): Android/data/<app>/files/Download.
                        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                            ?: context.filesDir.resolve("exports")
                        dir.mkdirs()
                        val file = dir.resolve(diagnosticZipName())
                        file.outputStream().use(com.ambient.tv.diagnostics.DiagnosticLog::export)
                        file.absolutePath
                    }
                }
            }
            diagnosticExportRunning.set(false)
            val path = result.getOrNull()
            when {
                result.isFailure -> finishFeedback(ticket, SettingsCategory.DIAGNOSTICS, R.string.notice_failed, false, error = result.exceptionOrNull())
                path != null -> finishFeedback(ticket, SettingsCategory.DIAGNOSTICS, R.string.diagnostics_exported_to, true, args = listOf(path))
                else -> finishFeedback(ticket, SettingsCategory.DIAGNOSTICS, R.string.diagnostics_exported, true)
            }
        }
    }

    fun clearDiagnosticLog() {
        val ticket = beginFeedback("diagnostics:clear")
        viewModelScope.launch {
            withContext(Dispatchers.IO) { com.ambient.tv.diagnostics.DiagnosticLog.clear() }
            finishFeedback(ticket, SettingsCategory.DIAGNOSTICS, R.string.diagnostics_cleared, true)
        }
    }

    fun diagnosticZipName(): String =
        "ambient-tv-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".zip"

    val networkDlnaRevision get() = networkDlnaStore.serverRevision
    fun networkDlnaServers() = networkDlnaStore.servers()
    val dlnaRendererRunning get() = dlnaRenderer.listening
    val dlnaRendererReceivedTitle get() = dlnaRenderer.receivedTitle
    fun dlnaRendererName() = dlnaRenderer.displayName()
    fun saveDlnaRendererName(name: String) {
        appPreferences.dlnaRendererName = name.trim().take(80)
        dlnaRenderer.refresh()
        finishFeedback(beginFeedback("renderer:name"), SettingsCategory.RECEIVER, R.string.notice_source_saved, true)
    }

    /**
     * Manual wake buttons wake the whole machine through [com.ambient.tv.wol.MachineWake]: one
     * burst per NIC, one shared wake card; pressing again while that machine wakes does nothing.
     */
    private fun manualWake(request: com.ambient.tv.wol.ManualWakeRequest) {
        if (com.ambient.tv.wol.canonicalWolMacKey(request.mac) == null) {
            validation(R.string.settings_status_mac_invalid, SettingsCategory.WAKE)
            return
        }
        machineWake.wake(request)
    }

    fun wakeNetworkServer(system: String) {
        val input = macInput(_uiState.value, system)
        val vpn = when (system) {
            "plex" -> _uiState.value.wireGuardPlexEnabled
            "jellyfin" -> _uiState.value.wireGuardJellyfinEnabled
            "emby" -> _uiState.value.wireGuardEmbyEnabled
            "tvheadend" -> _uiState.value.wireGuardTvheadendEnabled
            else -> false
        }
        val host = NetworkEndpoint.extractHost(input.first)
        manualWake(com.ambient.tv.wol.ManualWakeRequest(system, subject = host, host = host, mac = input.third,
            probePort = input.second, wireGuardHost = host.takeIf { vpn }))
    }

    fun wakeFileServer(source: com.ambient.tv.data.files.FileSource) {
        manualWake(com.ambient.tv.wol.ManualWakeRequest("files", "files:${source.id}", source.name, source.host, source.wolMac,
            source.port, source.wolBroadcast, source.wolPort, source.host.takeIf { source.wireGuard }))
    }

    /** Saves the DLNA server's MAC; an invalid entry is kept out of the store (field shows it). */
    fun setDlnaWakeMac(server: com.ambient.tv.data.dlna.DlnaServer, mac: String) {
        networkDlnaStore.updateWake(server.id, mac, server.wolAuto && mac.isNotBlank())
    }

    fun setDlnaAutoWake(server: com.ambient.tv.data.dlna.DlnaServer, enabled: Boolean) {
        networkDlnaStore.updateWake(server.id, server.wolMac, enabled)
    }

    /** ARP/neighbour lookup for the ContentDirectory host, like the other systems. */
    fun resolveDlnaMac(server: com.ambient.tv.data.dlna.DlnaServer) {
        val page = SettingsCategory.WAKE
        val target = com.ambient.tv.data.dlna.dlnaWakeEndpoint(server)
        if (target == null) { validation(R.string.settings_status_mac_missing_host, page); return }
        val ticket = beginFeedback("mac:dlna:${server.id}")
        viewModelScope.launch {
            val result = suspendResult { macAddressResolver.resolveMacAddress(target.first, target.second) }
            val mac = result.getOrNull()
            if (mac != null) networkDlnaStore.updateWake(server.id, mac, server.wolAuto)
            finishFeedback(ticket, page, if (mac != null) R.string.settings_status_mac_found else R.string.settings_status_mac_not_found,
                mac != null, "dlna", server.name, result.exceptionOrNull(), delivery = FeedbackDelivery.CONTEXTUAL,
                args = listOfNotNull(mac))
        }
    }

    fun wakeDlnaServer(server: com.ambient.tv.data.dlna.DlnaServer) {
        val endpoint = com.ambient.tv.data.dlna.dlnaWakeEndpoint(server)
        manualWake(com.ambient.tv.wol.ManualWakeRequest("dlna", "dlna:${server.id}", server.name, endpoint?.first,
            server.wolMac, endpoint?.second))
    }

    // ---- Wake page: one section per machine instead of one per service ----

    /** Bumps when SMB/NFS sources or DLNA servers change, so the grouped Wake page recomputes. */
    val wakeSourcesRevision: kotlinx.coroutines.flow.Flow<Pair<Long, Long>> =
        kotlinx.coroutines.flow.combine(fileStore.revision, networkDlnaStore.serverRevision) { files, dlna -> files to dlna }

    private val _wakeGroupBusy = MutableStateFlow<Set<String>>(emptySet())
    /** Group keys with a running MAC lookup. */
    val wakeGroupBusy = _wakeGroupBusy.asStateFlow()

    /** Last MAC found by a group lookup; the open MAC field takes it over (the nonce repeats equal finds). */
    data class WakeMacResolved(val groupKey: String, val mac: String, val nonce: Long)
    private val _wakeMacResolved = MutableStateFlow<WakeMacResolved?>(null)
    val wakeMacResolved = _wakeMacResolved.asStateFlow()

    /** All wakeable services, media servers first so their MAC wins in a group. */
    internal fun wakeMembers(state: SettingsUiState = _uiState.value): List<WakeMember> = buildList {
        if (state.host.isNotBlank()) add(WakeMember(WakeMemberKind.PLEX, "plex", "", state.host,
            state.port.toIntOrNull() ?: 32400, state.macAddress, state.autoWakeEnabled, state.wireGuardPlexEnabled))
        if (state.jellyfinHost.isNotBlank()) add(WakeMember(WakeMemberKind.JELLYFIN, "jellyfin", "", state.jellyfinHost,
            state.jellyfinPort.toIntOrNull() ?: 8096, state.jellyfinMacAddress, state.jellyfinAutoWakeEnabled, state.wireGuardJellyfinEnabled))
        if (state.embyHost.isNotBlank()) add(WakeMember(WakeMemberKind.EMBY, "emby", "", state.embyHost,
            state.embyPort.toIntOrNull() ?: 8096, state.embyMacAddress, state.embyAutoWakeEnabled, state.wireGuardEmbyEnabled))
        if (state.tvheadendHost.isNotBlank()) add(WakeMember(WakeMemberKind.TVHEADEND, "tvheadend", "", state.tvheadendHost,
            state.tvheadendPort.toIntOrNull() ?: 9981, state.tvheadendMacAddress, state.tvheadendAutoWakeEnabled, state.wireGuardTvheadendEnabled))
        fileStore.sources().filter { it.kind != "local" }.forEach { source ->
            val key = "files:${source.id}"
            add(WakeMember(WakeMemberKind.FILES, source.id, source.name, source.host, source.port,
                state.pendingSourceWakeMacs[key] ?: source.wolMac, state.pendingSourceWakeAuto[key] ?: source.wolAuto,
                source.wireGuard, source.wolBroadcast, source.wolPort))
        }
        networkDlnaStore.servers().forEach { server ->
            val endpoint = com.ambient.tv.data.dlna.dlnaWakeEndpoint(server)
            val key = "dlna:${server.id}"
            add(WakeMember(WakeMemberKind.DLNA, server.id, server.name, endpoint?.first.orEmpty(),
                endpoint?.second ?: 80, state.pendingSourceWakeMacs[key] ?: server.wolMac, state.pendingSourceWakeAuto[key] ?: server.wolAuto))
        }
    }

    internal fun wakeGroups(state: SettingsUiState = _uiState.value): List<WakeHostGroup> = groupWakeTargets(wakeMembers(state))

    fun wakeScrollIndex(state: SettingsUiState, target: String): Int =
        networkWakeScrollIndex(wakeGroups(state), hasWakeProfilePicker(state), target)

    /**
     * Writes one MAC into every service of the machine. Like the server MAC fields, NAS/DLNA values
     * are drafts too: stored together when the page or Settings is left, dropped by "discard".
     */
    internal fun setGroupWakeMac(group: WakeHostGroup, value: String) {
        val formatted = formatMacAddressInput(value)
        group.members.forEach { member ->
            when (member.kind) {
                WakeMemberKind.PLEX -> _uiState.update { it.copy(macAddress = formatted) }
                WakeMemberKind.JELLYFIN -> _uiState.update { it.copy(jellyfinMacAddress = formatted) }
                WakeMemberKind.EMBY -> _uiState.update { it.copy(embyMacAddress = formatted) }
                WakeMemberKind.TVHEADEND -> _uiState.update { it.copy(tvheadendMacAddress = formatted) }
                WakeMemberKind.FILES, WakeMemberKind.DLNA -> {
                    val key = sourceWakeKey(member)
                    _uiState.update { it.copy(pendingSourceWakeMacs = it.pendingSourceWakeMacs + (key to formatted)) }
                }
            }
        }
    }

    private fun sourceWakeKey(member: WakeMember) = member.kind.linkTarget + ":" + member.id

    /** The NAS editor on the Wake page saved this source directly: its older draft must not overwrite that. */
    fun dropSourceWakeDraft(sourceId: String) {
        val key = "files:$sourceId"
        _uiState.update { it.copy(pendingSourceWakeMacs = it.pendingSourceWakeMacs - key, pendingSourceWakeAuto = it.pendingSourceWakeAuto - key) }
        savedState.update { it.copy(pendingSourceWakeMacs = it.pendingSourceWakeMacs - key, pendingSourceWakeAuto = it.pendingSourceWakeAuto - key) }
    }

    /** Stores the Wake page's NAS/DLNA drafts; MACs were validated before. */
    private fun applySourceWakeDrafts(state: SettingsUiState) {
        val keys = state.pendingSourceWakeMacs.keys + state.pendingSourceWakeAuto.keys
        keys.forEach { key ->
            val kind = key.substringBefore(':'); val id = key.substringAfter(':')
            val draftMac = state.pendingSourceWakeMacs[key]?.let { canonicalWakeMac(it).orEmpty() }
            val draftAuto = state.pendingSourceWakeAuto[key]
            when (kind) {
                "files" -> fileStore.sources().firstOrNull { it.id == id }?.let { source ->
                    fileStore.updateWake(id, draftMac ?: source.wolMac, draftAuto ?: source.wolAuto)
                }
                "dlna" -> networkDlnaStore.servers().firstOrNull { it.id == id }?.let { server ->
                    val mac = draftMac ?: server.wolMac
                    networkDlnaStore.updateWake(id, mac, (draftAuto ?: server.wolAuto) && mac.isNotBlank())
                }
            }
        }
    }

    internal fun setWakeMemberAutoWake(member: WakeMember, enabled: Boolean) {
        when (member.kind) {
            WakeMemberKind.PLEX -> onAutoWakeToggled(enabled)
            WakeMemberKind.JELLYFIN -> onJellyfinAutoWakeChanged(enabled)
            WakeMemberKind.EMBY -> onEmbyAutoWakeChanged(enabled)
            WakeMemberKind.TVHEADEND -> onTvheadendAutoWakeChanged(enabled)
            // Kept with the MAC draft, so switching on auto-wake for a just-typed MAC is not lost.
            WakeMemberKind.FILES, WakeMemberKind.DLNA ->
                _uiState.update { it.copy(pendingSourceWakeAuto = it.pendingSourceWakeAuto + (sourceWakeKey(member) to enabled)) }
        }
    }

    /** ARP/neighbour lookup at the machine's address; a found MAC goes to every service on it. */
    internal fun lookupGroupMac(group: WakeHostGroup, verify: Boolean) {
        val page = SettingsCategory.WAKE
        val probe = group.probe
        if (probe == null) { validation(R.string.settings_status_mac_missing_host, page); return }
        val entered = group.mac.takeIf(String::isNotBlank)
        if (verify && entered == null) { validation(R.string.settings_status_mac_invalid, page); return }
        if (!_wakeGroupBusy.compareAndSetAdd(group.key)) return
        val ticket = beginFeedback("mac:group:${group.key}")
        viewModelScope.launch {
            try {
                val result = suspendResult { macAddressResolver.resolveMacAddress(probe.normalizedHost, probe.port) }
                val mac = result.getOrNull()
                val success = mac != null && (!verify || mac.equals(entered, ignoreCase = true))
                val message = when {
                    mac == null -> if (verify) R.string.settings_status_mac_not_verifiable else R.string.settings_status_mac_not_found
                    !verify -> R.string.settings_status_mac_found
                    success -> R.string.settings_status_mac_matches
                    else -> R.string.settings_status_mac_mismatch
                }
                val args = if (mac == null) emptyList() else if (!verify) listOf(mac)
                    else if (success) listOf(entered!!) else listOf(entered!!, mac)
                finishFeedback(ticket, page, message, success, probe.kind.linkTarget, probe.normalizedHost,
                    result.exceptionOrNull(), delivery = FeedbackDelivery.CONTEXTUAL, args = args)
                // Re-read the group: the user may have edited it while the lookup ran.
                if (!verify && mac != null) wakeGroups().firstOrNull { it.key == group.key }?.let {
                    setGroupWakeMac(it, mac)
                    _wakeMacResolved.value = WakeMacResolved(group.key, mac, System.nanoTime())
                }
            } finally { _wakeGroupBusy.update { it - group.key } }
        }
    }

    internal fun wakeGroup(group: WakeHostGroup) {
        val target = group.wakeTarget
        val probe = group.probe ?: target
        val member = if (target.kind.isMediaServer) target.kind.linkTarget else "${target.kind.linkTarget}:${target.id}"
        manualWake(com.ambient.tv.wol.ManualWakeRequest(target.kind.linkTarget, member,
            group.hosts.firstOrNull() ?: target.label, probe.normalizedHost.ifBlank { null }, group.mac,
            probe.port, target.broadcast, target.wolPort, group.probe?.normalizedHost?.takeIf { group.wireGuard }))
    }

    /** Tests every media server on the machine; each reports its own result. */
    internal fun testGroup(group: WakeHostGroup) {
        group.members.forEach { member ->
            when (member.kind) {
                WakeMemberKind.PLEX -> testConnection()
                WakeMemberKind.JELLYFIN -> testJellyfinConnection()
                WakeMemberKind.EMBY -> testEmbyConnection()
                WakeMemberKind.TVHEADEND -> testTvheadendConnection()
                else -> Unit
            }
        }
    }

    private fun MutableStateFlow<Set<String>>.compareAndSetAdd(key: String): Boolean {
        while (true) {
            val current = value
            if (key in current) return false
            if (compareAndSet(current, current + key)) return true
        }
    }

    fun dlnaRendererEnabled(): Boolean = appPreferences.dlnaRendererEnabled
    fun setDlnaRendererEnabled(enabled: Boolean) {
        appPreferences.dlnaRendererEnabled = enabled
        // Switching it off also forgets which senders were allowed without asking.
        if (!enabled) appPreferences.dlnaRendererTrusted = emptySet()
        dlnaRenderer.refresh()
    }
    fun refreshDlnaRenderer() = dlnaRenderer.refresh()
    fun dlnaRendererAsk(): Boolean = appPreferences.dlnaRendererAsk
    fun setDlnaRendererAsk(ask: Boolean) { appPreferences.dlnaRendererAsk = ask }
    fun dlnaRendererTrustedCount(): Int = appPreferences.dlnaRendererTrusted.size
    fun forgetDlnaRendererSenders() { appPreferences.dlnaRendererTrusted = emptySet() }

    /** Android TV home screen rows; only offered where the TV provider exists. */
    val launcherIntegrationSupported: Boolean get() = launcherPublisher.supported
    /** Which media servers feed the "Continue watching · all systems" Home row. */
    fun allContinueSystems(): Set<String> = appPreferences.allContinueSystems
    fun setAllContinueSystem(system: String, included: Boolean) {
        val current = appPreferences.allContinueSystems
        appPreferences.allContinueSystems = if (included) current + system else current - system
    }

    fun launcherIntegrationEnabled(): Boolean = appPreferences.launcherIntegrationEnabled
    fun setLauncherIntegrationEnabled(enabled: Boolean) {
        appPreferences.launcherIntegrationEnabled = enabled
        // On again: the next Home load or playback stop republishes.
        if (!enabled) launcherPublisher.clearAll()
    }

    fun clearRecentTvChannels() = settingsAction("settings:recent-tv", SettingsCategory.TVHEADEND, R.string.settings_recent_tv_cleared, null, null) { clearRecentTvChannelsImpl() }

    private fun clearRecentTvChannelsImpl() {
        appPreferences.clearRecentTvChannels()
        _uiState.update { it.copy(statusMessage = context.getString(R.string.settings_recent_tv_cleared)) }
    }

    fun onPlexEnabledChanged(value: Boolean) = _uiState.update { current ->
        current.copy(plexEnabled = value)
    }
    fun onJellyfinEnabledChanged(value: Boolean) = _uiState.update { it.copy(jellyfinEnabled = value) }
    fun onJellyfinHostChanged(value: String) = _uiState.update { it.copy(jellyfinHost = value) }
    fun onJellyfinPortChanged(value: String) = _uiState.update { it.copy(jellyfinPort = value.filter(Char::isDigit)) }
    fun onJellyfinHttpsChanged(value: Boolean) = _uiState.update { it.copy(jellyfinUseHttps = value) }
    fun onJellyfinBasePathChanged(value: String) = _uiState.update { it.copy(jellyfinBasePath = value) }
    fun onJellyfinUsernameChanged(value: String) = _uiState.update { it.copy(jellyfinUsername = value) }
    fun onJellyfinPasswordChanged(value: String) = _uiState.update { it.copy(jellyfinPassword = value) }
    fun onJellyfinMacChanged(value: String) = _uiState.update { it.copy(jellyfinMacAddress = value) }
    fun onJellyfinAutoWakeChanged(value: Boolean) = _uiState.update { it.copy(jellyfinAutoWakeEnabled = value) }
    fun onJellyfinSectionVisibilityChanged(key: String, visible: Boolean) = _uiState.update { state ->
        state.copy(jellyfinHiddenSectionKeys = if (visible) state.jellyfinHiddenSectionKeys - key else state.jellyfinHiddenSectionKeys + key)
    }

    fun signInJellyfin() {
        val state = _uiState.value
        if (state.isSigningInJellyfin) return
        if (state.jellyfinHost.isBlank() || state.jellyfinUsername.isBlank()) {
            _uiState.update { it.copy(statusMessage = context.getString(R.string.settings_jellyfin_missing_credentials)) }
            return
        }
        viewModelScope.launch {
            notifyLogin("jellyfin", NoticePhase.WORKING, state.jellyfinHost)
            _uiState.update { it.copy(isSigningInJellyfin = true, statusMessage = null) }
            jellyfinAuthRepository.signIn(
                host = state.jellyfinHost,
                port = state.jellyfinPort.toIntOrNull() ?: AppPreferences.DEFAULT_JELLYFIN_PORT,
                useHttps = state.jellyfinUseHttps,
                basePath = state.jellyfinBasePath,
                username = state.jellyfinUsername,
                password = state.jellyfinPassword,
                macAddress = normalizeMacAddress(state.jellyfinMacAddress),
                autoWake = state.jellyfinAutoWakeEnabled,
            ).fold(
                onSuccess = {
                    notifyLogin("jellyfin", NoticePhase.SUCCESS, it.name, state.jellyfinUsername)
                    jellyfinRepository.invalidateCaches()
                    val sections = jellyfinRepository.getLibrarySections().getOrDefault(emptyList())
                    if (appPreferences.homeRows.none { row -> row.system == HomeRowDefinition.SYSTEM_JELLYFIN }) {
                        appPreferences.homeRows = appPreferences.homeRows + listOf(
                            HomeRowDefinition(system = HomeRowDefinition.SYSTEM_JELLYFIN, contentKey = "continue"),
                            HomeRowDefinition(system = HomeRowDefinition.SYSTEM_JELLYFIN, contentKey = "recent"),
                            HomeRowDefinition(system = HomeRowDefinition.SYSTEM_JELLYFIN, contentKey = "collections"),
                            HomeRowDefinition(system = HomeRowDefinition.SYSTEM_JELLYFIN, contentKey = "libraries"),
                        )
                    }
                    _uiState.update { current -> current.copy(
                        jellyfinConfigured = true, jellyfinEnabled = true,
                        jellyfinLibrarySections = sections,
                        homeRows = if (current.homeRows != savedState.value.homeRows) current.homeRows else appPreferences.homeRows,
                        isSigningInJellyfin = false,
                        savedJellyfinServers = appPreferences.jellyfinServerProfiles,
                        activeJellyfinServerId = appPreferences.activeJellyfinServerId,
                        statusMessage = null,
                    ) }
                    refreshSavedValues()
                },
                onFailure = { notifyLogin("jellyfin", NoticePhase.ERROR, state.jellyfinHost, reason = failureReasonText(context, it, state.jellyfinHost)); _uiState.update { current -> current.copy(isSigningInJellyfin = false, statusMessage = it.message ?: context.getString(R.string.settings_status_error)) } },
            )
        }
    }

    fun signOutJellyfin() {
        if (_uiState.value.isSigningInJellyfin) return
        val active = appPreferences.activeJellyfinServerProfile()
        if (active != null) {
            tokenStore.removeJellyfinTokenForServer(active.id)
            tokenStore.removeJellyfinPasswordForServer(active.id)
            appPreferences.upsertJellyfinServerProfile(
                active.copy(userId = null, authenticated = false),
                makeActive = true,
            )
        } else {
            appPreferences.jellyfinUserId = null
        }
        jellyfinRepository.invalidateCaches()
        _uiState.update {
            it.copy(
                jellyfinConfigured = false,
                jellyfinPassword = "",
                jellyfinLibrarySections = emptyList(),
                savedJellyfinServers = appPreferences.jellyfinServerProfiles,
                statusMessage = null,
            )
        }
        notifyLogin("jellyfin", NoticePhase.INFO, active?.name)
        refreshSavedValues()
    }

    fun testJellyfinConnection() {
        if (_uiState.value.isTestingJellyfin) return
        val page = SettingsCategory.JELLYFIN
        val ticket = beginFeedback("test:jellyfin")
        val persisted = runCatching { persistJellyfin(_uiState.value); refreshSavedValues() }
        if (persisted.isFailure) {
            finishFeedback(ticket, page, R.string.notice_failed, false, "jellyfin", error = persisted.exceptionOrNull(), delivery = FeedbackDelivery.NOTIFICATION)
            return
        }
        _uiState.update { it.copy(isTestingJellyfin = true) }
        viewModelScope.launch {
            try {
                val result = suspendResult {
                    
                    check(jellyfinConnectionManager.reconnect()) { context.getString(R.string.server_connection_failed) }
                    jellyfinConnectionManager.testCurrentServer().getOrThrow().also { refreshBus.requestRefresh() }
                }
                
                finishFeedback(ticket, page, if (result.isSuccess) R.string.notice_test_ok else R.string.notice_failed,
                    result.isSuccess, "jellyfin", error = result.exceptionOrNull(), delivery = FeedbackDelivery.NOTIFICATION)
            } finally { _uiState.update { it.copy(isTestingJellyfin = false) } }
        }
    }

    fun resolveJellyfinMac() { lookupMac("jellyfin", verify = false) }

    fun selectJellyfinServer(profile: JellyfinServerProfile) {
        if (!appPreferences.activateJellyfinServer(profile.id)) return
        jellyfinRepository.invalidateCaches()
        _uiState.update { it.copy(
            jellyfinConfigured = profile.authenticated &&
                !profile.userId.isNullOrBlank() &&
                !tokenStore.jellyfinTokenForServer(profile.id).isNullOrBlank(),
            jellyfinEnabled = true,
            jellyfinHost = profile.host, jellyfinPort = profile.port.toString(), jellyfinUseHttps = profile.useHttps,
            jellyfinBasePath = profile.basePath,
            jellyfinUsername = profile.username.orEmpty(),
            jellyfinPassword = tokenStore.jellyfinPasswordForServer(profile.id).orEmpty(),
            jellyfinMacAddress = profile.macAddress.orEmpty(), jellyfinAutoWakeEnabled = profile.autoWakeEnabled,
            activeJellyfinServerId = profile.id, statusMessage = profile.name,
        ) }
        viewModelScope.launch {
            val sections = jellyfinRepository.getLibrarySections().getOrDefault(emptyList())
            _uiState.update { it.copy(jellyfinLibrarySections = sections) }
        }
        finishFeedback(beginFeedback("server:jellyfin"), SettingsCategory.JELLYFIN, R.string.notice_server_selected, true, "jellyfin", profile.name)
        refreshSavedValues()
    }

    fun discoverJellyfinServers() {
        if (_uiState.value.isDiscoveringJellyfin) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscoveringJellyfin = true, statusMessage = null) }
            val servers = jellyfinLanDiscovery.discover()
            _uiState.update { it.copy(
                isDiscoveringJellyfin = false,
                discoveredJellyfinServers = servers,
                discoveredFocusSystem = if (servers.isEmpty()) it.discoveredFocusSystem else "jellyfin",
                statusMessage = if (servers.isEmpty()) context.getString(R.string.settings_jellyfin_discovery_none) else null,
            ) }
        }
    }

    fun selectDiscoveredJellyfinServer(server: DiscoveredJellyfinServer) {
        val saved = appPreferences.jellyfinServerProfiles.firstOrNull { profile ->
            (!server.serverId.isNullOrBlank() && profile.serverId == server.serverId) ||
                (profile.host.equals(server.host, ignoreCase = true) && profile.port == server.port)
        }
        val token = saved?.let { tokenStore.jellyfinTokenForServer(it.id) }
        if (
            saved != null &&
            mediaServerDiscoveredServerAction(token, saved.userId, saved.authenticated) == DiscoveredServerAction.Connect
        ) {
            val refreshed = saved.copy(
                name = server.name.ifBlank { saved.name },
                host = server.host,
                port = server.port,
                useHttps = server.useHttps,
                basePath = server.basePath,
                serverId = server.serverId ?: saved.serverId,
            )
            if (refreshed != saved) appPreferences.upsertJellyfinServerProfile(refreshed, makeActive = false)
            selectJellyfinServer(refreshed)
            return
        }
        val current = _uiState.value
        val sameDraft = current.jellyfinHost.equals(server.host, ignoreCase = true) &&
            (current.jellyfinPort.toIntOrNull() ?: -1) == server.port
        _uiState.update {
            it.copy(
                jellyfinHost = server.host,
                jellyfinPort = server.port.toString(),
                jellyfinUseHttps = server.useHttps,
                jellyfinBasePath = server.basePath,
                jellyfinUsername = saved?.username?.takeIf { name -> name.isNotBlank() }
                    ?: current.jellyfinUsername.takeIf { sameDraft }.orEmpty(),
                jellyfinPassword = saved?.id?.let(tokenStore::jellyfinPasswordForServer)
                    ?.takeIf { password -> password.isNotBlank() }
                    ?: current.jellyfinPassword.takeIf { sameDraft }.orEmpty(),
                jellyfinConfigured = false,
                statusMessage = server.name,
            )
        }
        // Existing username/password sign-in. No connection until that succeeds.
        signInJellyfin()
    }

    fun removeJellyfinServer(profile: JellyfinServerProfile) = settingsAction("server:remove:jellyfin:${profile.id}", SettingsCategory.JELLYFIN, R.string.feedback_server_removed, "jellyfin", profile.name) { removeJellyfinServerImpl(profile) }

    private fun removeJellyfinServerImpl(profile: JellyfinServerProfile) {
        appPreferences.removeJellyfinServerProfile(profile.id) ?: return
        tokenStore.removeJellyfinTokenForServer(profile.id)
        tokenStore.removeJellyfinPasswordForServer(profile.id)
        jellyfinRepository.invalidateCaches()
        val active = appPreferences.activeJellyfinServerProfile()
        _uiState.update { it.copy(
            savedJellyfinServers = appPreferences.jellyfinServerProfiles,
            activeJellyfinServerId = appPreferences.activeJellyfinServerId,
            jellyfinConfigured = appPreferences.isJellyfinConfigured,
            jellyfinHost = active?.host.orEmpty(), jellyfinPort = (active?.port ?: 8096).toString(),
            jellyfinUseHttps = active?.useHttps ?: false, jellyfinUsername = active?.username.orEmpty(),
            jellyfinPassword = active?.id?.let(tokenStore::jellyfinPasswordForServer).orEmpty(),
            jellyfinBasePath = active?.basePath.orEmpty(),
            jellyfinMacAddress = active?.macAddress.orEmpty(), jellyfinAutoWakeEnabled = active?.autoWakeEnabled ?: true,
        ) }
        refreshSavedValues()
    }


    fun onEmbyEnabledChanged(value: Boolean) = _uiState.update { it.copy(embyEnabled = value) }
    fun onEmbyHostChanged(value: String) = _uiState.update { it.copy(embyHost = value) }
    fun onEmbyPortChanged(value: String) = _uiState.update { it.copy(embyPort = value.filter(Char::isDigit)) }
    fun onEmbyHttpsChanged(value: Boolean) = _uiState.update { it.copy(embyUseHttps = value) }
    fun onEmbyBasePathChanged(value: String) = _uiState.update { it.copy(embyBasePath = value) }
    fun onEmbyUsernameChanged(value: String) = _uiState.update { it.copy(embyUsername = value) }
    fun onEmbyPasswordChanged(value: String) = _uiState.update { it.copy(embyPassword = value) }
    fun onEmbyMacChanged(value: String) = _uiState.update { it.copy(embyMacAddress = value) }
    fun onEmbyAutoWakeChanged(value: Boolean) = _uiState.update { it.copy(embyAutoWakeEnabled = value) }
    fun onEmbySectionVisibilityChanged(key: String, visible: Boolean) = _uiState.update { state ->
        state.copy(embyHiddenSectionKeys = if (visible) state.embyHiddenSectionKeys - key else state.embyHiddenSectionKeys + key)
    }

    fun signInEmby() {
        val state = _uiState.value
        if (state.isSigningInEmby) return
        if (state.embyHost.isBlank() || state.embyUsername.isBlank()) {
            _uiState.update { it.copy(statusMessage = context.getString(R.string.settings_emby_missing_credentials)) }
            return
        }
        viewModelScope.launch {
            notifyLogin("emby", NoticePhase.WORKING, state.embyHost)
            _uiState.update { it.copy(isSigningInEmby = true, statusMessage = null) }
            embyAuthRepository.signIn(
                host = state.embyHost,
                port = state.embyPort.toIntOrNull() ?: AppPreferences.DEFAULT_EMBY_PORT,
                useHttps = state.embyUseHttps,
                basePath = state.embyBasePath,
                username = state.embyUsername,
                password = state.embyPassword,
                macAddress = normalizeMacAddress(state.embyMacAddress),
                autoWake = state.embyAutoWakeEnabled,
            ).fold(
                onSuccess = {
                    notifyLogin("emby", NoticePhase.SUCCESS, it.name, state.embyUsername)
                    embyRepository.invalidateCaches()
                    val sections = embyRepository.getLibrarySections().getOrDefault(emptyList())
                    if (appPreferences.homeRows.none { row -> row.system == HomeRowDefinition.SYSTEM_EMBY }) {
                        appPreferences.homeRows = appPreferences.homeRows + listOf(
                            HomeRowDefinition(system = HomeRowDefinition.SYSTEM_EMBY, contentKey = "continue"),
                            HomeRowDefinition(system = HomeRowDefinition.SYSTEM_EMBY, contentKey = "recent"),
                            HomeRowDefinition(system = HomeRowDefinition.SYSTEM_EMBY, contentKey = "collections"),
                            HomeRowDefinition(system = HomeRowDefinition.SYSTEM_EMBY, contentKey = "libraries"),
                        )
                    }
                    _uiState.update { current -> current.copy(
                        embyConfigured = true, embyEnabled = true,
                        embyLibrarySections = sections,
                        homeRows = if (current.homeRows != savedState.value.homeRows) current.homeRows else appPreferences.homeRows,
                        isSigningInEmby = false,
                        savedEmbyServers = appPreferences.embyServerProfiles,
                        activeEmbyServerId = appPreferences.activeEmbyServerId,
                        statusMessage = null,
                    ) }
                    refreshSavedValues()
                },
                onFailure = { notifyLogin("emby", NoticePhase.ERROR, state.embyHost, reason = failureReasonText(context, it, state.embyHost)); _uiState.update { current -> current.copy(isSigningInEmby = false, statusMessage = it.message ?: context.getString(R.string.settings_status_error)) } },
            )
        }
    }

    fun signOutEmby() {
        if (_uiState.value.isSigningInEmby) return
        val active = appPreferences.activeEmbyServerProfile()
        if (active != null) {
            tokenStore.removeEmbyTokenForServer(active.id)
            tokenStore.removeEmbyPasswordForServer(active.id)
            appPreferences.upsertEmbyServerProfile(
                active.copy(userId = null, authenticated = false),
                makeActive = true,
            )
        } else {
            appPreferences.embyUserId = null
        }
        embyRepository.invalidateCaches()
        _uiState.update {
            it.copy(
                embyConfigured = false,
                embyPassword = "",
                embyLibrarySections = emptyList(),
                savedEmbyServers = appPreferences.embyServerProfiles,
                statusMessage = null,
            )
        }
        notifyLogin("emby", NoticePhase.INFO, active?.name)
        refreshSavedValues()
    }

    fun testEmbyConnection() {
        if (_uiState.value.isTestingEmby) return
        val page = SettingsCategory.EMBY
        val ticket = beginFeedback("test:emby")
        val persisted = runCatching { persistEmby(_uiState.value); refreshSavedValues() }
        if (persisted.isFailure) {
            finishFeedback(ticket, page, R.string.notice_failed, false, "emby", error = persisted.exceptionOrNull(), delivery = FeedbackDelivery.NOTIFICATION)
            return
        }
        _uiState.update { it.copy(isTestingEmby = true) }
        viewModelScope.launch {
            try {
                val result = suspendResult {
                    
                    check(embyConnectionManager.reconnect()) { context.getString(R.string.server_connection_failed) }
                    embyConnectionManager.testCurrentServer().getOrThrow().also { refreshBus.requestRefresh() }
                }
                
                finishFeedback(ticket, page, if (result.isSuccess) R.string.notice_test_ok else R.string.notice_failed,
                    result.isSuccess, "emby", error = result.exceptionOrNull(), delivery = FeedbackDelivery.NOTIFICATION)
            } finally { _uiState.update { it.copy(isTestingEmby = false) } }
        }
    }

    fun resolveEmbyMac() { lookupMac("emby", verify = false) }

    fun selectEmbyServer(profile: EmbyServerProfile) {
        if (!appPreferences.activateEmbyServer(profile.id)) return
        embyRepository.invalidateCaches()
        _uiState.update { it.copy(
            embyConfigured = profile.authenticated &&
                !profile.userId.isNullOrBlank() &&
                !tokenStore.embyTokenForServer(profile.id).isNullOrBlank(),
            embyEnabled = true,
            embyHost = profile.host, embyPort = profile.port.toString(), embyUseHttps = profile.useHttps,
            embyBasePath = profile.basePath,
            embyUsername = profile.username.orEmpty(),
            embyPassword = tokenStore.embyPasswordForServer(profile.id).orEmpty(),
            embyMacAddress = profile.macAddress.orEmpty(), embyAutoWakeEnabled = profile.autoWakeEnabled,
            activeEmbyServerId = profile.id, statusMessage = profile.name,
        ) }
        viewModelScope.launch {
            val sections = embyRepository.getLibrarySections().getOrDefault(emptyList())
            _uiState.update { it.copy(embyLibrarySections = sections) }
        }
        finishFeedback(beginFeedback("server:emby"), SettingsCategory.EMBY, R.string.notice_server_selected, true, "emby", profile.name)
        refreshSavedValues()
    }

    fun discoverEmbyServers() {
        if (_uiState.value.isDiscoveringEmby) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscoveringEmby = true, statusMessage = null) }
            val servers = embyLanDiscovery.discover()
            _uiState.update { it.copy(
                isDiscoveringEmby = false,
                discoveredEmbyServers = servers,
                discoveredFocusSystem = if (servers.isEmpty()) it.discoveredFocusSystem else "emby",
                statusMessage = if (servers.isEmpty()) context.getString(R.string.settings_emby_discovery_none) else null,
            ) }
        }
    }

    fun selectDiscoveredEmbyServer(server: DiscoveredEmbyServer) {
        val saved = appPreferences.embyServerProfiles.firstOrNull { profile ->
            (!server.serverId.isNullOrBlank() && profile.serverId == server.serverId) ||
                (profile.host.equals(server.host, ignoreCase = true) && profile.port == server.port)
        }
        val token = saved?.let { tokenStore.embyTokenForServer(it.id) }
        if (
            saved != null &&
            mediaServerDiscoveredServerAction(token, saved.userId, saved.authenticated) == DiscoveredServerAction.Connect
        ) {
            val refreshed = saved.copy(
                name = server.name.ifBlank { saved.name },
                host = server.host,
                port = server.port,
                useHttps = server.useHttps,
                basePath = server.basePath,
                serverId = server.serverId ?: saved.serverId,
            )
            if (refreshed != saved) appPreferences.upsertEmbyServerProfile(refreshed, makeActive = false)
            selectEmbyServer(refreshed)
            return
        }
        val current = _uiState.value
        val sameDraft = current.embyHost.equals(server.host, ignoreCase = true) &&
            (current.embyPort.toIntOrNull() ?: -1) == server.port
        _uiState.update {
            it.copy(
                embyHost = server.host,
                embyPort = server.port.toString(),
                embyUseHttps = server.useHttps,
                embyBasePath = server.basePath,
                embyUsername = saved?.username?.takeIf { name -> name.isNotBlank() }
                    ?: current.embyUsername.takeIf { sameDraft }.orEmpty(),
                embyPassword = saved?.id?.let(tokenStore::embyPasswordForServer)
                    ?.takeIf { password -> password.isNotBlank() }
                    ?: current.embyPassword.takeIf { sameDraft }.orEmpty(),
                embyConfigured = false,
                statusMessage = server.name,
            )
        }
        signInEmby()
    }

    fun removeEmbyServer(profile: EmbyServerProfile) = settingsAction("server:remove:emby:${profile.id}", SettingsCategory.EMBY, R.string.feedback_server_removed, "emby", profile.name) { removeEmbyServerImpl(profile) }

    private fun removeEmbyServerImpl(profile: EmbyServerProfile) {
        appPreferences.removeEmbyServerProfile(profile.id) ?: return
        tokenStore.removeEmbyTokenForServer(profile.id)
        tokenStore.removeEmbyPasswordForServer(profile.id)
        embyRepository.invalidateCaches()
        val active = appPreferences.activeEmbyServerProfile()
        _uiState.update { it.copy(
            savedEmbyServers = appPreferences.embyServerProfiles,
            activeEmbyServerId = appPreferences.activeEmbyServerId,
            embyConfigured = appPreferences.isEmbyConfigured,
            embyHost = active?.host.orEmpty(), embyPort = (active?.port ?: 8096).toString(),
            embyUseHttps = active?.useHttps ?: false, embyUsername = active?.username.orEmpty(),
            embyPassword = active?.id?.let(tokenStore::embyPasswordForServer).orEmpty(),
            embyBasePath = active?.basePath.orEmpty(),
            embyMacAddress = active?.macAddress.orEmpty(), embyAutoWakeEnabled = active?.autoWakeEnabled ?: true,
        ) }
        refreshSavedValues()
    }

    fun enableFilesForManagement() {
        appPreferences.filesEnabled = true
        _uiState.update { it.copy(filesEnabled = true) }
        refreshSavedValues()
    }

    fun onFilesEnabledChanged(value: Boolean) {
        _uiState.update { it.copy(filesEnabled = value) }
    }

    /** A blank name drops the entry, so the system shows its provider name again. */
    fun onSystemNameChanged(systemKey: String, value: String) = _uiState.update { state ->
        val name = value.take(SystemNameOverrides.MAX_LENGTH)
        state.copy(systemNames = if (name.isBlank()) state.systemNames - systemKey else state.systemNames + (systemKey to name))
    }

    fun onYoutubeEnabledChanged(value: Boolean) {
        _uiState.update { it.copy(youtubeEnabled = value) }
    }

    fun requestYouTubeSignIn() {
        if (!smartTubeEngine.available || _uiState.value.youtubeAuthBusy) return
        youtubeSignInWatchJob?.cancel()
        notifyLogin("youtube", NoticePhase.WORKING)
        viewModelScope.launch {
            _uiState.update { it.copy(
                youtubeAuthBusy = true,
                youtubeAuthError = null,
                youtubeAuthErrorRes = null,
                youtubeSignInCode = null,
            ) }
            suspendResult { smartTubeEngine.requestSignInCode() }
                .onSuccess { code ->
                    val signed = suspendResult { smartTubeEngine.isSignedIn() }.getOrDefault(false)
                    _uiState.update { it.copy(
                        youtubeAuthBusy = false,
                        youtubeSignedIn = signed,
                        youtubeSignInCode = if (signed) null else code,
                        youtubeAuthErrorRes = if (!signed && code == null) R.string.youtube_error_login_start else null,
                    ) }
                    if (signed) notifyLogin("youtube", NoticePhase.SUCCESS)
                    else if (code == null) notifyLogin("youtube", NoticePhase.ERROR)
                    if (!signed && code != null) watchForYouTubeSignIn()
                }
                .onFailure { error ->
                    notifyLogin("youtube", NoticePhase.ERROR, reason = failureReasonText(context, error))
                    _uiState.update { it.copy(
                        youtubeAuthBusy = false,
                        youtubeAuthError = error.message?.takeIf(String::isNotBlank),
                        youtubeAuthErrorRes = if (error.message.isNullOrBlank()) R.string.youtube_error_login_failed else null,
                    ) }
                }
        }
    }

    fun signOutYouTube() {
        if (!smartTubeEngine.available || _uiState.value.youtubeAuthBusy) return
        youtubeSignInWatchJob?.cancel()
        youtubeSignInWatchJob = null
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    youtubeAuthBusy = true,
                    youtubeSignInCode = null,
                    youtubeAuthError = null,
                    youtubeAuthErrorRes = null,
                )
            }
            suspendResult { smartTubeEngine.signOut() }.fold(
                onSuccess = {
                    notifyLogin("youtube", NoticePhase.INFO)
                    youtubeContentCache.clear()
                    _uiState.update { state ->
                        state.copy(
                            youtubeSignedIn = false,
                            youtubeAuthBusy = false,
                            statusMessage = null,
                        )
                    }
                },
                onFailure = { error ->
                    notifyLogin("youtube", NoticePhase.ERROR, reason = failureReasonText(context, error))
                    _uiState.update { state ->
                        state.copy(
                            youtubeAuthBusy = false,
                            youtubeAuthError = error.message?.takeIf(String::isNotBlank),
                            youtubeAuthErrorRes = if (error.message.isNullOrBlank()) R.string.settings_youtube_sign_out_failed else null,
                        )
                    }
                },
            )
        }
    }

    private fun watchForYouTubeSignIn() {
        youtubeSignInWatchJob?.cancel()
        youtubeSignInWatchJob = viewModelScope.launch {
            repeat(300) {
                delay(1_000)
                if (suspendResult { smartTubeEngine.isSignedIn() }.getOrDefault(false)) {
                    notifyLogin("youtube", NoticePhase.SUCCESS)
                    appPreferences.youtubeEnabled = true
                    _uiState.update { it.copy(
                        youtubeEnabled = true,
                        youtubeSignedIn = true,
                        youtubeSignInCode = null,
                        youtubeAuthError = null,
                        youtubeAuthErrorRes = null,
                        youtubeAuthBusy = false,
                    ) }
                    refreshSavedValues()
                    return@launch
                }
            }
            notifyLogin("youtube", NoticePhase.ERROR, message = R.string.notice_login_expired)
            _uiState.update { it.copy(
                youtubeAuthBusy = false,
                youtubeAuthError = null,
                youtubeAuthErrorRes = R.string.youtube_error_login_expired,
            ) }
        }
    }

    fun onSpotifySubmenuChanged(key: String, enabled: Boolean) = _uiState.update { state ->
        if (key !in AppPreferences.SPOTIFY_SUBMENU_KEYS) state else state.copy(spotifySubmenuKeys = if (enabled) (state.spotifySubmenuKeys + key).distinct() else state.spotifySubmenuKeys.filterNot { it == key })
    }
    fun onTvheadendSubmenuChanged(key: String, enabled: Boolean) = _uiState.update { state ->
        if (key !in AppPreferences.TVH_SUBMENU_KEYS) state else state.copy(tvheadendSubmenuKeys = if (enabled) (state.tvheadendSubmenuKeys + key).distinct() else state.tvheadendSubmenuKeys.filterNot { it == key })
    }
    fun onPlexSectionVisibilityChanged(key: String, visible: Boolean) = _uiState.update { state ->
        state.copy(plexHiddenSectionKeys = if (visible) state.plexHiddenSectionKeys - key else state.plexHiddenSectionKeys + key)
    }
    fun onSidebarSettingsShortcutChanged(system: String, visible: Boolean) = _uiState.update { state ->
        state.copy(sidebarSettingsShortcutHidden = if (visible) state.sidebarSettingsShortcutHidden - system else state.sidebarSettingsShortcutHidden + system)
    }

    fun onSpotifyEnabledChanged(value: Boolean) = _uiState.update { it.copy(spotifyEnabled = value) }
    fun onSpotifyClientIdChanged(value: String) = _uiState.update { it.copy(spotifyClientId = value.trim()) }
    fun loginSpotify() {
        appPreferences.spotifyClientId = _uiState.value.spotifyClientId
        refreshSavedValues()
        spotifyRepository.login(context).onFailure { error -> _uiState.update { it.copy(spotifyAuthError = error.message) } }
    }
    fun logoutSpotify() { spotifyRepository.logoutWebApi() }
    fun onSpotifyDeviceNameChanged(value: String) = _uiState.update { it.copy(spotifyDeviceName = value) }
    fun onSpotifyBitrateChanged(value: Int) = _uiState.update { it.copy(spotifyBitrateKbps = value) }
    fun onSpotifyVisualizerChanged(value: String) = _uiState.update { it.copy(spotifyVisualizerModeKey = value) }
    fun onMediaAudioPassthroughChanged(value: Boolean) = _uiState.update { it.copy(mediaAudioPassthroughEnabled = value) }

    fun onHostChanged(value: String) = _uiState.update { current ->
        val matching = appPreferences.serverProfiles.firstOrNull {
            it.host.equals(value.trim(), ignoreCase = true) && it.port == (current.port.toIntOrNull() ?: 32400)
        }
        current.copy(
            host = value,
            selectedMachineIdentifier = matching?.machineIdentifier,
            token = matching?.let { tokenStore.tokenForServer(it.id) }.orEmpty(),
            plexSignedIn = matching?.let { !tokenStore.tokenForServer(it.id).isNullOrBlank() } == true,
            macAddress = matching?.macAddress.orEmpty(),
            autoWakeEnabled = matching?.autoWakeEnabled ?: current.autoWakeEnabled,
        )
    }

    fun onPortChanged(value: String) = _uiState.update { current ->
        val digits = value.filter(Char::isDigit)
        val matching = appPreferences.serverProfiles.firstOrNull {
            it.host.equals(current.host.trim(), ignoreCase = true) && it.port == (digits.toIntOrNull() ?: -1)
        }
        current.copy(
            port = digits,
            selectedMachineIdentifier = matching?.machineIdentifier,
            token = matching?.let { tokenStore.tokenForServer(it.id) }.orEmpty(),
            plexSignedIn = matching?.let { !tokenStore.tokenForServer(it.id).isNullOrBlank() } == true,
            macAddress = matching?.macAddress.orEmpty(),
            autoWakeEnabled = matching?.autoWakeEnabled ?: current.autoWakeEnabled,
        )
    }
    fun onTokenChanged(value: String) = _uiState.update { it.copy(token = value) }
    fun onMacAddressChanged(value: String) = _uiState.update { it.copy(macAddress = formatMacAddressInput(value)) }
    fun onAutoWakeToggled(value: Boolean) = _uiState.update { it.copy(autoWakeEnabled = value) }

    fun setWireGuardPlexEnabled(enabled: Boolean) = setWireGuardSystem(enabled, WireGuardSystem.PLEX)

    fun setWireGuardJellyfinEnabled(enabled: Boolean) = setWireGuardSystem(enabled, WireGuardSystem.JELLYFIN)

    fun setWireGuardEmbyEnabled(enabled: Boolean) = setWireGuardSystem(enabled, WireGuardSystem.EMBY)

    fun setWireGuardTvheadendEnabled(enabled: Boolean) = setWireGuardSystem(enabled, WireGuardSystem.TVHEADEND)

    private enum class WireGuardSystem { PLEX, JELLYFIN, EMBY, TVHEADEND }

    private fun wireGuardHost(system: WireGuardSystem): String = when (system) {
        WireGuardSystem.PLEX -> appPreferences.host.trim()
        WireGuardSystem.JELLYFIN -> appPreferences.jellyfinHost.trim()
        WireGuardSystem.EMBY -> appPreferences.embyHost.trim()
        WireGuardSystem.TVHEADEND -> NetworkEndpoint.extractHost(appPreferences.tvheadendHost).trim()
    }

    private fun setWireGuardPreference(system: WireGuardSystem, enabled: Boolean) {
        when (system) {
            WireGuardSystem.PLEX -> appPreferences.wireGuardPlexEnabled = enabled
            WireGuardSystem.JELLYFIN -> appPreferences.wireGuardJellyfinEnabled = enabled
            WireGuardSystem.EMBY -> appPreferences.wireGuardEmbyEnabled = enabled
            WireGuardSystem.TVHEADEND -> appPreferences.wireGuardTvheadendEnabled = enabled
        }
    }

    // Includes NAS sources routed through the tunnel, so turning off Plex etc. doesn't cut them off.
    private fun anyWireGuardSystemEnabled(): Boolean = wireGuardRoute.anySystemEnabled()

    private fun setWireGuardSystem(enabled: Boolean, system: WireGuardSystem) {
        val host = wireGuardHost(system)
        if (enabled) {
            val profile = appPreferences.wireGuardProfile()
            val numericHost = runCatching { com.ambient.tv.vpn.ipv4ToInt(host) }.isSuccess
            if (host.isBlank() || profile == null || (numericHost && !hostCovered(profile.allowedIps, host))) {
                _uiState.update { it.copy(statusMessage = context.getString(R.string.wg_host_outside)) }
                return
            }
        }
        setWireGuardPreference(system, enabled)
        _uiState.update { state ->
            when (system) {
                WireGuardSystem.PLEX -> state.copy(wireGuardPlexEnabled = enabled)
                WireGuardSystem.JELLYFIN -> state.copy(wireGuardJellyfinEnabled = enabled)
                WireGuardSystem.EMBY -> state.copy(wireGuardEmbyEnabled = enabled)
                WireGuardSystem.TVHEADEND -> state.copy(wireGuardTvheadendEnabled = enabled)
            }
        }
        viewModelScope.launch {
            if (!anyWireGuardSystemEnabled()) {
                wireGuardRoute.stop()
                return@launch
            }
            val activation = wireGuardRoute.ensureActive()
            activation.onFailure { error ->
                if (!enabled) return@onFailure
                setWireGuardPreference(system, false)
                _uiState.update { state ->
                    val failed = error.message ?: context.getString(R.string.wg_failed)
                    when (system) {
                        WireGuardSystem.PLEX -> state.copy(wireGuardPlexEnabled = false, statusMessage = failed)
                        WireGuardSystem.JELLYFIN -> state.copy(wireGuardJellyfinEnabled = false, statusMessage = failed)
                        WireGuardSystem.EMBY -> state.copy(wireGuardEmbyEnabled = false, statusMessage = failed)
                        WireGuardSystem.TVHEADEND -> state.copy(wireGuardTvheadendEnabled = false, statusMessage = failed)
                    }
                }
            }
            // If adding one system failed, restore the tunnel for any systems that were already valid.
            if (activation.isFailure && enabled && anyWireGuardSystemEnabled()) wireGuardRoute.ensureActive()
        }
    }

    private var wireGuardConfigBusy = false

    fun importWireGuardConfig(text: String) { readWireGuardConfig { text } }

    private fun readWireGuardConfig(read: suspend () -> String) {
        if (wireGuardConfigBusy) return
        wireGuardConfigBusy = true
        val ticket = beginFeedback("vpn:configuration")
        viewModelScope.launch {
            try {
                val result = suspendResult {
                    val parsed = parseWireGuardConfig(read())
                    appPreferences.setWireGuardProfile(parsed.profile)
                    tokenStore.wireGuardPrivateKey = parsed.privateKey
                    tokenStore.wireGuardPresharedKey = parsed.presharedKey
                    if (runCatching { com.ambient.tv.vpn.ipv4ToInt(appPreferences.host.trim()) }.isSuccess &&
                        !hostCovered(parsed.profile.allowedIps, appPreferences.host)) appPreferences.wireGuardPlexEnabled = false
                    if (runCatching { com.ambient.tv.vpn.ipv4ToInt(appPreferences.jellyfinHost.trim()) }.isSuccess &&
                        !hostCovered(parsed.profile.allowedIps, appPreferences.jellyfinHost)) appPreferences.wireGuardJellyfinEnabled = false
                    if (runCatching { com.ambient.tv.vpn.ipv4ToInt(appPreferences.embyHost.trim()) }.isSuccess &&
                        !hostCovered(parsed.profile.allowedIps, appPreferences.embyHost)) appPreferences.wireGuardEmbyEnabled = false
                    val tvheadendHost = NetworkEndpoint.extractHost(appPreferences.tvheadendHost).trim()
                    if (runCatching { com.ambient.tv.vpn.ipv4ToInt(tvheadendHost) }.isSuccess &&
                        !hostCovered(parsed.profile.allowedIps, tvheadendHost)) appPreferences.wireGuardTvheadendEnabled = false
                    _uiState.update {
                        it.copy(
                            wireGuardSummary = wireGuardSummary(parsed.profile),
                            wireGuardTestMessage = null,
                            wireGuardPlexEnabled = appPreferences.wireGuardPlexEnabled,
                            wireGuardJellyfinEnabled = appPreferences.wireGuardJellyfinEnabled,
                            wireGuardEmbyEnabled = appPreferences.wireGuardEmbyEnabled,
                            wireGuardTvheadendEnabled = appPreferences.wireGuardTvheadendEnabled,
                            statusMessage = null,
                        )
                    }

                }
                // A rejected config names what is wrong (missing key, bad Endpoint, …); never key material.
                val reason = result.exceptionOrNull()?.let { error ->
                    (error as? IllegalArgumentException)?.message?.takeIf { it.isNotBlank() }
                        ?: com.ambient.tv.notifications.failureReasonText(context, error)
                }
                finishFeedback(ticket, SettingsCategory.WIREGUARD,
                    if (result.isSuccess) R.string.wg_saved else R.string.wg_import_failed_reason,
                    result.isSuccess, subject = "WireGuard", error = result.exceptionOrNull(),
                    args = listOfNotNull(reason))
                // Tunnel activation has its own central connection feedback.
                if (result.isSuccess) {
                    if (!anyWireGuardSystemEnabled()) wireGuardRoute.stop() else wireGuardRoute.ensureActive()
                }
            } finally { wireGuardConfigBusy = false }
        }
    }

    fun wireGuardPrepareIntent(): android.content.Intent? = VpnService.prepare(context)

    fun wireGuardInUse(): Boolean = wireGuardRoute.anySystemEnabled()

    fun testWireGuard() {
        if (_uiState.value.wireGuardTesting) return
        val ticket = beginFeedback("vpn:test")
        _uiState.update { it.copy(wireGuardTesting = true, wireGuardTestMessage = null) }
        viewModelScope.launch {
            try {
                val result = suspendResult { wireGuardRoute.testConnection().getOrThrow() }
                _uiState.update { it.copy(wireGuardTestOk = result.isSuccess) }
                finishFeedback(ticket, SettingsCategory.WIREGUARD,
                    if (result.isSuccess) R.string.wg_test_ok else R.string.notice_vpn_failed,
                    result.isSuccess, subject = "WireGuard", error = result.exceptionOrNull(),
                    delivery = FeedbackDelivery.NOTIFICATION)
            } finally { _uiState.update { it.copy(wireGuardTesting = false) } }
        }
    }

    /** Forgets the tunnel: profile, keys and every system/NAS opt-in, then stops the route. */
    fun removeWireGuardConfig() {
        if (wireGuardConfigBusy) return
        wireGuardConfigBusy = true
        val ticket = beginFeedback("vpn:configuration")
        viewModelScope.launch {
            try {
                val result = suspendResult {
                    wireGuardRoute.stop()
                    withContext(Dispatchers.IO) {
                        appPreferences.setWireGuardProfile(null)
                        tokenStore.wireGuardPrivateKey = null
                        tokenStore.wireGuardPresharedKey = null
                        appPreferences.wireGuardPlexEnabled = false
                        appPreferences.wireGuardJellyfinEnabled = false
                        appPreferences.wireGuardEmbyEnabled = false
                        appPreferences.wireGuardTvheadendEnabled = false
                        val sources = appPreferences.fileSources
                        if (sources.any { it.wireGuard }) appPreferences.fileSources = sources.map { it.copy(wireGuard = false) }
                    }
                    _uiState.update {
                        it.copy(
                            wireGuardSummary = "",
                            wireGuardPlexEnabled = false,
                            wireGuardJellyfinEnabled = false,
                            wireGuardEmbyEnabled = false,
                            wireGuardTvheadendEnabled = false,
                            wireGuardTestMessage = null,
                            statusMessage = null,
                        )
                    }
                }
                finishFeedback(ticket, SettingsCategory.WIREGUARD,
                    if (result.isSuccess) R.string.wg_removed else R.string.notice_failed,
                    result.isSuccess, subject = "WireGuard", error = result.exceptionOrNull())
            } finally { wireGuardConfigBusy = false }
        }
    }

    /** Configured Files sources double as a file picker on Android TV, where DocumentsUI is often absent. */
    fun wireGuardFileSources(): List<FileSource> = textFileImporter.sources()

    fun wireGuardFileRoot(source: FileSource): String = textFileImporter.root(source)

    suspend fun listWireGuardFiles(source: FileSource, path: String): Result<List<FileEntry>> =
        textFileImporter.listRemote(source, path, ::isWireGuardConfigFile)

    fun importWireGuardFile(source: FileSource, path: String) {
        readWireGuardConfig { textFileImporter.readRemote(source, path, WIREGUARD_FILE_LIMIT) }
    }

    fun importWireGuardUri(uri: Uri) {
        readWireGuardConfig { textFileImporter.readUri(uri, WIREGUARD_FILE_LIMIT) }
    }

    /** `.conf` (wg-quick, apps, providers), `.wg`, and `.conf.txt` as mail and share targets rename it. */
    private fun isWireGuardConfigFile(entry: FileEntry): Boolean {
        val name = entry.name.lowercase()
        return FilePaths.extension(entry.name) in setOf("conf", "wg", "cfg") || name.endsWith(".conf.txt")
    }

    /** Internal storage first, then mounted USB/SD volumes, derived from the app's per-volume dirs. */
    fun wireGuardDeviceRoots(): List<DeviceStorageRoot> = textFileImporter.deviceRoots()

    fun hasWireGuardDeviceAccess(): Boolean = textFileImporter.hasDeviceAccess()

    suspend fun listWireGuardDeviceFiles(path: String): Result<List<FileEntry>> =
        textFileImporter.listDevice(path, ::isWireGuardConfigFile)

    fun importWireGuardDeviceFile(path: String) {
        readWireGuardConfig { textFileImporter.readDevice(path, WIREGUARD_FILE_LIMIT) }
    }

    fun startWireGuard() {
        viewModelScope.launch {
            wireGuardRoute.ensureActive().onFailure { error ->
                _uiState.update { it.copy(statusMessage = error.message ?: context.getString(R.string.wg_failed)) }
            }
        }
    }

    fun stopWireGuard() = wireGuardRoute.disconnect()

    fun onWireGuardConsentDenied() {
        finishFeedback(beginFeedback("vpn:consent"), SettingsCategory.WIREGUARD,
            R.string.wg_consent, false, subject = "WireGuard", delivery = FeedbackDelivery.NOTIFICATION)
    }

    /** The stored tunnel for the details card; read again whenever the summary changes. */
    fun wireGuardProfile(): WireGuardProfile? = appPreferences.wireGuardProfile()

    private fun wireGuardSummary(profile: WireGuardProfile): String =
        "${profile.endpointHost}:${profile.endpointPort} · ${profile.allowedIps.joinToString()}"

    fun onChooseServerOnStartupToggled(value: Boolean) = _uiState.update { it.copy(chooseServerOnStartup = value) }
    fun onPlexHttpsChanged(value: Boolean) = _uiState.update { it.copy(plexUseHttps = value) }

    /** Switches the edited Plex server to its HTTPS plex.direct address (saved with Save). */
    fun usePlexDirectSuggestion() = _uiState.update { state ->
        val suggestion = state.plexDirectSuggestion ?: return@update state
        state.copy(
            host = suggestion.substringBeforeLast(':'),
            port = suggestion.substringAfterLast(':'),
            plexUseHttps = true,
            plexDirectSuggestion = null,
        )
    }

    fun dismissPlexDirectSuggestion() = _uiState.update { it.copy(plexDirectSuggestion = null) }

    fun onLocalNetworkOnlyToggled(value: Boolean) = _uiState.update { it.copy(localNetworkOnly = value) }
    fun onPlexThemeMusicToggled(value: Boolean) = _uiState.update { it.copy(plexThemeMusicEnabled = value) }
    fun onJellyfinThemeMusicToggled(value: Boolean) = _uiState.update { it.copy(jellyfinThemeMusicEnabled = value) }
    fun onEmbyThemeMusicToggled(value: Boolean) = _uiState.update { it.copy(embyThemeMusicEnabled = value) }
    fun onThemeMusicVolumePercentChanged(value: Int) = _uiState.update {
        it.copy(themeMusicVolumePercent = value.coerceIn(0, 100))
    }
    fun onAutoSelectDisplayVersionToggled(value: Boolean) = _uiState.update { it.copy(autoSelectDisplayVersion = value) }
    fun onDisplayRefreshAdjustmentChanged(value: String) = _uiState.update {
        it.copy(
            displayRefreshAdjustmentKey = value,
            preferredDisplayModeKey = if (value == AppPreferences.DISPLAY_REFRESH_OFF &&
                it.preferredDisplayModeKey in setOf(AppPreferences.DISPLAY_MODE_AUTO_CONTENT, AppPreferences.DISPLAY_MODE_AUTO_DISPLAY)
            ) "system" else it.preferredDisplayModeKey,
        )
    }
    fun onDisplayChangeNotificationsChanged(value: Boolean) = _uiState.update { it.copy(displayChangeNotificationsEnabled = value) }
    fun onSvpAvailableChanged(value: Boolean) = _uiState.update { it.copy(svpAvailable = value) }
    fun onDisplayModeWhitelistChanged(value: Set<String>) = _uiState.update { it.copy(displayModeWhitelist = value) }
    fun onPreferredDisplayModeChanged(value: String) = _uiState.update {
        it.copy(
            preferredDisplayModeKey = value,
            displayRefreshAdjustmentKey = if (value in setOf(AppPreferences.DISPLAY_MODE_AUTO_CONTENT, AppPreferences.DISPLAY_MODE_AUTO_DISPLAY) &&
                it.displayRefreshAdjustmentKey == AppPreferences.DISPLAY_REFRESH_OFF
            ) AppPreferences.DISPLAY_REFRESH_START_STOP else it.displayRefreshAdjustmentKey,
        )
    }
    fun onAllowDoubleRefreshRateChanged(value: Boolean) = _uiState.update { it.copy(allowDoubleRefreshRate = value) }
    fun onAllowThreeTwoPulldownChanged(value: Boolean) = _uiState.update { it.copy(allowThreeTwoPulldown = value) }
    fun onDisplaySwitchPauseChanged(value: Int) = _uiState.update { it.copy(displaySwitchPauseMs = AppPreferences.normalizeDisplaySwitchPauseMs(value)) }
    fun onMediaSelectionBehaviorChanged(value: String) = _uiState.update { it.copy(mediaSelectionBehaviorKey = MediaSelectionPreferences.normalize(value)) }
    private fun SettingsUiState.withHomeRows(rows: List<HomeRowDefinition>) = copy(
        // Collapse legacy duplicates that share the same (system, contentKey).
        homeRows = rows.distinctBy { it.system to it.contentKey },
        tvheadendShowOnHome = rows.any { it.enabled && it.system == HomeRowDefinition.SYSTEM_TVHEADEND && it.contentKey == "live" },
    )

    fun addHomeRow(system: String, contentKey: String) = _uiState.update { state ->
        if (!homeRowSourceAddable(system, contentKey, state)) return@update state
        // Already listed (including disabled): re-enable only via the row toggle, not the add chip.
        if (homeRowAlreadyPresent(system, contentKey, state)) return@update state
        // Photos are mostly landscape; poster (2:3) tiles would crop them heavily.
        state.withHomeRows(
            state.homeRows + HomeRowDefinition(
                system = system,
                contentKey = contentKey,
                displayMode = if (system in setOf(HomeRowDefinition.SYSTEM_PHOTOS, HomeRowDefinition.SYSTEM_TRAILERS)) {
                    HomeRowDefinition.DISPLAY_LANDSCAPE
                } else {
                    HomeRowDefinition.DISPLAY_POSTER
                },
            ),
        )
    }
    fun onSystemHomeRowChanged(system: String, rowType: String, visible: Boolean) = _uiState.update { state ->
        val key = "$system.$rowType"
        state.copy(systemHomeHiddenRows = if (visible) state.systemHomeHiddenRows - key else state.systemHomeHiddenRows + key)
    }

    private var homeGenreJob: Job? = null

    /** Lists the genres of [system]'s movie and show libraries for a "top in genre" Home row. */
    fun pickHomeGenre(system: String) {
        homeGenreJob?.cancel()
        _uiState.update { it.copy(homeGenrePicker = HomeGenrePicker(system)) }
        homeGenreJob = viewModelScope.launch {
            val repository: MediaRepository
            val hidden: Set<String>
            when (system) {
                HomeRowDefinition.SYSTEM_JELLYFIN -> { repository = jellyfinRepository; hidden = appPreferences.jellyfinHiddenSectionKeys }
                HomeRowDefinition.SYSTEM_EMBY -> { repository = embyRepository; hidden = appPreferences.embyHiddenSectionKeys }
                else -> { repository = mediaRepository; hidden = emptySet() }
            }
            val genres = coroutineScope {
                repository.getLibrarySections().getOrDefault(emptyList())
                    .filter { it.type in setOf("movie", "show", "mixed") && it.key !in hidden }
                    .map { section -> async { repository.getGenres(section.key).getOrDefault(emptyList()) } }
                    .awaitAll().flatten()
            }.distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
            _uiState.update { state ->
                if (state.homeGenrePicker?.system == system) state.copy(homeGenrePicker = HomeGenrePicker(system, loading = false, genres = genres)) else state
            }
        }
    }

    fun closeHomeGenrePicker() {
        homeGenreJob?.cancel()
        _uiState.update { it.copy(homeGenrePicker = null) }
    }

    fun updateHomeRow(row: HomeRowDefinition) = _uiState.update { state ->
        state.withHomeRows(state.homeRows.map { if (it.id == row.id) row else it })
    }
    fun removeHomeRow(id: String) = _uiState.update { state -> state.withHomeRows(state.homeRows.filterNot { it.id == id }) }
    fun moveHomeRow(id: String, direction: Int) = _uiState.update { state ->
        val index = state.homeRows.indexOfFirst { it.id == id }
        val target = (index + direction).takeIf { index >= 0 && it in state.homeRows.indices } ?: return@update state
        state.copy(homeRows = state.homeRows.toMutableList().also { rows ->
            val row = rows.removeAt(index)
            rows.add(target, row)
        })
    }
    fun onPlexAutoRefreshIntervalChanged(value: Int) = _uiState.update {
        it.copy(plexAutoRefreshIntervalSeconds = value.takeIf { seconds -> seconds in AppPreferences.PLEX_REFRESH_INTERVALS } ?: 60)
    }
    fun onLibraryGridColumnsChanged(value: Int) = _uiState.update { it.copy(libraryGridColumns = value.coerceIn(4, 12)) }
    fun onLibraryListRowsChanged(value: Int) = _uiState.update { it.copy(libraryListRows = value.coerceIn(4, 10)) }
    fun onHighQualityAssetsChanged(value: Boolean) = _uiState.update { it.copy(highQualityAssetsEnabled = value) }
    fun onUiLanguageChanged(value: String) = _uiState.update { it.copy(uiLanguageKey = LanguagePreferences.normalizeKey(value)) }
    fun onMetadataLanguageChanged(value: String) = _uiState.update { it.copy(metadataLanguageKey = LanguagePreferences.normalizeKey(value)) }
    fun onAudioLanguageChanged(value: String) = _uiState.update { it.copy(audioLanguageKey = LanguagePreferences.normalizeKey(value)) }
    fun onSubtitleModeChanged(value: String) = _uiState.update { it.copy(subtitleModeKey = value, forcedSubtitlesEnabled = value == "forced") }
    fun onSubtitleLanguageChanged(value: String) = _uiState.update { it.copy(subtitleLanguageKey = value) }
    fun onForcedSubtitlesInUiLanguageChanged(value: Boolean) = _uiState.update { it.copy(forcedSubtitlesInUiLanguage = value) }
    fun onFullSubtitlesOnForeignAudioChanged(value: Boolean) = _uiState.update { it.copy(fullSubtitlesOnForeignAudio = value) }
    fun onSubtitleFallbackChanged(value: String) = _uiState.update { it.copy(subtitleFallbackLanguageKey = value) }
    // Appearance + player backends stay in the draft until Save. MainActivity applies a live
    // preview via onUiPreferencesPreview; discard/dispose reloads the persisted baseline
    // (SETTINGS_035). Immediate AppPreferences writes previously marked these fields dirty
    // while making Discard a no-op.
    /** Overscan is a display calibration: it applies and persists immediately, outside the draft. */
    var overscanPercent: Int
        get() = appPreferences.overscanPercent
        set(value) { appPreferences.overscanPercent = value }

    fun onUiScaleChanged(value: Float) {
        val normalized = com.ambient.tv.data.local.UiScalePolicy.normalize(value)
        _uiState.update { it.copy(uiScale = normalized) }
    }

    fun onAccentColorChanged(value: String) {
        _uiState.update { it.copy(accentColorKey = value) }
    }

    fun onCustomAccentHexChanged(value: String) {
        val normalized = value.take(9)
        _uiState.update { it.copy(customAccentHex = normalized) }
    }

    fun onCustomPlaybackAccentHexChanged(value: String) {
        _uiState.update { it.copy(customPlaybackAccentHex = value.take(9)) }
    }

    fun onSwapAccentColors() {
        _uiState.update { it.withSwappedAccentColors() }
    }

    fun onDualAccentChanged(value: Boolean) {
        _uiState.update { it.copy(dualAccentEnabled = value) }
    }

    fun onThemeModeChanged(value: String) {
        _uiState.update { it.copy(themeModeKey = value) }
    }

    fun onUiHdrModeChanged(value: String) {
        _uiState.update { it.copy(uiHdrModeKey = com.ambient.tv.ui.display.normalizedUiHdrMode(value)) }
    }

    fun onSdrToHdrPeakNitsChanged(value: Int) {
        _uiState.update { it.copy(sdrToHdrPeakNits = com.ambient.tv.ui.display.normalizedSdrToHdrPeakNits(value)) }
    }

    fun onSdrToHdrChanged(value: Boolean) {
        _uiState.update { it.copy(sdrToHdrEnabled = value) }
    }

    fun onUiPerformanceModeChanged(value: String) {
        val normalized = value.takeIf { it in AppPreferences.PERFORMANCE_MODES } ?: "auto"
        _uiState.update { it.copy(uiPerformanceModeKey = normalized) }
    }

    fun onFrameDropTestSecondsChanged(value: Int) {
        _uiState.update { it.copy(frameDropTestSeconds = if (value in 2..60) value else 0) }
    }

    fun onPlaybackPerformanceModeChanged(value: String) {
        val normalized = value.takeIf { it in AppPreferences.PERFORMANCE_MODES } ?: "auto"
        _uiState.update { it.copy(playbackPerformanceModeKey = normalized) }
    }

    private var performanceProbeJob: kotlinx.coroutines.Job? = null

    fun cancelPerformanceProbe() {
        performanceProbeJob?.cancel()
        performanceProbeJob = null
        _uiState.update { it.copy(performanceProbe = null) }
    }

    fun checkUiPerformance() = runPerformanceProbe(com.ambient.tv.ui.performance.PerformanceProbeTarget.UI)

    fun checkPlaybackPerformance() = runPerformanceProbe(com.ambient.tv.ui.performance.PerformanceProbeTarget.PLAYBACK)

    /** Measures one side off-screen, stores only that preference, then posts a short notice. */
    private fun runPerformanceProbe(target: com.ambient.tv.ui.performance.PerformanceProbeTarget) {
        if (_uiState.value.performanceProbe != null) return
        _uiState.update { it.copy(performanceProbe = target) }
        performanceProbeJob = viewModelScope.launch {
            val mode = runCatching {
                withContext(Dispatchers.Default) {
                    val elapsed = when (target) {
                        com.ambient.tv.ui.performance.PerformanceProbeTarget.UI ->
                            com.ambient.tv.ui.performance.PerformanceProbe.measureUiDrawCost()
                        com.ambient.tv.ui.performance.PerformanceProbeTarget.PLAYBACK ->
                            com.ambient.tv.ui.performance.PerformanceProbe.measurePlaybackFilterLoad()
                    }
                    com.ambient.tv.ui.performance.PerformanceProbe.classify(elapsed)
                }
            }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                com.ambient.tv.ui.performance.PerformanceMode.BALANCED
            }
            val key = mode.key
            when (target) {
                com.ambient.tv.ui.performance.PerformanceProbeTarget.UI -> {
                    appPreferences.uiPerformanceModeKey = key
                    _uiState.update { it.copy(uiPerformanceModeKey = key, performanceProbe = null) }
                    savedState.update { it.copy(uiPerformanceModeKey = key) }
                }
                com.ambient.tv.ui.performance.PerformanceProbeTarget.PLAYBACK -> {
                    appPreferences.playbackPerformanceModeKey = key
                    _uiState.update { it.copy(playbackPerformanceModeKey = key, performanceProbe = null) }
                    savedState.update { it.copy(playbackPerformanceModeKey = key) }
                }
            }
            delay(60)
            val label = context.getString(when (mode) {
                com.ambient.tv.ui.performance.PerformanceMode.MAXIMUM -> R.string.settings_performance_maximum
                com.ambient.tv.ui.performance.PerformanceMode.BALANCED -> R.string.settings_performance_balanced
                com.ambient.tv.ui.performance.PerformanceMode.LOW -> R.string.settings_performance_low
                com.ambient.tv.ui.performance.PerformanceMode.AUTO -> R.string.settings_performance_auto
            })
            AppNotifications.post(AppNotice(
                key = "performance-probe:${target.name}",
                message = if (target == com.ambient.tv.ui.performance.PerformanceProbeTarget.UI) {
                    R.string.notice_ui_performance
                } else {
                    R.string.notice_playback_performance
                },
                phase = NoticePhase.SUCCESS,
                kind = NoticeKind.SETTINGS,
                args = listOf(label),
                interruptPlayback = false,
            ))
        }
    }

    fun onHomeBackgroundEverywhereChanged(value: Boolean) = _uiState.update { it.copy(homeBackgroundEverywhere = value) }

    fun onFocusArtworkBackdropChanged(value: Boolean) = _uiState.update { it.copy(focusArtworkBackdrop = value) }

    fun onHomeHeroStyleChanged(value: String) {
        _uiState.update { it.copy(homeHeroStyleKey = HomeHeroStyle.normalize(value)) }
    }

    fun onHomeHeroIntensityChanged(value: Int) {
        _uiState.update { it.copy(homeHeroIntensityPercent = HomeHeroStyle.normalizeIntensity(value)) }
    }

    fun onHomeHeroSpreadChanged(value: String) {
        _uiState.update { it.copy(homeHeroSpreadKey = HomeHeroStyle.normalizeSpread(value)) }
    }

    fun onNotificationDisplayDurationChanged(value: String) {
        val normalized = value.takeIf { it in AppPreferences.NOTIFICATION_DISPLAY_DURATIONS } ?: "normal"
        _uiState.update { it.copy(notificationDisplayDurationKey = normalized) }
    }

    fun onNavSoundsEnabledChanged(value: Boolean) {
        _uiState.update { it.copy(navSoundsEnabled = value) }
    }

    fun onNavSoundBankChanged(value: String) {
        _uiState.update { it.copy(navSoundBank = value) }
    }

    fun onNavSoundsVolumeChanged(value: Float) {
        _uiState.update { it.copy(navSoundsVolume = value.coerceIn(0f, 1f)) }
    }

    fun onPlaybackBackendChanged(value: String) {
        _uiState.update { it.copy(playbackBackendKey = value) }
    }

    fun onTvheadendPlaybackBackendChanged(value: String) {
        _uiState.update { it.copy(tvheadendPlaybackBackendKey = value) }
    }

    fun onBackgroundVideoDimChanged(value: Int) = _uiState.update { it.copy(backgroundVideoDimPercent = value.coerceIn(60, 90)) }

    fun onMpvVideoOutputModeChanged(value: String) = _uiState.update {
        it.copy(mpvVideoOutputModeKey = value.takeIf { mode -> mode in AppPreferences.MPV_VIDEO_OUTPUT_MODES } ?: "direct")
    }

    fun onMpvVideoDecoderChanged(value: String) = _uiState.update {
        it.copy(mpvVideoDecoderKey = value.takeIf { key -> key in AppPreferences.MPV_VIDEO_DECODERS } ?: "auto")
    }

    fun onMpvHdrGpuChanged(value: Boolean) {
        // Old runtime verdicts belong to the previous setting; start clean either way.
        com.ambient.tv.ui.player.backend.MpvHdrGpuSession.clear()
        _uiState.update { it.copy(mpvHdrGpuEnabled = value) }
    }

    fun onVlcVideoOutputModeChanged(value: String) = _uiState.update {
        it.copy(vlcVideoOutputModeKey = value.takeIf { key -> key in AppPreferences.VLC_VIDEO_OUTPUT_MODES } ?: "auto")
    }

    fun onVisibleBackgroundVideoPlaybackChanged(value: Boolean) = _uiState.update {
        it.copy(visibleBackgroundVideoPlayback = value)
    }

    fun onAudioVisualizerModeChanged(value: String) = _uiState.update { it.copy(audioVisualizerModeKey = value) }
    fun onProjectmPresetIntervalChanged(value: Int) = _uiState.update { it.copy(projectmPresetIntervalSeconds = value.coerceIn(10, 600)) }
    fun onProjectmTransitionChanged(value: Int) = _uiState.update { it.copy(projectmTransitionSeconds = value.coerceIn(0, 10)) }
    fun onProjectmQualityChanged(value: String) = _uiState.update { it.copy(projectmQualityKey = value) }
    fun onProjectmResolutionChanged(value: String) = _uiState.update {
        it.copy(projectmResolutionKey = com.ambient.tv.ui.common.projectm.ProjectMRenderResolution.normalize(value))
    }
    fun onProjectmShowPresetNameChanged(value: Boolean) = _uiState.update { it.copy(projectmShowPresetName = value) }
    fun onProjectmHdr4kChanged(value: Boolean) = _uiState.update { it.copy(projectmHdr4kAllowed = value) }
    fun onProjectmCategoryChanged(value: String) = _uiState.update { it.copy(projectmPresetCategoryKey = value) }
    fun onBigSeekJumpChanged(value: Int) = _uiState.update { it.copy(bigSeekJumpSeconds = value.coerceIn(10, 300)) }
    fun onCreditsSkipModeChanged(value: String) = _uiState.update { it.copy(creditsSkipMode = value) }

    fun onIntroSkipModeChanged(value: String) = _uiState.update { it.copy(introSkipMode = value) }

    fun onNextUpEnabledChanged(value: Boolean) = _uiState.update { it.copy(nextUpEnabled = value) }
    fun onNextUpLeadSecondsChanged(value: Int) = _uiState.update {
        it.copy(nextUpLeadSeconds = value.takeIf(AppPreferences.NEXT_UP_LEAD_SECONDS::contains) ?: 30)
    }
    fun onNextUpAutoplayEpisodesChanged(value: Boolean) = _uiState.update { it.copy(nextUpAutoplayEpisodes = value) }
    fun onNextUpAutoplayCollectionsChanged(value: Boolean) = _uiState.update { it.copy(nextUpAutoplayCollections = value) }
    fun onNextUpOverlayEnabledChanged(value: Boolean) = _uiState.update { it.copy(nextUpOverlayEnabled = value) }
    fun onNextUpStillWatchingEpisodesChanged(value: Int) = _uiState.update {
        it.copy(nextUpStillWatchingEpisodes = value.takeIf(AppPreferences.NEXT_UP_STILL_WATCHING_VALUES::contains) ?: 3)
    }
    fun onSleepAutoStartChanged(value: String) = _uiState.update {
        it.copy(sleepAutoStartKey = value.takeIf(AppPreferences.SLEEP_AUTO_START_KEYS::contains) ?: AppPreferences.SLEEP_AUTO_START_OFF)
    }
    fun onSleepDailyStopChanged(minuteOfDay: Int) = _uiState.update {
        it.copy(sleepDailyStopMinute = minuteOfDay.takeIf { m -> m in 0 until 1440 } ?: AppPreferences.SLEEP_DAILY_STOP_OFF)
    }
    fun onSleepWarningMinutesChanged(value: Int) = _uiState.update {
        it.copy(sleepWarningMinutes = value.takeIf(AppPreferences.SLEEP_WARNING_VALUES::contains) ?: AppPreferences.SLEEP_WARNING_DEFAULT)
    }
    fun onWolRetryCountChanged(value: Int) = _uiState.update { it.copy(wolRetryCount = com.ambient.tv.wol.normalizedWolRetryCount(value)) }
    fun onYoutubeSponsorBlockChanged(value: Boolean) = _uiState.update { it.copy(youtubeSponsorBlockEnabled = value) }
    fun onYoutubeAutoplayNextChanged(value: Boolean) = _uiState.update { it.copy(youtubeAutoplayNextEnabled = value) }
    fun onYoutubeFeedChanged(key: String, enabled: Boolean) = _uiState.update { state ->
        if (key !in AppPreferences.YOUTUBE_FEED_KEYS || key !in state.youtubeSupportedFeedKeys) state else {
            val next = if (enabled) (state.youtubeFeedKeys + key).distinct() else state.youtubeFeedKeys.filterNot { it == key }
            state.copy(youtubeFeedKeys = next.ifEmpty { listOf("home") })
        }
    }
    fun onYoutubeMaxVideoHeightChanged(value: Int) = _uiState.update { it.copy(youtubeMaxVideoHeight = value.takeIf { v -> v in setOf(0, 720, 1080, 1440, 2160) } ?: 0) }

    fun onScreensaverEnabledChanged(value: Boolean) = _uiState.update { it.copy(screensaverEnabled = value) }
    fun onScreensaverTimeoutChanged(value: Int) = _uiState.update { it.copy(screensaverTimeoutMinutes = value.coerceIn(1, 120)) }
    fun onScreensaverModeChanged(value: String) = _uiState.update { it.copy(screensaverModeKey = value) }
    fun onScreensaverShowClockChanged(value: Boolean) = _uiState.update { it.copy(screensaverShowClock = value) }
    fun onScreensaverShowDateChanged(value: Boolean) = _uiState.update { it.copy(screensaverShowDate = value) }
    fun onScreensaver24HourClockChanged(value: Boolean) = _uiState.update { it.copy(screensaverUse24HourClock = value) }
    fun onScreensaverUseArtworkChanged(value: Boolean) = _uiState.update { it.copy(screensaverUseArtwork = value) }
    fun onScreensaverArtworkIntervalChanged(value: Int) = _uiState.update { it.copy(screensaverArtworkIntervalSeconds = value.coerceIn(10, 300)) }
    fun onScreensaverQualityChanged(value: String) = _uiState.update { it.copy(screensaverQualityKey = value) }
    fun onScreensaverDimPercentChanged(value: Int) = _uiState.update { it.copy(screensaverDimPercent = value.coerceIn(20, 100)) }

    fun onTvheadendEnabledChanged(value: Boolean) = _uiState.update { it.copy(tvheadendEnabled = value) }
    fun onTvheadendHostChanged(value: String) = _uiState.update { it.copy(tvheadendHost = value) }
    fun onTvheadendPortChanged(value: String) = _uiState.update { it.copy(tvheadendPort = value.filter(Char::isDigit)) }
    fun onTvheadendHttpsChanged(value: Boolean) = _uiState.update { it.copy(tvheadendUseHttps = value) }
    fun onTvheadendUsernameChanged(value: String) = _uiState.update { it.copy(tvheadendUsername = value) }
    fun onTvheadendPasswordChanged(value: String) = _uiState.update { it.copy(tvheadendPassword = value) }
    fun onTvheadendDeinterlaceModeChanged(value: String) = _uiState.update {
        it.copy(tvheadendDeinterlaceModeKey = value.takeIf { key -> key in setOf("off", "auto", "on") } ?: "off")
    }
    fun onTvheadendDeinterlaceFilterChanged(value: String) = _uiState.update {
        if (it.tvheadendPlaybackBackendKey == "mpv") it.copy(
            tvheadendMpvDeinterlaceFilterKey = com.ambient.tv.data.local.DeinterlaceCatalog.normalize("mpv", value),
        ) else it.copy(tvheadendDeinterlaceFilterKey = com.ambient.tv.data.local.DeinterlaceCatalog.normalize("vlc", value))
    }
    fun onTvheadendStreamProfileChanged(value: String) = _uiState.update { it.copy(tvheadendStreamProfile = value) }
    fun onTvheadendMacAddressChanged(value: String) = _uiState.update { it.copy(tvheadendMacAddress = formatMacAddressInput(value)) }
    fun onTvheadendAutoWakeChanged(value: Boolean) = _uiState.update { it.copy(tvheadendAutoWakeEnabled = value) }
    fun onTvheadendShowOnHomeChanged(value: Boolean) = _uiState.update { state ->
        val rows = state.homeRows
        val hasLiveRow = rows.any { it.system == HomeRowDefinition.SYSTEM_TVHEADEND && it.contentKey == "live" }
        state.copy(
            tvheadendShowOnHome = value,
            homeRows = if (value && !hasLiveRow) rows + HomeRowDefinition(
                system = HomeRowDefinition.SYSTEM_TVHEADEND, contentKey = "live",
            ) else rows.map { row ->
                if (row.system == HomeRowDefinition.SYSTEM_TVHEADEND && row.contentKey == "live") row.copy(enabled = value) else row
            },
        )
    }

    private fun persistTvheadend(state: SettingsUiState = _uiState.value) {
        if (state.tvheadendEnabled && state.tvheadendHost.isNotBlank()) appPreferences.initializeTimeshiftStorage(context)
        // Disabled: stored channels, guide and recordings of this server are deleted, not kept offline.
        if (appPreferences.tvheadendEnabled && !state.tvheadendEnabled) tvheadendRepository.clearPersistentCache()
        appPreferences.tvheadendEnabled = state.tvheadendEnabled
        appPreferences.tvheadendHost = state.tvheadendHost.trim()
        appPreferences.tvheadendPort = state.tvheadendPort.toIntOrNull() ?: 9981
        appPreferences.tvheadendUseHttps = state.tvheadendUseHttps
        appPreferences.tvheadendUsername = state.tvheadendUsername.trim()
        if (state.tvheadendPassword != tokenStore.tvheadendPassword.orEmpty()) appPreferences.tvheadendLoginVerified = false
        tokenStore.tvheadendPassword = state.tvheadendPassword
        appPreferences.tvheadendStreamProfile = state.tvheadendStreamProfile.trim()
        appPreferences.tvheadendDeinterlaceModeKey = state.tvheadendDeinterlaceModeKey
        appPreferences.tvheadendDeinterlaceFilterKey = state.tvheadendDeinterlaceFilterKey
        appPreferences.tvheadendMpvDeinterlaceFilterKey = state.tvheadendMpvDeinterlaceFilterKey
        appPreferences.tvheadendMacAddress = normalizeMacAddress(state.tvheadendMacAddress).orEmpty()
        appPreferences.tvheadendAutoWakeEnabled = state.tvheadendAutoWakeEnabled
        appPreferences.tvheadendShowOnHome = state.tvheadendShowOnHome
        appPreferences.tvheadendSubmenuKeys = state.tvheadendSubmenuKeys
        appPreferences.tvheadendPlaybackBackendKey = state.tvheadendPlaybackBackendKey
    }

    fun resolveTvheadendMacAddress() { lookupMac("tvheadend", verify = false) }

    private fun tvheadendTestMessage(error: Throwable): Pair<Int, List<String>> {
        val host = appPreferences.tvheadendHost.trim()
        val port = appPreferences.tvheadendPort.toString()
        // Android 17+: without the local network permission every LAN connection fails.
        if (!com.ambient.tv.util.LocalNetworkAccess.isGranted(context)) return R.string.tvh_test_permission to emptyList()
        return when (com.ambient.tv.tvheadend.data.classifyTvheadendTestFailure(error)) {
            com.ambient.tv.tvheadend.data.TvheadendTestFailure.AUTH ->
                R.string.tvh_test_auth to listOf(com.ambient.tv.tvheadend.data.tvheadendFailureHttpCode(error)?.toString().orEmpty())
            com.ambient.tv.tvheadend.data.TvheadendTestFailure.HTTP ->
                R.string.tvh_test_http to listOf(com.ambient.tv.tvheadend.data.tvheadendFailureHttpCode(error)?.toString().orEmpty())
            com.ambient.tv.tvheadend.data.TvheadendTestFailure.HOST -> R.string.tvh_test_host to listOf(host)
            com.ambient.tv.tvheadend.data.TvheadendTestFailure.TLS -> R.string.tvh_test_tls to emptyList()
            com.ambient.tv.tvheadend.data.TvheadendTestFailure.UNREACHABLE -> R.string.tvh_test_unreachable to listOf(host, port)
            com.ambient.tv.tvheadend.data.TvheadendTestFailure.NOT_LOCAL -> {
                val rejected = generateSequence(error) { it.cause }.filterIsInstance<com.ambient.tv.tvheadend.data.TvheadendNotLocalException>().firstOrNull()
                R.string.tvh_test_not_local to listOf(rejected?.host?.ifBlank { null } ?: host, rejected?.addresses?.joinToString().orEmpty())
            }
            com.ambient.tv.tvheadend.data.TvheadendTestFailure.VPN -> R.string.tvh_test_vpn to listOf(sanitizeFailureDetail(error.message.orEmpty()))
            com.ambient.tv.tvheadend.data.TvheadendTestFailure.WAKE -> R.string.tvh_wake_timeout to emptyList()
            com.ambient.tv.tvheadend.data.TvheadendTestFailure.OTHER ->
                R.string.tvh_test_other to listOf(sanitizeFailureDetail(error.message ?: error.javaClass.simpleName))
        }
    }

    fun testTvheadendConnection() {
        if (_uiState.value.isTestingTvheadend) return
        val page = SettingsCategory.TVHEADEND
        val ticket = beginFeedback("test:tvheadend")
        val persisted = runCatching { persistTvheadend(); refreshSavedValues() }
        if (persisted.isFailure) {
            finishFeedback(ticket, page, R.string.notice_failed, false, "tvheadend", error = persisted.exceptionOrNull(), delivery = FeedbackDelivery.NOTIFICATION)
            return
        }
        _uiState.update { it.copy(isTestingTvheadend = true) }
        viewModelScope.launch {
            try {
                val result = suspendResult {
                    tvheadendRepository.invalidateAll()
                    tvheadendRepository.testConnection(explicit = true).getOrThrow()
                }
                
                val error = result.exceptionOrNull()
                if (error == null) {
                    finishFeedback(ticket, page, R.string.notice_test_ok, true, "tvheadend", delivery = FeedbackDelivery.NOTIFICATION)
                } else {
                    // Name the actual cause instead of a generic "failed": login, port, HTTPS, LAN-only…
                    timber.log.Timber.w(error, "TVHeadend connection test failed")
                    val (message, args) = tvheadendTestMessage(error)
                    finishFeedback(ticket, page, message, false, "tvheadend", error = error,
                        delivery = FeedbackDelivery.NOTIFICATION, args = args)
                }
            } finally { _uiState.update { it.copy(isTestingTvheadend = false) } }
        }
    }

    /** The first discovered server took (or gave up on) its one-shot focus. */
    fun consumeDiscoveredFocus(system: String) {
        _uiState.update { if (it.discoveredFocusSystem == system) it.copy(discoveredFocusSystem = null) else it }
    }

    fun discoverServers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscoveringServers = true, statusMessage = null) }
            val found = suspendResult { lanDiscovery.discover() }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    isDiscoveringServers = false,
                    discoveredServers = found,
                    discoveredFocusSystem = if (found.isEmpty()) it.discoveredFocusSystem else "plex",
                    statusMessage = if (found.isEmpty()) {
                        context.getString(R.string.settings_discovery_none)
                    } else {
                        context.getString(R.string.settings_discovery_found, found.size)
                    },
                )
            }
        }
    }

    fun selectDiscoveredServer(server: DiscoveredPlexServer) {
        val saved = appPreferences.serverProfiles.firstOrNull {
            (!server.machineIdentifier.isNullOrBlank() && it.machineIdentifier == server.machineIdentifier) ||
                (it.host == server.host && it.port == server.port)
        }
        val current = _uiState.value
        // A saved server token wins. Otherwise keep an account token already in the form,
        // including one obtained by startAccountLinking() just before discovery.
        val token = saved?.let { profile -> tokenStore.tokenForServer(profile.id) }?.trim()?.takeIf { it.isNotEmpty() }
            ?: current.token.trim().takeIf { it.isNotEmpty() }
        val updated = current.copy(
            host = server.host,
            port = server.port.toString(),
            plexUseHttps = saved?.useHttps ?: current.plexUseHttps,
            macAddress = saved?.macAddress.orEmpty(),
            autoWakeEnabled = saved?.autoWakeEnabled ?: true,
            selectedMachineIdentifier = server.machineIdentifier,
            token = token.orEmpty(),
            plexSignedIn = !token.isNullOrBlank(),
            statusMessage = context.getString(R.string.settings_discovery_selected, server.name),
        )
        _uiState.value = updated
        if (plexDiscoveredServerAction(token) == DiscoveredServerAction.Connect) {
            // Token is already present, so picking the server sticks without an extra Save,
            // the same way selectSavedServer() does.
            cancelAccountLinking()
            persistPlex(updated)
            refreshSavedValues()
        } else {
            // Address only. Account linking persists the server after a token exists.
            startAccountLinking()
        }
    }

    fun selectSavedServer(profile: ServerProfile) {
        appPreferences.activateServer(profile.id)
        mediaRepository.invalidateCaches()
        _uiState.update {
            it.copy(
                host = profile.host,
                port = profile.port.toString(),
                plexUseHttps = profile.useHttps,
                macAddress = profile.macAddress.orEmpty(),
                autoWakeEnabled = profile.autoWakeEnabled,
                selectedMachineIdentifier = profile.machineIdentifier,
                token = tokenStore.tokenForServer(profile.id).orEmpty(),
                plexSignedIn = !tokenStore.tokenForServer(profile.id).isNullOrBlank(),
                activeServerId = profile.id,
                statusMessage = null,
            )
        }
        finishFeedback(beginFeedback("server:plex"), SettingsCategory.PLEX, R.string.notice_server_selected, true, "plex", profile.name)
        refreshSavedValues()
    }

    fun removeSavedServer(profile: ServerProfile) = settingsAction("server:remove:plex:${profile.id}", SettingsCategory.PLEX, R.string.feedback_server_removed, "plex", profile.name) { removeSavedServerImpl(profile) }

    private fun removeSavedServerImpl(profile: ServerProfile) {
        val wasActive = appPreferences.activeServerId == profile.id
        appPreferences.removeServerProfile(profile.id) ?: return
        tokenStore.removeTokenForServer(profile.id)
        mediaRepository.invalidateCaches()
        val active = appPreferences.activeServerProfile()
        _uiState.update { current ->
            current.copy(
                host = if (wasActive) active?.host.orEmpty() else current.host,
                port = if (wasActive) (active?.port ?: 32400).toString() else current.port,
                plexUseHttps = if (wasActive) active?.useHttps ?: false else current.plexUseHttps,
                macAddress = if (wasActive) active?.macAddress.orEmpty() else current.macAddress,
                autoWakeEnabled = if (wasActive) active?.autoWakeEnabled ?: true else current.autoWakeEnabled,
                selectedMachineIdentifier = if (wasActive) active?.machineIdentifier else current.selectedMachineIdentifier,
                token = if (wasActive) active?.let { tokenStore.tokenForServer(it.id) }.orEmpty() else current.token,
                plexSignedIn = if (wasActive) active?.let { !tokenStore.tokenForServer(it.id).isNullOrBlank() } == true else current.plexSignedIn,
                savedServers = appPreferences.serverProfiles,
                activeServerId = appPreferences.activeServerId,
                statusMessage = context.getString(R.string.settings_server_removed, profile.name),
            )
        }
        refreshSavedValues()
    }


    /**
     * Saved address or login data that the server has not confirmed yet is checked right away,
     * so the system appears in menus as soon as the login works (and stays hidden if it does
     * not). Runs beyond this screen: the user usually leaves Settings right after saving.
     */
    private fun verifyPendingLogins() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        if (appPreferences.hasTvheadendEndpoint && !appPreferences.tvheadendLoginVerified) scope.launch {
            tvheadendRepository.invalidateAll()
            tvheadendRepository.testConnection()
        }
        if (appPreferences.hasPlexEndpoint && !appPreferences.plexLoginVerified) scope.launch {
            connectionManager.testCurrentServer()
        }
    }

    private fun saveDraft(state: SettingsUiState, deferred: Boolean): Boolean? {
        val assetQualityChanged = state.highQualityAssetsEnabled != appPreferences.highQualityAssetsEnabled
        val uiLanguageChanged = state.uiLanguageKey != appPreferences.uiLanguageKey
        val metadataLanguageChanged = state.metadataLanguageKey != appPreferences.metadataLanguageKey
        val normalizedPlexMac = normalizeMacAddress(state.macAddress)
        if (state.macAddress.isNotBlank() && normalizedPlexMac == null) {
            validation(R.string.settings_status_mac_invalid)
            return null
        }
        val normalizedTvhMac = normalizeMacAddress(state.tvheadendMacAddress)
        if (state.tvheadendMacAddress.isNotBlank() && normalizedTvhMac == null) {
            validation(R.string.settings_status_mac_invalid)
            return null
        }
        val normalizedJellyfinMac = normalizeMacAddress(state.jellyfinMacAddress)
        if (state.jellyfinMacAddress.isNotBlank() && normalizedJellyfinMac == null) {
            validation(R.string.settings_status_mac_invalid)
            return null
        }
        val normalizedEmbyMac = normalizeMacAddress(state.embyMacAddress)
        if (state.embyMacAddress.isNotBlank() && normalizedEmbyMac == null) {
            validation(R.string.settings_status_mac_invalid)
            return null
        }
        // Only drafts of sources that still exist count; a removed source must not block saving.
        val liveWakeKeys = fileStore.sources().map { "files:${it.id}" }.toSet() + networkDlnaStore.servers().map { "dlna:${it.id}" }
        if (state.pendingSourceWakeMacs.any { (key, mac) -> key in liveWakeKeys && mac.isNotBlank() && canonicalWakeMac(mac) == null }) {
            validation(R.string.settings_status_mac_invalid)
            return null
        }
        val baseline = savedState.value
        if (state.mpvImageOptions != baseline.mpvImageOptions) appPreferences.mpvImageOptions = state.mpvImageOptions
        if (state.vlcImageOptions != baseline.vlcImageOptions) appPreferences.vlcImageOptions = state.vlcImageOptions
        if (state.media3ImageOptions != baseline.media3ImageOptions) appPreferences.media3ImageOptions = state.media3ImageOptions
        if (state.audioSettings != baseline.audioSettings) appPreferences.audioSettings = state.audioSettings
        appPreferences.plexEnabled = state.plexEnabled
        persistJellyfin(state.copy(jellyfinMacAddress = normalizedJellyfinMac.orEmpty()))
        persistEmby(state.copy(embyMacAddress = normalizedEmbyMac.orEmpty()))
        appPreferences.filesEnabled = state.filesEnabled
        if (state.systemNames != baseline.systemNames) appPreferences.systemNames = state.systemNames
        appPreferences.youtubeEnabled = state.youtubeEnabled
        appPreferences.spotifyEnabled = state.spotifyEnabled
        if (state.spotifyClientId != appPreferences.spotifyClientId) {
            spotifyRepository.logoutWebApi()
        }
        appPreferences.spotifyClientId = state.spotifyClientId
        appPreferences.spotifyDeviceName = state.spotifyDeviceName
        appPreferences.spotifyBitrateKbps = state.spotifyBitrateKbps
        appPreferences.spotifyVisualizerModeKey = state.spotifyVisualizerModeKey
        appPreferences.spotifySubmenuKeys = state.spotifySubmenuKeys
        appPreferences.plexHiddenSectionKeys = state.plexHiddenSectionKeys
        appPreferences.jellyfinHiddenSectionKeys = state.jellyfinHiddenSectionKeys
        appPreferences.embyHiddenSectionKeys = state.embyHiddenSectionKeys
        appPreferences.sidebarSettingsShortcutHidden = state.sidebarSettingsShortcutHidden
        spotifyRepository.reconcileReceiverForPlatform()
        appPreferences.chooseServerOnStartup = state.chooseServerOnStartup
        appPreferences.localNetworkOnly = state.localNetworkOnly
        appPreferences.plexThemeMusicEnabled = state.plexThemeMusicEnabled
        appPreferences.jellyfinThemeMusicEnabled = state.jellyfinThemeMusicEnabled
        appPreferences.embyThemeMusicEnabled = state.embyThemeMusicEnabled
        appPreferences.themeMusicVolume = state.themeMusicVolumePercent.coerceIn(0, 100) / 100f
        appPreferences.autoSelectDisplayVersion = state.autoSelectDisplayVersion
        appPreferences.displayChangeNotificationsEnabled = state.displayChangeNotificationsEnabled
        appPreferences.svpAvailable = state.svpAvailable
        appPreferences.displayRefreshAdjustmentKey = state.displayRefreshAdjustmentKey
        appPreferences.displayModeWhitelist = state.displayModeWhitelist
        appPreferences.preferredDisplayModeKey = state.preferredDisplayModeKey
        appPreferences.allowDoubleRefreshRate = state.allowDoubleRefreshRate
        appPreferences.allowThreeTwoPulldown = state.allowThreeTwoPulldown
        appPreferences.displaySwitchPauseMs = state.displaySwitchPauseMs
        appPreferences.mediaSelectionBehaviorKey = state.mediaSelectionBehaviorKey
        appPreferences.homeRows = state.homeRows
        appPreferences.systemHomeHiddenRows = state.systemHomeHiddenRows
        appPreferences.plexAutoRefreshIntervalSeconds = state.plexAutoRefreshIntervalSeconds
        appPreferences.libraryGridColumns = state.libraryGridColumns
        appPreferences.libraryListRows = state.libraryListRows
        appPreferences.highQualityAssetsEnabled = state.highQualityAssetsEnabled
        appPreferences.uiLanguageKey = state.uiLanguageKey
        appPreferences.metadataLanguageKey = state.metadataLanguageKey
        appPreferences.audioLanguageKey = state.audioLanguageKey
        appPreferences.subtitleModeKey = state.subtitleModeKey
        appPreferences.subtitleLanguageKey = state.subtitleLanguageKey
        appPreferences.subtitleFallbackLanguageKey = state.subtitleFallbackLanguageKey
        appPreferences.fullSubtitlesOnForeignAudio = state.fullSubtitlesOnForeignAudio
        appPreferences.forcedSubtitlesInUiLanguage = state.forcedSubtitlesInUiLanguage

        if (assetQualityChanged || metadataLanguageChanged) {
            // Metadata language changes must rebuild provider-backed items just like artwork URL
            // quality changes; otherwise stale localized titles/descriptions survive in caches.
            mediaRepository.invalidateCaches()
        }
        if (metadataLanguageChanged) {
            youtubeContentCache.clear()
            viewModelScope.launch(Dispatchers.IO) { suspendResult { smartTubeEngine.applyMetadataLanguage() } }
        }
        appPreferences.uiScale = state.uiScale
        appPreferences.accentColorKey = state.accentColorKey
        appPreferences.customAccentHex = state.customAccentHex
        appPreferences.customPlaybackAccentHex = state.customPlaybackAccentHex
        appPreferences.dualAccentEnabled = state.dualAccentEnabled
        // While typing, a deferred (incomplete) colour must not be overwritten with the stored one.
        if (!deferred) _uiState.update { it.copy(
            customAccentHex = appPreferences.customAccentHex,
            customPlaybackAccentHex = appPreferences.customPlaybackAccentHex,
        ) }
        appPreferences.themeModeKey = state.themeModeKey
        appPreferences.uiHdrModeKey = state.uiHdrModeKey
        appPreferences.sdrToHdrEnabled = state.sdrToHdrEnabled
        appPreferences.sdrToHdrPeakNits = state.sdrToHdrPeakNits
        appPreferences.performanceModeKey = state.performanceModeKey
        appPreferences.uiPerformanceModeKey = state.uiPerformanceModeKey
        appPreferences.playbackPerformanceModeKey = state.playbackPerformanceModeKey
        appPreferences.frameDropTestSeconds = state.frameDropTestSeconds
        appPreferences.homeBackgroundEverywhere = state.homeBackgroundEverywhere
        appPreferences.focusArtworkBackdrop = state.focusArtworkBackdrop
        appPreferences.homeHeroStyleKey = state.homeHeroStyleKey
        appPreferences.homeHeroIntensityPercent = state.homeHeroIntensityPercent
        appPreferences.homeHeroSpreadKey = state.homeHeroSpreadKey
        appPreferences.notificationDisplayDurationKey = state.notificationDisplayDurationKey
        appPreferences.navSoundsEnabled = state.navSoundsEnabled
        appPreferences.navSoundsVolume = state.navSoundsVolume
        appPreferences.navSoundBank = state.navSoundBank
        appPreferences.playbackBackendKey = state.playbackBackendKey
        appPreferences.tvheadendPlaybackBackendKey = state.tvheadendPlaybackBackendKey
        appPreferences.mpvVideoOutputModeKey = state.mpvVideoOutputModeKey
        appPreferences.mpvVideoDecoderKey = state.mpvVideoDecoderKey
        appPreferences.mpvHdrGpuEnabled = state.mpvHdrGpuEnabled
        appPreferences.vlcVideoOutputModeKey = state.vlcVideoOutputModeKey
        appPreferences.visibleBackgroundVideoPlayback = state.visibleBackgroundVideoPlayback
        appPreferences.backgroundVideoDimPercent = state.backgroundVideoDimPercent
        appPreferences.mediaAudioPassthroughEnabled = state.mediaAudioPassthroughEnabled
        appPreferences.audioVisualizerModeKey = state.audioVisualizerModeKey
        appPreferences.projectmPresetIntervalSeconds = state.projectmPresetIntervalSeconds
        appPreferences.projectmTransitionSeconds = state.projectmTransitionSeconds
        appPreferences.projectmQualityKey = state.projectmQualityKey
        appPreferences.projectmResolutionKey = state.projectmResolutionKey
        appPreferences.projectmHdr4kAllowed = state.projectmHdr4kAllowed
        appPreferences.projectmShowPresetName = state.projectmShowPresetName
        appPreferences.projectmPresetCategoryKey = state.projectmPresetCategoryKey
        appPreferences.bigSeekJumpSeconds = state.bigSeekJumpSeconds
        appPreferences.creditsSkipMode = state.creditsSkipMode
        appPreferences.introSkipMode = state.introSkipMode
        appPreferences.nextUpEnabled = state.nextUpEnabled
        appPreferences.nextUpLeadSeconds = state.nextUpLeadSeconds
        appPreferences.nextUpAutoplayEpisodes = state.nextUpAutoplayEpisodes
        appPreferences.nextUpAutoplayCollections = state.nextUpAutoplayCollections
        appPreferences.nextUpOverlayEnabled = state.nextUpOverlayEnabled
        appPreferences.nextUpStillWatchingEpisodes = state.nextUpStillWatchingEpisodes
        appPreferences.sleepAutoStartKey = state.sleepAutoStartKey
        appPreferences.sleepWarningMinutes = state.sleepWarningMinutes
        appPreferences.sleepDailyStopMinute = state.sleepDailyStopMinute
        appPreferences.wolRetryCount = state.wolRetryCount
        appPreferences.youtubeSponsorBlockEnabled = state.youtubeSponsorBlockEnabled
        appPreferences.youtubeAutoplayNextEnabled = state.youtubeAutoplayNextEnabled
        appPreferences.youtubeFeedKeys = state.youtubeFeedKeys
        appPreferences.youtubeMaxVideoHeight = state.youtubeMaxVideoHeight
        appPreferences.screensaverEnabled = state.screensaverEnabled
        appPreferences.screensaverTimeoutMinutes = state.screensaverTimeoutMinutes
        appPreferences.screensaverModeKey = state.screensaverModeKey
        appPreferences.screensaverShowClock = state.screensaverShowClock
        appPreferences.screensaverShowDate = state.screensaverShowDate
        appPreferences.screensaverUse24HourClock = state.screensaverUse24HourClock
        appPreferences.screensaverUseArtwork = state.screensaverUseArtwork
        appPreferences.screensaverArtworkIntervalSeconds = state.screensaverArtworkIntervalSeconds
        appPreferences.screensaverDimPercent = state.screensaverDimPercent
        appPreferences.screensaverQualityKey = state.screensaverQualityKey
        appPreferences.photoSlideshowIntervalSeconds = state.photoSlideshowIntervalSeconds
        appPreferences.photoTransitionKey = state.photoTransitionKey
        appPreferences.photoKenBurns = state.photoKenBurns
        appPreferences.photoShuffle = state.photoShuffle
        appPreferences.photoIncludeSubfolders = state.photoIncludeSubfolders
        appPreferences.photoSortKey = state.photoSortKey
        appPreferences.photoShowInfo = state.photoShowInfo
        appPreferences.screensaverPhotoAlbumKey = state.screensaverPhotoAlbumKey
        appPreferences.screensaverPhotoAlbumTitle = state.screensaverPhotoAlbumTitle
        persistTvheadend(state)
        persistPlex(state)
        var committed = state
        if (state.pendingSourceWakeMacs.isNotEmpty() || state.pendingSourceWakeAuto.isNotEmpty()) {
            applySourceWakeDrafts(state)
            committed = state.copy(pendingSourceWakeMacs = emptyMap(), pendingSourceWakeAuto = emptyMap())
            _uiState.update {
                if (it.pendingSourceWakeMacs == state.pendingSourceWakeMacs && it.pendingSourceWakeAuto == state.pendingSourceWakeAuto)
                    it.copy(pendingSourceWakeMacs = emptyMap(), pendingSourceWakeAuto = emptyMap()) else it
            }
        }
        // Deferred fields stay pending (and visible as such) until they are committed.
        savedState.value = committed
        return uiLanguageChanged
    }

    private fun persistPlex(state: SettingsUiState) {
        val host = state.host.trim()
        if (host.isBlank()) return
        val port = state.port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 32400
        val normalizedPlexMac = normalizeMacAddress(state.macAddress)
        val existing = appPreferences.serverProfiles.firstOrNull {
            (!state.selectedMachineIdentifier.isNullOrBlank() && it.machineIdentifier == state.selectedMachineIdentifier) ||
                (it.host.equals(host, ignoreCase = true) && it.port == port)
        }
        val name = state.discoveredServers.firstOrNull {
            it.host == host && it.port == port
        }?.name ?: existing?.name ?: host
        val profile = ServerProfile(
            id = existing?.id ?: state.selectedMachineIdentifier ?: UUID.randomUUID().toString(),
            name = name,
            host = host,
            port = port,
            useHttps = state.plexUseHttps,
            machineIdentifier = state.selectedMachineIdentifier ?: existing?.machineIdentifier,
            macAddress = normalizedPlexMac,
            autoWakeEnabled = state.autoWakeEnabled,
        )
        val previousHost = appPreferences.host
        val previousPort = appPreferences.port
        val previousHttps = appPreferences.plexUseHttps
        appPreferences.upsertServerProfile(profile, makeActive = true)
        appPreferences.plexUseHttps = state.plexUseHttps
        val newToken = state.token.trim().takeIf { it.isNotEmpty() }
        // A different token is an unconfirmed login until the server accepts it.
        if (newToken != tokenStore.token) appPreferences.plexLoginVerified = false
        tokenStore.token = newToken
        if (!previousHost.equals(host, ignoreCase = true) || previousPort != port || previousHttps != state.plexUseHttps) {
            mediaRepository.invalidateCaches()
        }
        _uiState.update {
            it.copy(
                plexConfigured = appPreferences.host.isNotBlank(),
                plexSignedIn = !tokenStore.token.isNullOrBlank(),
                savedServers = appPreferences.serverProfiles,
                activeServerId = appPreferences.activeServerId,
            )
        }
    }

    private fun persistJellyfin(state: SettingsUiState) {
        val previousEndpoint = listOf(appPreferences.jellyfinHost, appPreferences.jellyfinPort.toString(), appPreferences.jellyfinUseHttps.toString(), appPreferences.jellyfinBasePath)
        appPreferences.jellyfinEnabled = state.jellyfinEnabled
        appPreferences.jellyfinHost = state.jellyfinHost
        appPreferences.jellyfinPort = state.jellyfinPort.toIntOrNull() ?: AppPreferences.DEFAULT_JELLYFIN_PORT
        appPreferences.jellyfinUseHttps = state.jellyfinUseHttps
        appPreferences.jellyfinBasePath = state.jellyfinBasePath
        appPreferences.jellyfinMacAddress = normalizeMacAddress(state.jellyfinMacAddress)
        appPreferences.jellyfinAutoWakeEnabled = state.jellyfinAutoWakeEnabled
        appPreferences.jellyfinHiddenSectionKeys = state.jellyfinHiddenSectionKeys
        val currentEndpoint = listOf(appPreferences.jellyfinHost, appPreferences.jellyfinPort.toString(), appPreferences.jellyfinUseHttps.toString(), appPreferences.jellyfinBasePath)
        if (previousEndpoint != currentEndpoint) jellyfinRepository.invalidateCaches()
        val activeProfile = appPreferences.activeJellyfinServerProfile()
        if (
            activeProfile != null &&
            activeProfile.host.equals(appPreferences.jellyfinHost, ignoreCase = true) &&
            activeProfile.port == appPreferences.jellyfinPort &&
            activeProfile.useHttps == appPreferences.jellyfinUseHttps &&
            activeProfile.basePath == appPreferences.jellyfinBasePath
        ) {
            appPreferences.upsertJellyfinServerProfile(
                activeProfile.copy(
                    macAddress = appPreferences.jellyfinMacAddress,
                    autoWakeEnabled = appPreferences.jellyfinAutoWakeEnabled,
                ),
                makeActive = true,
            )
        }
    }


    private fun persistEmby(state: SettingsUiState) {
        val previousEndpoint = listOf(appPreferences.embyHost, appPreferences.embyPort.toString(), appPreferences.embyUseHttps.toString(), appPreferences.embyBasePath)
        appPreferences.embyEnabled = state.embyEnabled
        appPreferences.embyHost = state.embyHost
        appPreferences.embyPort = state.embyPort.toIntOrNull() ?: AppPreferences.DEFAULT_EMBY_PORT
        appPreferences.embyUseHttps = state.embyUseHttps
        appPreferences.embyBasePath = state.embyBasePath
        appPreferences.embyMacAddress = normalizeMacAddress(state.embyMacAddress)
        appPreferences.embyAutoWakeEnabled = state.embyAutoWakeEnabled
        appPreferences.embyHiddenSectionKeys = state.embyHiddenSectionKeys
        val currentEndpoint = listOf(appPreferences.embyHost, appPreferences.embyPort.toString(), appPreferences.embyUseHttps.toString(), appPreferences.embyBasePath)
        if (previousEndpoint != currentEndpoint) embyRepository.invalidateCaches()
        val activeProfile = appPreferences.activeEmbyServerProfile()
        if (
            activeProfile != null &&
            activeProfile.host.equals(appPreferences.embyHost, ignoreCase = true) &&
            activeProfile.port == appPreferences.embyPort &&
            activeProfile.useHttps == appPreferences.embyUseHttps &&
            activeProfile.basePath == appPreferences.embyBasePath
        ) {
            appPreferences.upsertEmbyServerProfile(
                activeProfile.copy(
                    macAddress = appPreferences.embyMacAddress,
                    autoWakeEnabled = appPreferences.embyAutoWakeEnabled,
                ),
                makeActive = true,
            )
        }
    }

    fun testConnection() {
        if (_uiState.value.macAddress.isNotBlank() && normalizeMacAddress(_uiState.value.macAddress) == null) {
            finishFeedback(beginFeedback("test:plex"), SettingsCategory.PLEX,
                R.string.settings_status_mac_invalid, false, "plex", delivery = FeedbackDelivery.NOTIFICATION)
            return
        }
        if (_uiState.value.isTestingConnection) return
        val page = SettingsCategory.PLEX
        val ticket = beginFeedback("test:plex")
        val persisted = runCatching { persistPlex(_uiState.value); refreshSavedValues() }
        if (persisted.isFailure) {
            finishFeedback(ticket, page, R.string.notice_failed, false, "plex", error = persisted.exceptionOrNull(), delivery = FeedbackDelivery.NOTIFICATION)
            return
        }
        _uiState.update { it.copy(isTestingConnection = true) }
        viewModelScope.launch {
            try {
                val result = suspendResult {
                    
                    check(connectionManager.reconnect()) { context.getString(R.string.server_connection_failed) }
                    connectionManager.testCurrentServer().getOrThrow().also { refreshBus.requestRefresh() }
                }
                val identity = result.getOrNull()
                if (identity?.machineIdentifier != null) {
                    appPreferences.activeServerProfile()?.let { current ->
                        if (current.machineIdentifier != identity.machineIdentifier)
                            appPreferences.upsertServerProfile(current.copy(machineIdentifier = identity.machineIdentifier), true)
                    }
                }
                _uiState.update { it.copy(savedServers = appPreferences.serverProfiles,
                    selectedMachineIdentifier = identity?.machineIdentifier ?: it.selectedMachineIdentifier) }
                finishFeedback(ticket, page, if (result.isSuccess) R.string.notice_test_ok else R.string.notice_failed,
                    result.isSuccess, "plex", error = result.exceptionOrNull(), delivery = FeedbackDelivery.NOTIFICATION)
            } finally { _uiState.update { it.copy(isTestingConnection = false) } }
        }
    }

    private fun macInput(state: SettingsUiState, system: String): Triple<String, Int, String> = when (system) {
        "jellyfin" -> Triple(state.jellyfinHost, state.jellyfinPort.toIntOrNull() ?: 8096, state.jellyfinMacAddress)
        "emby" -> Triple(state.embyHost, state.embyPort.toIntOrNull() ?: 8096, state.embyMacAddress)
        "tvheadend" -> Triple(state.tvheadendHost, state.tvheadendPort.toIntOrNull() ?: 9981, state.tvheadendMacAddress)
        else -> Triple(state.host, state.port.toIntOrNull() ?: 32400, state.macAddress)
    }

    private fun macBusy(system: String, busy: Boolean, mac: String? = null) {
        _uiState.update { when (system) {
            "jellyfin" -> it.copy(isResolvingJellyfinMac = busy, jellyfinMacAddress = mac ?: it.jellyfinMacAddress)
            "emby" -> it.copy(isResolvingEmbyMac = busy, embyMacAddress = mac ?: it.embyMacAddress)
            "tvheadend" -> it.copy(isResolvingTvheadendMac = busy, tvheadendMacAddress = mac ?: it.tvheadendMacAddress)
            else -> it.copy(isResolvingMac = busy, macAddress = mac ?: it.macAddress)
        } }
    }

    private fun lookupMac(system: String, verify: Boolean) {
        val state = _uiState.value
        if (when (system) {
            "jellyfin" -> state.isResolvingJellyfinMac
            "emby" -> state.isResolvingEmbyMac
            "tvheadend" -> state.isResolvingTvheadendMac
            else -> state.isResolvingMac
        }) return
        val page = SettingsCategory.WAKE
        val input = macInput(state, system)
        val host = NetworkEndpoint.extractHost(input.first)
        val entered = normalizeMacAddress(input.third)
        if (host.isBlank()) { validation(R.string.settings_status_mac_missing_host, page); return }
        if (verify && entered == null) { validation(R.string.settings_status_mac_invalid, page); return }
        val ticket = beginFeedback("mac:$system")
        macBusy(system, true)
        viewModelScope.launch {
            try {
                val result = suspendResult { macAddressResolver.resolveMacAddress(host, input.second) }
                // A response for an edited endpoint must not overwrite the new form.
                if (macInput(_uiState.value, system) != input) { feedback.cancel(ticket.key); return@launch }
                val mac = result.getOrNull()
                val success = mac != null && (!verify || mac.equals(entered, ignoreCase = true))
                val message = when {
                    mac == null -> if (verify) R.string.settings_status_mac_not_verifiable else R.string.settings_status_mac_not_found
                    !verify -> R.string.settings_status_mac_found
                    success -> R.string.settings_status_mac_matches
                    else -> R.string.settings_status_mac_mismatch
                }
                val args = if (mac == null) emptyList() else if (!verify) listOf(mac)
                    else if (success) listOf(entered!!) else listOf(entered!!, mac)
                finishFeedback(ticket, page, message, success, system, error = result.exceptionOrNull(),
                    delivery = FeedbackDelivery.CONTEXTUAL, args = args)
                if (!verify && mac != null) macBusy(system, true, mac)
            } finally { macBusy(system, false) }
        }
    }

    fun resolveMacAddress() { lookupMac("plex", verify = false) }

    fun verifyMacAddress() { lookupMac("plex", verify = true) }
    fun verifyJellyfinMac() { lookupMac("jellyfin", verify = true) }
    fun verifyEmbyMac() { lookupMac("emby", verify = true) }

    fun verifyTvheadendMacAddress() { lookupMac("tvheadend", verify = true) }

    fun startAccountLinking() {
        linkingJob?.cancel()
        notifyLogin("plex", NoticePhase.WORKING)
        linkingJob = viewModelScope.launch {
            _uiState.update { it.copy(isLinkingAccount = true, linkCode = null, linkQrUrl = null, statusMessage = null) }
            val pinResult = plexAuthRepository.createPin()
            val pin = pinResult.getOrNull()
            if (pin == null) {
                _uiState.update { it.copy(isLinkingAccount = false, statusMessage = context.getString(R.string.settings_link_failed)) }
                notifyLogin("plex", NoticePhase.ERROR, reason = pinResult.exceptionOrNull()?.let { failureReasonText(context, it, "plex.tv") })
                return@launch
            }
            _uiState.update {
                it.copy(
                    linkCode = PlexAuthLink.typedCode(pin.code, pin.shortCode),
                    linkQrUrl = PlexAuthLink.qrPayload(appPreferences.clientIdentifier, pin.code),
                )
            }
            val deadline = System.currentTimeMillis() + LINK_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(LINK_POLL_INTERVAL_MS)
                val token = plexAuthRepository.pollToken(pin.id).getOrNull()
                if (!token.isNullOrBlank()) {
                    // The PIN endpoint returns the Plex account token; a shared server needs its
                    // own token from plex.tv resources. Put it into the visible field immediately
                    // and persist it when we can unambiguously identify the edited server. This
                    // avoids a successful login appearing to do nothing and also survives leaving
                    // Settings before pressing Save.
                    val state = _uiState.value
                    val port = state.port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 32400
                    val link = plexAuthRepository.serverLink(
                        accountToken = token.trim(),
                        machineIdentifier = state.selectedMachineIdentifier,
                        host = state.host.trim(),
                        port = port,
                    )
                    val normalizedToken = link.token
                    val matchingProfile = appPreferences.serverProfiles.firstOrNull { profile ->
                        (!state.selectedMachineIdentifier.isNullOrBlank() && profile.machineIdentifier == state.selectedMachineIdentifier) ||
                            (profile.host.equals(state.host.trim(), ignoreCase = true) && profile.port == port)
                    }
                    matchingProfile?.let { profile -> tokenStore.setTokenForServer(profile.id, normalizedToken) }
                    _uiState.update {
                        it.copy(
                            isLinkingAccount = false,
                            linkCode = null,
                            linkQrUrl = null,
                            token = normalizedToken,
                            plexSignedIn = true,
                            statusMessage = null,
                            // Offer the server's certificate-backed address instead of plain HTTP.
                            plexDirectSuggestion = if (!it.plexUseHttps && link.secureHost != null && link.securePort != null)
                                "${link.secureHost}:${link.securePort}" else null,
                            // Pins the saved profile to this server so a later address change
                            // (e.g. to plex.direct) updates it instead of adding a duplicate.
                            selectedMachineIdentifier = it.selectedMachineIdentifier ?: link.machineIdentifier,
                        )
                    }
                    notifyLogin("plex", NoticePhase.SUCCESS, _uiState.value.host)
                    persistPlex(_uiState.value)
                    refreshSavedValues()
                    return@launch
                }
            }
            notifyLogin("plex", NoticePhase.ERROR, message = R.string.notice_login_expired)
            _uiState.update {
                it.copy(isLinkingAccount = false, linkCode = null, linkQrUrl = null, statusMessage = context.getString(R.string.settings_link_timeout))
            }
        }
    }

    /**
     * A credentials export is always passphrase-protected; a normal export only when a passphrase
     * was entered (otherwise plain JSON). [passphrase] is wiped afterwards.
     */
    fun exportSettings(uri: Uri, includeCredentials: Boolean = false, passphrase: CharArray? = null) {
        if (_uiState.value.isExportingSettings || _uiState.value.isImportingSettings) return
        val ticket = beginFeedback("settings:export")
        _uiState.update { it.copy(isExportingSettings = true) }
        viewModelScope.launch {
            val result = suspendResult {
                withContext(Dispatchers.IO) {
                    val plain = settingsBackupManager.exportJson(includeCredentials = includeCredentials)
                    val payload = if (includeCredentials || (passphrase != null && passphrase.isNotEmpty())) {
                        com.ambient.tv.data.local.BackupEncryption.encrypt(plain, requireNotNull(passphrase))
                    } else plain
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                        writer.write(payload)
                    } ?: throw java.io.IOException()
                }
            }
            passphrase?.fill('\u0000')
            _uiState.update { it.copy(isExportingSettings = false) }
            finishFeedback(ticket, SettingsCategory.BACKUP,
                if (result.isSuccess) R.string.settings_export_success else R.string.settings_export_failed,
                result.isSuccess, error = result.exceptionOrNull())
        }
    }

    fun cancelEncryptedImport() {
        _uiState.update { it.copy(pendingEncryptedImport = null, backupPassphraseWrong = false) }
    }

    fun importSettings(uri: Uri, passphrase: CharArray? = null) {
        if (_uiState.value.isImportingSettings || _uiState.value.isExportingSettings) return
        val ticket = beginFeedback("settings:import")
        val previousUiLanguage = appPreferences.uiLanguageKey
        _uiState.update { it.copy(isImportingSettings = true) }
        viewModelScope.launch {
            var imported = false
            var needsPassphrase = false
            var wrongPassphrase = false
            val result = suspendResult {
                // Read with a cap: picking a video by mistake must fail cleanly, not run out of memory.
                // The envelope check parses the whole file, so it stays off the main thread too.
                val (stored, encrypted) = withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val content = StringBuilder()
                        val chunk = CharArray(64 * 1024)
                        while (true) {
                            val read = reader.read(chunk)
                            if (read < 0) break
                            content.append(chunk, 0, read)
                            if (content.length > MAX_BACKUP_IMPORT_CHARS) throw java.io.IOException("Backup file too large")
                        }
                        content.toString()
                    } ?: throw java.io.IOException()
                    text to com.ambient.tv.data.local.BackupEncryption.isEncrypted(text)
                }
                val raw = if (encrypted) {
                    if (passphrase == null) {
                        needsPassphrase = true
                        return@suspendResult
                    }
                    try {
                        withContext(Dispatchers.Default) { com.ambient.tv.data.local.BackupEncryption.decrypt(stored, passphrase) }
                    } catch (failure: com.ambient.tv.data.local.BackupEncryption.WrongPassphraseException) {
                        wrongPassphrase = true
                        throw failure
                    } finally {
                        passphrase.fill('\u0000')
                    }
                } else stored
                withContext(Dispatchers.IO) { settingsBackupManager.importJson(raw) }
                imported = true
                com.ambient.tv.ui.player.BackgroundPlaybackSession.stop()
                mediaRepository.invalidateCaches()
                jellyfinRepository.invalidateCaches()
                embyRepository.invalidateCaches()
                tvheadendRepository.invalidateAll()
                youtubeContentCache.clear()
                spotifyRepository.reloadAuthorization()
                withContext(Dispatchers.IO) {
                    spotifyRepository.reconcileReceiverForPlatform()
                    if (appPreferences.youtubeEnabled) smartTubeEngine.applyMetadataLanguage()
                }
            }
            _uiState.value = if (imported) loadInitialState().copy(
                uiLanguageReloadRequired = previousUiLanguage != appPreferences.uiLanguageKey,
            ) else _uiState.value.copy(isImportingSettings = false)
            if (needsPassphrase || wrongPassphrase) {
                // Ask (again) for the passphrase; nothing was imported yet.
                feedback.cancel(ticket.key)
                _uiState.update { it.copy(pendingEncryptedImport = uri, backupPassphraseWrong = wrongPassphrase) }
                return@launch
            }
            _uiState.update { it.copy(pendingEncryptedImport = null, backupPassphraseWrong = false) }
            if (imported && result.isFailure) {
                val warning = feedback.complete(ticket, OperationFeedback(ticket.key, feedbackScope(SettingsCategory.BACKUP),
                    R.string.feedback_import_refresh_warning, FeedbackPhase.WARNING))
                _uiState.update { it.copy(operationFeedback = warning) }
            } else {
                finishFeedback(ticket, SettingsCategory.BACKUP,
                    if (imported) R.string.settings_import_success else R.string.settings_import_failed,
                    imported, error = result.exceptionOrNull())
            }
            if (imported) {
                acceptSavedDraft()
                youtubeSettingsLoaded = false
                if (openedPage == SettingsCategory.YOUTUBE || openedPage == SettingsCategory.HOME) loadYouTubeSettings()
            }
        }
    }

    fun acknowledgeUiLanguageReload() {
        _uiState.update { it.copy(uiLanguageReloadRequired = false) }
    }

    fun cancelAccountLinking() {
        AppNotifications.dismiss("login:plex")
        linkingJob?.cancel()
        linkingJob = null
        _uiState.update { it.copy(isLinkingAccount = false, linkCode = null, linkQrUrl = null) }
    }

    fun signOutPlex() {
        cancelAccountLinking()
        // A Plex account token commonly authorizes every saved server. Clear every stored copy
        // so switching servers cannot silently restore the account after an explicit sign-out.
        tokenStore.retainOnlyServerTokens(emptySet())
        mediaRepository.invalidateCaches()
        _uiState.update {
            it.copy(
                plexSignedIn = false,
                token = "",
                plexLibrarySections = emptyList(),
                statusMessage = null,
            )
        }
        notifyLogin("plex", NoticePhase.INFO)
        refreshSavedValues()
    }

    private fun normalizeMacAddress(value: String): String? {
        val hex = value.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            .uppercase()
        if (hex.length != 12) return null
        val bytes = runCatching { hex.chunked(2).map { it.toInt(16) } }.getOrNull() ?: return null
        if (bytes.all { it == 0 } || bytes.all { it == 0xFF }) return null
        // Wake-on-LAN targets a single NIC; multicast MAC addresses are not valid targets.
        if ((bytes.first() and 0x01) != 0) return null
        return bytes.joinToString(":") { "%02X".format(it) }
    }

    override fun onCleared() {
        youtubeSignInWatchJob?.cancel()
        linkingJob?.cancel()
        listOf("plex", "jellyfin", "emby", "youtube").forEach { AppNotifications.store.cancelWorking("login:$it") }
        super.onCleared()
    }

}

internal const val AUTOSAVE_DEBOUNCE_MS = 400L

/** Settings backups are a few hundred KB at most; anything far larger is not a backup. */
private const val MAX_BACKUP_IMPORT_CHARS = 8 * 1024 * 1024
