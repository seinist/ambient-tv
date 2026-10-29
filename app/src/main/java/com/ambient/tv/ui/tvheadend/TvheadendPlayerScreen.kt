@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.tvheadend

import com.ambient.tv.ui.player.PlayerPanelVisibility
import com.ambient.tv.ui.theme.appControlSurface
import com.ambient.tv.ui.player.PlayerOverlayVisibility
import com.ambient.tv.ui.player.PlaybackSessionContent
import com.ambient.tv.ui.player.RequestPlayerOverlayFocus
import com.ambient.tv.ui.player.LocalPlaybackMetadata
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.LocalPersistentPlaybackVideoHost
import com.ambient.tv.ui.player.Media3PlaybackHandle
import com.ambient.tv.ui.player.Media3VideoTexture
import com.ambient.tv.ui.player.rememberMedia3NerdStats
import com.ambient.tv.ui.player.PlaybackFpsOverlay
import com.ambient.tv.ui.player.PlaybackPerformanceNotice
import com.ambient.tv.ui.player.PlaybackNerdStatsOverlay
import com.ambient.tv.ui.player.rememberDisplayRefreshHz
import com.ambient.tv.ui.player.rememberMedia3NerdOverlayModel
import com.ambient.tv.ui.player.rememberMedia3PlaybackFps
import com.ambient.tv.ui.player.media3LoadHint
import com.ambient.tv.ui.player.formatNerdBitrate
import com.ambient.tv.ui.player.formatNerdDurationMs
import com.ambient.tv.ui.player.formatNerdHz
import com.ambient.tv.ui.player.backend.tvheadendMpvDeinterlaceMode
import com.ambient.tv.ui.player.findActivity

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.ui.common.AudioPlaybackVisualizer
import com.ambient.tv.ui.common.projectm.LocalProjectMBrowser
import com.ambient.tv.ui.common.projectm.ProjectMAvailability
import com.ambient.tv.ui.common.projectm.ProjectMVisualizer
import com.ambient.tv.ui.common.projectm.rememberProjectMBrowser
import androidx.compose.runtime.CompositionLocalProvider
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.PlayerTouchLayer
import com.ambient.tv.ui.common.PlayerPinchZoom
import com.ambient.tv.ui.common.pinchVideoAspectRatio
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.PlayerPanelEntry
import com.ambient.tv.ui.common.RightSideLazyPanel
import com.ambient.tv.ui.common.RightSidePanel
import com.ambient.tv.ui.common.overlayLazyScrollbar
import com.ambient.tv.ui.common.RightPanelChoice
import com.ambient.tv.ui.common.UnifiedBackendPlayerPanel
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.rememberPlayerPanelNavigation
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.player.PlayerBufferingSpinner
import com.ambient.tv.ui.player.media3WaitingForPlaybackPicture
import com.ambient.tv.ui.player.PlaybackEngineFactory
import com.ambient.tv.ui.player.RuntimeCodecSupport
import com.ambient.tv.ui.player.HdrSupport
import com.ambient.tv.ui.player.PlayerResizeMode
import com.ambient.tv.ui.player.PlayerAspectPreset
import com.ambient.tv.ui.player.TvPlayerSeekBar
import com.ambient.tv.ui.player.SleepTimerConfig
import com.ambient.tv.ui.player.SleepTimerKind
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.delay
import timber.log.Timber
import com.ambient.tv.ui.player.handlePlayerOsdKey
import com.ambient.tv.ui.player.OsdKeyState
import com.ambient.tv.ui.player.OsdContent
import com.ambient.tv.ui.player.OsdSeekSteps
import com.ambient.tv.ui.player.OsdKeyTarget

private data class TvhTrackOption(
    val label: String,
    val language: String?,
    val group: Tracks.Group,
    val trackIndex: Int,
    val selected: Boolean,
    val forced: Boolean,
)

