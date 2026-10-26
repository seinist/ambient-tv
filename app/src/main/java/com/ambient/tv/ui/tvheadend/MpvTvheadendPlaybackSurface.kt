package com.ambient.tv.ui.tvheadend

import com.ambient.tv.ui.common.projectm.LocalProjectMBrowser
import com.ambient.tv.ui.common.projectm.rememberProjectMBrowser
import com.ambient.tv.ui.common.projectm.ProjectMAvailability
import com.ambient.tv.ui.common.projectm.NativeProjectMVisualizer

import com.ambient.tv.ui.player.PlayerPanelVisibility
import com.ambient.tv.ui.player.PlayerOverlayVisibility
import com.ambient.tv.ui.common.rememberPlayerPanelNavigation
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.tvheadend.domain.TvhChannel
import com.ambient.tv.tvheadend.domain.TvhEpgEvent
import com.ambient.tv.tvheadend.domain.TvhPlaybackSource
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.PlayerTouchLayer
import com.ambient.tv.ui.player.PlayerBufferingSpinner
import com.ambient.tv.ui.player.PlayerSeekModeBar
import com.ambient.tv.ui.player.waitingForPlaybackPicture
import com.ambient.tv.ui.common.UnifiedBackendPlayerPanel
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.PLAYER_CONTROLS_TIMEOUT_MS
import com.ambient.tv.ui.player.TvPlayerSeekBar
import com.ambient.tv.ui.player.RequestPlayerOverlayFocus
import com.ambient.tv.ui.player.SleepTimerConfig
import com.ambient.tv.ui.player.SleepTimerKind
import com.ambient.tv.ui.player.AudioPassthroughPolicy
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.LocalPersistentPlaybackVideoHost
import com.ambient.tv.ui.player.backend.MpvVideoSurface
import com.ambient.tv.ui.player.backend.rememberMpvPlaybackController
import com.ambient.tv.ui.player.backend.rememberMpvPlaybackSnapshot
import com.ambient.tv.ui.player.PlaybackFpsOverlay
import com.ambient.tv.ui.player.PlaybackPerformanceNotice
import com.ambient.tv.ui.player.PlaybackNerdStatsOverlay
import com.ambient.tv.ui.player.playbackOverlayFps
import com.ambient.tv.ui.player.rememberDisplayRefreshHz
import com.ambient.tv.ui.player.rememberMpvNerdOverlayModel
import com.ambient.tv.ui.player.mpvLoadHint
import com.ambient.tv.ui.player.formatMpvDuration
import com.ambient.tv.ui.player.formatNerdBitrate
import com.ambient.tv.ui.player.formatNerdByteRate
import com.ambient.tv.ui.player.formatNerdSeconds
import com.ambient.tv.ui.player.formatNerdFps
import com.ambient.tv.ui.player.formatNerdHz
import com.ambient.tv.ui.player.formatMpvScalerName
import com.ambient.tv.ui.player.formatMpvUpscalerForStats
import com.ambient.tv.ui.player.formatMpvScalerStat
import com.ambient.tv.ui.player.formatMpvDebandStat
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.ambient.tv.ui.player.handlePlayerOsdKey
import com.ambient.tv.ui.player.OsdKeyState
import com.ambient.tv.ui.player.OsdContent
import com.ambient.tv.ui.player.OsdSeekSteps
import com.ambient.tv.ui.player.OsdKeyTarget

