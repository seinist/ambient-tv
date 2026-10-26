package com.ambient.tv.ui.player

import com.ambient.tv.ui.common.projectm.LocalProjectMBrowser
import com.ambient.tv.ui.common.projectm.rememberProjectMBrowser
import com.ambient.tv.ui.common.projectm.ProjectMAvailability
import com.ambient.tv.ui.common.projectm.NativeProjectMVisualizer

import com.ambient.tv.ui.performance.LocalPerformanceProfile

import com.ambient.tv.ui.common.rememberPlayerPanelNavigation
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.PlayerTouchLayer
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerExitButton
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.PlayerSleepTimerButton
import com.ambient.tv.ui.common.PLAYER_CONTROLS_TIMEOUT_MS
import com.ambient.tv.ui.common.UnifiedBackendPlayerPanel
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.formatHdrMetadata
import com.ambient.tv.ui.player.backend.MpvVideoSurface
import com.ambient.tv.ui.player.backend.rememberMpvPlaybackController
import com.ambient.tv.ui.player.backend.rememberMpvPlaybackSnapshot
import kotlinx.coroutines.delay

@Composable
internal fun MpvPlayerContent(
    state: PlayerUiState.Ready,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onPlayNext: (String) -> Unit,
    onVideoFormatChanged: (Float, Int?, Int?) -> Unit,
) {
    val context = LocalContext.current
    val controller = rememberMpvPlaybackController(
        context, state.streamUrl, state.startPositionMs, state.streamHeaders, state.audioPassthroughEnabled,
        viewModel.mpvVideoOutputMode(),
        knownAudioCodec = state.audioCodec,
    )
    if (controller == null) {
        val message = stringResource(R.string.player_mpv_init_failed)
        LaunchedEffect(Unit) {
            if (viewModel.acceptsPlaybackEvents("mpv")) viewModel.reportPlaybackError(message)
        }
        return
    }
    val playbackKey = com.ambient.tv.ui.player.LocalPlaybackMetadata.current?.route ?: state.streamUrl
    val panelNavigation = rememberPlayerPanelNavigation(playbackKey)
    var panel by panelNavigation
    // Back is owned by the dispatcher, so nested panels/popups close one level per press.
    val snapshot = rememberMpvPlaybackSnapshot(
        controller,
        controller.audioSession.performanceHint || controller.audioSession.showFpsOverlay,
    )
    LaunchedEffect(controller, controller.sourceHdrStandard) {
        controller.sourceHdrStandard?.let(viewModel::applySourceHdrFromRuntime)
    }
    LaunchedEffect(controller, snapshot.videoSize, snapshot.containerFps) {
        val dimensions = com.ambient.tv.ui.player.DisplayRefreshRateMatcher.parseVideoDimensions(snapshot.videoSize)
        val fps = snapshot.containerFps?.toFloat() ?: 0f
        if (fps > 0f || dimensions != null) {
            onVideoFormatChanged(fps, dimensions?.first, dimensions?.second)
            if (dimensions != null) viewModel.applyResolutionProfileFromRuntime(
                dimensions.first, dimensions.second, controller.positionMs(),
            )
        }
    }
    val isAudioItem = state.mediaType.equals("track", true) || state.mediaType.equals("audio", true)
    var controlsVisible by com.ambient.tv.ui.player.rememberPlayerControlsVisible()
    val changingItem = com.ambient.tv.ui.player.LocalPlayerChangingItem.current
    var seekFeedbackVisible by remember(controller) { mutableStateOf(false) }
    val handheldUi = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    // Back stops; only the exit-fullscreen button keeps playback in the background (TV).
    fun exitOnBack() {
        if (com.ambient.tv.ui.player.backLeavesPlaybackRunning(isAudioItem, handheldUi)) onBack()
        else BackgroundPlaybackSession.stop()
    }
    androidx.activity.compose.BackHandler(enabled = panel == UnifiedPlayerPanel.NONE) {
        // Handheld: swipe Back leaves the player instead of only hiding the OSD.
        if (controlsVisible && !handheldUi) controlsVisible = false else exitOnBack()
    }
    val timelineDurationMs = state.durationMs ?: snapshot.durationMs.takeIf { it > 0L }?.plus(state.timelineOffsetMs) ?: 0L
    val performance = LocalPerformanceProfile.current
    val chromeVisible = controlsVisible || seekFeedbackVisible || panel != UnifiedPlayerPanel.NONE
    val segmentNavigation = LocalPlaybackNavigation.current
    androidx.compose.runtime.SideEffect {
        segmentNavigation?.chromeVisible = chromeVisible
        segmentNavigation?.skipOverlayBlocked = panel != UnifiedPlayerPanel.NONE
        segmentNavigation?.revealControls = { controlsVisible = true }
        PlayerVideoChrome.immersive = !chromeVisible
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { PlayerVideoChrome.immersive = false }
    }
    val seek = rememberPlaybackSeekState(
        key = controller,
        enginePositionMs = { controller.positionMs() + state.timelineOffsetMs },
        commit = viewModel::seekTimeline,
        pollEnabled = !performance.pollOsdPositionOnlyWhenChromeVisible || chromeVisible,
    )
    fun seekBy(deltaMs: Long) = seek.seekBy(deltaMs, timelineDurationMs)
    val seekAcceleration = remember(seek) { SeekKeyAcceleration() }
    fun heldSeekStep(baseMs: Long, event: androidx.compose.ui.input.key.KeyEvent) =
        seekAcceleration.step(baseMs, event.nativeKeyEvent.keyCode, event.nativeKeyEvent.eventTime, event.nativeKeyEvent.repeatCount, timelineDurationMs)
    var seekFeedbackVersion by remember(controller) { mutableStateOf(0) }
    LaunchedEffect(controller) {
        controller.setDeinterlace(controller.deinterlaceModeKey != "off", controller.deinterlaceFilterKey, controller.deinterlaceModeKey == "auto")
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
    val initialVideoPresentation = remember(controller) { BackgroundPlaybackSession.videoPresentation.value }
    var aspectKey by remember(playbackKey) { mutableStateOf(initialVideoPresentation.aspectKey()) }
    var zoomFactor by remember(playbackKey) { mutableStateOf(initialVideoPresentation.zoom) }
    var resizeKey by remember(playbackKey) { mutableStateOf(initialVideoPresentation.resizeKey()) }
    var sleepTimer by com.ambient.tv.ui.player.rememberPlaybackSleepTimer(controller)
    DisposableEffect(controller) {
        viewModel.restoreReloadedAudio(controller.audioSession)
        controller.audioSession.conversionLocationChanged = { viewModel.audioConversionLocationChanged(it, controller.positionMs()) }
        onDispose { controller.audioSession.conversionLocationChanged = { false } }
    }
    // Read from the player: automatic selection and renderer restores change these without the panel.
    val selectedAudio = controller.audioTracks().firstOrNull { it.selected }?.id?.toString() ?: "auto"
    val speed = controller.currentSpeed()
    val selectedSubtitle = controller.subtitleTracks().firstOrNull { it.selected }?.id?.toString() ?: "off"
    val playFocusRequester = com.ambient.tv.ui.player.rememberPlayerPlayFocus()
    val persistentVideoHost = LocalPersistentPlaybackVideoHost.current
    val projectMBrowser = rememberProjectMBrowser(state.projectmPresetCategoryKey)
    val audioOnlyContent = audioOnlyPlaybackHint(state.sourceRatingKey, state.mediaType, state.videoCodec) || snapshot.confirmedAudioOnly
    val showProjectM = audioOnlyContent && state.audioVisualizerModeKey == "projectm" && ProjectMAvailability.isAvailable
    LaunchedEffect(controller, audioOnlyContent) {
        if (audioOnlyContent) controller.audioSession.updateAudioOnly(true)
    }

    val audioOptions = state.audioTracks.takeIf { state.serverAudioStreamId != null || viewModel.serverAudioSelectionEnabled() }?.mapNotNull { track ->
        track.id?.let { it.toString() to audioTrackLabel(track.title, track.language, track.channels, track.codec) }
    } ?: controller.audioTracks().map { it.id.toString() to it.label }.ifEmpty { listOf("auto" to stringResource(R.string.player_default_track)) }
    val subtitleOptions = listOf("off" to stringResource(R.string.player_subtitles_off)) + controller.subtitleTracks().map { it.id.toString() to it.label }

    LaunchedEffect(
        controller, state.preferredAudioLanguage, state.preferredSubtitleLanguage,
        state.subtitlesEnabled, state.forcedSubtitlesEnabled,
    ) {
        if (controller.restoredOutputAudioSelection) return@LaunchedEffect
        repeat(24) { attempt ->
            if (attempt > 0) delay(250)
            val audioTracks = controller.audioTracks()
            if (audioTracks.isEmpty()) return@repeat
            val preferredAudio = controller.preferredAudioTrack(state.preferredAudioLanguage) ?: audioTracks.firstOrNull { LanguagePreferences.matches(it.language, state.preferredAudioLanguage) }
            if (preferredAudio == null) return@repeat
            if (!preferredAudio.selected) controller.selectAudio(preferredAudio.id)
            return@LaunchedEffect
        }
    }

    RequestPlayerOverlayFocus(
        requester = playFocusRequester,
        enabled = controlsVisible && panel == UnifiedPlayerPanel.NONE,
        playbackStarted = snapshot.isPlaying,
        panelNavigation = panelNavigation,
    )
    LaunchedEffect(controller) {
        var lastPositionMs = controller.positionMs()
        var stalledForMs = 0L
        while (true) {
            delay(2_000)
            val positionMs = controller.positionMs()
            // Slow networks (WAN, WireGuard, 4K over Wi-Fi) only rebuffer; another renderer would
            // not help. Count only stalls where mpv has data it does not consume.
            stalledForMs = if (
                mpvStallCounts(controller.wantsToPlay(), positionMs, lastPositionMs, controller.durationMs(),
                    controller.ended, controller.waitingForData(), viewModel.mpvExplicitlyRequested())
            ) {
                stalledForMs + 2_000L
            } else {
                0L
            }
            lastPositionMs = positionMs
            if (stalledForMs >= 12_000L) {
                // Some SHIELD/libmpv MediaCodec combinations stop draining after a seek without
                // reporting an error. Recover through the normal renderer hand-off instead of
                // leaving a frozen frame (or overlapping two decoder instances).
                viewModel.switchPlaybackBackend("vlc", positionMs, makeGlobal = false, persistForTitle = false)
                break
            }
        }
    }
    LaunchedEffect(controller.errorMessage) {
        if (!BackgroundPlaybackSession.owns(controller) || !viewModel.acceptsPlaybackEvents("mpv")) return@LaunchedEffect
        controller.errorMessage?.let { message ->
            if (message.startsWith("MPV audio:")) viewModel.requestAudioFallback(snapshot.positionMs, message)
            else viewModel.reportPlaybackError(message)
        }
    }
    MpvRenderOverloadDialog(controller) { panel = UnifiedPlayerPanel.VIDEO }
    LaunchedEffect(seekFeedbackVersion) { if (seekFeedbackVersion > 0) { seekFeedbackVisible = true; delay(PLAYER_CONTROLS_TIMEOUT_MS); seekFeedbackVisible = false } }

    com.ambient.tv.ui.player.PlayerControlsAutoHide(
        controlsVisible, snapshot.isPlaying, panel != UnifiedPlayerPanel.NONE, changingItem,
    ) { controlsVisible = it }

    // One remote handling for the engine and, while the focus is in the panel above it, the panel host.
    val chapterNavigation = com.ambient.tv.ui.player.LocalPlaybackNavigation.current
    val osdKeys: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        // Shared remote handling (PlayerOsdKeys); only the actions below are MPV's own.
        val restartTitle = { seek.seekTo(0L) }
        handlePlayerOsdKey(
            event.key, event.type == KeyEventType.KeyDown,
            OsdKeyState(OsdContent.VIDEO, controlsVisible, panel != UnifiedPlayerPanel.NONE, seekFeedbackVisible),
            OsdSeekSteps(state.seekJumpMs, held = { heldSeekStep(it, event) }),
            object : OsdKeyTarget {
                override fun chapterSeekDelta(direction: Int) = chapterNavigation?.timeline?.chapterStepDelta(seek.positionMs, direction)
                override fun isPlaying() = snapshot.isPlaying
                override fun togglePlayback() { controller.playPause() }
                override fun play() { controller.play() }
                override fun pause() { controller.pause() }
                override fun stop() { BackgroundPlaybackSession.stop() }
                override fun seek(deltaMs: Long, revealOsd: Boolean) {
                    seekBy(deltaMs)
                    if (revealOsd) controlsVisible = true else seekFeedbackVersion++
                }
                override fun showOsd() { controlsVisible = true }
                override fun hideSeekFeedback() { seekFeedbackVisible = false }
                override fun restart() { restartTitle() }
                override fun next() { (state.nextAudioTrack ?: state.nextVideoItem)?.ratingKey?.let(onPlayNext) }
                override fun previousTrack() = Unit
                override fun zap(delta: Int) = Unit
            },
        )
    }
    PublishPlayerPanelKeys(osdKeys)
    CompositionLocalProvider(LocalProjectMBrowser provides projectMBrowser, LocalPlayerPanelNavigation provides panelNavigation) {
    Box(
        Modifier.fillMaxSize().background(if (persistentVideoHost) Color.Transparent else Color.Black).onPreviewKeyEvent(osdKeys).focusRequester(rememberPlayerEngineFocus()).focusable(),
    ) {
        if (!persistentVideoHost && !audioOnlyContent) MpvVideoSurface(controller, Modifier.fillMaxSize(), state.sourceAspectRatioHint)
        if (showProjectM) {
            androidx.compose.runtime.key(controller) {
                NativeProjectMVisualizer(projectMBrowser, 2, controlsVisible, Modifier.fillMaxSize())
            }
        }
        PlayerBufferingSpinner(
            visible = waitingForPlaybackPicture(snapshot.wantsToPlay, snapshot.isPlaying),
            modifier = Modifier.align(Alignment.Center),
        )
        if (!controlsVisible && seekFeedbackVisible) PlayerSeekModeBar(seek.positionMs, timelineDurationMs, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
        PlayerTouchLayer(
            onToggleControls = { if (panel != UnifiedPlayerPanel.NONE) panelNavigation.close() else controlsVisible = !controlsVisible },
            onTogglePlayback = { controller.playPause(); controlsVisible = true },
            onSeekBack = { seekBy(-state.seekJumpMs); controlsVisible = true },
            onSeekForward = { seekBy(state.seekJumpMs); controlsVisible = true },
        )
        PlaybackPerformanceNotice(mpvLoadHint(controller, snapshot, controller.deinterlaceModeKey != "off"))
        val showNerdOverlay = controller.audioSession.performanceHint
        val showFpsOverlay = controller.audioSession.showFpsOverlay
        val displayRefreshHz = rememberDisplayRefreshHz()
        PlaybackNerdStatsOverlay(
            audioConversion = audioConversionDiagnostic(context, state, controller.audioSession, snapshot.audioCodec),
            audioRoute = audioRouteSummaryText(controller.audioSession),
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
        PublishPlayerOsd(PlayerOsdModel(
            state = state,
            positionMs = seek.positionMs,
            durationMs = timelineDurationMs,
            timelineAlways = false,
            remainingMs = timelineDurationMs.takeIf { it > 0L }?.let { (it - seek.positionMs).coerceAtLeast(0L) },
            clockEndPadding = 48.dp,
            isPlaying = snapshot.isPlaying,
            loading = waitingForPlaybackPicture(snapshot.wantsToPlay, snapshot.isPlaying),
            seekState = seek,
            onSeekBarSeek = { seekBy(it) },
            onSeekBarInteraction = { controlsVisible = true },
            onSeekBarToggle = controller::playPause,
            onSeekBarPlay = controller::play,
            onSeekButton = { seekBy(it) },
            onPlayPauseButton = controller::playPause,
            onPlayNext = onPlayNext,
            onBack = onBack,
            onPanelChange = { panel = it },
            badges = {
                PlayerTechBadgeRow {
                    MetaBadge("MPV", emphasized = true)
                    MetaBadge(state.playbackMode.name.replace('_', ' '))
                    PlayerVideoBadges(
                        dimensions = snapshot.videoSize,
                        hdrLabel = formatHdrMetadata(state.videoDynamicRange, state.videoProfile),
                        hasVideo = snapshot.videoSize != null,
                    )
                    formatNerdFps(snapshot.containerFps)?.let { MetaBadge(it) }
                    com.ambient.tv.ui.player.backend.svpBadgeLabel(snapshot.svpState)?.let { MetaBadge(it, emphasized = true) }
                    snapshot.videoCodec?.let { MetaBadge(playerCodecLabel(it)) }
                    snapshot.audioCodec?.let { MetaBadge(playerCodecLabel(it)) }
                    audioOutputBadge(controller.audioSession, state.serverAudioConversionCodec)?.let { MetaBadge(it) }
                }
            },
        ), visible = controlsVisible)

        // Drawn by PersistentPlayerOverlays above the engine, so it survives a renderer switch.
        // Its content is only built with the OSD or a panel on screen, as when it was drawn here.
        if (controlsVisible || panel != UnifiedPlayerPanel.NONE) {
            PublishPlayerPanel(PlayerPanelModel(
                scrimAlpha = 0.32f,
                projectMBrowser = LocalProjectMBrowser.current,
                audioSession = controller.audioSession,
                showVisualizer = showProjectM,
                hasVideo = com.ambient.tv.ui.player.mpvPanelHasVideo(snapshot.videoCodec, audioOnlyContent),
                isLive = false,
                scalingKey = resizeKey,
                aspectKey = aspectKey,
                zoomFactor = zoomFactor,
                speed = speed,
                onScalingChanged = { resizeKey = it; controller.setVideoPresentation(resizeKey, if (aspectKey == "auto") null else aspectKey, zoomFactor) },
                onAspectChanged = { aspectKey = it; controller.setVideoPresentation(resizeKey, if (aspectKey == "auto") null else aspectKey, zoomFactor) },
                onZoomChanged = { zoomFactor = it; controller.setVideoPresentation(resizeKey, if (aspectKey == "auto") null else aspectKey, zoomFactor) },
                onSpeedChanged = if (controller.bitstreamPermitted) null else ({ controller.setSpeed(it) }),
                audioOptions = audioOptions,
                selectedAudio = state.serverAudioStreamId?.toString() ?: selectedAudio,
                onAudioSelected = {
                    if (state.serverAudioStreamId != null || viewModel.serverAudioSelectionEnabled()) it.toLongOrNull()?.let { id -> viewModel.requestServerAudioTrack(id, controller.positionMs()) }
                    else { controller.selectAudio(it.toIntOrNull()) }
                },
                subtitleOptions = subtitleOptions,
                selectedSubtitle = selectedSubtitle,
                onSubtitleSelected = { controller.selectSubtitle(it.toIntOrNull()) },
                sleepTimer = sleepTimer,
                onSleepChanged = { sleepTimer = it },
                rendererKey = state.playbackBackendKey,
                onRendererSelected = { key, global -> viewModel.switchPlaybackBackend(key, snapshot.positionMs, global) },
                infoLines = buildList {
                    add(stringResource(R.string.player_nerd_backend) to "MPV")
                    add(stringResource(R.string.player_stream_mode) to state.playbackMode.name.replace('_', ' '))
                    state.container?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_container) to it.uppercase()) }
                    state.sourceBitrateKbps?.takeIf { it > 0 }?.let { kbps ->
                        formatNerdBitrate(kbps.toLong() * 1000L)?.let { add(stringResource(R.string.player_nerd_source_bitrate) to it) }
                    }
                    add(stringResource(R.string.player_info_video_output) to DirectAvDiagnostics.video(
                        context, state.videoCodec, state.videoDynamicRange, state.videoBitDepth, state.videoProfile, com.ambient.tv.ui.player.rendererTransitionValue(if (controller.supportsGpuFilters) "MPV GPU" else "MPV Direct", controller.engineConfirmed, stringResource(R.string.player_nerd_switching)),
                    ))
                    snapshot.videoOutput?.let { add(stringResource(R.string.player_nerd_vo) to it) }
                    snapshot.audioOutputDriver?.let { add(stringResource(R.string.player_nerd_ao) to it) }
                    listOfNotNull(
                        snapshot.videoSize,
                        formatNerdFps(snapshot.fps),
                        snapshot.videoCodec?.uppercase(),
                        state.videoBitDepth?.takeIf { it > 0 }?.let { "$it-bit" },
                    ).joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_video) to it) }
                    formatHdrMetadata(state.videoDynamicRange, state.videoProfile)?.let { add(stringResource(R.string.player_nerd_hdr) to it) }
                    snapshot.decoder?.let { add(stringResource(R.string.player_nerd_hardware_decode) to
                        if (controller.supportsGpuFilters) com.ambient.tv.ui.player.backend.mpvDecoderPathLabel(it) else it) }
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
                        // Untouched defaults run cheap (Sparsam or a low-end device); chosen values run as chosen.
                        val cheapDefaults = com.ambient.tv.ui.player.mpvCheapDefaultsActive()
                        val stored = controller.imageOptions.normalized()
                        val defaults = com.ambient.tv.data.local.MpvImageOptions()
                        fun replaced(value: String, default: String) =
                            com.ambient.tv.ui.player.backend.mpvSparsamReplacesDefault(value, default, cheapDefaults)
                        formatMpvUpscalerForStats(stored.scaler, snapshot.activeScaler, snapshot.activeShaderPreset, snapshot.activeUpscaling, sparsam = replaced(stored.scaler, defaults.scaler))?.let { add(stringResource(R.string.player_nerd_upscaler) to it) }
                        formatMpvScalerStat(snapshot.activeChromaScaler, stored.chromaScaler, replaced(stored.chromaScaler, defaults.chromaScaler))?.let { add(stringResource(R.string.player_nerd_chroma_scaler) to it) }
                        formatMpvScalerStat(snapshot.activeDownscaler, stored.downscaler, replaced(stored.downscaler, defaults.downscaler))?.let { add(stringResource(R.string.player_nerd_downscaler) to it) }
                        formatMpvDebandStat(stored.deband, replaced(stored.deband, defaults.deband), stringResource(R.string.player_nerd_enabled))?.let { add(stringResource(R.string.player_nerd_deband_configured) to it) }
                        if (snapshot.sparsam) add(stringResource(R.string.player_nerd_sparsam) to stringResource(R.string.settings_performance_low))
                        val (renderW, renderH) = gpuRenderSize(context, controller.gpuRenderAtPanel)
                        add(stringResource(R.string.player_nerd_render_resolution) to
                            gpuRenderResolutionLabel(true, controller.gpuRenderAtPanel, renderW, renderH))
                        add(stringResource(R.string.player_nerd_sdr_curve) to
                            com.ambient.tv.ui.player.backend.mpvSdrSurfaceLabel(controller.sdrSurfaceDataSpace))
                    }
                    if (controller.deinterlaceModeKey != "off") {
                        add(stringResource(R.string.player_nerd_deinterlace_configured) to listOf(controller.deinterlaceModeKey, controller.deinterlaceFilterKey).filter { it.isNotBlank() && it != "auto" }.joinToString(" · ").ifBlank { controller.deinterlaceModeKey })
                    }
                    snapshot.activeVideoFilters?.let { add(stringResource(R.string.player_nerd_video_filters) to it) }
                    controller.loadedImageShaders().takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.player_nerd_shaders_loaded) to it.joinToString()) }
                    add(stringResource(R.string.audio_route_output) to audioRouteSummaryText(controller.audioSession))
                    add(stringResource(R.string.player_info_audio_output) to DirectAvDiagnostics.audio(context, controller.bitstreamPermitted, snapshot.audioCodec ?: state.audioCodec, snapshot.audioOutputFormat, controller.audioSession.outputPlan().mode))
                    audioConversionDiagnostic(context, state, controller.audioSession, snapshot.audioCodec)?.let { add(stringResource(R.string.audio_conversion_location) to it) }
                    (snapshot.audioCodec ?: state.audioCodec)?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_audio) to it.uppercase()) }
                    formatNerdBitrate(snapshot.audioBitrateBitsPerSecond)?.let { add(stringResource(R.string.player_nerd_audio_bitrate) to it) }
                },
            ))
        }
    }
    }
}

internal fun formatMpvDuration(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)

/**
 * A 2 s watchdog sample counts as a decoder stall only while playback is wanted, the clock does
 * not move and mpv is not waiting for data. The file end (ended or the last 5 s) is not a stall,
 * and an explicit MPV choice is never moved to another renderer automatically.
 */
internal fun mpvStallCounts(
    wantsToPlay: Boolean, positionMs: Long, lastPositionMs: Long, durationMs: Long,
    ended: Boolean, waitingForData: Boolean, mpvExplicit: Boolean,
): Boolean = wantsToPlay && !ended && !mpvExplicit && !waitingForData &&
    kotlin.math.abs(positionMs - lastPositionMs) < 500L &&
    !(durationMs > 0L && durationMs - positionMs <= 5_000L)