@Composable
fun TvheadendPlayerScreen(
    onBack: () -> Unit,
    viewModel: TvheadendPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val persistentVideoHost = LocalPersistentPlaybackVideoHost.current
    val tvhContext = LocalContext.current
    val activity = remember(tvhContext) { tvhContext.findActivity() }
    val ready = state as? TvheadendPlayerState.Ready
    val readyMetadata = ready?.let(viewModel::metadata)
    val displaySnapshot by BackgroundPlaybackSession.snapshot.collectAsState()
    val displayRoute = readyMetadata?.route ?: displaySnapshot.metadata?.route
    if (readyMetadata?.isLive == true) com.ambient.tv.companion.CompanionSourceControls(readyMetadata.route,
        com.ambient.tv.companion.CompanionPlaybackControls.SourceControls(next = { viewModel.zap(1) }, previous = { viewModel.zap(-1) }))
    // Keep the desktop-mode lease alive through Loading/zapping. Capture the original mode
    // once for this player page, not from every newly tuned channel.
    val requestDisplayMode = com.ambient.tv.ui.player.rememberDisplayModeRequest(
        activity = activity,
        playbackKey = displayRoute ?: "${viewModel.sourceSystem}:loading",
        adjustmentKey = viewModel.displayRefreshAdjustmentKey,
        preferredModeKey = viewModel.preferredDisplayModeKey.also { com.ambient.tv.ui.player.PlaybackDisplayModeRevision.tick },
        whitelist = viewModel.displayModeWhitelist,
        allowDoubleRate = viewModel.allowDoubleRefreshRate,
        allowThreeTwoPulldown = viewModel.allowThreeTwoPulldown,
        metadataRoute = displayRoute,
        switchPauseMs = viewModel.displaySwitchPauseMs,
    )
    val recordingControl by viewModel.recordingControl.collectAsState()
    CompositionLocalProvider(
        LocalLiveRecordingControl provides recordingControl.copy(
            available = recordingControl.available && viewModel.directRecordingEnabled && !viewModel.changingChannel,
        ),
        LocalLiveTimeshiftChanged provides viewModel::applyTimeshiftSetting,
        LocalLiveTimeshiftConfigurable provides viewModel.timeshiftConfigurable,
    ) {
    com.ambient.tv.ui.player.PersistentPlayerChrome(
        viewModel, viewModel.changingChannel, initialZapOsd = viewModel.startsWithLiveChannel,
    ) {
    // A channel change shows the OSD with the new channel; Up/Down keep zapping meanwhile.
    val zapChrome = com.ambient.tv.ui.player.LocalPlayerChromeState.current
    LaunchedEffect(viewModel.zapVersion) {
        if (viewModel.zapVersion > 0 && zapChrome != null && zapChrome.panels.panel == UnifiedPlayerPanel.NONE) {
            // An automatic/compact channel change keeps Up/Down available for zapping.
            // The expanded bar's channel buttons retain their controls and focus instead.
            val zapOnly = !zapChrome.osd.expanded || zapChrome.osd.closing
            zapChrome.visible.value = true
            zapChrome.shownByZap.value = zapOnly
        }
    }
    Box(Modifier.fillMaxSize().background(if (persistentVideoHost) Color.Transparent else Color.Black)
        .onPreviewKeyEvent {
            // TEXT and the red key open teletext / HbbTV when the channel offers them.
            (it.type == KeyEventType.KeyDown && com.ambient.tv.ui.player.teletext.liveServiceKey(tvhContext, it.nativeKeyEvent.keyCode)) ||
                (viewModel.changingChannel && it.key != Key.Back && it.key != Key.MediaStop)
        }) {
        PlayerBufferingSpinner(visible = viewModel.changingChannel, modifier = Modifier.align(Alignment.Center))
        when (val current = state) {
            TvheadendPlayerState.Stopped -> LaunchedEffect(Unit) { onBack() }
            TvheadendPlayerState.Loading -> PlayerBufferingSpinner(
                visible = true,
                modifier = Modifier.align(Alignment.Center),
            )
            is TvheadendPlayerState.Error -> {
                LaunchedEffect(current.message) {
                    val snapshot = BackgroundPlaybackSession.snapshot.value
                    if (snapshot.replacingSource && viewModel.ownsCompanionMedia(snapshot.metadata?.companionMedia)) BackgroundPlaybackSession.failSourceReplacement(snapshot.generation, current.message)
                }
                val title = when (current.kind) {
                    TvheadendPlaybackErrorKind.SERVER_UNAVAILABLE -> com.ambient.tv.ui.common.systemString(com.ambient.tv.ui.common.MediaSystem.fromKey(viewModel.sourceSystem) ?: com.ambient.tv.ui.common.MediaSystem.TVHEADEND, R.string.tvh_server_unavailable_title)
                    TvheadendPlaybackErrorKind.CHANNEL_UNAVAILABLE -> stringResource(R.string.tvh_channel_unavailable_title)
                    TvheadendPlaybackErrorKind.CHANNEL_NOT_FOUND -> stringResource(R.string.tvh_channel_missing_title)
                    TvheadendPlaybackErrorKind.RECORDING_UNAVAILABLE -> stringResource(R.string.tvh_recording_unavailable_title)
                    TvheadendPlaybackErrorKind.GENERIC -> stringResource(R.string.player_error)
                }
                val body = when (current.kind) {
                    TvheadendPlaybackErrorKind.SERVER_UNAVAILABLE -> current.message
                    TvheadendPlaybackErrorKind.CHANNEL_UNAVAILABLE -> current.channelName?.let {
                        stringResource(R.string.tvh_channel_unavailable_body_named, it)
                    } ?: stringResource(R.string.tvh_channel_unavailable_body)
                    TvheadendPlaybackErrorKind.CHANNEL_NOT_FOUND -> stringResource(R.string.tvh_channel_missing_body)
                    TvheadendPlaybackErrorKind.RECORDING_UNAVAILABLE -> stringResource(R.string.tvh_recording_unavailable_body)
                    TvheadendPlaybackErrorKind.GENERIC -> current.message
                }
                StatePanel(
                    title = title,
                    body = body,
                    actionLabel = stringResource(R.string.home_retry),
                    onAction = viewModel::retry,
                    secondaryActionLabel = stringResource(R.string.back),
                    onSecondaryAction = onBack,
                    modifier = Modifier.align(Alignment.Center).widthIn(max = 680.dp),
                )
            }
            is TvheadendPlayerState.Ready -> {
                val playbackMetadata = checkNotNull(readyMetadata)
                val resolutionProfile = com.ambient.tv.ui.player.ResolutionVideoProfileStore.load(tvhContext, playbackMetadata)
                val titleProfile = com.ambient.tv.ui.player.TitleVideoSettingsStore.load(tvhContext, playbackMetadata)
                val formatChanged: (Float, Int?, Int?) -> Unit = { fps, width, height ->
                    requestDisplayMode(fps, width, height)
                    if (width != null && height != null) viewModel.applyResolutionProfileFromRuntime(
                        width, height, BackgroundPlaybackSession.currentStatus()?.positionMs ?: 0L,
                    )
                }
                PlaybackSessionContent(playbackMetadata, onBack) {
                    val backend = com.ambient.tv.ui.player.backend.PlaybackBackendResolver.resolve(current.playbackBackendKey)
                    if (backend == com.ambient.tv.ui.player.backend.PlaybackBackend.VLC) {
                        VlcTvheadendPlaybackSurface(
                            source = current.source,
                            channel = current.channel,
                            imageHeaders = playbackMetadata.artworkHeaders,
                            panelArtworkUrl = current.artworkUrl,
                            panelArtworkFallbackUrls = current.artworkFallbackUrls,
                            nowEvent = current.nowEvent,
                            nextEvent = current.nextEvent,
                            onBack = onBack,
                            onZap = viewModel::zap,
                            onPlaybackError = viewModel::requestPlaybackFallback,
                            initialDeinterlaceMode = titleProfile?.vlcDeinterlaceMode ?: resolutionProfile?.vlcDeinterlaceMode ?: viewModel.tvheadendDeinterlaceModeKey,
                            initialDeinterlaceFilter = titleProfile?.vlcDeinterlaceFilter ?: resolutionProfile?.vlcDeinterlaceFilter ?: viewModel.tvheadendDeinterlaceFilterKey,
                            onDeinterlaceChanged = { _, _ -> },
                            initialScalingFilter = viewModel.vlcScalingFilterKey,
                            seekJumpMs = viewModel.seekJumpMs,
                            rendererKey = current.playbackBackendKey,
                            onRendererSelected = viewModel::switchPlaybackBackend,
                            audioVisualizerModeKey = current.audioVisualizerModeKey,
                            audioPassthroughEnabled = current.audioPassthroughEnabled,
                            preferredAudioLanguage = viewModel.preferredAudioLanguage,
                            forcedSubtitlesEnabled = viewModel.forcedSubtitlesEnabled,
                            onVideoFormatChanged = formatChanged,
                        )
                    } else if (backend == com.ambient.tv.ui.player.backend.PlaybackBackend.MPV) {
                        MpvTvheadendPlaybackSurface(
                            source = current.source,
                            channel = current.channel,
                            imageHeaders = playbackMetadata.artworkHeaders,
                            panelArtworkUrl = current.artworkUrl,
                            panelArtworkFallbackUrls = current.artworkFallbackUrls,
                            nowEvent = current.nowEvent,
                            nextEvent = current.nextEvent,
                            onBack = onBack,
                            onZap = viewModel::zap,
                            onPlaybackError = viewModel::requestPlaybackFallback,
                            initialDeinterlaceMode = titleProfile?.mpvDeinterlaceMode ?: resolutionProfile?.mpvDeinterlaceMode ?: tvheadendMpvDeinterlaceMode(viewModel.tvheadendDeinterlaceModeKey),
                            initialDeinterlaceFilter = titleProfile?.mpvDeinterlaceFilter ?: resolutionProfile?.mpvDeinterlaceFilter ?: viewModel.tvheadendMpvDeinterlaceFilterKey,
                            onDeinterlaceChanged = { _, _ -> },
                            seekJumpMs = viewModel.seekJumpMs,
                            rendererKey = current.playbackBackendKey,
                            onRendererSelected = viewModel::switchPlaybackBackend,
                            initialVideoOutputMode = viewModel.mpvVideoOutputModeKey,
                            audioVisualizerModeKey = current.audioVisualizerModeKey,
                            audioPassthroughEnabled = current.audioPassthroughEnabled,
                            preferredAudioLanguage = viewModel.preferredAudioLanguage,
                            forcedSubtitlesEnabled = viewModel.forcedSubtitlesEnabled,
                            onVideoFormatChanged = formatChanged,
                            onSourceHdrChanged = viewModel::applySourceHdrFromRuntime,
                        )
                    } else {
                        TvheadendPlaybackSurface(
                            source = current.source,
                            channel = current.channel,
                            imageHeaders = playbackMetadata.artworkHeaders,
                            panelArtworkUrl = current.artworkUrl,
                            panelArtworkFallbackUrls = current.artworkFallbackUrls,
                            nowEvent = current.nowEvent,
                            nextEvent = current.nextEvent,
                            onBack = onBack,
                            onZap = viewModel::zap,
                            onPlaybackFallback = viewModel::requestPlaybackFallback,
                            onVideoFallback = viewModel::requestVideoFallback,
                            onAudioFallback = viewModel::requestAudioFallback,
                            preferredAudioLanguage = viewModel.preferredAudioLanguage,
                            preferredSubtitleLanguage = viewModel.preferredSubtitleLanguage,
                            subtitlesEnabled = viewModel.subtitlesEnabled,
                            forcedSubtitlesEnabled = viewModel.forcedSubtitlesEnabled,
                            onRememberAudio = viewModel::rememberAudioLanguage,

                            seekJumpMs = viewModel.seekJumpMs,
                            rendererKey = current.playbackBackendKey,
                            onRendererSelected = viewModel::switchPlaybackBackend,
                            audioVisualizerModeKey = current.audioVisualizerModeKey,
                            projectmPresetIntervalSeconds = current.projectmPresetIntervalSeconds,
                            projectmTransitionSeconds = current.projectmTransitionSeconds,
                            projectmQualityKey = current.projectmQualityKey,
                            projectmPresetCategoryKey = current.projectmPresetCategoryKey,
                            audioPassthroughEnabled = current.audioPassthroughEnabled,
                            onVideoFormatChanged = formatChanged,
                            onSourceHdrChanged = viewModel::applySourceHdrFromRuntime,
                        )
                    }
                    // Teletext is decoded in the app, so it stays across renderer switches; below the OSD.
                    // TVHeadend serves a second connection from the same tuner; IPTV only via Timeshift.
                    if (current.source.isLive) com.ambient.tv.ui.player.teletext.LiveTeletextHost(
                        current.source.url, current.source.headers,
                        ownConnectionAllowed = viewModel.sourceSystem == "tvheadend",
                        channelName = current.channel?.name,
                        channelKey = playbackMetadata.route,
                    )
                    // Outside the engine branch: a renderer switch replaces the engine, not OSD and panel.
                    com.ambient.tv.ui.player.PersistentPlayerOverlays()
                }
            }
        }
    }
    }
    }
}