@Composable
internal fun MpvTvheadendPlaybackSurface(
    source: TvhPlaybackSource,
    channel: TvhChannel?,
    imageHeaders: Map<String, String>,
    panelArtworkUrl: String?,
    panelArtworkFallbackUrls: List<String>,
    nowEvent: TvhEpgEvent?,
    nextEvent: TvhEpgEvent?,
    onBack: () -> Unit,
    onZap: (Int) -> Unit,
    onPlaybackError: (String) -> Unit,
    initialDeinterlaceMode: String,
    initialDeinterlaceFilter: String,
    onDeinterlaceChanged: (String, String) -> Unit,
    seekJumpMs: Long,
    rendererKey: String,
    onRendererSelected: (String, Long, Boolean) -> Unit,
    initialVideoOutputMode: String,
    audioVisualizerModeKey: String,
    audioPassthroughEnabled: Boolean,
    preferredAudioLanguage: String?,
    forcedSubtitlesEnabled: Boolean,
    onVideoFormatChanged: (Float, Int?, Int?) -> Unit,
    onSourceHdrChanged: (com.ambient.tv.ui.player.HdrStandard?) -> Unit,
) {
    val context = LocalContext.current
    val controller = rememberMpvPlaybackController(
        context, source.url, source.startPositionMs, source.headers, audioPassthroughEnabled,
        initialVideoOutputMode,
    )
    if (controller == null) {
        val message = stringResource(R.string.player_mpv_init_failed)
        LaunchedEffect(Unit) { onPlaybackError(message) }
        return
    }
    val playbackKey = com.ambient.tv.ui.player.LocalPlaybackMetadata.current?.route ?: source.url
    val panelNavigation = rememberPlayerPanelNavigation(playbackKey)
    var panel by panelNavigation
    // Row key of the video panel's Player area, focused when Back leaves that area.
    val rendererEntryTitle = stringResource(R.string.player_renderer)
    // Back is owned by the dispatcher, so nested panels/popups close one level per press.
    val snapshot = rememberMpvPlaybackSnapshot(
        controller,
        controller.audioSession.performanceHint || controller.audioSession.showFpsOverlay,
    )
    val hdrChanged by rememberUpdatedState(onSourceHdrChanged)
    LaunchedEffect(controller, controller.sourceHdrStandard) {
        controller.sourceHdrStandard?.let(hdrChanged)
    }
    // Live TV and TVHeadend recordings are broadcast: a 576i recording deinterlaces to 50 Hz too.
    val liveSource = com.ambient.tv.ui.player.LocalPlaybackMetadata.current
        ?.let { it.isLive || it.liveTvSourceId != null } == true
    LaunchedEffect(controller, snapshot.videoSize, snapshot.containerFps, controller.deinterlaceModeKey, controller.deinterlaceFilterKey, liveSource, controller.shieldSoftwareStatus) {
        val dimensions = com.ambient.tv.ui.player.DisplayRefreshRateMatcher.parseVideoDimensions(snapshot.videoSize)
        // Match the deinterlaced cadence: 25 fps double-rate → 50 Hz; field-rate 50 stays 50 Hz.
        val fps = (if (liveSource) com.ambient.tv.ui.player.backend.mpvLiveDisplayFps(
            snapshot.containerFps, controller.supportsGpuFilters, controller.deinterlaceModeKey, controller.deinterlaceFilterKey,
            shieldSoftware = controller.shieldSoftwareStatus != null,
        ) else snapshot.containerFps)?.toFloat() ?: 0f
        if (fps > 0f || dimensions != null) onVideoFormatChanged(fps, dimensions?.first, dimensions?.second)
    }
    com.ambient.tv.ui.player.MpvRenderOverloadDialog(controller, panelNavigation) { panelNavigation.openVideoSettings() }
    val persistentVideoHost = LocalPersistentPlaybackVideoHost.current
    val projectMBrowser = rememberProjectMBrowser(com.ambient.tv.ui.player.videoImagePreferences(context).projectmPresetCategoryKey)
    val audioOnlyContent = controller.audioOnly || snapshot.confirmedAudioOnly
    val showProjectM = audioOnlyContent && audioVisualizerModeKey == "projectm" && ProjectMAvailability.isAvailable
    LaunchedEffect(controller, audioOnlyContent) {
        if (audioOnlyContent) controller.audioSession.updateAudioOnly(true)
    }
    var controlsVisible by com.ambient.tv.ui.player.rememberPlayerControlsVisible()
    val chromeState = com.ambient.tv.ui.player.LocalPlayerChromeState.current
    val changingItem = com.ambient.tv.ui.player.LocalPlayerChangingItem.current
    // Back hides the OSD, then stops - radio included. Only the exit-fullscreen button keeps
    // playing in the background; on a handheld, leaving an audio-only screen still does.
    val handheldUi = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    fun exitOnBack() {
        if (com.ambient.tv.ui.player.backLeavesPlaybackRunning(snapshot.confirmedAudioOnly, handheldUi)) onBack()
        else BackgroundPlaybackSession.stop()
    }
    androidx.activity.compose.BackHandler(enabled = panel == UnifiedPlayerPanel.NONE) {
        // Handheld: swipe Back leaves the player instead of only hiding the OSD.
        if (controlsVisible && !handheldUi) controlsVisible = false else exitOnBack()
    }
    fun heldSeekStep(baseMs: Long, event: androidx.compose.ui.input.key.KeyEvent) =
        com.ambient.tv.ui.player.acceleratedSeekStepMs(baseMs, event.nativeKeyEvent.repeatCount, snapshot.durationMs)
    var seekFeedbackVersion by remember(controller) { mutableStateOf(0) }
    var seekFeedbackVisible by remember(controller) { mutableStateOf(false) }
    var resizeKey by remember(playbackKey) { mutableStateOf("fit") }
    var aspectKey by remember(playbackKey) { mutableStateOf("auto") }
    var zoomFactor by remember(playbackKey) { mutableStateOf(1f) }
    var speed by remember(playbackKey) { mutableStateOf(1f) }
    LaunchedEffect(controller.bitstreamPermitted) { if (controller.bitstreamPermitted) speed = 1f }
    val performance = LocalPerformanceProfile.current
    LaunchedEffect(controller, initialDeinterlaceMode, initialDeinterlaceFilter) {
        com.ambient.tv.ui.player.AutomaticVideoProfiles.state(controller).deinterlace.apply(
            controller.deinterlaceModeKey to controller.deinterlaceFilterKey,
            initialDeinterlaceMode to initialDeinterlaceFilter,
        ) {
            controller.setDeinterlace(it.first != "off", it.second, it.first == "auto")
            controller.deinterlaceModeKey to controller.deinterlaceFilterKey
        }
    }
    var appliedVideoVisualGate by remember(controller) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(performance.gateExpensiveVideoVisuals, controller) {
        val gate = performance.gateExpensiveVideoVisuals
        if (appliedVideoVisualGate == null) {
            appliedVideoVisualGate = gate
            return@LaunchedEffect
        }
        if (appliedVideoVisualGate == gate) return@LaunchedEffect
        appliedVideoVisualGate = gate
        controller.updateImageOptions(controller.imageOptions)
        if (controller.deinterlaceModeKey != "off") controller.setDeinterlace(true, controller.deinterlaceFilterKey, controller.deinterlaceModeKey == "auto")
    }
    var sleepTimer by com.ambient.tv.ui.player.rememberPlaybackSleepTimer(controller)
    var selectedAudio by remember(playbackKey) { mutableStateOf(controller.audioTracks().firstOrNull { it.selected }?.id?.toString() ?: "auto") }
    val selectedSubtitle = controller.subtitleTracks().firstOrNull { it.selected }?.id?.toString() ?: "off"
    val playFocusRequester = com.ambient.tv.ui.player.rememberPlayerPlayFocus()
    val audioOptions = controller.audioTracks().map { it.id.toString() to it.label }.ifEmpty { listOf("auto" to stringResource(R.string.player_default_track)) }
    val subtitleOptions = listOf("off" to stringResource(R.string.player_subtitles_off)) + controller.subtitleTracks().map { it.id.toString() to it.label }

    LaunchedEffect(controller, preferredAudioLanguage, forcedSubtitlesEnabled) {
        if (controller.restoredOutputAudioSelection) return@LaunchedEffect
        repeat(24) { attempt ->
            if (attempt > 0) delay(250)
            val audioTracks = controller.audioTracks()
            if (audioTracks.isEmpty()) return@repeat
            val preferred = controller.preferredAudioTrack(preferredAudioLanguage) ?: audioTracks.firstOrNull { LanguagePreferences.matches(it.language, preferredAudioLanguage) }
            if (preferred == null) return@repeat
            if (!preferred.selected) controller.selectAudio(preferred.id)
            return@LaunchedEffect
        }
    }

    LaunchedEffect(controller.errorMessage) {
        val message = controller.errorMessage ?: return@LaunchedEffect
        if (!BackgroundPlaybackSession.owns(controller)) return@LaunchedEffect
        onPlaybackError(message)
    }
    RequestPlayerOverlayFocus(
        requester = playFocusRequester,
        playbackIdentity = source.url,
        enabled = controlsVisible && panel == UnifiedPlayerPanel.NONE,
        playbackStarted = snapshot.isPlaying,
        panelNavigation = panelNavigation,
    )
    LaunchedEffect(seekFeedbackVersion) { if (seekFeedbackVersion > 0) { seekFeedbackVisible = true; delay(PLAYER_CONTROLS_TIMEOUT_MS); seekFeedbackVisible = false } }
    com.ambient.tv.ui.player.PlayerControlsAutoHide(
        controlsVisible, snapshot.isPlaying, panel != UnifiedPlayerPanel.NONE, changingItem,
    ) { controlsVisible = it }

    // One remote handling for the engine and, while the focus is in the OSD or panel above it, the host.
    val chapterNavigation = com.ambient.tv.ui.player.LocalPlaybackNavigation.current
    val osdKeys: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = osdKeys@{ event ->
        // Timeshift owns Rewind/Forward while it buffers a live channel; media keys act with a panel open too.
        if (source.isLive && handleTimeshiftMediaKey(event, snapshot.positionMs, seekJumpMs) { controlsVisible = true }) return@osdKeys true
        // Shared remote handling (PlayerOsdKeys); only the actions below are MPV's own.
        handlePlayerOsdKey(
            event.key, event.type == KeyEventType.KeyDown,
            OsdKeyState(
                content = if (source.isLive) OsdContent.LIVE else OsdContent.RECORDING,
                osdVisible = controlsVisible,
                panelOpen = panel != UnifiedPlayerPanel.NONE,
                seekFeedbackVisible = seekFeedbackVisible,
                zapOsd = chromeState?.shownByZap?.value == true,
                canSeekForward = true,
            ),
            OsdSeekSteps(seekJumpMs, held = { heldSeekStep(it, event) }),
            object : OsdKeyTarget {
                override fun chapterSeekDelta(direction: Int) = chapterNavigation?.timeline?.chapterStepDelta(controller.positionMs(), direction)
                override fun isPlaying() = snapshot.isPlaying
                override fun togglePlayback() { controller.playPause() }
                override fun play() { controller.play() }
                override fun pause() { controller.pause() }
                override fun stop() { BackgroundPlaybackSession.stop() }
                override fun seek(deltaMs: Long, revealOsd: Boolean) {
                    controller.seekBy(deltaMs)
                    if (revealOsd) controlsVisible = true else seekFeedbackVersion++
                }
                override fun showOsd() { controlsVisible = true }
                override fun hideSeekFeedback() { seekFeedbackVisible = false }
                override fun restart() { controller.seekTo(0L) }
                override fun next() = Unit
                override fun previousTrack() = Unit
                override fun zap(delta: Int) { onZap(delta) }
            },
        )
    }
    com.ambient.tv.ui.player.PublishPlayerPanelKeys(osdKeys)
    CompositionLocalProvider(LocalProjectMBrowser provides projectMBrowser, LocalPlayerPanelNavigation provides panelNavigation) {
    Box(
        Modifier.fillMaxSize().background(if (persistentVideoHost) Color.Transparent else Color.Black).onPreviewKeyEvent(osdKeys).focusRequester(com.ambient.tv.ui.player.rememberPlayerEngineFocus()).focusable(),
    ) {
        if (!persistentVideoHost && !audioOnlyContent) MpvVideoSurface(controller, Modifier.fillMaxSize())
        if (showProjectM) {
            androidx.compose.runtime.key(controller) {
                NativeProjectMVisualizer(projectMBrowser, 2, controlsVisible, Modifier.fillMaxSize())
            }
        }
        PlayerBufferingSpinner(
            visible = waitingForPlaybackPicture(snapshot.wantsToPlay, snapshot.isPlaying),
            modifier = Modifier.align(Alignment.Center),
        )
        if (!controlsVisible && !source.isLive && seekFeedbackVisible) PlayerSeekModeBar(snapshot.positionMs, snapshot.durationMs, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
        PlayerTouchLayer(
            onToggleControls = { if (panel == UnifiedPlayerPanel.NONE) controlsVisible = !controlsVisible },
            onTogglePlayback = { controller.playPause(); controlsVisible = true },
            onSeekBack = if (source.isLive) null else ({ controller.seekBy(-10_000); controlsVisible = true }),
            onSeekForward = if (source.isLive) null else ({ controller.seekBy(30_000); controlsVisible = true }),
        )
        PlaybackPerformanceNotice(mpvLoadHint(controller, snapshot, controller.deinterlaceModeKey != "off"))
        val showNerdOverlay = controller.audioSession.performanceHint
        val showFpsOverlay = controller.audioSession.showFpsOverlay
        val displayRefreshHz = rememberDisplayRefreshHz()
        PlaybackNerdStatsOverlay(
            audioConversion = controller.audioSession.activeLocalConversion?.let { "${stringResource(com.ambient.tv.R.string.audio_conversion_local)} · $it" },
            enabled = showNerdOverlay,
            model = rememberMpvNerdOverlayModel(
                snapshot = snapshot,
                enabled = showNerdOverlay,
                audioOutputMode = controller.audioSession.outputPlan().mode,
            ),
        )
        PlaybackFpsOverlay(
            enabled = showFpsOverlay,
            fps = playbackOverlayFps(snapshot.fps, snapshot.containerFps),
            svpStatus = snapshot.svpStatus,
            sourceFps = snapshot.containerFps,
            refreshHz = snapshot.displayFps ?: displayRefreshHz,
            nerdStatsVisible = showNerdOverlay,
        )
        // Drawn by PersistentPlayerOverlays above the engine, so it survives a renderer switch.
        com.ambient.tv.ui.player.PublishPlayerOsd(LiveOsdModel(
            source = source,
            channel = channel,
            imageHeaders = imageHeaders,
            artworkUrl = panelArtworkUrl,
            artworkFallbackUrls = panelArtworkFallbackUrls,
            nowEvent = nowEvent,
            nextEvent = nextEvent,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            playing = snapshot.isPlaying,
            loading = waitingForPlaybackPicture(snapshot.wantsToPlay, snapshot.isPlaying),
            backendLabel = "MPV",
            mediaBadges = listOfNotNull(
                controller.sourceHdrStandard?.label,
                snapshot.videoSize,
                formatNerdFps(snapshot.containerFps),
                snapshot.videoCodec,
                snapshot.audioCodec,
                snapshot.decoder,
            ),
            seekJumpMs = seekJumpMs,
            svpBadge = com.ambient.tv.ui.player.backend.svpBadgeLabel(snapshot.svpState),
            audioBadge = com.ambient.tv.ui.player.audioOutputBadge(controller.audioSession),
            onTogglePlayback = controller::playPause,
            onSeekBy = { delta -> controller.seekBy(delta); controlsVisible = true },
            onZap = onZap,
            onStop = BackgroundPlaybackSession::stop,
            onBack = onBack,
            onOpenAudio = { panel = UnifiedPlayerPanel.AUDIO },
            onOpenSubtitles = { panel = UnifiedPlayerPanel.SUBTITLES },
            onOpenRenderer = { panelNavigation.openVideoSection("renderer", "entry:" + rendererEntryTitle) },
            onOpenSleep = { panel = UnifiedPlayerPanel.SLEEP },
            onOpenSettings = { panel = UnifiedPlayerPanel.SETTINGS },
            audioSelected = panel.isAudio,
            subtitlesSelected = panel == UnifiedPlayerPanel.SUBTITLES,
            rendererSelected = panel.isVideo,
            sleepSelected = panel == UnifiedPlayerPanel.SLEEP,
            settingsSelected = panel != UnifiedPlayerPanel.NONE,
        ), visible = controlsVisible)

        // Drawn by PersistentPlayerOverlays above the engine, so it survives a renderer switch.
        // Its content is only built with the OSD or a panel on screen, as when it was drawn here.
        if (controlsVisible || panel != UnifiedPlayerPanel.NONE) {
            com.ambient.tv.ui.player.PublishPlayerPanel(com.ambient.tv.ui.player.PlayerPanelModel(
                scrimAlpha = 0.32f,
                projectMBrowser = LocalProjectMBrowser.current,
                audioSession = controller.audioSession,
                showVisualizer = showProjectM,
                hasVideo = com.ambient.tv.ui.player.mpvPanelHasVideo(snapshot.videoCodec, audioOnlyContent),
                isLive = source.isLive,
                scalingKey = resizeKey,
                aspectKey = aspectKey,
                zoomFactor = zoomFactor,
                speed = speed,
                onScalingChanged = { resizeKey = it; controller.setVideoPresentation(resizeKey, if (aspectKey == "auto") null else aspectKey, zoomFactor) },
                onAspectChanged = { aspectKey = it; controller.setVideoPresentation(resizeKey, if (aspectKey == "auto") null else aspectKey, zoomFactor) },
                onZoomChanged = { zoomFactor = it; controller.setVideoPresentation(resizeKey, if (aspectKey == "auto") null else aspectKey, zoomFactor) },
                onSpeedChanged = if (source.isLive || controller.bitstreamPermitted) null else ({ v -> speed = v; controller.setSpeed(v) }),
                audioOptions = audioOptions,
                selectedAudio = selectedAudio,
                onAudioSelected = { selectedAudio = it; controller.selectAudio(it.toIntOrNull()) },
                subtitleOptions = subtitleOptions,
                selectedSubtitle = selectedSubtitle,
                onSubtitleSelected = { controller.selectSubtitle(it.toIntOrNull()) },
                sleepTimer = sleepTimer,
                onSleepChanged = { sleepTimer = it },
                rendererKey = rendererKey,
                onRendererSelected = { key, global -> onRendererSelected(key, snapshot.positionMs, global) },
                infoLines = buildList {
                    add(stringResource(R.string.player_nerd_backend) to "MPV")
                    add(stringResource(R.string.player_playback_info) to (source.streamProfile ?: if (source.isLive) "Live TV" else "Recording"))
                    add(stringResource(R.string.player_info_video_output) to com.ambient.tv.ui.player.DirectAvDiagnostics.video(context, snapshot.videoCodec, null, null, null, com.ambient.tv.ui.player.rendererTransitionValue(if (controller.supportsGpuFilters) "MPV GPU" else "MPV Direct", controller.engineConfirmed, stringResource(R.string.player_nerd_switching))))
                    snapshot.videoOutput?.let { add(stringResource(R.string.player_nerd_vo) to it) }
                    snapshot.audioOutputDriver?.let { add(stringResource(R.string.player_nerd_ao) to it) }
                    listOfNotNull(snapshot.videoSize, formatNerdFps(snapshot.fps), snapshot.videoCodec?.uppercase()).joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_video) to it) }
                    snapshot.decoder?.let { add(stringResource(R.string.player_nerd_hardware_decode) to it) }
                    snapshot.pixelFormat?.let { add(stringResource(R.string.player_nerd_pixel_format) to it) }
                    snapshot.outputPixelFormat?.takeIf { it != snapshot.pixelFormat }?.let { add(stringResource(R.string.player_nerd_output_pixel_format) to it) }
                    formatNerdBitrate(snapshot.videoBitrateBitsPerSecond)?.let { add(stringResource(R.string.player_nerd_video_bitrate) to it) }
                    formatNerdHz(snapshot.displayFps)?.let { add(stringResource(R.string.player_nerd_display_refresh) to it) }
                    snapshot.frameDropCount?.let { add(stringResource(R.string.player_nerd_dropped_frames) to it.toString()) }
                    snapshot.decoderFrameDropCount?.takeIf { it > 0 }?.let { add(stringResource(R.string.player_nerd_decoder_drops) to it.toString()) }
                    formatNerdSeconds(snapshot.avSyncSeconds)?.let { add(stringResource(R.string.player_nerd_av_sync) to it) }
                    formatNerdSeconds(snapshot.cacheDurationSeconds)?.let { add(stringResource(R.string.player_nerd_buffer_health) to it) }
                    formatNerdByteRate(snapshot.cacheSpeedBytesPerSecond)?.let { add(stringResource(R.string.player_nerd_cache_speed) to it) }
                    if (controller.supportsGpuFilters) {
                        formatMpvUpscalerForStats(controller.imageOptions.scaler, snapshot.activeScaler, snapshot.activeShaderPreset, snapshot.activeUpscaling, sparsam = snapshot.sparsam)?.let { add(stringResource(R.string.player_nerd_upscaler) to it) }
                        formatMpvScalerStat(snapshot.activeChromaScaler, controller.imageOptions.chromaScaler, snapshot.sparsam)?.let { add(stringResource(R.string.player_nerd_chroma_scaler) to it) }
                        formatMpvScalerStat(snapshot.activeDownscaler, controller.imageOptions.downscaler, snapshot.sparsam)?.let { add(stringResource(R.string.player_nerd_downscaler) to it) }
                        formatMpvDebandStat(controller.imageOptions.deband, snapshot.sparsam, "Enabled")?.let { add(stringResource(R.string.player_nerd_deband_configured) to it) }
                        if (snapshot.sparsam) add(stringResource(R.string.player_nerd_sparsam) to stringResource(R.string.settings_performance_low))
                    }
                    if (controller.deinterlaceModeKey != "off") add(stringResource(R.string.player_nerd_deinterlace_configured) to listOf(controller.deinterlaceModeKey, controller.deinterlaceFilterKey).filter { it.isNotBlank() && it != "auto" }.joinToString(" · ").ifBlank { controller.deinterlaceModeKey })
                    snapshot.activeVideoFilters?.let { add(stringResource(R.string.player_nerd_video_filters) to it) }
                    controller.loadedImageShaders().takeIf { it.isNotEmpty() }?.let { add("Shaders (loaded; conditional)" to it.joinToString()) }
                    add(stringResource(R.string.player_info_audio_output) to com.ambient.tv.ui.player.DirectAvDiagnostics.audio(context, controller.bitstreamPermitted, snapshot.audioCodec, snapshot.audioOutputFormat))
                    snapshot.audioCodec?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_audio) to it.uppercase()) }
                    formatNerdBitrate(snapshot.audioBitrateBitsPerSecond)?.let { add(stringResource(R.string.player_nerd_audio_bitrate) to it) }
                },
                settingsExtras = { JumpToLivePanelEntry(source.isLive, snapshot.positionMs) },
                onDeinterlaceChanged = onDeinterlaceChanged,
            ))
        }
    }
    }
}

private fun clock(epochSeconds: Long): String = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
