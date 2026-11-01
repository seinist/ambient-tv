package com.ambient.tv.ui.player

import com.ambient.tv.util.suspendResult
import com.ambient.tv.notifications.notifyResult
import com.ambient.tv.notifications.NoticeKind

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.BuildConfig
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.jellyfin.JellyfinBackend
import com.ambient.tv.data.emby.EmbyBackend
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaTrack
import com.ambient.tv.domain.model.MediaTrackType
import com.ambient.tv.domain.playback.PlaybackMode
import com.ambient.tv.domain.playback.PlaybackPlanner
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.ui.player.backend.MpvBackendAvailability
import com.ambient.tv.connectivity.PlaybackServerRecovery
import com.ambient.tv.connectivity.PlaybackServerUnreachable
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Ready(
        val streamUrl: String,
        val playbackMode: PlaybackMode,
        val playbackReason: String,
        val title: String,
        val artworkUrl: String?,
        val posterArtUrl: String? = null,
        val panelArtworkUrl: String?,
        val panelArtworkFallbackUrls: List<String>,
        val mediaType: String,
        val parentTitle: String?,
        val grandparentTitle: String?,
        val seasonIndex: Int?,
        val episodeIndex: Int?,
        val year: Int?,
        /** IMDb id from the server's GUIDs, for an online subtitle search. */
        val imdbId: String? = null,
        val summary: String?,
        val tagline: String?,
        val startPositionMs: Long,
        val durationMs: Long?,
        val videoCodec: String?,
        val videoDynamicRange: String?,
        val videoBitDepth: Int?,
        val videoProfile: String?,
        val audioCodec: String?,
        val container: String?,
        val sourceBitrateKbps: Int?,
        val audioTracks: List<MediaTrack>,
        val subtitleTracks: List<MediaTrack>,
        val hardwareVideoDecoder: Boolean,
        val hardwareAudioDecoder: Boolean,
        val timelineOffsetMs: Long,
        val nextEpisode: MediaItem?,
        val nextCollectionItem: MediaItem?,
        val nextAudioTrack: MediaItem?,
        val preferredAudioLanguage: String?,
        val preferredSubtitleLanguage: String?,
        val subtitlesEnabled: Boolean,
        val forcedSubtitlesEnabled: Boolean,
        val sourceRatingKey: String,
        val sourceMediaIndex: Int,
        val selectedVersionIndex: Int,
        val autoVersionSelection: Boolean,
        val companionVersions: List<Pair<String, String>> = emptyList(),
        val streamHeaders: Map<String, String>,
        val playbackBackendKey: String,
        val seekJumpMs: Long,
        val audioVisualizerModeKey: String,
        val audioPassthroughEnabled: Boolean,
        val projectmPresetIntervalSeconds: Int,
        val projectmTransitionSeconds: Int,
        val projectmQualityKey: String,
        val projectmPresetCategoryKey: String,
        val sourceAspectRatioHint: Float? = null,
        val sourceWidth: Int? = null,
        val sourceHeight: Int? = null,
        val runtimeHdrStandard: HdrStandard? = null,
        val runtimeHdrKnown: Boolean = false,
        val timelineSeekToken: Long = 0,
        val timelineSeekMs: Long? = null,
        val requestedRatingKey: String = sourceRatingKey,
        val serverAudioStreamId: Long? = null,
        val serverAudioConversionCodec: String? = null,
        val playbackRoute: String? = null,
        val successorsLoading: Boolean = false,
        /**
         * Gapless music: the stream and start the running Media3 engine was created with. Set
         * after the engine moved on to this item by itself, so the screen keeps that engine.
         */
        val engineStreamUrl: String? = null,
        val engineStartPositionMs: Long = 0,
    ) : PlayerUiState {
        val nextVideoItem: MediaItem? get() = nextEpisode ?: nextCollectionItem
        /** What the engine was built from: the item's own stream, or the first of a gapless chain. */
        val playerStreamUrl: String get() = engineStreamUrl ?: streamUrl
        val playerStartPositionMs: Long get() = if (engineStreamUrl != null) engineStartPositionMs else startPositionMs
    }
    data class Error(val message: String) : PlayerUiState
}


/** Gapless needs a song played directly by Media3 without a server audio conversion. */
internal fun gaplessEligible(mediaType: String, mode: PlaybackMode, onMedia3: Boolean, serverAudioConversion: String?): Boolean =
    (mediaType.equals("track", ignoreCase = true) || mediaType.equals("audio", ignoreCase = true)) &&
        mode == PlaybackMode.DIRECT_PLAY && onMedia3 && serverAudioConversion == null

internal fun audioOnlyPlaybackHint(sourceRatingKey: String, mediaType: String, videoCodec: String?): Boolean {
    val sourceMayHaveVideoWithoutCodecMetadata =
        com.ambient.tv.data.files.FileKeys.owns(sourceRatingKey) ||
            com.ambient.tv.data.shared.SharedMediaRepository.owns(sourceRatingKey) ||
            com.ambient.tv.data.dlna.DlnaKeys.owns(sourceRatingKey)
    return (!sourceMayHaveVideoWithoutCodecMetadata && videoCodec.isNullOrBlank()) ||
        mediaType.equals("track", ignoreCase = true) ||
        mediaType.equals("audio", ignoreCase = true)
}