@Composable
private fun TvheadendPlaybackSurface(
    source: com.ambient.tv.tvheadend.domain.TvhPlaybackSource,
    channel: com.ambient.tv.tvheadend.domain.TvhChannel?,
    imageHeaders: Map<String, String>,
    panelArtworkUrl: String?,
    panelArtworkFallbackUrls: List<String>,
    nowEvent: com.ambient.tv.tvheadend.domain.TvhEpgEvent?,
    nextEvent: com.ambient.tv.tvheadend.domain.TvhEpgEvent?,
    onBack: () -> Unit,
    onZap: (Int) -> Unit,
    onPlaybackFallback: (String) -> Unit,
    onVideoFallback: (String) -> Unit,
    onAudioFallback: (String) -> Unit,
    preferredAudioLanguage: String?,
    preferredSubtitleLanguage: String?,
    subtitlesEnabled: Boolean,
    forcedSubtitlesEnabled: Boolean,
    onRememberAudio: (String?) -> Unit,

    seekJumpMs: Long,
    rendererKey: String,
    onRendererSelected: (String, Long, Boolean) -> Unit,
    audioVisualizerModeKey: String,
    projectmPresetIntervalSeconds: Int,
    projectmTransitionSeconds: Int,
    projectmQualityKey: String,
    projectmPresetCategoryKey: String,
    audioPassthroughEnabled: Boolean,
    onVideoFormatChanged: (Float, Int?, Int?) -> Unit,
    onSourceHdrChanged: (com.ambient.tv.ui.player.HdrStandard?) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val metadata = LocalPlaybackMetadata.current
    val player = remember(source.url, source.startPositionMs) {
        PlaybackEngineFactory.create(
            context = context,
            streamUrl = source.url,
            startPositionMs = source.startPositionMs,
            streamHeaders = source.headers,
            audioPassthroughEnabled = audioPassthroughEnabled,
            metadata = metadata,
        )
    }
    com.ambient.tv.ui.player.ObserveMedia3DisplayFormat(player, onVideoFormatChanged, onSourceHdrChanged)
    com.ambient.tv.ui.player.RestoreMedia3PlaybackProfile(player, metadata)
    val osd = com.ambient.tv.ui.player.rememberPlayerOsdController()
    var controlsVisible by com.ambient.tv.ui.player.rememberPlayerControlsVisible()
    val chromeState = com.ambient.tv.ui.player.LocalPlayerChromeState.current
    val changingItem = com.ambient.tv.ui.player.LocalPlayerChangingItem.current
    val panelNavigation = rememberPlayerPanelNavigation(player)
    var panel by panelNavigation
    com.ambient.tv.ui.player.Media3RenderOverloadDialog(player, panelNavigation) { panelNavigation.openVideoSettings() }
    // Row key of the video panel's Player area, focused when Back leaves that area.
    val rendererEntryTitle = stringResource(R.string.player_renderer)
    val tvhAudio = com.ambient.tv.ui.player.Media3AudioProcessing.session(player)
    val nerdStats = rememberMedia3NerdStats(
        player,
        tvhAudio?.performanceHint == true || tvhAudio?.showFpsOverlay == true,
    )
    // Back is owned by the dispatcher, so nested panels/popups close one level per press.
    // TV Back follows the minimize setting after hiding the OSD. On a handheld, live content
    // and audio keep playing when leaving the player, even before tracks are discovered.
    val handheldUi = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val backMinimizes = com.ambient.tv.ui.player.rememberBackMinimizesPlayback()
    fun exitOnBack() {
        val audioOnly = player.currentTracks.groups.let { groups ->
            groups.any { it.type == C.TRACK_TYPE_AUDIO } && groups.none { it.type == C.TRACK_TYPE_VIDEO }
        }
        if (com.ambient.tv.ui.player.backLeavesPlaybackRunning(audioOnly, handheldUi, backMinimizes(), isLive = source.isLive)) onBack()
        else BackgroundPlaybackSession.stop()
    }
    androidx.activity.compose.BackHandler(enabled = panel == UnifiedPlayerPanel.NONE) {
        // Handheld: swipe Back leaves the player instead of only hiding the OSD.
        if (controlsVisible && !handheldUi) controlsVisible = false else exitOnBack()
    }
    fun userSeekBy(deltaMs: Long) {
        BackgroundPlaybackSession.userSeek()
        val target = player.currentPosition + deltaMs
        val max = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        player.seekTo(target.coerceIn(0L, max))
    }
    fun heldSeekStep(baseMs: Long, event: androidx.compose.ui.input.key.KeyEvent) =
        com.ambient.tv.ui.player.acceleratedSeekStepMs(baseMs, event.nativeKeyEvent.repeatCount, player.duration.takeIf { it > 0 } ?: 0L)
    val playFocusRequester = com.ambient.tv.ui.player.rememberPlayerPlayFocus()
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var playWhenReady by remember(player) { mutableStateOf(player.playWhenReady) }
    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var currentTracks by remember(player) { mutableStateOf(player.currentTracks) }
    var videoFormat by remember(player) { mutableStateOf(player.videoFormat) }
    var audioFormat by remember(player) { mutableStateOf<Format?>(null) }
    var fallbackRequested by remember(player) { mutableStateOf(false) }
    var preferencesApplied by remember(player) { mutableStateOf(player.currentTracks.groups.isNotEmpty()) }
    var playbackEnded by remember(player) { mutableStateOf(false) }
    var playerResizeMode by remember(player) { mutableStateOf(PlayerResizeMode.FIT) }
    var aspectPreset by remember(player) { mutableStateOf(PlayerAspectPreset.AUTO) }
    var videoZoom by remember(player) { mutableStateOf(1f) }
    var speed by remember(player) { mutableStateOf(player.playbackParameters.speed) }
    LaunchedEffect(com.ambient.tv.ui.player.Media3AudioProcessing.session(player)?.bitstreamPermitted) { speed = player.playbackParameters.speed }
    var sleepTimer by com.ambient.tv.ui.player.rememberPlaybackSleepTimer(player)

    LaunchedEffect(player, playerResizeMode, aspectPreset, videoZoom) {
        (BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)
            ?.takeIf { it.player === player }
            ?.setVideoPresentation(
                resizeMode = when (playerResizeMode) {
                    PlayerResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    PlayerResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    PlayerResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                },
                aspectRatio = aspectPreset.ratio,
                zoom = videoZoom,
            )
    }

    val displayLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val audioOptions = remember(currentTracks, displayLocale) { tvhTrackOptions(currentTracks, C.TRACK_TYPE_AUDIO, displayLocale) }
    val subtitleOptions = remember(currentTracks) { tvhTrackOptions(currentTracks, C.TRACK_TYPE_TEXT) }

    DisposableEffect(player) {
        fun publishHdr(format: androidx.media3.common.Format?) {
            format ?: return
            val standard = com.ambient.tv.ui.player.HdrSupport.sourceStandard(format)
            if (standard != null || format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_SDR)
                onSourceHdrChanged(standard)
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }

            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) { playWhenReady = ready }

            override fun onTracksChanged(tracks: Tracks) {
                currentTracks = tracks
                videoFormat = selectedFormat(tracks, C.TRACK_TYPE_VIDEO) ?: player.videoFormat
                audioFormat = selectedFormat(tracks, C.TRACK_TYPE_AUDIO) ?: player.audioFormat
                tvhAudio?.let { session ->
                    session.sourceCodec = audioFormat?.let { format ->
                        com.ambient.tv.ui.player.Media3AudioProcessing.codecKey(format.sampleMimeType) ?: format.sampleMimeType
                    }
                    session.sourceChannels = audioFormat?.channelCount?.takeIf { it > 0 }
                    session.refreshRoute(context)
                }
                videoFormat?.let { format ->
                    publishHdr(format)
                    onVideoFormatChanged(format.frameRate.takeIf { it > 0f } ?: 0f, format.width.takeIf { it > 0 }, format.height.takeIf { it > 0 })
                }
                Timber.d("TVH tracks changed: video=$videoFormat audio=$audioFormat")
                if (!preferencesApplied && tracks.groups.isNotEmpty()) {
                    preferencesApplied = applyTvhTrackPreferences(
                        player, tracks, preferredAudioLanguage, preferredSubtitleLanguage, subtitlesEnabled, forcedSubtitlesEnabled,
                    )
                }
            }

            override fun onPlaybackStateChanged(newPlaybackState: Int) {
                playbackState = newPlaybackState
                if (!source.isLive && newPlaybackState == Player.STATE_ENDED) {
                    playbackEnded = true
                    return
                }
                if (newPlaybackState == Player.STATE_READY) {
                    videoFormat = player.videoFormat ?: videoFormat
                    audioFormat = player.audioFormat ?: audioFormat
                    player.videoFormat?.let { format ->
                        publishHdr(format)
                        onVideoFormatChanged(format.frameRate.takeIf { it > 0f } ?: 0f, format.width.takeIf { it > 0 }, format.height.takeIf { it > 0 })
                    }
                    val videoAssessment = RuntimeCodecSupport.assess(player.videoFormat)
                    val audioAssessment = RuntimeCodecSupport.assess(player.audioFormat)
                    Timber.d(
                        "TVH READY profile=${source.streamProfile ?: "default"}; " +
                            "video=${player.videoFormat} hw=${videoAssessment.hardwareAccelerated} decoder=${videoAssessment.decoderName}; " +
                            "audio=${player.audioFormat} hw=${audioAssessment.hardwareAccelerated} decoder=${audioAssessment.decoderName}",
                    )
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val format = player.videoFormat
                publishHdr(format)
                onVideoFormatChanged(
                    format?.frameRate?.takeIf { it > 0f } ?: 0f,
                    videoSize.width.takeIf { it > 0 },
                    videoSize.height.takeIf { it > 0 },
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                // Renderer switches release this ExoPlayer from BackgroundPlaybackSession while
                // this listener is still attached for one frame. Ignore teardown errors so they
                // cannot flip the UI to CHANNEL_UNAVAILABLE during an Auto→VLC hand-off.
                val active = BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle
                if (active?.player !== player) return
                // A failing image effect chain continues without effects instead of a fallback.
                if (com.ambient.tv.ui.player.Media3ImageProcessing.recoverFromEffectFailure(player, error)) return
                com.ambient.tv.diagnostics.DiagnosticLog.error("playback", "TVHeadend Media3 ${error.errorCodeName}", error)
                val renderer = (error as? ExoPlaybackException)?.rendererName.orEmpty()
                val reason = listOf(renderer, error.message.orEmpty()).filter { it.isNotBlank() }.joinToString(": ")
                Timber.e(error, "TVHeadend playback error; profile=${source.streamProfile}")
                if (com.ambient.tv.connectivity.PlaybackServerUnreachable.isNetworkish(error)) {
                    onPlaybackFallback(reason.ifBlank { "timeout" })
                    return
                }
                if (!fallbackRequested) {
                    fallbackRequested = true
                    val failure = com.ambient.tv.ui.player.playbackFailureKind(
                        renderer, (error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType, reason)
                    if (failure == com.ambient.tv.ui.player.PlaybackFailureKind.AUDIO) {
                        com.ambient.tv.ui.player.AudioCodecRejects.reject(tvhAudio?.sourceCodec)
                        onAudioFallback(reason)
                    } else if (failure == com.ambient.tv.ui.player.PlaybackFailureKind.VIDEO) {
                        onVideoFallback(reason)
                    } else onPlaybackFallback(reason.ifBlank { "TVHeadend decoder/stream error" })
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            if (metadata == null) {
                com.ambient.tv.ui.player.Media3ImageProcessing.release(player)
                player.release()
            }
        }
    }

    val decoderErrorMessage = stringResource(R.string.player_error_video_decoder)
    com.ambient.tv.ui.player.Media3VideoTrackGuard(player) {
        if (!fallbackRequested) {
            fallbackRequested = true
            onVideoFallback(decoderErrorMessage)
        }
    }
    LaunchedEffect(player, playbackState, currentTracks) {
        if (playbackState != Player.STATE_READY || fallbackRequested) return@LaunchedEffect
        delay(com.ambient.tv.ui.player.MEDIA3_AUDIO_CHECK_DELAY_MS)
        if (player.playbackState != Player.STATE_READY ||
            (BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)?.player !== player) return@LaunchedEffect
        // Only unsupported tracks count; an empty track list proves nothing about the channel.
        if (com.ambient.tv.ui.player.media3AudioUnusable(player.currentTracks, player.audioFormat)) {
            fallbackRequested = true
            onAudioFallback("No locally supported audio track")
        }
    }


    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }


    com.ambient.tv.ui.player.PlayerControlsAutoHide(
        controlsVisible, isPlaying, panel != UnifiedPlayerPanel.NONE, changingItem,
    ) { controlsVisible = it }

    RequestPlayerOverlayFocus(
        requester = playFocusRequester,
        playbackIdentity = source.url,
        enabled = controlsVisible && panel == UnifiedPlayerPanel.NONE,
        playbackStarted = isPlaying,
        panelNavigation = panelNavigation,
    )

    val persistentVideoHost = LocalPersistentPlaybackVideoHost.current
    Box(modifier = Modifier.fillMaxSize()) {
    if (!persistentVideoHost) Media3VideoTexture(
        player = player,
        resizeMode = when (playerResizeMode) {
            PlayerResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            PlayerResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            PlayerResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        },
        aspectRatio = aspectPreset.ratio,
        zoom = videoZoom,
        modifier = Modifier.fillMaxSize(),
    )

    val projectMBrowser = rememberProjectMBrowser(projectmPresetCategoryKey)
    // One remote handling for the engine and, while the focus is in the OSD or panel above it, the host.
    val chapterNavigation = com.ambient.tv.ui.player.LocalPlaybackNavigation.current
    val osdKeys: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = osdKeys@{ event ->
        // Timeshift owns Rewind/Forward while it buffers a live channel; media keys act with a panel open too.
        if (source.isLive && handleTimeshiftMediaKey(event, positionMs, seekJumpMs) { osd.reveal() }) return@osdKeys true
        // Shared remote handling (PlayerOsdKeys); only the actions below are Media3's own.
        handlePlayerOsdKey(
            event.key, event.type == KeyEventType.KeyDown,
            OsdKeyState(
                content = if (source.isLive) OsdContent.LIVE else OsdContent.RECORDING,
                osdVisible = controlsVisible && !osd.seekFeedback && !osd.closing,
                panelOpen = panel != UnifiedPlayerPanel.NONE,
                seekFeedbackVisible = osd.seekFeedback,
                zapOsd = chromeState?.shownByZap?.value == true,
                canSeekForward = player.duration > 0,
                closing = osd.closing,
            ),
            OsdSeekSteps(BackgroundPlaybackSession.seekJumpMs(seekJumpMs), held = { heldSeekStep(it, event) }),
            object : OsdKeyTarget {
                override fun chapterSeekDelta(direction: Int) = chapterNavigation?.chapterStep(player.currentPosition, direction)
                override fun isPlaying() = player.isPlaying
                override fun togglePlayback() { BackgroundPlaybackSession.userToggle() }
                override fun play() { BackgroundPlaybackSession.userPlay() }
                override fun pause() { BackgroundPlaybackSession.userPause() }
                override fun stop() { BackgroundPlaybackSession.stop() }
                override fun seek(deltaMs: Long, revealOsd: Boolean) {
                    userSeekBy(deltaMs)
                    if (revealOsd) osd.reveal() else osd.showSeekFeedback()
                }
                override fun showOsd() { osd.reveal() }
                override fun hideSeekFeedback() { osd.reveal() }
                override fun restart() { BackgroundPlaybackSession.userSeek(); player.seekTo(0L) }
                override fun next() = Unit
                override fun previousTrack() = Unit
                override fun zap(delta: Int) { onZap(delta) }
            },
        )
    }
    com.ambient.tv.ui.player.PublishPlayerPanelKeys(osdKeys)
    CompositionLocalProvider(LocalProjectMBrowser provides projectMBrowser, LocalPlayerPanelNavigation provides panelNavigation) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent(osdKeys)
            // Without this, the embedded PlayerView (a plain Android View) can end up holding
            // system focus instead of this Box, so onPreviewKeyEvent above stops receiving
            // D-pad/OK presses entirely - every on-screen button then looks dead.
            .focusRequester(com.ambient.tv.ui.player.rememberPlayerEngineFocus()).focusable(),
    ) {
        PlayerBufferingSpinner(
            visible = media3WaitingForPlaybackPicture(playbackState, playWhenReady),
            modifier = Modifier.align(Alignment.Center),
        )
        val hasAudioTrack = currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
        val hasVideoTrack = currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        if (hasAudioTrack && !hasVideoTrack) {
            when {
                audioVisualizerModeKey == "projectm" && ProjectMAvailability.isAvailable -> ProjectMVisualizer(
                    analyzer = PlaybackEngineFactory.audioSpectrum(player),
                    intervalSeconds = projectmPresetIntervalSeconds,
                    transitionSeconds = projectmTransitionSeconds,
                    qualityKey = projectmQualityKey,
                    categoryKey = projectmPresetCategoryKey,
                    showControls = controlsVisible,
                    browser = projectMBrowser,
                    modifier = Modifier.fillMaxSize(),
                )
                audioVisualizerModeKey != "off" -> AudioPlaybackVisualizer(Modifier.fillMaxSize(), PlaybackEngineFactory.audioSpectrum(player))
            }
        }
        PlayerTouchLayer(
            onToggleControls = {
                if (panel == UnifiedPlayerPanel.NONE) controlsVisible = !controlsVisible
            },
            onTogglePlayback = {
                BackgroundPlaybackSession.userToggle()
                controlsVisible = true
            },
            onSeekBack = if (source.isLive) null else ({
                userSeekBy(-BackgroundPlaybackSession.seekJumpMs(seekJumpMs))
                controlsVisible = true
            }),
            onSeekForward = if (source.isLive) null else ({
                userSeekBy(BackgroundPlaybackSession.seekJumpMs(seekJumpMs))
                controlsVisible = true
            }),
            pinchZoom = if (!hasVideoTrack) null else PlayerPinchZoom(
                key = player,
                enabled = panel == UnifiedPlayerPanel.NONE,
                presentation = com.ambient.tv.ui.player.VideoPresentation(
                    resizeMode = when (playerResizeMode) {
                        PlayerResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        PlayerResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        PlayerResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    },
                    aspectRatio = aspectPreset.ratio,
                    zoom = videoZoom,
                ),
                sourceAspectRatio = { player.pinchVideoAspectRatio() },
                onChanged = {
                    playerResizeMode = PlayerResizeMode.FIT
                    aspectPreset = PlayerAspectPreset.AUTO
                    videoZoom = it.zoom
                },
            ),
        )

        val tvhPlan = tvhAudio?.outputPlan()
        LaunchedEffect(tvhPlan?.needsMpv, tvhPlan?.mode) {
            if (tvhPlan?.needsMpv == true && com.ambient.tv.ui.player.AudioEngineRedirectGuard.once(source.url)) {
                onRendererSelected("mpv", player.currentPosition.coerceAtLeast(0L), false)
            }
        }
        PlaybackPerformanceNotice(media3LoadHint(player, nerdStats))
        val showNerdOverlay = tvhAudio?.performanceHint == true
        val showFpsOverlay = tvhAudio?.showFpsOverlay == true
        PlaybackNerdStatsOverlay(
            audioConversion = tvhAudio?.activeLocalConversion?.let { "${stringResource(com.ambient.tv.R.string.audio_conversion_local)} · $it" },
            enabled = showNerdOverlay,
            model = rememberMedia3NerdOverlayModel(
                player = player,
                stats = nerdStats,
                enabled = showNerdOverlay,
                audioOutputMode = tvhAudio?.outputPlan()?.mode,
            ),
        )
        PlaybackFpsOverlay(
            enabled = showFpsOverlay,
            fps = rememberMedia3PlaybackFps(player, showFpsOverlay && !showNerdOverlay),
            cadenceFps = player.videoFormat?.frameRate?.toDouble(),
            refreshHz = rememberDisplayRefreshHz(),
            nerdStatsVisible = showNerdOverlay,
        )

        // Drawn by PersistentPlayerOverlays above the engine, so it survives a renderer switch.
        val videoLabel = videoFormat?.let { format ->
            listOfNotNull(
                "${format.width}×${format.height}".takeIf { format.width > 0 && format.height > 0 },
                com.ambient.tv.ui.player.formatNerdFps(format.frameRate.toDouble()),
                HdrSupport.sourceStandard(format)?.label,
                format.codecs ?: format.sampleMimeType?.substringAfter('/'),
            ).joinToString(" · ")
        }
        val audioLabel = audioFormat?.let { it.codecs ?: it.sampleMimeType?.substringAfter('/') }

        com.ambient.tv.ui.player.PublishPlayerOsd(LiveOsdModel(
            source = source,
            channel = channel,
            imageHeaders = imageHeaders,
            artworkUrl = panelArtworkUrl,
            artworkFallbackUrls = panelArtworkFallbackUrls,
            nowEvent = nowEvent,
            nextEvent = nextEvent,
            positionMs = positionMs,
            durationMs = durationMs,
            playing = isPlaying,
            loading = media3WaitingForPlaybackPicture(playbackState, playWhenReady),
            backendLabel = "Media3",
            mediaBadges = listOfNotNull(videoLabel, audioLabel),
            audioBadge = com.ambient.tv.ui.player.audioOutputBadge(tvhAudio),
            seekJumpMs = seekJumpMs,
            onTogglePlayback = { BackgroundPlaybackSession.userToggle() },
            onSeekBy = { delta ->
                userSeekBy(delta)
                controlsVisible = true
            },
            onZap = onZap,
            onStop = BackgroundPlaybackSession::stop,
            onBack = onBack,
            onOpenAudio = { panel = UnifiedPlayerPanel.AUDIO },
            onOpenSubtitles = { panel = UnifiedPlayerPanel.SUBTITLES },
            onOpenRenderer = { panelNavigation.openVideoSection("renderer", "entry:" + rendererEntryTitle) },
            onOpenVisualizer = if (hasAudioTrack && !hasVideoTrack) ({ panel = UnifiedPlayerPanel.VISUALIZER }) else null,
            onOpenSleep = { panel = UnifiedPlayerPanel.SLEEP },
            onOpenSettings = { panel = UnifiedPlayerPanel.SETTINGS },
            audioSelected = panel.isAudio,
            subtitlesSelected = panel == UnifiedPlayerPanel.SUBTITLES,
            rendererSelected = panel.isVideo,
            visualizerSelected = panel.isVisualizer,
            sleepSelected = panel == UnifiedPlayerPanel.SLEEP,
            settingsSelected = panel != UnifiedPlayerPanel.NONE,
        ), visible = controlsVisible)

        // Drawn by PersistentPlayerOverlays above the engine, so it survives a renderer switch.
        // Its content is only built with the OSD or a panel on screen, as when it was drawn here.
        if (controlsVisible || panel != UnifiedPlayerPanel.NONE) {
            val audioPairs = audioOptions.mapIndexed { index, option -> index.toString() to option.label }
            val subtitlePairs = listOf("off" to stringResource(R.string.player_subtitles_off)) +
                subtitleOptions.mapIndexed { index, option -> index.toString() to option.label }
            com.ambient.tv.ui.player.PublishPlayerPanel(com.ambient.tv.ui.player.PlayerPanelModel(
                scrimAlpha = 0.30f,
                projectMBrowser = LocalProjectMBrowser.current,
                audioSession = com.ambient.tv.ui.player.Media3AudioProcessing.session(player),
                // A channel still opening has no tracks yet: keep the video panel unless it is radio.
                hasVideo = com.ambient.tv.ui.player.playbackPanelHasVideo(
                    videoTracks = if (videoFormat != null) 1 else currentTracks.groups.count { it.type == C.TRACK_TYPE_VIDEO },
                    audioTracks = currentTracks.groups.count { it.type == C.TRACK_TYPE_AUDIO },
                    audioOnly = metadata?.hasVideo == false),
                isLive = source.isLive,
                scalingKey = when (playerResizeMode) {
                    PlayerResizeMode.ZOOM -> "zoom"
                    PlayerResizeMode.STRETCH -> "stretch"
                    PlayerResizeMode.FIT -> "fit"
                },
                aspectKey = when (aspectPreset) {
                    PlayerAspectPreset.RATIO_16_9 -> "16:9"
                    PlayerAspectPreset.RATIO_4_3 -> "4:3"
                    PlayerAspectPreset.RATIO_21_9 -> "21:9"
                    PlayerAspectPreset.AUTO -> "auto"
                },
                zoomFactor = videoZoom,
                speed = speed,
                onScalingChanged = { key ->
                    playerResizeMode = when (key) {
                        "zoom" -> PlayerResizeMode.ZOOM
                        "stretch" -> PlayerResizeMode.STRETCH
                        else -> PlayerResizeMode.FIT
                    }
                },
                onAspectChanged = { key ->
                    aspectPreset = when (key) {
                        "16:9" -> PlayerAspectPreset.RATIO_16_9
                        "4:3" -> PlayerAspectPreset.RATIO_4_3
                        "21:9" -> PlayerAspectPreset.RATIO_21_9
                        else -> PlayerAspectPreset.AUTO
                    }
                },
                onZoomChanged = { videoZoom = it },
                onSpeedChanged = if (source.isLive || (com.ambient.tv.ui.player.Media3AudioProcessing.session(player)?.bitstreamPermitted ?: audioPassthroughEnabled)) null else ({ value -> speed = value; player.setPlaybackSpeed(value) }),
                audioOptions = audioPairs.ifEmpty { listOf("default" to stringResource(R.string.player_default_track)) },
                selectedAudio = audioOptions.indexOfFirst { it.selected }.takeIf { it >= 0 }?.toString() ?: "default",
                onAudioSelected = { key ->
                    key.toIntOrNull()?.let { audioOptions.getOrNull(it) }?.let { option ->
                        selectTvhTrack(player, C.TRACK_TYPE_AUDIO, option)
                        onRememberAudio(option.language)
                    }
                },
                subtitleOptions = subtitlePairs,
                selectedSubtitle = subtitleOptions.indexOfFirst { it.selected }.takeIf { it >= 0 }?.toString() ?: "off",
                onSubtitleSelected = { key ->
                    val option = key.toIntOrNull()?.let { subtitleOptions.getOrNull(it) }
                    selectTvhTrack(player, C.TRACK_TYPE_TEXT, option)

                },
                sleepTimer = sleepTimer,
                onSleepChanged = { sleepTimer = it },
                rendererKey = rendererKey,
                onRendererSelected = { key, global -> onRendererSelected(key, player.currentPosition, global) },
                infoLines = buildList {
                    add(stringResource(R.string.player_nerd_image_pipeline) to com.ambient.tv.ui.player.Media3ImageProcessing.get(context, player).diagnosticSummary())
                    add(stringResource(R.string.player_nerd_backend) to "Media3")
                    add(stringResource(R.string.player_playback_info) to (source.streamProfile ?: if (source.isLive) "Live TV" else "Recording"))
                    add(stringResource(R.string.player_info_video_output) to com.ambient.tv.ui.player.DirectAvDiagnostics.video(context, videoFormat, "Media3"))
                    videoFormat?.let { format ->
                        listOfNotNull(
                            if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else null,
                            format.frameRate.takeIf { it > 0f }?.let { "%.3f fps".format(java.util.Locale.US, it) },
                            format.codecs ?: format.sampleMimeType?.substringAfter('/'),
                        ).joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_video) to it) }
                        formatNerdBitrate(format.bitrate.takeIf { it > 0 }?.toLong())?.let { add(stringResource(R.string.player_nerd_video_bitrate) to it) }
                        HdrSupport.sourceStandard(format)?.label?.let { add(stringResource(R.string.player_nerd_hdr) to it) }
                    }
                    nerdStats.videoDecoderName?.let { add(stringResource(R.string.player_nerd_video_decoder) to it) }
                    nerdStats.surfaceSize?.let { add(stringResource(R.string.player_nerd_surface) to it) }
                    activity?.let(androidx.core.content.ContextCompat::getDisplayOrDefault)?.refreshRate?.takeIf { it > 0f }?.let { add(stringResource(R.string.player_nerd_display_refresh) to formatNerdHz(it.toDouble()).orEmpty()) }
                    nerdStats.renderedVideoFrames?.let { add(stringResource(R.string.player_nerd_rendered_frames) to it.toString()) }
                    nerdStats.droppedVideoFrames?.let { add(stringResource(R.string.player_nerd_dropped_frames) to it.toString()) }
                    nerdStats.skippedVideoFrames?.takeIf { it > 0 }?.let { add(stringResource(R.string.player_nerd_skipped_frames) to it.toString()) }
                    nerdStats.averageVideoProcessingOffsetUs?.let { add(stringResource(R.string.player_nerd_frame_processing_offset) to "%.2f ms".format(java.util.Locale.US, it / 1000.0)) }
                    add(stringResource(R.string.player_info_audio_output) to com.ambient.tv.ui.player.DirectAvDiagnostics.audio(
                        context, (com.ambient.tv.ui.player.Media3AudioProcessing.session(player)?.bitstreamPermitted ?: audioPassthroughEnabled), audioFormat?.sampleMimeType?.substringAfterLast('/'),
                    ))
                    audioFormat?.let { format ->
                        listOfNotNull(
                            format.codecs ?: format.sampleMimeType?.substringAfter('/'),
                            format.channelCount.takeIf { it > 0 }?.let { "$it ch" },
                            format.sampleRate.takeIf { it > 0 }?.let { "%.1f kHz".format(java.util.Locale.US, it / 1000.0) },
                        ).joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_audio) to it) }
                        formatNerdBitrate(format.bitrate.takeIf { it > 0 }?.toLong())?.let { add(stringResource(R.string.player_nerd_audio_bitrate) to it) }
                    }
                    nerdStats.audioDecoderName?.let { add(stringResource(R.string.player_nerd_audio_decoder) to it) }
                    nerdStats.audioUnderruns.takeIf { it > 0 }?.let { add(stringResource(R.string.player_nerd_audio_underruns) to it.toString()) }
                    formatNerdDurationMs(nerdStats.bufferDurationMs)?.let { add(stringResource(R.string.player_nerd_buffer_health) to it) }
                    formatNerdBitrate(nerdStats.bandwidthEstimateBitsPerSecond)?.let { add(stringResource(R.string.player_nerd_network_throughput) to it) }
                },
                showVisualizer = hasAudioTrack && !hasVideoTrack,
                hasVideoExtras = true,
                // Only a hint, no options: no "Advanced" heading above it.
                videoExtras = {
                    Text(
                        stringResource(R.string.tvh_deinterlace_media3_hint),
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                    )
                },
            ))
        }
    }
    }
    }
}


@Composable
private fun TvNowPlayingInfo(
    source: com.ambient.tv.tvheadend.domain.TvhPlaybackSource,
    channel: com.ambient.tv.tvheadend.domain.TvhChannel?,
    imageHeaders: Map<String, String>,
    nowEvent: com.ambient.tv.tvheadend.domain.TvhEpgEvent?,
    nextEvent: com.ambient.tv.tvheadend.domain.TvhEpgEvent?,
    positionMs: Long,
    durationMs: Long,
    videoFormat: Format?,
    audioFormat: Format?,
) {
    val nowSeconds = System.currentTimeMillis() / 1000L
    val eventDuration = nowEvent?.durationSeconds?.times(1000L) ?: 0L
    val eventPosition = nowEvent?.let { ((nowSeconds - it.startEpochSeconds).coerceAtLeast(0L) * 1000L) } ?: 0L
    val progressDuration = if (source.isLive && eventDuration > 0L) eventDuration else (durationMs.takeIf { it > 0L } ?: source.durationMs ?: 0L)
    val progressPosition = if (source.isLive && eventDuration > 0L) eventPosition else positionMs
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        channel?.let {
            Box(Modifier.width(76.dp).height(44.dp), contentAlignment = Alignment.Center) {
                ArtworkImage(
                    model = channel.iconUrl,
                    title = channel.name,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    headers = imageHeaders,
                )
            }
        }
        if (source.isLive) MetaBadge(stringResource(R.string.tvh_live), emphasized = true)
        source.streamProfile?.let { MetaBadge(it) }
        Text(source.title, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.78f))
    }
    Text(
        text = nowEvent?.title ?: source.title,
        style = MaterialTheme.typography.headlineMedium,
        color = Color.White,
        maxLines = 1,
    )
    val subtitle = nowEvent?.subtitle ?: source.subtitle
    subtitle?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.78f), maxLines = 1)
    }
    val description = nowEvent?.description?.takeIf { it.isNotBlank() }
        ?: nowEvent?.summary?.takeIf { it.isNotBlank() }
        ?: source.description?.takeIf { it.isNotBlank() }
    description?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(0.72f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (nowEvent != null) {
            Text(
                "${formatClockEpoch(nowEvent.startEpochSeconds)} – ${formatClockEpoch(nowEvent.stopEpochSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
            )
            Text(
                stringResource(R.string.player_duration_value, formatTvhDuration(nowEvent.durationSeconds * 1000L)),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.62f),
            )
        } else if (durationMs > 0L) {
            Text(
                "${formatTvhDuration(positionMs)} / ${formatTvhDuration(durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
            )
        }
        playbackBadges(videoFormat, audioFormat)
    }
    if (progressDuration > 0L) {
        androidx.compose.material3.LinearProgressIndicator(
            progress = { (progressPosition.toFloat() / progressDuration.toFloat()).coerceIn(0f, 1f) },
                color = com.ambient.tv.ui.theme.LocalPlaybackAccent.current.indicator,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    nextEvent?.let { event ->
        Text(
            stringResource(R.string.tvh_next_program, formatClockEpoch(event.startEpochSeconds), event.title),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.62f),
            maxLines = 1,
        )
    }
    if (source.isLive) Text(stringResource(R.string.tvh_zap_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f))
}