internal fun usesProviderStreamHeaders(ratingKey: String): Boolean =
    !com.ambient.tv.data.shared.SharedMediaRepository.owns(ratingKey) &&
        !com.ambient.tv.data.files.FileKeys.owns(ratingKey) &&
        !com.ambient.tv.data.dlna.DlnaKeys.owns(ratingKey)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
    private val playbackAvailability: com.ambient.tv.data.browse.PlaybackAvailability,
    private val sourceAvailability: com.ambient.tv.data.browse.SourceAvailability,
    private val codecCapabilities: DeviceCodecCapabilities,
    private val appPreferences: AppPreferences,
    private val performanceProfileResolver: com.ambient.tv.ui.performance.PerformanceProfileResolver,
    private val tokenStore: TokenStore,
    private val displayVersionSelector: DisplayVersionSelector,
    private val playbackServerRecovery: PlaybackServerRecovery,
    private val resumeConnectivityMonitor: ResumeConnectivityMonitor,
    private val companionMapper: com.ambient.tv.companion.CompanionMediaMapper,
) : ViewModel() {

    fun displayRefreshAdjustmentKey(): String = appPreferences.displayRefreshAdjustmentKey
    fun preferredDisplayModeKey(): String = appPreferences.preferredDisplayModeKey
    fun displayModeWhitelist(): Set<String> = appPreferences.displayModeWhitelist
    fun allowDoubleRefreshRate(): Boolean = appPreferences.allowDoubleRefreshRate
    fun allowThreeTwoPulldown(): Boolean = appPreferences.allowThreeTwoPulldown
    fun displaySwitchPauseMs(): Int = appPreferences.displaySwitchPauseMs

    private var ratingKey: String = checkNotNull(savedStateHandle["ratingKey"])
    private var mediaIndex: Int = savedStateHandle.get<String>("mediaIndex")?.toIntOrNull() ?: 0
    private var restartFromBeginning: Boolean = savedStateHandle.get<Boolean>("restart") ?: false
    private val savedResumeRequest = SavedResumeRequest()
    private val collectionId: String? = savedStateHandle.get<String>("collectionId")?.takeIf { it.isNotBlank() }
    private val collectionSort: String? = savedStateHandle.get<String>("collectionSort")?.takeIf { it.isNotBlank() }
    val reopenRoute get() = com.ambient.tv.ui.navigation.Destinations.player(
        ratingKey,
        mediaIndex,
        collectionId = collectionId,
        collectionSort = collectionSort,
    )
    private fun titleSource(ratingKey: String = this.ratingKey): String = when {
        com.ambient.tv.data.shared.SharedMediaRepository.owns(ratingKey) -> "shared"
        com.ambient.tv.data.dlna.DlnaKeys.owns(ratingKey) -> "dlna"
        com.ambient.tv.data.files.FileKeys.owns(ratingKey) -> "files"
        EmbyBackend.owns(ratingKey) -> "emby"
        JellyfinBackend.owns(ratingKey) -> "jellyfin"
        else -> "plex"
    }
    internal fun ownsCompanionMedia(media: com.ambient.tv.companion.CompanionMedia?) = com.ambient.tv.companion.samePlaybackMedia(companionMedia, media)
    private val companionMedia get() = runCatching { companionMapper.fromRoute(reopenRoute) }.getOrNull()
    private var parentMediaIds: List<String> = emptyList()
    private var videoScope: PlaybackVideoScope? = null

    /**
     * Server, season, series and collections of [item]; episodes take the collections of their series.
     * Playback waits at most [VIDEO_SCOPE_COLLECTIONS_WAIT_MS] for collections; a slower lookup finishes in the
     * background (cached for the next play), and a timeout or failure is never cached as "no collections".
     */
    private suspend fun loadVideoScope(item: MediaItem): PlaybackVideoScope? {
        val source = titleSource()
        if (source !in setOf("plex", "jellyfin", "emby")) return null
        val serverId = when (source) {
            "plex" -> appPreferences.activeServerId
            "jellyfin" -> appPreferences.activeJellyfinServerId
            else -> appPreferences.activeEmbyServerId
        }
        val owner = videoScopeOwner(item)
        val movie = item.type.equals("movie", ignoreCase = true)
        // Jellyfin/Emby fetch memberships separately (once per server, then cached by the repository).
        suspend fun withCollections(media: MediaItem): List<com.ambient.tv.domain.model.MediaTag>? =
            media.collections.ifEmpty {
                if (source == "plex") emptyList()
                else mediaRepository.enrichCollections(listOf(media)).getOrNull()?.firstOrNull()?.collections ?: return null
            }
        val collections = owner?.let { id ->
            if (movie && item.collections.isNotEmpty()) return@let item.collections
            SeriesCollectionsCache.get("$source:${serverId.orEmpty().length}:${serverId.orEmpty()}:$id", VIDEO_SCOPE_COLLECTIONS_WAIT_MS) {
                if (movie) withCollections(item) else mediaRepository.getMetadata(id).getOrNull()?.let { withCollections(it) }
            }
        }.orEmpty()
        return playbackVideoScope(item, serverId, collections)
    }
    suspend fun loadTimeline(sourceKey: String, sourceIndex: Int) =
        mediaRepository.getPlaybackTimeline(sourceKey, sourceIndex).getOrDefault(com.ambient.tv.domain.model.PlaybackTimeline())

    private var forcedVersion: Int? = null
    private var versionResumeMs: Long? = null
    private var conversionRestart: AudioConversionRestart? = null
    private var restoreConversionSettings = false
    fun selectCompanionVersion(key: String) {
        if (changingItem) return
        val state = _uiState.value as? PlayerUiState.Ready ?: return
        val index = key.toIntOrNull() ?: return
        if (state.companionVersions.none { it.first == key } || index == state.selectedVersionIndex) return
        BackgroundPlaybackSession.cancelResumeRewind()
        forcedVersion = index
        versionResumeMs = (BackgroundPlaybackSession.currentStatus()?.positionMs ?: state.startPositionMs) + state.timelineOffsetMs
        _uiState.value = PlayerUiState.Loading
        // A still-running load of the previous version must not finish over the new one.
        playbackLoadJob?.cancel()
        playbackLoadJob = viewModelScope.launch { loadPlayback() }
    }

    /** Movies and episodes in direct play only: an added subtitle file cannot reach a server transcode. */
    private fun subtitleSearchInfo(state: PlayerUiState.Ready): SubtitleSearchInfo? {
        if (state.playbackMode != PlaybackMode.DIRECT_PLAY) return null
        val type = state.mediaType.lowercase()
        if (type != "movie" && type != "episode") return null
        val fileKey = state.requestedRatingKey.takeIf { com.ambient.tv.data.files.FileKeys.owns(it) }
        val episode = type == "episode"
        val title = if (episode) state.grandparentTitle?.takeIf(String::isNotBlank) ?: state.title
            else if (fileKey != null) state.title.substringBeforeLast('.') else state.title
        return SubtitleSearchInfo(title = title, year = state.year, season = state.seasonIndex.takeIf { episode },
            episode = state.episodeIndex.takeIf { episode }, imdbId = state.imdbId, fileMediaId = fileKey)
    }

    fun metadata(state: PlayerUiState.Ready): PlaybackMetadata = metadata(state, parentMediaIds)

    private fun metadata(state: PlayerUiState.Ready, parentMediaIds: List<String>): PlaybackMetadata {
        val ratingKey = state.requestedRatingKey
        val reopenRoute = state.playbackRoute ?: reopenRoute
        val companionMedia = runCatching { companionMapper.fromRoute(reopenRoute) }.getOrNull()
        val nextEpisode = state.nextEpisode?.takeIf { state.mediaType.equals("episode", ignoreCase = true) }
        val next = nextEpisode ?: state.nextCollectionItem?.takeIf { state.mediaType.equals("movie", ignoreCase = true) }
        val copy = miniPlayerCopy(state.mediaType, state.title, state.grandparentTitle)
        val art = miniPlayerArtwork(
            mediaType = state.mediaType,
            posterArtUrl = state.posterArtUrl,
            thumbUrl = state.artworkUrl,
            panelArtworkUrl = state.panelArtworkUrl,
            panelFallbacks = state.panelArtworkFallbackUrls,
        )
        return PlaybackMetadata(
            route = reopenRoute,
            title = copy.title,
            subtitle = copy.subtitle,
            source = titleSource(ratingKey),
            mediaId = ratingKey,
            companionMedia = companionMedia,
            parentMediaIds = parentMediaIds,
            videoScope = videoScope,
            companionNext = state.nextAudioTrack?.toNextUpCandidate(source = titleSource(ratingKey), headers = state.streamHeaders),
            hasVideo = !audioOnlyPlaybackHint(ratingKey, state.mediaType, state.videoCodec) &&
                !state.mediaType.equals("photo", ignoreCase = true),
            artworkUrl = art.url,
            artworkHeaders = state.streamHeaders,
            artworkFallbackUrls = art.fallbacks,
            artworkAspectRatio = art.aspectRatio,
            sourceAspectRatioHint = state.sourceAspectRatioHint,
            sourceWidth = state.sourceWidth,
            sourceHeight = state.sourceHeight,
            timelineOffsetMs = state.timelineOffsetMs,
            durationMs = state.durationMs ?: 0,
            resumeState = state,
            subtitleTracks = state.subtitleTracks + OnlineSubtitleTracks.forRoute(reopenRoute),
            externalSubtitlesAllowed = state.playbackMode == PlaybackMode.DIRECT_PLAY,
            subtitleSearch = subtitleSearchInfo(state),
            timelineSeekToken = state.timelineSeekToken,
            timelineSeekMs = state.timelineSeekMs,
            handheldHdrStandard = if (state.runtimeHdrKnown) state.runtimeHdrStandard else if (state.playbackMode != PlaybackMode.TRANSCODE)
                HdrSupport.sourceStandard(state.videoCodec, state.videoDynamicRange) else null,
            hdrBaseLayer = if (state.playbackMode != PlaybackMode.TRANSCODE) dolbyVisionBaseLayer(state.videoDynamicRange) else null,
            creditsSkipMode = if (state.mediaType.equals("episode", ignoreCase = true) || state.mediaType.equals("movie", ignoreCase = true)) appPreferences.creditsSkipMode else "off",
            introSkipMode = if (state.mediaType.equals("episode", ignoreCase = true)) appPreferences.introSkipMode else "off",
            successorsLoading = state.successorsLoading,
            nextUp = next?.let { item ->
                item.toNextUpCandidate(source = titleSource(ratingKey), headers = state.streamHeaders)
            },
            nextUpConfig = NextUpConfig(
                enabled = appPreferences.nextUpEnabled,
                leadSeconds = appPreferences.nextUpLeadSeconds,
                creditsDelaySeconds = appPreferences.nextUpCreditsDelaySeconds,
                autoplay = if (state.mediaType.equals("episode", ignoreCase = true)) appPreferences.nextUpAutoplayEpisodes
                else appPreferences.nextUpAutoplayCollections,
                stillWatchingEvery = appPreferences.nextUpStillWatchingEpisodes,
                showOverlay = appPreferences.nextUpOverlayEnabled,
            ),
        )
    }

    fun nextUpEnabled(): Boolean = appPreferences.nextUpEnabled
    fun setNextUpAutoplay(mediaType: String, enabled: Boolean) {
        if (mediaType.equals("episode", ignoreCase = true)) appPreferences.nextUpAutoplayEpisodes = enabled
        else appPreferences.nextUpAutoplayCollections = enabled
    }
    fun setIntroSkipMode(value: String) {
        appPreferences.introSkipMode = value
    }
    fun setCreditsSkipMode(value: String) {
        appPreferences.creditsSkipMode = value
    }
    var changingItem by mutableStateOf(false)
        private set
    private var itemTransitionToken: Long? = null
    private var playbackLoadJob: Job? = null
    private var successorsJob: Job? = null

    /** Keep the fullscreen destination and its OSD; only replace the playback request. */
    fun playNext(nextId: String) {
        if (changingItem || nextId == ratingKey) return
        val current = _uiState.value as? PlayerUiState.Ready ?: return
        if (nextId != current.nextAudioTrack?.ratingKey && nextId != current.nextVideoItem?.ratingKey) return
        changingItem = true
        clearGaplessNext()
        itemTransitionToken = BackgroundPlaybackSession.beginItemTransition()
        playbackLoadJob?.cancel()
        successorsJob?.cancel()
        timelineSeekJob?.cancel()
        ratingKey = nextId
        mediaIndex = 0
        restartFromBeginning = false
        savedResumeRequest.skip()
        savedStateHandle["ratingKey"] = nextId
        savedStateHandle["mediaIndex"] = "0"
        savedStateHandle["restart"] = false
        forcedVersion = null
        versionResumeMs = null
        conversionRestart = null
        restoreConversionSettings = false
        audioFallbackBackends.clear()
        serverUnreachableRetryAttempted = false
        videoFallbackAttempted = false
        lastReadyForAudioRecovery = null
        playbackLoadJob = viewModelScope.launch {
            try {
                loadPlayback()
                (_uiState.value as? PlayerUiState.Error)?.let { error ->
                    BackgroundPlaybackSession.pause()
                    itemTransitionToken?.let { BackgroundPlaybackSession.failSourceReplacement(it, error.message) }
                }
            } finally { changingItem = false }
        }
    }

    private val audioFallbackBackends = mutableSetOf<String>()
    private var serverAudioFallbackCodec: String? = null
    private var serverAudioFallbackInFlight = false
    private var lastReadyForAudioRecovery: PlayerUiState.Ready? = null
    private var lastAudioRecoveryPositionMs = 0L
    private var serverUnreachableRetryAttempted = false
    private var videoFallbackAttempted = false

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private var lastReadyRatingKey: String? = null
    private var lastReadyMediaType: String? = null

    /**
     * Item that was actually playing when the player closes. Next up keeps the same screen, so
     * this follows the episode on screen, and an error after playback still remembers it.
     */
    fun playbackExit(): Pair<String?, String?> {
        val ready = _uiState.value as? PlayerUiState.Ready
        return (ready?.requestedRatingKey ?: lastReadyRatingKey) to (ready?.mediaType ?: lastReadyMediaType)
    }

    init {
        viewModelScope.launch {
            _uiState.collect { state ->
                if (state is PlayerUiState.Ready) {
                    lastReadyRatingKey = state.requestedRatingKey
                    lastReadyMediaType = state.mediaType
                }
            }
        }
        viewModelScope.launch {
            var seenVpn = resumeConnectivityMonitor.vpnDropGeneration.value
            resumeConnectivityMonitor.vpnDropGeneration.collect { generation ->
                if (generation == seenVpn) return@collect
                seenVpn = generation
                if (_uiState.value is PlayerUiState.Ready &&
                    PlaybackServerUnreachable.appliesToLocalMedia(ratingKey)
                ) {
                    handleServerUnreachable(context.getString(R.string.wg_vpn_network_lost))
                }
            }
        }

        playbackLoadJob = viewModelScope.launch { loadPlayback() }
    }

    private suspend fun loadPlayback() {
        val ratingKey = ratingKey
        val reopenRoute = reopenRoute
        val transitionToken = itemTransitionToken
        if (!restartFromBeginning && forcedVersion == null) {
            (BackgroundPlaybackSession.cached(reopenRoute) as? PlayerUiState.Ready)?.let {
                savedResumeRequest.skip()
                _uiState.value = it.copy(playbackBackendKey = BackgroundPlaybackSession.snapshot.value.backend)
                if (it.successorsLoading) {
                    val item = mediaRepository.getMetadata(ratingKey).getOrNull()
                    currentCoroutineContext().ensureActive()
                    if (item != null) loadSuccessors(item)
                    else _uiState.update { current ->
                        if (current is PlayerUiState.Ready) current.copy(successorsLoading = false) else current
                    }
                } else {
                    // Reopened while playing: this screen takes the queued next song over again.
                    parentMediaIds = BackgroundPlaybackSession.snapshot.value.metadata?.parentMediaIds ?: parentMediaIds
                    prepareGaplessNext(it.nextAudioTrack)
                }
                return
            }
        } else if (forcedVersion == null && BackgroundPlaybackSession.snapshot.value.metadata?.route == reopenRoute) {
            BackgroundPlaybackSession.stop()
        }
        if (!playbackAvailability.media(ratingKey)) {
            _uiState.value = PlayerUiState.Error(context.getString(R.string.browse_playback_unavailable))
            return
        }
        serverAudioFallbackCodec = null
        serverAudioFallbackInFlight = false
        mediaRepository.getMetadata(ratingKey).fold(
            onSuccess = { item ->
                currentCoroutineContext().ensureActive()
                if (item == null) {
                    sourceAvailability.notifyBlocked()
                    _uiState.value = PlayerUiState.Error(context.getString(R.string.player_media_not_found))
                    return@fold
                }
                videoScope = loadVideoScope(item)
                currentCoroutineContext().ensureActive()
                var selectedVersionIndex = forcedVersion ?: displayVersionSelector.selectIndex(item.mediaVersions, mediaIndex)
                var version = item.mediaVersions.getOrNull(selectedVersionIndex)
                if (version == null) {
                    _uiState.value = PlayerUiState.Error(context.getString(R.string.player_version_not_found))
                    return@fold
                }

                var assessment = codecCapabilities.assess(version)
                if (
                    routedAudioOptions().localFallback == "off" &&
                    appPreferences.playbackBackendKey == "media3" &&
                    appPreferences.autoSelectDisplayVersion && forcedVersion == null &&
                    assessment.audioOnlyFallbackRecommended
                ) {
                    val selectedPixels = version.pixelCount
                    val compatible = item.mediaVersions
                        .mapIndexed { index, candidate -> Triple(index, candidate, codecCapabilities.assess(candidate)) }
                        .filter { (_, _, candidateAssessment) ->
                            candidateAssessment.videoSupported && candidateAssessment.audioSupported
                        }
                        .filter { (_, candidate, _) ->
                            selectedPixels <= 0L || candidate.pixelCount <= 0L || candidate.pixelCount <= selectedPixels
                        }
                        .maxWithOrNull(
                            compareBy<Triple<Int, com.ambient.tv.domain.model.MediaVersion, DeviceCodecCapabilities.Assessment>> {
                                it.second.pixelCount
                            }.thenBy { it.second.bitrateKbps ?: 0 },
                        )
                    if (compatible != null && compatible.first != selectedVersionIndex) {
                        Timber.i(
                            "Media3 version compatibility: replacing version %d (%s/%s) with %d (%s/%s)",
                            selectedVersionIndex,
                            assessment.videoCodec,
                            assessment.audioCodec,
                            compatible.first,
                            compatible.third.videoCodec,
                            compatible.third.audioCodec,
                        )
                        selectedVersionIndex = compatible.first
                        version = compatible.second
                        assessment = compatible.third
                    }
                }

                val playbackRatingKey = version.sourceRatingKey ?: ratingKey
                val playbackMediaIndex = version.sourceMediaIndex
                Timber.i(
                    "Version selection: requested=%d selected=%d display=%s auto=%s sourceKey=%s sourceMedia=%d",
                    mediaIndex,
                    selectedVersionIndex,
                    displayVersionSelector.displayLabel(),
                    appPreferences.autoSelectDisplayVersion,
                    playbackRatingKey,
                    playbackMediaIndex,
                )
                Timber.i(
                    "Codec preflight: video=%s supported=%s hw=%s, audio=%s supported=%s hw=%s",
                    assessment.videoCodec,
                    assessment.videoSupported,
                    assessment.hardwareVideoDecoder,
                    assessment.audioCodec,
                    assessment.audioSupported,
                    assessment.hardwareAudioDecoder,
                )

                // DetailScreen only offers "resume" while meaningful progress remains. Plex may
                // retain a viewOffset at (or just before) the end for watched items; feeding that
                // position back into a fresh player makes the decoder open and immediately close.
                val resumeAtEnd = (item.progressFraction ?: 0f) > 0.94f
                val savedPositionMs = versionResumeMs ?: if (restartFromBeginning || resumeAtEnd) {
                    0L
                } else {
                    item.viewOffsetMs ?: 0L
                }
                // Work on the absolute media position before server conversion creates a
                // relative stream timeline. Engine/version reloads retain their exact clock.
                val startPositionMs = savedResumeRequest.position(
                    savedPositionMs,
                    appPreferences.resumeRewindSeconds,
                    eligible = versionResumeMs == null && !restartFromBeginning && !resumeAtEnd &&
                        !audioOnlyPlaybackHint(ratingKey, item.type, version.videoCodec) &&
                        !item.type.equals("photo", ignoreCase = true),
                )
                val route = AudioPassthroughPolicy.probeCached(context)
                val conversionMetadata = PlaybackMetadata(reopenRoute, "", source = titleSource(), mediaId = ratingKey,
                    sourceWidth = version.width, sourceHeight = version.height, videoScope = videoScope)
                val conversionSettings = conversionRestart?.settings ?: TitleVideoSettingsStore.load(context, conversionMetadata)?.audio
                    ?: ResolutionVideoProfileStore.load(context, conversionMetadata)?.audio ?: appPreferences.audioSettings
                val conversionOptions = conversionSettings.currentForRoute(route.profile)
                val preferredAudio = conversionRestart?.streamId?.let { id -> version.tracks.firstOrNull { it.type == MediaTrackType.AUDIO && it.id == id } }
                    ?: com.ambient.tv.data.local.audioTrackForConversion(version.tracks,
                    appPreferences.playbackAudioLanguage, conversionSettings.tracks)
                var plan = when {
                    com.ambient.tv.data.shared.SharedMediaRepository.owns(ratingKey) || com.ambient.tv.data.files.FileKeys.owns(ratingKey) || com.ambient.tv.data.dlna.DlnaKeys.owns(ratingKey) -> PlaybackPlanner.directPlay(version)
                    assessment.videoFallbackRecommended -> {
                        videoFallbackAttempted = true
                        videoFallbackChannels = videoFallbackAudioChannels(conversionOptions, route, preferredAudio?.channels)
                        mediaRepository.getVideoCompatibleStreamUrl(playbackRatingKey, playbackMediaIndex, startPositionMs, videoFallbackChannels)
                            .fold(
                                onSuccess = { PlaybackPlanner.videoFallback(it, assessment.videoCodec) },
                                onFailure = {
                                    Timber.w(it, "Video fallback URL could not be created; trying Direct Play")
                                    PlaybackPlanner.directPlay(version)
                                },
                            )
                    }
                    // Keep the original stream until a specific audio-only recovery is needed.
                    else -> PlaybackPlanner.directPlay(version)
                }

                var convertedStreamId: Long? = null
                if (plan?.mode == PlaybackMode.DIRECT_PLAY && supportsServerAudioConversion(playbackRatingKey) &&
                    ((conversionRestart?.wasServerConverted == true && conversionOptions.conversionLocation == "server" && conversionOptions.localFallback != "off") ||
                    shouldConvertAudioOnServer(conversionOptions, preferredAudio?.codec ?: assessment.audioCodec,
                        preferredAudio?.channels, route, assessment.audioSupported))) {
                    val codec = serverAudioTarget(conversionOptions, route, preferredAudio?.channels, appPreferences.mediaAudioPassthroughEnabled)
                    val channels = serverAudioChannels(conversionOptions, route, preferredAudio?.channels, codec)
                    Timber.i("Server audio conversion target=%s channels=%d", codec, channels)
                    val result = mediaRepository.getAudioCompatibleStreamUrl(playbackRatingKey, playbackMediaIndex,
                        startPositionMs, codec, preferredAudio?.id, channels)
                    if (result.isFailure) {
                        _uiState.value = PlayerUiState.Error(context.getString(R.string.player_audio_conversion_failed))
                        return@fold
                    }
                    plan = PlaybackPlanner.audioFallback(result.getOrThrow(), assessment.audioCodec, codec)
                    serverAudioFallbackCodec = codec
                    convertedStreamId = preferredAudio?.id
                }

                val selectedPlan = plan
                _uiState.value = if (selectedPlan != null) {
                    if (selectedPlan.mode == PlaybackMode.DIRECT_PLAY) {
                        mediaRepository.preparePlayback(playbackRatingKey, playbackMediaIndex)
                    }
                    val panelArtwork = PlaybackPanelArtworkResolver.resolve(
                        backdropUrl = item.artUrl,
                        thumbnailUrl = item.thumbUrl,
                        highQualityAssets = performanceProfileResolver.effectiveHighQualityAssets(),
                    )
                    parentMediaIds = listOfNotNull(item.parentRatingKey, item.grandparentRatingKey).distinct()
                    currentCoroutineContext().ensureActive()
                    if (transitionToken != null && !BackgroundPlaybackSession.isSourceReplacement(transitionToken)) return
                    itemTransitionToken = null
                    PlayerUiState.Ready(
                        streamUrl = selectedPlan.url,
                        playbackMode = selectedPlan.mode,
                        playbackReason = selectedPlan.reason,
                        title = item.title,
                        artworkUrl = item.thumbUrl,
                        posterArtUrl = item.posterArtUrl,
                        panelArtworkUrl = panelArtwork.primaryUrl,
                        panelArtworkFallbackUrls = panelArtwork.fallbackUrls,
                        mediaType = item.type,
                        parentTitle = item.parentTitle,
                        grandparentTitle = item.grandparentTitle,
                        seasonIndex = item.parentIndex,
                        episodeIndex = item.index,
                        year = item.year,
                        imdbId = (item.guids + listOfNotNull(item.guid)).firstNotNullOfOrNull { Regex("""imdb://(tt\d+)""").find(it)?.groupValues?.get(1) },
                        summary = item.summary,
                        tagline = item.tagline,
                        startPositionMs = if (selectedPlan.mode == PlaybackMode.DIRECT_PLAY) startPositionMs else 0L,
                        durationMs = item.durationMs,
                        videoCodec = version.videoCodec,
                        videoDynamicRange = version.videoDynamicRange,
                        videoBitDepth = version.tracks.firstOrNull { it.type == MediaTrackType.VIDEO }?.bitDepth,
                        videoProfile = version.tracks.firstOrNull { it.type == MediaTrackType.VIDEO }?.profile,
                        audioCodec = serverAudioFallbackCodec ?: assessment.audioCodec,
                        serverAudioStreamId = convertedStreamId,
                        serverAudioConversionCodec = serverAudioFallbackCodec,
                        container = version.container,
                        sourceBitrateKbps = version.bitrateKbps,
                        audioTracks = version.tracks.filter { it.type == MediaTrackType.AUDIO },
                        subtitleTracks = version.tracks.filter { it.type == MediaTrackType.SUBTITLE },
                        hardwareVideoDecoder = assessment.hardwareVideoDecoder,
                        hardwareAudioDecoder = assessment.hardwareAudioDecoder,
                        timelineOffsetMs = if (selectedPlan.mode == PlaybackMode.DIRECT_PLAY) 0L else startPositionMs,
                        successorsLoading = true,
                        requestedRatingKey = ratingKey,
                        playbackRoute = reopenRoute,
                        nextEpisode = null,
                        nextCollectionItem = null,
                        nextAudioTrack = null,
                        preferredAudioLanguage = appPreferences.playbackAudioLanguage,
                        preferredSubtitleLanguage = appPreferences.preferredSubtitleLanguage,
                        subtitlesEnabled = appPreferences.subtitlesEnabled,
                        forcedSubtitlesEnabled = appPreferences.forcedSubtitlesEnabled,
                        sourceRatingKey = playbackRatingKey,
                        sourceMediaIndex = playbackMediaIndex,
                        selectedVersionIndex = selectedVersionIndex,
                        autoVersionSelection = forcedVersion == null && appPreferences.autoSelectDisplayVersion,
                        companionVersions = item.mediaVersions.mapIndexed { index, v -> index.toString() to listOfNotNull(v.resolution, v.videoCodec, v.audioCodec, v.bitrateKbps?.let { "$it kb/s" }).joinToString(" · ").ifBlank { "${index + 1}" } },
                        streamHeaders = streamHeaders(),
                        playbackBackendKey = resolvedPlaybackBackend(
                            audioOnly = audioOnlyPlaybackHint(ratingKey, item.type, version.videoCodec),
                            requested = TitleVideoSettingsStore.rendererFor(context,
                                PlaybackMetadata(reopenRoute, "", source = titleSource(), mediaId = ratingKey, videoScope = videoScope),
                                ResolutionVideoProfileStore.rendererFor(context, version.width,
                                    appPreferences.playbackBackendKey)).also { requestedBackendKey = it },
                            audioCodec = serverAudioFallbackCodec ?: assessment.audioCodec,
                            audioChannels = version.selectedAudioTrack?.channels,
                            media3CanDecode = serverAudioFallbackCodec != null || assessment.audioSupported,
                            videoCodec = version.videoCodec,
                            dynamicRange = version.videoDynamicRange,
                            videoProfile = version.tracks.firstOrNull { it.type == MediaTrackType.VIDEO }?.profile,
                        ),
                        seekJumpMs = appPreferences.bigSeekJumpSeconds * 1000L,
                        audioVisualizerModeKey = appPreferences.audioVisualizerModeKey,
                        audioPassthroughEnabled = serverAudioTargetIsEncoded(serverAudioFallbackCodec) || appPreferences.mediaAudioPassthroughEnabled,
                        projectmPresetIntervalSeconds = appPreferences.projectmPresetIntervalSeconds,
                        projectmTransitionSeconds = appPreferences.projectmTransitionSeconds,
                        projectmQualityKey = appPreferences.projectmQualityKey,
                        projectmPresetCategoryKey = appPreferences.projectmPresetCategoryKey,
                        sourceAspectRatioHint = sourceAspectRatioHint(version.width, version.height),
                        sourceWidth = version.width?.takeIf { it > 0 },
                        sourceHeight = version.height?.takeIf { it > 0 },
                    )
                } else {
                    PlayerUiState.Error(context.getString(R.string.player_no_playable_url))
                }
                if (_uiState.value is PlayerUiState.Ready) loadSuccessors(item)
            },
            onFailure = { error ->
                currentCoroutineContext().ensureActive()
                sourceAvailability.notifyBlocked()
                _uiState.value = PlayerUiState.Error(error.message ?: context.getString(R.string.player_load_failed))
            },
        )
    }

    /** Next-up metadata must not delay opening the actual stream. */
    private fun loadSuccessors(item: MediaItem) {
        successorsJob?.cancel()
        val ratingKey = ratingKey
        successorsJob = viewModelScope.launch {
            val nextEpisode = if (item.type == "episode") {
                mediaRepository.getNextEpisode(ratingKey).getOrNull()
            } else null
            val nextCollectionItem = if (item.type == "movie" && collectionId != null) {
                val collectionItems = mediaRepository.getItemsByCollection(collectionId).getOrDefault(emptyList())
                    .filter { it.type == "movie" }
                    .let { items ->
                        when (collectionSort?.uppercase()) {
                            "YEAR" -> items.sortedWith(compareByDescending<MediaItem> { it.year ?: 0 }.thenBy { it.title.lowercase() })
                            else -> items.sortedBy { it.title.lowercase() }
                        }
                    }
                val currentPosition = collectionItems.indexOfFirst { ratingKey in it.allRatingKeys }
                if (currentPosition >= 0) collectionItems.getOrNull(currentPosition + 1) else null
            } else null
            val nextAudioTrack = if (item.type in setOf("track", "audio")) {
                mediaRepository.getNextTrack(ratingKey).getOrNull()
            } else null
            _uiState.update { current ->
                if (current is PlayerUiState.Ready && current.requestedRatingKey == ratingKey) current.copy(
                    successorsLoading = false,
                    nextEpisode = nextEpisode,
                    nextCollectionItem = nextCollectionItem,
                    nextAudioTrack = nextAudioTrack,
                ) else current
            }
            prepareGaplessNext(nextAudioTrack)
        }
    }

    /** The next song, resolved ahead so Media3 can queue it and play on without a gap. */
    data class GaplessNext(val ratingKey: String, val streamUrl: String, internal val state: PlayerUiState.Ready, internal val item: MediaItem)

    private val _gaplessNext = kotlinx.coroutines.flow.MutableStateFlow<GaplessNext?>(null)
    val gaplessNext: kotlinx.coroutines.flow.StateFlow<GaplessNext?> = _gaplessNext
    private var gaplessJob: Job? = null

    private fun clearGaplessNext() {
        gaplessJob?.cancel()
        gaplessJob = null
        _gaplessNext.value = null
        Media3GaplessQueue.clear()
    }

    private fun prepareGaplessNext(next: MediaItem?) {
        clearGaplessNext()
        val current = _uiState.value as? PlayerUiState.Ready ?: return
        if (next == null) return
        val expected = current.requestedRatingKey
        gaplessJob = viewModelScope.launch {
            val prepared = resolveGaplessNext(current, next) ?: return@launch
            currentCoroutineContext().ensureActive()
            val now = _uiState.value as? PlayerUiState.Ready ?: return@launch
            if (now.requestedRatingKey != expected) return@launch
            _gaplessNext.value = prepared
            queueGaplessNext(now.playbackRoute ?: reopenRoute, prepared)
        }
    }

    /** The screen's engine is ready: queue a song prepared before the engine existed. */
    fun queuePreparedGaplessNext() {
        val prepared = _gaplessNext.value ?: return
        val now = _uiState.value as? PlayerUiState.Ready ?: return
        queueGaplessNext(now.playbackRoute ?: reopenRoute, prepared)
    }

    /** Hands the prepared song to the running Media3 engine, which keeps it while minimized. */
    private fun queueGaplessNext(fromRoute: String, prepared: GaplessNext) {
        val player = (BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)?.player ?: return
        val parents = gaplessParents(prepared.item)
        Media3GaplessQueue.offer(player, GaplessQueue.Entry(
            ratingKey = prepared.ratingKey,
            streamUrl = prepared.streamUrl,
            fromRoute = fromRoute,
            metadata = metadata(prepared.state, parents),
            continuation = { continueGaplessWithoutScreen(prepared, parents) },
        ))
    }

    private fun gaplessParents(item: MediaItem): List<String> =
        listOfNotNull(item.parentRatingKey, item.grandparentRatingKey).distinct()

    /**
     * The engine played on to [adopted] without the player screen (minimized or in the background).
     * Fills in its successor for the notification and the reopened player, and queues that song.
     * Runs outside viewModelScope: this ViewModel may already be gone. Uses only app singletons
     * and the stored state, never this ViewModel's per-item fields.
     */
    private suspend fun continueGaplessWithoutScreen(adopted: GaplessNext, parents: List<String>) {
        val route = adopted.state.playbackRoute ?: return
        mediaRepository.preparePlayback(adopted.state.sourceRatingKey, adopted.state.sourceMediaIndex)
        val next = mediaRepository.getNextTrack(adopted.ratingKey).getOrNull()
        currentCoroutineContext().ensureActive()
        if (BackgroundPlaybackSession.snapshot.value.metadata?.route != route) return
        val state = adopted.state.copy(successorsLoading = false, nextAudioTrack = next)
        BackgroundPlaybackSession.updateMetadata(metadata(state, parents))
        val prepared = next?.let { resolveGaplessNext(state, it) } ?: return
        currentCoroutineContext().ensureActive()
        if (BackgroundPlaybackSession.snapshot.value.metadata?.route != route) return
        val player = (BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)?.player ?: return
        val nextParents = gaplessParents(prepared.item)
        Media3GaplessQueue.offer(player, GaplessQueue.Entry(
            ratingKey = prepared.ratingKey,
            streamUrl = prepared.streamUrl,
            fromRoute = route,
            metadata = metadata(prepared.state, nextParents),
            continuation = { continueGaplessWithoutScreen(prepared, nextParents) },
        ))
    }

    /**
     * Only the plain case is prepared: a song played directly by Media3, followed by a song from
     * the same source that Media3 can also play directly. Anything else (server conversion,
     * multichannel, another renderer) keeps the normal item change.
     */
    private suspend fun resolveGaplessNext(current: PlayerUiState.Ready, next: MediaItem): GaplessNext? {
        if (!appPreferences.gaplessMusicEnabled) return null
        val onMedia3 = com.ambient.tv.ui.player.backend.PlaybackBackendResolver.resolve(current.playbackBackendKey) ==
            com.ambient.tv.ui.player.backend.PlaybackBackend.MEDIA3
        if (!gaplessEligible(current.mediaType, current.playbackMode, onMedia3, current.serverAudioConversionCodec)) return null
        if (titleSource(next.ratingKey) != titleSource(current.requestedRatingKey)) return null
        val item = mediaRepository.getMetadata(next.ratingKey).getOrNull() ?: return null
        if (!item.type.equals("track", true) && !item.type.equals("audio", true)) return null
        val versionIndex = displayVersionSelector.selectIndex(item.mediaVersions, 0)
        val version = item.mediaVersions.getOrNull(versionIndex) ?: return null
        val assessment = codecCapabilities.assess(version)
        val audio = version.selectedAudioTrack
        if (!assessment.audioSupported || (audio?.channels ?: 2) > 2) return null
        val route = AudioPassthroughPolicy.probeCached(context)
        if (shouldConvertAudioOnServer(appPreferences.audioSettings.currentForRoute(route.profile),
                audio?.codec ?: assessment.audioCodec, audio?.channels, route, assessment.audioSupported)) return null
        val plan = PlaybackPlanner.directPlay(version)?.takeIf { it.mode == PlaybackMode.DIRECT_PLAY } ?: return null
        currentCoroutineContext().ensureActive()
        val now = current
        val panelArtwork = PlaybackPanelArtworkResolver.resolve(
            backdropUrl = item.artUrl,
            thumbnailUrl = item.thumbUrl,
            highQualityAssets = performanceProfileResolver.effectiveHighQualityAssets(),
        )
        val sourceKey = version.sourceRatingKey ?: next.ratingKey
        val state = now.copy(
            streamUrl = plan.url,
            playbackReason = plan.reason,
            title = item.title,
            artworkUrl = item.thumbUrl,
            posterArtUrl = item.posterArtUrl,
            panelArtworkUrl = panelArtwork.primaryUrl,
            panelArtworkFallbackUrls = panelArtwork.fallbackUrls,
            mediaType = item.type,
            parentTitle = item.parentTitle,
            grandparentTitle = item.grandparentTitle,
            seasonIndex = item.parentIndex,
            episodeIndex = item.index,
            year = item.year,
            imdbId = null,
            summary = item.summary,
            tagline = item.tagline,
            startPositionMs = 0L,
            durationMs = item.durationMs,
            videoCodec = version.videoCodec,
            videoDynamicRange = version.videoDynamicRange,
            videoBitDepth = null,
            videoProfile = null,
            audioCodec = assessment.audioCodec,
            container = version.container,
            sourceBitrateKbps = version.bitrateKbps,
            audioTracks = version.tracks.filter { it.type == MediaTrackType.AUDIO },
            subtitleTracks = emptyList(),
            hardwareAudioDecoder = assessment.hardwareAudioDecoder,
            timelineOffsetMs = 0L,
            nextEpisode = null,
            nextCollectionItem = null,
            nextAudioTrack = null,
            sourceRatingKey = sourceKey,
            sourceMediaIndex = version.sourceMediaIndex,
            selectedVersionIndex = versionIndex,
            companionVersions = emptyList(),
            timelineSeekMs = null,
            requestedRatingKey = next.ratingKey,
            serverAudioStreamId = null,
            serverAudioConversionCodec = null,
            playbackRoute = com.ambient.tv.ui.navigation.Destinations.player(next.ratingKey, 0,
                collectionId = collectionId, collectionSort = collectionSort),
            successorsLoading = true,
            engineStreamUrl = now.playerStreamUrl,
            engineStartPositionMs = now.playerStartPositionMs,
        )
        return GaplessNext(next.ratingKey, plan.url, state, item)
    }

    /**
     * Media3 moved on to the queued song by itself: the player state follows without touching
     * the engine. False when the song is not the prepared one (the caller then plays it normally).
     */
    fun adoptGaplessNext(nextRatingKey: String): Boolean {
        val prepared = _gaplessNext.value?.takeIf { it.ratingKey == nextRatingKey } ?: return false
        val current = _uiState.value as? PlayerUiState.Ready ?: return false
        if (changingItem) return false
        val fromRoute = current.playbackRoute ?: reopenRoute
        clearGaplessNext()
        successorsJob?.cancel()
        timelineSeekJob?.cancel()
        ratingKey = nextRatingKey
        mediaIndex = 0
        restartFromBeginning = false
        savedResumeRequest.skip()
        savedStateHandle["ratingKey"] = nextRatingKey
        savedStateHandle["mediaIndex"] = "0"
        savedStateHandle["restart"] = false
        forcedVersion = null
        versionResumeMs = null
        conversionRestart = null
        restoreConversionSettings = false
        audioFallbackBackends.clear()
        serverAudioFallbackCodec = null
        serverUnreachableRetryAttempted = false
        videoFallbackAttempted = false
        lastReadyForAudioRecovery = null
        parentMediaIds = gaplessParents(prepared.item)
        val state = prepared.state
        if (!BackgroundPlaybackSession.continueWithNextItem(fromRoute, metadata(state))) return false
        _uiState.value = state
        mediaRepository.preparePlayback(state.sourceRatingKey, state.sourceMediaIndex)
        loadSuccessors(prepared.item)
        return true
    }

    private fun routedAudioOptions(): com.ambient.tv.data.local.AudioOptions =
        appPreferences.audioSettings.currentForRoute(AudioPassthroughPolicy.probeCached(context).profile)

    private fun isPlexAudioSource(id: String): Boolean =
        !JellyfinBackend.owns(id) && !EmbyBackend.owns(id) &&
            !com.ambient.tv.data.shared.SharedMediaRepository.owns(id) &&
            !com.ambient.tv.data.files.FileKeys.owns(id) && !com.ambient.tv.data.dlna.DlnaKeys.owns(id)

    private fun supportsServerAudioConversion(id: String): Boolean =
        !com.ambient.tv.data.shared.SharedMediaRepository.owns(id) &&
            !com.ambient.tv.data.files.FileKeys.owns(id) && !com.ambient.tv.data.dlna.DlnaKeys.owns(id)

    private fun activeAudioOptions(): com.ambient.tv.data.local.AudioOptions = when (val handle = BackgroundPlaybackSession.activeHandle()) {
        is Media3PlaybackHandle -> Media3AudioProcessing.session(handle.player)?.options
        is com.ambient.tv.ui.player.backend.MpvPlaybackController -> handle.audioSession.options
        is com.ambient.tv.ui.player.backend.VlcPlaybackController -> handle.audioSession.options
        else -> null
    } ?: routedAudioOptions()

    private fun activeAudioSession(): AudioSession? = when (val handle = BackgroundPlaybackSession.activeHandle()) {
        is Media3PlaybackHandle -> Media3AudioProcessing.session(handle.player)
        is com.ambient.tv.ui.player.backend.MpvPlaybackController -> handle.audioSession
        is com.ambient.tv.ui.player.backend.VlcPlaybackController -> handle.audioSession
        else -> null
    }

    /** The active profile limited by what failed in this playback (stereo retry, rejected forced channels). */
    private fun activeConversionOptions(): com.ambient.tv.data.local.AudioOptions =
        activeAudioSession()?.let { serverConversionOptions(it.options, it.forceStereoPcm, it.forcedChannelsFailed) }
            ?: routedAudioOptions()

    /** Audio width of the current H.264/AAC video fallback; reused when a seek re-requests it. */
    private var videoFallbackChannels = 2

    fun serverAudioSelectionEnabled(): Boolean = (_uiState.value as? PlayerUiState.Ready)?.let {
        supportsServerAudioConversion(it.sourceRatingKey) && activeAudioOptions().conversionLocation == "server"
    } == true

    fun requestServerAudioTrack(streamId: Long, positionMs: Long) {
        if (changingItem) return
        val current = _uiState.value as? PlayerUiState.Ready ?: return
        val track = current.audioTracks.firstOrNull { it.id == streamId } ?: return
        if (!supportsServerAudioConversion(current.sourceRatingKey)) return
        startServerAudioFallback(current, positionMs,
            serverAudioTarget(activeConversionOptions(), AudioPassthroughPolicy.probeCached(context), track.channels, appPreferences.mediaAudioPassthroughEnabled), streamId,
            sourceChannels = track.channels)
    }

    fun restoreReloadedAudio(session: AudioSession) {
        if (!restoreConversionSettings) return
        conversionRestart?.restore(session)
        restoreConversionSettings = false
    }

    fun mpvAudioAllowed(state: PlayerUiState.Ready): Boolean =
        RendererCapabilityPolicy.effectiveBackend("mpv", state.videoCodec, state.videoDynamicRange,
            state.videoProfile, state.runtimeHdrStandard == HdrStandard.DOLBY_VISION) == "mpv"

    fun audioConversionLocationChanged(session: AudioSession, positionMs: Long): Boolean {
        val current = _uiState.value as? PlayerUiState.Ready ?: return false
        if (changingItem || !supportsServerAudioConversion(current.sourceRatingKey)) return false
        val restart = session.conversionRestart(current.serverAudioStreamId ?: conversionRestart?.streamId,
            positionMs, current.timelineOffsetMs, current.serverAudioConversionCodec != null)
        conversionRestart = restart
        restoreConversionSettings = true
        // Re-open the original media version at the absolute position. This also leaves a
        // previous server-converted URL when switching back to local conversion.
        versionResumeMs = restart.absolutePositionMs
        BackgroundPlaybackSession.stop()
        playbackLoadJob?.cancel()
        _uiState.value = PlayerUiState.Loading
        playbackLoadJob = viewModelScope.launch { loadPlayback() }
        return true
    }

    private fun postAudioRecoveryNotice(message: Int, failed: Boolean = false) {
        com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
            key = "playback:audio-conversion", message = message, kind = NoticeKind.PLAYBACK,
            phase = if (failed) com.ambient.tv.notifications.NoticePhase.ERROR else com.ambient.tv.notifications.NoticePhase.INFO,
        ))
    }

    /** Renderer preference does not prevent local audio recovery; video safeguards still apply. */
    private fun resolvedPlaybackBackend(
        audioOnly: Boolean,
        requested: String,
        audioCodec: String?,
        audioChannels: Int?,
        media3CanDecode: Boolean,
        videoCodec: String?,
        dynamicRange: String?,
        videoProfile: String?,
    ): String {
        AudioCodecRejects.clear()
        val route = runCatching {
            AudioPassthroughPolicy.probeCached(context).copy(mpvAvailable = MpvBackendAvailability.isAvailable)
        }.getOrDefault(AudioRouteCapabilities(mpvAvailable = MpvBackendAvailability.isAvailable))
        val permittedRoute = route.copy(mpvAvailable = route.mpvAvailable &&
            RendererCapabilityPolicy.effectiveBackend("mpv", videoCodec, dynamicRange, videoProfile) == "mpv")
        val audioPlan = planAudioOutput(
            options = appPreferences.audioSettings.currentForRoute(route.profile).let {
                if (audioOnly) it.copy(output = "pcm", localFallback = "pcm", localAc3 = false) else it
            },
            sourceCodec = audioCodec,
            route = permittedRoute,
            backend = requested,
            sourcePassthrough = appPreferences.mediaAudioPassthroughEnabled,
            media3CanDecode = media3CanDecode,
            sourceChannels = audioChannels,
            media3LocalAvailable = com.ambient.tv.ui.player.audio.LocalAudioBridge.AVAILABLE &&
                com.ambient.tv.ui.player.audio.localDecodableSourceCodec(audioCodec),
        )
        val resolved = when {
            requested in setOf("auto", "media3") &&
                com.ambient.tv.ui.player.audio.LocalAudioBridge.AVAILABLE &&
                com.ambient.tv.ui.player.audio.localDecodableSourceCodec(audioCodec) -> requested
            // DTS-HD → DTS core needs no native library; Media3 strips the extension itself.
            requested in setOf("auto", "media3") && audioPlan.mode == AudioOutputMode.DTS_CORE -> requested
            audioPlan.needsMpv && MpvBackendAvailability.isAvailable -> "mpv"
            audioPlan.mode == AudioOutputMode.PCM && !media3CanDecode &&
                requested in setOf("auto", "media3") && appPreferences.audioSettings.currentForRoute(route.profile).localFallback != "off" -> "vlc"
            else -> requested
        }
        Timber.i(
            "Audio plan: mode=%s spdif=%s needsMpv=%s requested=%s resolved=%s",
            audioPlan.mode, audioPlan.spdifCodecs, audioPlan.needsMpv, requested, resolved,
        )
        return RendererCapabilityPolicy.effectiveBackend(resolved, videoCodec, dynamicRange, videoProfile)
            .let { if (it == "mpv" && !MpvBackendAvailability.isAvailable) "media3" else it }
    }

    fun followAudioPlan(positionMs: Long) {
        if (routedAudioOptions().localFallback == "off" || !MpvBackendAvailability.isAvailable) return
        val current = _uiState.value as? PlayerUiState.Ready ?: return
        if (!mpvAudioAllowed(current)) return
        if (current.playbackBackendKey == "mpv" || "mpv" in audioFallbackBackends) return
        if (!AudioEngineRedirectGuard.once(current.streamUrl)) return
        val plan = when (val handle = BackgroundPlaybackSession.activeHandle()) {
            is Media3PlaybackHandle -> Media3AudioProcessing.session(handle.player)?.outputPlan()
            is com.ambient.tv.ui.player.backend.VlcPlaybackController -> handle.audioSession.outputPlan()
            else -> null
        }
        switchPlaybackBackend("mpv", positionMs, makeGlobal = false, persistForTitle = false)
        if (plan?.mode == AudioOutputMode.LOCAL_AC3) postAudioRecoveryNotice(R.string.notice_audio_local_ac3_conversion)
    }

    private fun plexStreamHeaders(): Map<String, String> = buildMap {
        put("X-Plex-Client-Identifier", appPreferences.clientIdentifier)
        put("X-Plex-Product", com.ambient.tv.branding.AppBrand.name)
        put("X-Plex-Version", BuildConfig.VERSION_NAME)
        put("X-Plex-Platform", "Android TV")
        put("X-Plex-Device", "Android TV")
        // Keep auth in both places: Plex part URLs already carry X-Plex-Token as a query
        // parameter, while a header survives redirects/proxies that may drop the query.
        tokenStore.token?.takeIf { it.isNotBlank() }?.let { put("X-Plex-Token", it) }
    }

    private fun streamHeaders(): Map<String, String> = if (!usesProviderStreamHeaders(ratingKey)) emptyMap() else if (EmbyBackend.owns(ratingKey) || JellyfinBackend.owns(ratingKey)) {
        buildMap {
            val token = if (EmbyBackend.owns(ratingKey)) tokenStore.embyToken else tokenStore.jellyfinToken
            val auth = buildString {
                append("MediaBrowser Client=\"${com.ambient.tv.branding.AppBrand.name}\", Device=\"Android TV\", DeviceId=\"")
                append(appPreferences.clientIdentifier)
                append("\", Version=\"").append(BuildConfig.VERSION_NAME).append('"')
                if (!token.isNullOrBlank()) append(", Token=\"").append(token).append('"')
            }
            put("Authorization", auth)
            put("X-Emby-Authorization", auth)
            if (!token.isNullOrBlank()) put("X-Emby-Token", token)
        }
    } else plexStreamHeaders()

    private var timelineSeekJob: kotlinx.coroutines.Job? = null
    fun canResumeSeekViaSource(): Boolean = (_uiState.value as? PlayerUiState.Ready)?.let {
        it.playbackMode == PlaybackMode.TRANSCODE ||
            it.playbackMode == PlaybackMode.DIRECT_STREAM && serverAudioFallbackCodec != null
    } == true

    fun seekTimeline(positionMs: Long) {
        BackgroundPlaybackSession.userSeek()
        seekTimelineInternal(positionMs, forResume = false, onComplete = null)
    }

    fun seekTimelineForResume(positionMs: Long, stillCurrent: () -> Boolean, onComplete: (Boolean) -> Unit) {
        seekTimelineInternal(positionMs, forResume = true, onComplete = onComplete, stillCurrent = stillCurrent)
    }

    private fun seekTimelineInternal(positionMs: Long, forResume: Boolean, onComplete: ((Boolean) -> Unit)?, stillCurrent: () -> Boolean = { true }) {
        if (changingItem || !stillCurrent()) { onComplete?.invoke(false); return }
        val current = _uiState.value as? PlayerUiState.Ready ?: run { onComplete?.invoke(false); return }
        val target = positionMs.coerceIn(0L, current.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE)
        timelineSeekJob?.cancel()
        val vlcCannotSeekTranscode = current.playbackMode == PlaybackMode.TRANSCODE &&
            (BackgroundPlaybackSession.activeHandle() as? com.ambient.tv.ui.player.backend.VlcPlaybackController)
                ?.canSeekNow() == false
        val canSeekDirectly = !forResume || BackgroundPlaybackSession.activeHandle()?.canSeekNow() == true
        if (target >= current.timelineOffsetMs && !vlcCannotSeekTranscode && canSeekDirectly) {
            if (forResume) {
                // Automatic rewind must not look like another user seek and cancel its owner.
                val handle = BackgroundPlaybackSession.activeHandle()
                val applied = handle != null && runCatching {
                    handle.seekTo(target - current.timelineOffsetMs)
                    BackgroundPlaybackSession.tick()
                }.isSuccess
                onComplete?.invoke(applied)
            } else {
                BackgroundPlaybackSession.seekTo(target - current.timelineOffsetMs)
            }
            return
        }
        if (current.playbackMode == PlaybackMode.DIRECT_STREAM && serverAudioFallbackCodec != null) {
            timelineSeekJob = startServerAudioFallback(current, target - current.timelineOffsetMs,
                serverAudioFallbackCodec!!, onComplete = onComplete, stillCurrent = stillCurrent)
            return
        }
        if (current.playbackMode != PlaybackMode.TRANSCODE) { onComplete?.invoke(false); return }
        timelineSeekJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var applied = false
            try {
                val result = suspendResult {
                    mediaRepository.getVideoCompatibleStreamUrl(current.sourceRatingKey, current.sourceMediaIndex, target, videoFallbackChannels).getOrThrow()
                }
                result.onSuccess { url ->
                    currentCoroutineContext().ensureActive()
                    val latest = _uiState.value as? PlayerUiState.Ready ?: return@onSuccess
                    if (!stillCurrent() || latest.streamUrl != current.streamUrl || changingItem ||
                        forResume && latest.playbackBackendKey != current.playbackBackendKey) return@onSuccess
                    if (forResume) BackgroundPlaybackSession.beginSourceReplacement(playWhenReadyOverride = true)
                    _uiState.value = latest.copy(streamUrl = url, runtimeHdrStandard = null, runtimeHdrKnown = false, startPositionMs = 0L, timelineOffsetMs = target,
                        timelineSeekToken = latest.timelineSeekToken + 1, timelineSeekMs = target)
                    applied = true
                }.onFailure {
                    if (!stillCurrent()) return@onFailure
                    Timber.w(it, "Chapter seek could not restart compatibility stream")
                    notifyResult("playback:seek", success = false, kind = NoticeKind.PLAYBACK, failureMessage = R.string.player_seek_failed)
                }
            } finally {
                onComplete?.invoke(applied)
            }
        }
    }

    fun requestAudioFallback(positionMs: Long, cause: String) {
        if (changingItem) return
        val current = _uiState.value as? PlayerUiState.Ready ?: return
        if (activeAudioOptions().conversionLocation == "server" && supportsServerAudioConversion(current.sourceRatingKey) &&
            serverAudioFallbackCodec == null && !serverAudioFallbackInFlight) {
            val track = com.ambient.tv.data.local.audioTrackForConversion(current.audioTracks,
                current.preferredAudioLanguage, appPreferences.audioSettings.tracks)
            startServerAudioFallback(current, positionMs,
                serverAudioTarget(activeConversionOptions(), AudioPassthroughPolicy.probeCached(context), track?.channels, appPreferences.mediaAudioPassthroughEnabled), track?.id,
                sourceChannels = track?.channels)
            return
        }
        val active = if (current.playbackBackendKey == "auto") "media3" else current.playbackBackendKey
        audioFallbackBackends.add(active)
        val localBackend = if (serverAudioFallbackCodec != null && current.playbackMode == PlaybackMode.DIRECT_STREAM) null else LocalAudioFallbackPolicy.nextBackend(
            active, audioFallbackBackends, MpvBackendAvailability.isAvailable,
            enabled = routedAudioOptions().localFallback != "off",
            videoCodec = current.videoCodec, dynamicRange = current.videoDynamicRange, videoProfile = current.videoProfile,
        )
        if (localBackend == null) {
            Timber.w("Local audio recovery exhausted: %s", cause)
            if (!serverAudioFallbackInFlight) {
                lastReadyForAudioRecovery = current
                lastAudioRecoveryPositionMs = positionMs
                _uiState.value = PlayerUiState.Error(context.getString(
                    when (serverAudioFallbackCodec) {
                        "ac3", "eac3" -> R.string.player_audio_ac3_failed
                        "aac" -> R.string.player_audio_conversion_failed
                        else -> R.string.player_local_audio_failed
                    }))
                postAudioRecoveryNotice(if (serverAudioFallbackCodec == null)
                    R.string.player_local_audio_failed else R.string.notice_audio_conversion_failed, failed = true)
            }
            return
        }
        val sourceCodec = when (val handle = BackgroundPlaybackSession.activeHandle()) {
            is Media3PlaybackHandle -> Media3AudioProcessing.session(handle.player)?.sourceCodec
            is com.ambient.tv.ui.player.backend.MpvPlaybackController -> handle.audioSession.sourceCodec
            is com.ambient.tv.ui.player.backend.VlcPlaybackController -> handle.audioSession.sourceCodec
            else -> null
        } ?: current.audioCodec
        AudioCodecRejects.reject(sourceCodec)
        Timber.w("Audio recovery keeps original URL and switches %s to %s: %s", active, localBackend, cause)
        switchPlaybackBackend(localBackend, positionMs, makeGlobal = false, persistForTitle = false)
        com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
            "playback:audio-recovery", R.string.notice_audio_restarting, subject = localBackend.uppercase(), kind = NoticeKind.PLAYBACK,
        ))
    }

    fun canTryAacAudioFallback(): Boolean = serverAudioTargetIsEncoded(serverAudioFallbackCodec) && lastReadyForAudioRecovery != null
    fun isServerAc3FallbackActive(): Boolean = serverAudioTargetIsEncoded(serverAudioFallbackCodec)
    fun isServerAudioFallbackActive(): Boolean = serverAudioFallbackCodec != null

    fun tryAacAudioFallback() {
        if (_uiState.value !is PlayerUiState.Error || !canTryAacAudioFallback()) return
        val ready = lastReadyForAudioRecovery ?: return
        startServerAudioFallback(ready, lastAudioRecoveryPositionMs, "aac",
            sourceChannels = ready.audioTracks.firstOrNull { it.id == ready.serverAudioStreamId }?.channels)
    }

    private fun startServerAudioFallback(
        current: PlayerUiState.Ready, positionMs: Long, codec: String, streamId: Long? = current.serverAudioStreamId,
        sourceChannels: Int? = null,
        onComplete: ((Boolean) -> Unit)? = null,
        stillCurrent: () -> Boolean = { true },
    ): Job? {
        if (!stillCurrent() || serverAudioFallbackInFlight || !supportsServerAudioConversion(current.sourceRatingKey)) {
            onComplete?.invoke(false)
            return null
        }
        serverAudioFallbackInFlight = true
        lastReadyForAudioRecovery = current.copy(serverAudioStreamId = streamId)
        lastAudioRecoveryPositionMs = positionMs
        val absolutePosition = (positionMs + current.timelineOffsetMs).coerceAtLeast(0L)
        // Keep the paused source and its controls during an automatic rewind request. A
        // newer pause/seek can cancel it without leaving the screen stuck in Loading.
        if (onComplete == null) _uiState.value = PlayerUiState.Loading
        postAudioRecoveryNotice(when (codec) {
            "ac3" -> R.string.notice_audio_ac3_conversion
            "eac3" -> R.string.notice_audio_eac3_conversion
            else -> R.string.notice_audio_aac_conversion
        })
        val channels = serverAudioChannels(activeConversionOptions(), AudioPassthroughPolicy.probeCached(context), sourceChannels, codec)
        Timber.i("Server audio conversion target=%s channels=%d", codec, channels)
        return viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var applied = false
            try {
                suspendResult {
                    mediaRepository.getAudioCompatibleStreamUrl(current.sourceRatingKey, current.sourceMediaIndex,
                        absolutePosition, codec, streamId, channels).getOrThrow()
                }.fold(
                    onSuccess = { url ->
                        currentCoroutineContext().ensureActive()
                        if (!stillCurrent() || ratingKey != current.requestedRatingKey || changingItem) return@fold
                        val latest = if (onComplete != null) {
                            (_uiState.value as? PlayerUiState.Ready)?.takeIf {
                                it.streamUrl == current.streamUrl && it.playbackBackendKey == current.playbackBackendKey
                            } ?: return@fold
                        } else current
                        serverAudioFallbackCodec = codec
                        AudioCodecRejects.clear()
                        if (onComplete != null) BackgroundPlaybackSession.beginSourceReplacement(playWhenReadyOverride = true)
                        _uiState.value = latest.copy(
                            streamUrl = url,
                            runtimeHdrStandard = null, runtimeHdrKnown = false,
                            playbackMode = PlaybackMode.DIRECT_STREAM,
                            playbackReason = PlaybackPlanner.audioFallback(url, current.audioCodec, codec).reason,
                            playbackBackendKey = if (onComplete != null) latest.playbackBackendKey else "media3",
                            audioCodec = codec,
                            serverAudioStreamId = streamId,
                            serverAudioConversionCodec = codec,
                            audioPassthroughEnabled = serverAudioTargetIsEncoded(serverAudioFallbackCodec) || appPreferences.mediaAudioPassthroughEnabled,
                            startPositionMs = 0L,
                            timelineOffsetMs = absolutePosition,
                        )
                        applied = true
                    },
                    onFailure = { error ->
                        currentCoroutineContext().ensureActive()
                        if (!stillCurrent() || ratingKey != current.requestedRatingKey || changingItem) return@fold
                        Timber.w(error, "Server audio conversion to %s failed", codec)
                        if (onComplete != null) {
                            notifyResult("playback:seek", success = false, kind = NoticeKind.PLAYBACK, failureMessage = R.string.player_seek_failed)
                        } else {
                            serverAudioFallbackCodec = codec
                            _uiState.value = PlayerUiState.Error(context.getString(R.string.player_audio_conversion_failed))
                            postAudioRecoveryNotice(R.string.notice_audio_conversion_failed, failed = true)
                        }
                    },
                )
            } finally {
                serverAudioFallbackInFlight = false
                onComplete?.invoke(applied)
            }
        }
    }

    fun requestVideoFallback(positionMs: Long, cause: String) {
        if (changingItem) return
        val current = _uiState.value as? PlayerUiState.Ready ?: return
        if (videoFallbackAttempted || current.playbackMode == PlaybackMode.TRANSCODE) {
            reportPlaybackError(context.getString(R.string.player_video_compatibility_failed))
            return
        }
        videoFallbackAttempted = true
        val absolutePosition = (positionMs + current.timelineOffsetMs).coerceAtLeast(0L)
        val track = current.serverAudioStreamId?.let { id -> current.audioTracks.firstOrNull { it.id == id } }
            ?: com.ambient.tv.data.local.audioTrackForConversion(current.audioTracks,
                current.preferredAudioLanguage, appPreferences.audioSettings.tracks)
        videoFallbackChannels = videoFallbackAudioChannels(activeConversionOptions(),
            AudioPassthroughPolicy.probeCached(context), track?.channels)
        viewModelScope.launch {
            mediaRepository.getVideoCompatibleStreamUrl(current.sourceRatingKey, current.sourceMediaIndex, absolutePosition, videoFallbackChannels).fold(
                onSuccess = { url ->
                    currentCoroutineContext().ensureActive()
                    if (ratingKey != current.requestedRatingKey || changingItem) return@fold
                    Timber.w("Switching to full H.264/AAC compatibility fallback: %s", cause)
                    val fallback = PlaybackPlanner.videoFallback(url, current.videoCodec)
                    com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
                        "playback:compatibility", R.string.notice_compatibility_starting, kind = NoticeKind.PLAYBACK,
                    ))
                    _uiState.value = current.copy(
                        streamUrl = fallback.url,
                        runtimeHdrStandard = null, runtimeHdrKnown = false,
                        playbackMode = fallback.mode,
                        playbackReason = "$cause; ${fallback.reason}",
                        startPositionMs = 0L,
                        hardwareVideoDecoder = false,
                        hardwareAudioDecoder = false,
                        timelineOffsetMs = absolutePosition,
                    )
                },
                onFailure = { error ->
                    currentCoroutineContext().ensureActive()
                    if (ratingKey != current.requestedRatingKey || changingItem) return@fold
                    Timber.e(error, "Video fallback failed")
                    switchToVlcOrError(error.message ?: cause)
                },
            )
        }
    }

    /** Renderer the user/title/profile asked for ("auto" when the app chooses). */
    private var requestedBackendKey: String = "auto"
    /** MPV was chosen on purpose; automatic recovery must not move playback to another renderer. */
    fun mpvExplicitlyRequested(): Boolean = requestedBackendKey == "mpv"

    fun switchPlaybackBackend(
        key: String,
        positionMs: Long,
        makeGlobal: Boolean,
        detectedDolbyVision: Boolean = false,
        persistForTitle: Boolean = true,
    ) {
        if (changingItem) return
        val normalized = key.takeIf { it in setOf("auto", "media3", "vlc", "mpv") } ?: "auto"
        val current = _uiState.value as? PlayerUiState.Ready ?: return
        if (normalized == "mpv" && !com.ambient.tv.ui.player.backend.MpvBackendAvailability.isAvailable) return
        BackgroundPlaybackSession.cancelResumeRewind()
        if (makeGlobal) appPreferences.playbackBackendKey = normalized
        val requested = (if (normalized == "auto" && !makeGlobal) ResolutionVideoProfileStore.rendererFor(
            context, current.sourceWidth, appPreferences.playbackBackendKey,
        ) else normalized).let { if (it == "mpv" && !MpvBackendAvailability.isAvailable) "media3" else it }
            .also { requestedBackendKey = it }
        val effective = RendererCapabilityPolicy.effectiveBackend(
            requested = requested,
            videoCodec = current.videoCodec,
            dynamicRange = current.videoDynamicRange,
            videoProfile = current.videoProfile,
            detectedDolbyVision = detectedDolbyVision || current.runtimeHdrStandard == HdrStandard.DOLBY_VISION,
        )
        // Recovery is temporary; only an explicit panel choice changes the title preference.
        if (persistForTitle) TitleVideoSettingsStore.rememberRenderer(context,
            PlaybackMetadata(reopenRoute, "", source = titleSource(), mediaId = ratingKey, videoScope = videoScope),
            if (makeGlobal || normalized == "auto") null else effective)
        val dolbyVisionFallbackReason = "Dolby Vision uses the Media3 hardware path"
        val fallbackReason = if (
            effective != requested && dolbyVisionFallbackReason !in current.playbackReason
        ) {
            "${current.playbackReason}; $dolbyVisionFallbackReason"
        } else {
            current.playbackReason
        }
        val currentEngine = com.ambient.tv.ui.player.backend.PlaybackBackendResolver
            .resolve(current.playbackBackendKey)
        val requestedEngine = com.ambient.tv.ui.player.backend.PlaybackBackendResolver
            .resolve(effective)
        if (currentEngine == requestedEngine) {
            // AUTO and Media3 are the same engine. A Dolby Vision MPV/VLC request is also kept on
            // that engine deliberately. Do not change startPositionMs: it is part of the engine
            // identity and would turn a no-op selection into a needless decoder restart.
            _uiState.value = current.copy(
                playbackBackendKey = effective,
                playbackReason = fallbackReason,
            )
            return
        }
        val authoritativePosition = rendererSwitchPositionMs(
            BackgroundPlaybackSession.currentStatus()?.positionMs,
            positionMs, rendererZeroPositionReady(BackgroundPlaybackSession.activeHandle()),
        )
        _uiState.value = current.copy(
            playbackBackendKey = effective,
            startPositionMs = authoritativePosition,
            playbackReason = fallbackReason,
        )
    }

    fun applySourceHdrFromRuntime(standard: HdrStandard?) {
        if (changingItem) return
        val current = _uiState.value as? PlayerUiState.Ready ?: return
        if (!current.runtimeHdrKnown || current.runtimeHdrStandard != standard)
            _uiState.value = current.copy(runtimeHdrStandard = standard, runtimeHdrKnown = true)
    }

    /** Late source geometry (files/DLNA) can select a class profile after playback has started. */
    fun applyResolutionProfileFromRuntime(width: Int, height: Int, positionMs: Long, detectedDolbyVision: Boolean = false) {
        if (changingItem) return
        val current = _uiState.value as? PlayerUiState.Ready ?: return
        if ((current.sourceWidth ?: 0) > 0 && (current.sourceHeight ?: 0) > 0) return
        val key = com.ambient.tv.data.local.VideoResolutionClass.key(width) ?: return
        val profile = appPreferences.resolutionVideoProfiles.let { profiles ->
            com.ambient.tv.data.local.matchingResolutionKey(profiles, key)?.let(profiles::get)
        }
        if (profile == null) {
            _uiState.value = current.copy(sourceWidth = width, sourceHeight = height)
            return
        }
        val title = TitleVideoSettingsStore.load(context,
            PlaybackMetadata(reopenRoute, "", source = titleSource(), mediaId = ratingKey, videoScope = videoScope))
        val preferred = title?.rendererOrDefault(profile.rendererOrDefault(appPreferences.playbackBackendKey))
            ?: profile.rendererOrDefault(appPreferences.playbackBackendKey)
        val effective = RendererCapabilityPolicy.effectiveBackend(
            preferred, current.videoCodec, current.videoDynamicRange, current.videoProfile,
            detectedDolbyVision || current.runtimeHdrStandard == HdrStandard.DOLBY_VISION,
        ).let { if (it == "mpv" && !MpvBackendAvailability.isAvailable) "media3" else it }
        val changingEngine = com.ambient.tv.ui.player.backend.PlaybackBackendResolver.resolve(current.playbackBackendKey) !=
            com.ambient.tv.ui.player.backend.PlaybackBackendResolver.resolve(effective)
        _uiState.value = current.copy(
            sourceWidth = width, sourceHeight = height,
            playbackBackendKey = if (changingEngine) effective else current.playbackBackendKey,
            startPositionMs = if (changingEngine) rendererSwitchPositionMs(
                BackgroundPlaybackSession.currentStatus()?.positionMs, positionMs,
                rendererZeroPositionReady(BackgroundPlaybackSession.activeHandle()),
            ) else current.startPositionMs,
        )
    }

    fun mpvVideoOutputMode(): String = appPreferences.mpvVideoOutputModeKey

    fun vlcScalingFilter(): String = appPreferences.vlcScalingFilterKey

    /** The UI state changes before Compose replaces the outgoing native controller. */
    fun acceptsPlaybackEvents(backendKey: String): Boolean {
        if (changingItem) return false
        val current = _uiState.value as? PlayerUiState.Ready ?: return false
        return com.ambient.tv.ui.player.backend.PlaybackBackendResolver.resolve(current.playbackBackendKey) ==
            com.ambient.tv.ui.player.backend.PlaybackBackendResolver.resolve(backendKey)
    }

    fun reportPlaybackError(message: String, cause: Throwable? = null) {
        if (changingItem) return
        if (PlaybackServerUnreachable.appliesToLocalMedia(ratingKey) &&
            PlaybackServerUnreachable.isNetworkish(cause, message)
        ) {
            handleServerUnreachable(message)
            return
        }
        switchToVlcOrError(message)
    }

    fun reportPlaybackException(error: androidx.media3.common.PlaybackException) {
        if (changingItem) return
        if (PlaybackServerUnreachable.appliesToLocalMedia(ratingKey) &&
            PlaybackServerUnreachable.isNetworkish(error)
        ) {
            handleServerUnreachable(error.message.orEmpty())
            return
        }
        reportPlaybackError(error.message.orEmpty(), error)
    }

    /**
     * "Retry" on the error screen: a fresh start of the title (new stream URL, renderer choice and
     * server check). It resumes where the server last saved the position.
     */
    fun retryPlayback() {
        if (_uiState.value !is PlayerUiState.Error) return
        savedResumeRequest.skip()
        serverUnreachableRetryAttempted = false
        videoFallbackAttempted = false
        runCatching { BackgroundPlaybackSession.stop() }
        itemTransitionToken = null
        _uiState.value = PlayerUiState.Loading
        playbackLoadJob = viewModelScope.launch { loadPlayback() }
    }

    private fun handleServerUnreachable(rawMessage: String) {
        Timber.w("Local media server unreachable during playback: %s", rawMessage)
        // Pause immediately so a dead socket does not keep spinning; stop if we cannot recover.
        runCatching { BackgroundPlaybackSession.pause() }
        val unreachable = context.getString(R.string.player_server_unreachable)
        if (serverUnreachableRetryAttempted) {
            runCatching { BackgroundPlaybackSession.stop() }
            _uiState.value = PlayerUiState.Error(unreachable)
            return
        }
        serverUnreachableRetryAttempted = true
        // Say what actually happens: a wake only when Wake-on-LAN is set up, otherwise one more check.
        _uiState.value = PlayerUiState.Error(context.getString(
            if (playbackServerRecovery.wakeConfigured(ratingKey)) R.string.player_server_unreachable_retrying
            else R.string.player_server_unreachable_rechecking))
        viewModelScope.launch {
            // suspendResult rethrows cancellation: leaving the player mid-recheck must not stop
            // whatever plays by then through the global session below.
            val recovered = suspendResult { playbackServerRecovery.tryWakeAndRecheck(ratingKey) }.getOrDefault(false)
            if (recovered) {
                Timber.i("Server reachable after one WoL/retry; reloading playback")
                loadPlayback()
            } else {
                runCatching { BackgroundPlaybackSession.stop() }
                _uiState.value = PlayerUiState.Error(unreachable)
            }
        }
    }

    private fun switchToVlcOrError(message: String) {
        val current = _uiState.value as? PlayerUiState.Ready
        if (current != null && requestedBackendKey == "auto" && current.playbackBackendKey != "vlc" &&
            RendererCapabilityPolicy.effectiveBackend("vlc", current.videoCodec, current.videoDynamicRange, current.videoProfile) == "vlc") {
            Timber.w("Media3 playback failed; AUTO is switching to VLC: %s", message)
            com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
                "playback:backend-switch", R.string.notice_switched_to_vlc, kind = NoticeKind.PLAYBACK,
                interruptPlayback = false,
            ))
            // Keep the engine clock so VLC does not reopen at the original startPositionMs.
            val resumeMs = rendererSwitchPositionMs(
                BackgroundPlaybackSession.currentStatus()?.positionMs,
                current.startPositionMs, rendererZeroPositionReady(BackgroundPlaybackSession.activeHandle()),
            )
            _uiState.value = current.copy(
                playbackBackendKey = "vlc",
                startPositionMs = resumeMs,
                playbackReason = "$message; VLC compatibility fallback",
            )
        } else {
            _uiState.value = PlayerUiState.Error(message)
        }
    }

    fun rememberAudioLanguage(language: String?) {
        appPreferences.audioLanguageKey = language ?: LanguagePreferences.AUTO
        val current = _uiState.value as? PlayerUiState.Ready ?: return
        _uiState.value = current.copy(preferredAudioLanguage = appPreferences.playbackAudioLanguage)
    }

    override fun onCleared() {
        itemTransitionToken?.let(BackgroundPlaybackSession::cancelSourceReplacement)
        super.onCleared()
    }

}