private fun formatClockEpoch(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))


private fun formatTvhDuration(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)

@Composable
private fun TvhOsdAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val background = when {
        focused -> Color.White
        primary -> Color.White.copy(alpha = 0.94f)
        else -> Color(0xD9181A1E)
    }
    val foreground = if (focused || primary) Color.Black else Color.White
    Row(
        modifier = modifier.width(if (primary) 58.dp else 48.dp).height(40.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(background, shape)
            .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.13f), shape)
            .activateOnTvKey(onClick = onClick).clickable(onClick = onClick).focusable(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = foreground, modifier = Modifier.size(20.dp))
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvhSettingsPanel(
    player: ExoPlayer,
    source: com.ambient.tv.tvheadend.domain.TvhPlaybackSource,
    currentTracks: Tracks,
    resizeMode: PlayerResizeMode,
    aspectPreset: PlayerAspectPreset,
    videoZoom: Float,
    rendererKey: String,
    onOpenRenderer: () -> Unit,
    onOpenVideo: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenInfo: () -> Unit,
    sleepTimer: SleepTimerConfig,
    onBack: () -> Unit,
) {
    val audio = tvhTrackOptions(currentTracks, C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.label ?: stringResource(R.string.player_default_track)
    val subtitle = tvhTrackOptions(currentTracks, C.TRACK_TYPE_TEXT).firstOrNull { it.selected }?.label ?: stringResource(R.string.player_off)
    TvhSidePanel(stringResource(R.string.player_playback_settings), onBack) {
        item {
            TvhSettingsRow(Icons.Filled.Movie, stringResource(R.string.player_renderer), tvhRendererLabel(rendererKey), Modifier, onOpenRenderer)
        }
        item {
            TvhSettingsRow(Icons.Filled.Movie, stringResource(R.string.player_video), tvhVideoSummary(player, resizeMode, aspectPreset, videoZoom), Modifier, onOpenVideo)
        }
        item { TvhSettingsRow(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.player_audio), audio, Modifier, onOpenAudio) }
        item { TvhSettingsRow(Icons.Filled.Subtitles, stringResource(R.string.player_subtitles), subtitle, Modifier, onOpenSubtitles) }
        item {
            TvhSettingsRow(
                Icons.Filled.Bedtime,
                stringResource(R.string.player_sleep_timer),
                tvhSleepTimerSummary(sleepTimer),
                Modifier,
                onOpenSleep,
            )
        }
        item {
            TvhSettingsRow(
                Icons.Filled.Info,
                stringResource(R.string.player_playback_info),
                source.streamProfile ?: stringResource(R.string.player_default_track),
                Modifier,
                onOpenInfo,
            )
        }
    }
}

@Composable
private fun TvhRendererSettingsPanel(
    currentKey: String,
    onSelected: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val options = buildList {
        add("auto" to stringResource(R.string.player_automatic))
        add("media3" to "Media3")
        add("vlc" to "VLC")
        if (com.ambient.tv.ui.player.backend.MpvBackendAvailability.isAvailable) add("mpv" to "MPV")
    }
    TvhSidePanel(stringResource(R.string.player_renderer), onBack) {
        item {
            PlayerDropUpSelector(
                label = stringResource(R.string.player_renderer_current),
                value = tvhRendererLabel(currentKey),
                options = options,
                selectedKey = currentKey,
                onSelected = { onSelected(it, false) },
            )
        }
    }
}

@Composable
private fun tvhRendererLabel(key: String): String = when (key) {
    "media3" -> "Media3"
    "vlc" -> "VLC"
    "mpv" -> "MPV"
    else -> stringResource(R.string.player_automatic)
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvhVideoSettingsPanel(
    player: ExoPlayer,
    allowSpeed: Boolean,
    resizeMode: PlayerResizeMode,
    aspectPreset: PlayerAspectPreset,
    videoZoom: Float,
    onResizeModeChange: (PlayerResizeMode) -> Unit,
    onAspectPresetChange: (PlayerAspectPreset) -> Unit,
    onVideoZoomChange: (Float) -> Unit,
    onBack: () -> Unit,
) {
    var speed by remember(player) { mutableStateOf(player.playbackParameters.speed) }
    TvhSidePanel(stringResource(R.string.player_video_settings), onBack) {
        item {
            Text(
                stringResource(R.string.tvh_deinterlace_media3_hint),
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 11.sp,
            )
        }
        if (aspectPreset == PlayerAspectPreset.AUTO) {
            item { Text(stringResource(R.string.player_scaling_mode), color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            item {
                TvhChoiceRow(stringResource(R.string.player_fit_screen), stringResource(R.string.player_fit_screen_hint), resizeMode == PlayerResizeMode.FIT, Modifier) { onResizeModeChange(PlayerResizeMode.FIT) }
            }
            item { TvhChoiceRow(stringResource(R.string.player_fill_screen), stringResource(R.string.player_fill_screen_hint), resizeMode == PlayerResizeMode.ZOOM) { onResizeModeChange(PlayerResizeMode.ZOOM) } }
            item { TvhChoiceRow(stringResource(R.string.player_stretch), stringResource(R.string.player_stretch_hint), resizeMode == PlayerResizeMode.STRETCH) { onResizeModeChange(PlayerResizeMode.STRETCH) } }
        }
        item {
            PlayerDropUpSelector(
                label = stringResource(R.string.player_aspect_ratio),
                value = when (aspectPreset) { PlayerAspectPreset.AUTO -> stringResource(R.string.player_aspect_auto); PlayerAspectPreset.RATIO_16_9 -> "16:9"; PlayerAspectPreset.RATIO_4_3 -> "4:3"; PlayerAspectPreset.RATIO_21_9 -> "21:9" },
                options = listOf("auto" to stringResource(R.string.player_aspect_auto), "16:9" to "16:9", "4:3" to "4:3", "21:9" to "21:9"),
                selectedKey = when (aspectPreset) { PlayerAspectPreset.AUTO -> "auto"; PlayerAspectPreset.RATIO_16_9 -> "16:9"; PlayerAspectPreset.RATIO_4_3 -> "4:3"; PlayerAspectPreset.RATIO_21_9 -> "21:9" },
                onSelected = { key ->
                    if (key != "auto") { onResizeModeChange(PlayerResizeMode.FIT); onVideoZoomChange(1f) }
                    onAspectPresetChange(when (key) { "16:9" -> PlayerAspectPreset.RATIO_16_9; "4:3" -> PlayerAspectPreset.RATIO_4_3; "21:9" -> PlayerAspectPreset.RATIO_21_9; else -> PlayerAspectPreset.AUTO }) },
            )
        }
        if (aspectPreset == PlayerAspectPreset.AUTO) {
            item {
                PlayerDropUpSelector(
                    label = stringResource(R.string.player_zoom_level),
                    value = "${(videoZoom * 100).toInt()}%",
                    options = listOf("1.0" to "100%", "1.1" to "110%", "1.25" to "125%", "1.5" to "150%"),
                    selectedKey = when { kotlin.math.abs(videoZoom - 1.5f) < .01f -> "1.5"; kotlin.math.abs(videoZoom - 1.25f) < .01f -> "1.25"; kotlin.math.abs(videoZoom - 1.1f) < .01f -> "1.1"; else -> "1.0" },
                    onSelected = { onVideoZoomChange(it.toFloatOrNull() ?: 1f) },
                )
            }
        }
        if (allowSpeed) {
            item {
                PlayerDropUpSelector(
                    label = stringResource(R.string.player_speed),
                    value = if (speed == 1f) "1×" else "${speed}×",
                    options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { it.toString() to (if (it == 1f) "1×" else "${it}×") },
                    selectedKey = speed.toString(),
                    onSelected = { it.toFloatOrNull()?.let { v -> speed = v; player.setPlaybackSpeed(v) } },
                )
            }
        }
    }
}

@Composable
private fun tvhSleepTimerSummary(config: SleepTimerConfig): String = when (config.kind) {
    SleepTimerKind.OFF -> stringResource(R.string.player_sleep_off)
    SleepTimerKind.DURATION -> {
        val remaining = ((config.deadlineRealtimeMs ?: android.os.SystemClock.elapsedRealtime()) - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        stringResource(R.string.player_sleep_remaining, kotlin.math.max(1, ((remaining + 59_999L) / 60_000L).toInt()))
    }
    SleepTimerKind.END_OF_ITEM -> stringResource(R.string.player_sleep_end_item)
    SleepTimerKind.PAUSE_INACTIVITY -> stringResource(R.string.player_sleep_pause_summary, config.minutes)
    SleepTimerKind.USER_INACTIVITY -> stringResource(R.string.player_sleep_input_summary, config.minutes)
    SleepTimerKind.CLOCK_TIME -> stringResource(R.string.player_sleep_clock_summary, com.ambient.tv.ui.player.sleepClockLabel(config.alsoStopRealtimeMs))
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvhSidePanel(
    title: String,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    RightSideLazyPanel(
        title = title,
        onBack = onBack,
        width = 500.dp,
        onOutsideTap = com.ambient.tv.ui.common.LocalPlayerPanelNavigation.current?.let { nav -> { nav.close() } } ?: onBack,
        requestHeaderFocus = true,
        content = content,
    )
}

@Composable
private fun TvhSettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    PlayerPanelEntry(icon = icon, title = title, value = value, onClick = onClick, modifier = modifier)
}

@Composable
private fun TvhChoiceRow(
    title: String,
    detail: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    RightPanelChoice(text = title, subtitle = detail, selected = selected, onClick = onClick, modifier = modifier)
}

@Composable
private fun tvhVideoSummary(player: ExoPlayer, resizeMode: PlayerResizeMode, aspectPreset: PlayerAspectPreset, videoZoom: Float): String {
    val mode = when (resizeMode) {
        PlayerResizeMode.FIT -> stringResource(R.string.player_video_mode_fit)
        PlayerResizeMode.ZOOM -> stringResource(R.string.player_fill_screen)
        PlayerResizeMode.STRETCH -> stringResource(R.string.player_stretch)
    }
    val aspect = when (aspectPreset) {
        PlayerAspectPreset.AUTO -> stringResource(R.string.player_aspect_auto)
        PlayerAspectPreset.RATIO_16_9 -> "16:9"
        PlayerAspectPreset.RATIO_4_3 -> "4:3"
        PlayerAspectPreset.RATIO_21_9 -> "21:9"
    }
    val speed = player.playbackParameters.speed
    return buildString {
        append(mode); append(" · "); append(aspect)
        if (kotlin.math.abs(videoZoom - 1f) >= 0.01f) { append(" · "); append((videoZoom * 100).toInt()); append("%") }
        if (kotlin.math.abs(speed - 1f) >= 0.01f) { append(" · "); append(speed); append("×") }
    }
}


@Composable
private fun playbackBadges(video: Format?, audio: Format?) {
    val videoDecoder = remember(video) { RuntimeCodecSupport.assess(video) }
    val audioDecoder = remember(audio) { RuntimeCodecSupport.assess(audio) }
    com.ambient.tv.ui.player.PlayerVideoBadges(
        width = video?.width,
        height = video?.height,
        hdrLabel = HdrSupport.sourceStandard(video)?.label,
        hasVideo = video != null,
    )
    video?.sampleMimeType?.substringAfterLast('/')?.uppercase()?.let { MetaBadge(it) }
    audio?.sampleMimeType?.substringAfterLast('/')?.uppercase()?.let { MetaBadge(it) }
    if (video != null) MetaBadge(if (videoDecoder.hardwareAccelerated) "VIDEO HW" else "VIDEO SW")
    if (audio != null) MetaBadge(if (audioDecoder.hardwareAccelerated) "AUDIO HW" else "AUDIO SW")
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvhTrackPanel(
    title: String,
    options: List<TvhTrackOption>,
    includeOff: Boolean,
    onSelect: (TvhTrackOption?) -> Unit,
    onBack: () -> Unit,
) {
    RightSidePanel(
        title = title,
        onBack = onBack,
        width = 500.dp,
        onOutsideTap = com.ambient.tv.ui.common.LocalPlayerPanelNavigation.current?.let { nav -> { nav.close() } } ?: onBack,
        requestHeaderFocus = true,
        scrollable = false,
    ) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f).overlayLazyScrollbar(listState), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (includeOff) item { TvhTrackRow(stringResource(R.string.player_off), false, Modifier) { onSelect(null) } }
            if (options.isEmpty()) item { Text(stringResource(R.string.player_no_track), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(options) { option ->
                TvhTrackRow(
                    option.label,
                    option.selected,
                    Modifier,
                ) { onSelect(option) }
            }
        }
    }
}

@Composable
private fun TvhTrackRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    RightPanelChoice(text = label, selected = selected, onClick = onClick, modifier = modifier)
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvhPlaybackInfoPanel(
    source: com.ambient.tv.tvheadend.domain.TvhPlaybackSource,
    videoFormat: Format?,
    audioFormat: Format?,
    onBack: () -> Unit,
) {
    val videoDecoder = remember(videoFormat) { RuntimeCodecSupport.assess(videoFormat) }
    val audioDecoder = remember(audioFormat) { RuntimeCodecSupport.assess(audioFormat) }
    RightSidePanel(
        title = stringResource(R.string.player_playback_info),
        onBack = onBack,
        width = 520.dp,
        onOutsideTap = com.ambient.tv.ui.common.LocalPlayerPanelNavigation.current?.let { nav -> { nav.close() } } ?: onBack,
        requestHeaderFocus = true,
    ) {
        infoLine(stringResource(R.string.player_info_backend), "Media3 / ExoPlayer → MediaCodec → SurfaceView")
        infoLine(stringResource(R.string.player_info_tvh_profile), source.streamProfile ?: stringResource(R.string.player_info_default))
        infoLine(stringResource(R.string.player_video_label), formatDescription(videoFormat))
        infoLine(stringResource(R.string.player_info_video_decoder), decoderDescription(videoDecoder))
        infoLine(stringResource(R.string.player_audio), formatDescription(audioFormat))
        infoLine(stringResource(R.string.player_info_audio_decoder), decoderDescription(audioDecoder))
        videoFormat?.frameRate?.takeIf { it > 0f }?.let { infoLine(stringResource(R.string.player_info_frame_rate), stringResource(R.string.player_info_frame_rate_value, it)) }
    }
}

@Composable
private fun infoLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}

@Composable
private fun decoderDescription(value: com.ambient.tv.ui.player.RuntimeDecoderAssessment): String = when {
    !value.supported -> stringResource(R.string.player_info_no_decoder)
    value.hardwareAccelerated -> stringResource(R.string.player_info_hardware_decoder, value.decoderName ?: "MediaCodec")
    else -> stringResource(R.string.player_info_software_decoder, value.decoderName ?: "MediaCodec")
}

@Composable
private fun formatDescription(format: Format?): String {
    if (format == null) return stringResource(R.string.player_info_preparing)
    return buildList {
        format.sampleMimeType?.let(::add)
        if (format.width > 0 && format.height > 0) add("${format.width}×${format.height}")
        if (format.channelCount > 0) add(stringResource(R.string.player_info_channels, format.channelCount))
        if (format.sampleRate > 0) add("${format.sampleRate} Hz")
    }.joinToString(" · ").ifBlank { stringResource(R.string.player_info_unknown) }
}

private fun selectedFormat(tracks: Tracks, type: Int): Format? {
    tracks.groups.filter { it.type == type }.forEach { group ->
        for (i in 0 until group.length) if (group.isTrackSelected(i)) return group.getTrackFormat(i)
    }
    return null
}

private fun tvhTrackOptions(tracks: Tracks, type: Int, displayLocale: java.util.Locale = java.util.Locale.getDefault()): List<TvhTrackOption> {
    val result = mutableListOf<TvhTrackOption>()
    tracks.groups.filter { it.type == type }.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            val label = buildList {
                format.label?.takeIf { it.isNotBlank() }?.let {
                    add(if (type == C.TRACK_TYPE_AUDIO) com.ambient.tv.ui.player.expandAudioLanguageCodes(it, displayLocale) else it)
                }
                format.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
                    add(if (type == C.TRACK_TYPE_AUDIO)
                        com.ambient.tv.ui.player.audioLanguageDisplayName(it, displayLocale) ?: it
                    else it.uppercase())
                }
                format.sampleMimeType?.substringAfterLast('/')?.uppercase()?.let(::add)
                if (format.channelCount > 0) add("${format.channelCount}ch")
            }.distinct().joinToString(" · ").ifBlank { "Track ${result.size + 1}" }
            result += TvhTrackOption(
                label, format.language, group, i, group.isTrackSelected(i),
                format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
            )
        }
    }
    return result
}

private fun selectTvhTrack(player: ExoPlayer, type: Int, option: TvhTrackOption?) {
    val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
    if (option == null) {
        builder.setTrackTypeDisabled(type, true)
    } else {
        builder.setTrackTypeDisabled(type, false)
        builder.setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex))
    }
    player.trackSelectionParameters = builder.build()
}


private fun applyTvhTrackPreferences(
    player: ExoPlayer,
    tracks: Tracks,
    audioLanguage: String?,
    subtitleLanguage: String?,
    subtitlesEnabled: Boolean,
    forcedSubtitlesEnabled: Boolean,
): Boolean {
    val audioOptions = tvhTrackOptions(tracks, C.TRACK_TYPE_AUDIO)
    val audio = com.ambient.tv.ui.player.chooseMedia3AudioTrack(player, audioOptions, audioLanguage) { it.group.getTrackFormat(it.trackIndex) } ?: audioOptions.firstOrNull { LanguagePreferences.matches(it.language, audioLanguage) }
    if (audio != null) selectTvhTrack(player, C.TRACK_TYPE_AUDIO, audio)
    return audio != null
}
