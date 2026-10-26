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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.formatHdrMetadata
import com.ambient.tv.ui.common.PlayerTouchLayer
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerExitButton
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.PlayerSleepTimerButton
import com.ambient.tv.ui.common.PLAYER_CONTROLS_TIMEOUT_MS
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.UnifiedBackendPlayerPanel
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.player.backend.VlcVideoSurface
import com.ambient.tv.ui.player.backend.rememberVlcPlaybackController
import com.ambient.tv.ui.player.backend.audioFormatLabel
import com.ambient.tv.ui.player.backend.videoSizeLabel
import com.ambient.tv.ui.player.backend.rememberVlcPlaybackSnapshot
import kotlinx.coroutines.delay

@Composable
internal fun VlcPlayerContent(
    state: PlayerUiState.Ready,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onPlayNext: (String) -> Unit,
    onVideoFormatChanged: (Float, Int?, Int?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = rememberVlcPlaybackController(
        context = context,
        url = state.streamUrl,
        startPositionMs = state.startPositionMs,
        headers = state.streamHeaders,
        initialAudioVisualization = !AudioPassthroughPolicy.isBitstreamActive(context, state.audioPassthroughEnabled, state.audioCodec) && state.audioVisualizerModeKey !in setOf("off", "projectm") && audioOnlyPlaybackHint(state.sourceRatingKey, state.mediaType, state.videoCodec),
        initialAudioPassthrough = state.audioPassthroughEnabled,
        initialScalingFilter = viewModel.vlcScalingFilter(),
    )
    if (controller == null) {
        val unavailable = stringResource(R.string.player_vlc_unavailable)
        LaunchedEffect(unavailable) { viewModel.reportPlaybackError(unavailable) }
        return
    }
    val playbackKey = com.ambient.tv.ui.player.LocalPlaybackMetadata.current?.route ?: state.streamUrl
    val panelNavigation = rememberPlayerPanelNavigation(playbackKey)
    var panel by panelNavigation
    VlcRenderOverloadDialog(controller, panelNavigation) { panelNavigation.openVideoSettings() }
    // Back is owned by the dispatcher, so nested panels/popups close one level per press.
    val snapshot = rememberVlcPlaybackSnapshot(
        controller,
        controller.audioSession.performanceHint || controller.audioSession.showFpsOverlay,
    )
    LaunchedEffect(controller, onVideoFormatChanged) {
        var lastFormat = ""
        while (true) {
            val stats = controller.runtimeStats()
            val fps = stats?.videoFps?.toFloat() ?: 0f
            val width = stats?.videoWidth
            val height = stats?.videoHeight
            if (width != null && height != null) viewModel.applyResolutionProfileFromRuntime(
                width, height, controller.status().positionMs,
            )
            val signature = "$fps:$width:$height"
            if ((fps > 0f || width != null || height != null) && signature != lastFormat) {
                onVideoFormatChanged(fps, width, height)
                lastFormat = signature
            }
            delay(750)
        }
    }
    LaunchedEffect(controller, state.audioCodec) {
        controller.updateSourceCodec(state.audioCodec)
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
        enginePositionMs = { controller.status().positionMs + state.timelineOffsetMs },
        commit = viewModel::seekTimeline,
        pollEnabled = !performance.pollOsdPositionOnlyWhenChromeVisible || chromeVisible,
    )
    fun seekBy(deltaMs: Long) = seek.seekBy(deltaMs, timelineDurationMs)
    val seekAcceleration = remember(seek) { SeekKeyAcceleration() }
    fun heldSeekStep(baseMs: Long, event: androidx.compose.ui.input.key.KeyEvent) =
        seekAcceleration.step(baseMs, event.nativeKeyEvent.keyCode, event.nativeKeyEvent.eventTime, event.nativeKeyEvent.repeatCount, timelineDurationMs)
    var seekFeedbackVersion by remember(controller) { mutableStateOf(0) }
    var appliedVideoVisualGate by remember(controller) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(performance.gateExpensiveVideoVisuals, controller) {
        val gate = performance.gateExpensiveVideoVisuals
        if (appliedVideoVisualGate == null) {
            appliedVideoVisualGate = gate
            return@LaunchedEffect
        }
        if (appliedVideoVisualGate == gate) return@LaunchedEffect
        appliedVideoVisualGate = gate
        controller.refreshEffectiveOverrides()
    }
    val initialVideoPresentation = remember(controller) { BackgroundPlaybackSession.videoPresentation.value }
    var resizeKey by remember(playbackKey) { mutableStateOf(initialVideoPresentation.resizeKey()) }
    var aspectKey by remember(playbackKey) { mutableStateOf(initialVideoPresentation.aspectKey()) }
    var zoomFactor by remember(playbackKey) { mutableStateOf(initialVideoPresentation.zoom) }
    var sleepTimer by com.ambient.tv.ui.player.rememberPlaybackSleepTimer(controller)
    DisposableEffect(controller) {
        viewModel.restoreReloadedAudio(controller.audioSession)
        controller.audioSession.conversionLocationChanged = { viewModel.audioConversionLocationChanged(it, controller.status().positionMs) }
        onDispose { controller.audioSession.conversionLocationChanged = { false } }
    }
    // Read from the player: automatic selection and renderer restores change these without the panel.
    val selectedAudio = controller.currentAudioTrackId().toString()
    val speed = controller.currentSpeed()
    val selectedSubtitle = controller.currentSubtitleTrackId().toString()
    val playFocusRequester = com.ambient.tv.ui.player.rememberPlayerPlayFocus()
    val persistentVideoHost = LocalPersistentPlaybackVideoHost.current
    val projectMBrowser = rememberProjectMBrowser(state.projectmPresetCategoryKey)
    val audioOnlyContent = audioOnlyPlaybackHint(state.sourceRatingKey, state.mediaType, state.videoCodec) || (snapshot.hasAudio && !snapshot.hasVideo)
    val showProjectM = audioOnlyContent && state.audioVisualizerModeKey == "projectm" && ProjectMAvailability.isAvailable
    LaunchedEffect(controller, audioOnlyContent) {
        if (audioOnlyContent) controller.audioSession.updateAudioOnly(true)
    }

    val audioOptions = state.audioTracks.takeIf { state.serverAudioStreamId != null || viewModel.serverAudioSelectionEnabled() }?.mapNotNull { track ->
        track.id?.let { it.toString() to audioTrackLabel(track.title, track.language, track.channels, track.codec) }
    } ?: controller.audioTracks().map { it.first.toString() to it.second }
    val subtitleOptions = listOf("-1" to stringResource(R.string.player_subtitles_off)) + controller.subtitleTracks().map { it.first.toString() to it.second }

    LaunchedEffect(
        controller, state.preferredAudioLanguage, state.preferredSubtitleLanguage,
        state.subtitlesEnabled, state.forcedSubtitlesEnabled,
    ) {
        if (controller.restoredOutputAudioSelection) return@LaunchedEffect
        repeat(24) { attempt ->
            if (attempt > 0) delay(250)
            val vlcAudio = controller.audioTracks()
            if (vlcAudio.isEmpty()) return@repeat
            val preferredAudioId = controller.preferredAudioTrack(state.preferredAudioLanguage)?.first ?: PlaybackLanguagePolicy.chooseVlcTrackId(
                vlcAudio, state.audioTracks, state.preferredAudioLanguage, forcedOnly = false,
            )
            if (preferredAudioId == null) return@repeat
            controller.selectAudioTrack(preferredAudioId)
            return@LaunchedEffect
        }
    }

    RequestPlayerOverlayFocus(
        requester = playFocusRequester,
        enabled = controlsVisible && panel == UnifiedPlayerPanel.NONE,
        playbackStarted = snapshot.isPlaying,
        panelNavigation = panelNavigation,
    )
    LaunchedEffect(controller.errorMessage) {
        if (BackgroundPlaybackSession.owns(controller) && viewModel.acceptsPlaybackEvents("vlc"))
            controller.errorMessage?.let(viewModel::reportPlaybackError)
    }
    LaunchedEffect(seekFeedbackVersion) { if (seekFeedbackVersion > 0) { seekFeedbackVisible = true; delay(PLAYER_CONTROLS_TIMEOUT_MS); seekFeedbackVisible = false } }

    com.ambient.tv.ui.player.PlayerControlsAutoHide(
        controlsVisible, snapshot.isPlaying, panel != UnifiedPlayerPanel.NONE, changingItem,
    ) { controlsVisible = it }

    // One remote handling for the engine and, while the focus is in the panel above it, the panel host.
    val chapterNavigation = com.ambient.tv.ui.player.LocalPlaybackNavigation.current
    val osdKeys: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        // Shared remote handling (PlayerOsdKeys); only the actions below are VLC's own.
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
        if (!persistentVideoHost && (!audioOnlyContent || controller.audioVisualizationEnabled)) VlcVideoSurface(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            sourceAspectRatioHint = state.sourceAspectRatioHint,
        )
        if (showProjectM) {
            androidx.compose.runtime.key(controller) {
                NativeProjectMVisualizer(projectMBrowser, 1, controlsVisible, Modifier.fillMaxSize())
            }
        }
        PlayerBufferingSpinner(
            visible = waitingForPlaybackPicture(snapshot.wantsToPlay, snapshot.isPlaying),
            modifier = Modifier.align(Alignment.Center),
        )
        if (!controlsVisible && seekFeedbackVisible) PlayerSeekModeBar(seek.positionMs, timelineDurationMs, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
        PlayerTouchLayer(
            onToggleControls = { controlsVisible = !controlsVisible },
            onTogglePlayback = { controller.playPause(); controlsVisible = true },
            onSeekBack = { seekBy(-state.seekJumpMs); controlsVisible = true },
            onSeekForward = { seekBy(state.seekJumpMs); controlsVisible = true },
        )
        val vlcPlan = controller.audioSession.outputPlan()
        LaunchedEffect(vlcPlan.needsMpv, vlcPlan.mode) {
            if (vlcPlan.needsMpv) viewModel.followAudioPlan(snapshot.positionMs)
        }
        PlaybackPerformanceNotice(vlcLoadHint(controller, snapshot, controller.deinterlaceMode != "off"))
        val showNerdOverlay = controller.audioSession.performanceHint
        val showFpsOverlay = controller.audioSession.showFpsOverlay
        PlaybackNerdStatsOverlay(
            audioConversion = audioConversionDiagnostic(context, state, controller.audioSession, snapshot.runtimeStats?.audioCodec),
            audioRoute = audioRouteSummaryText(controller.audioSession),
            enabled = showNerdOverlay,
            model = rememberVlcNerdOverlayModel(
                snapshot = snapshot,
                enabled = showNerdOverlay,
                audioCodec = snapshot.runtimeStats?.audioCodec ?: state.audioCodec,
                audioOutputMode = controller.audioSession.outputPlan().mode,
            ),
        )
        PlaybackFpsOverlay(
            enabled = showFpsOverlay,
            fps = snapshot.runtimeStats?.videoFps,
            svpStatus = snapshot.svpStatus,
            refreshHz = rememberDisplayRefreshHz(),
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
                    MetaBadge("VLC", emphasized = true)
                    MetaBadge(state.playbackMode.name.replace('_', ' '))
                    PlayerVideoBadges(
                        dimensions = snapshot.runtimeStats?.videoSizeLabel(),
                        hdrLabel = formatHdrMetadata(state.videoDynamicRange, state.videoProfile),
                        hasVideo = snapshot.hasVideo,
                    )
                    formatNerdFps(snapshot.runtimeStats?.videoFps)?.let { MetaBadge(it) }
                    com.ambient.tv.ui.player.backend.svpBadgeLabel(snapshot.svpState)?.let { MetaBadge(it, emphasized = true) }
                    (snapshot.runtimeStats?.videoCodec ?: state.videoCodec)?.let { MetaBadge(playerCodecLabel(it)) }
                    (snapshot.runtimeStats?.audioCodec ?: state.audioCodec)?.let { MetaBadge(playerCodecLabel(it)) }
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
                hasVideo = snapshot.hasVideo,
                isLive = false,
                scalingKey = resizeKey,
                aspectKey = aspectKey,
                zoomFactor = zoomFactor,
                speed = speed,
                onScalingChanged = { resizeKey = it; controller.setVideoPresentation(resizeKey, aspectKey.takeUnless { value -> value == "auto" }, zoomFactor) },
                onAspectChanged = { aspectKey = it; controller.setVideoPresentation(resizeKey, it.takeUnless { value -> value == "auto" }, zoomFactor) },
                onZoomChanged = { zoomFactor = it; controller.setVideoPresentation(resizeKey, aspectKey.takeUnless { value -> value == "auto" }, zoomFactor) },
                onSpeedChanged = if (controller.bitstreamPermitted) null else ({ controller.setSpeed(it) }),
                audioOptions = audioOptions.ifEmpty { listOf("default" to stringResource(R.string.player_default_track)) },
                selectedAudio = state.serverAudioStreamId?.toString() ?: selectedAudio,
                onAudioSelected = {
                    if (state.serverAudioStreamId != null || viewModel.serverAudioSelectionEnabled()) it.toLongOrNull()?.let { id -> viewModel.requestServerAudioTrack(id, controller.status().positionMs) }
                    else { it.toIntOrNull()?.let(controller::selectAudioTrack) }
                },
                subtitleOptions = subtitleOptions,
                selectedSubtitle = selectedSubtitle,
                onSubtitleSelected = { controller.selectSubtitleTrack(it.toIntOrNull()?.takeIf { id -> id >= 0 }) },
                sleepTimer = sleepTimer,
                onSleepChanged = { sleepTimer = it },
                rendererKey = state.playbackBackendKey,
                onRendererSelected = { key, global -> viewModel.switchPlaybackBackend(key, snapshot.positionMs, global) },
                infoLines = buildList {
                    add(stringResource(R.string.player_nerd_backend) to "VLC")
                    add(stringResource(R.string.player_stream_mode) to state.playbackMode.name.replace('_', ' '))
                    state.container?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_container) to it.uppercase()) }
                    state.sourceBitrateKbps?.takeIf { it > 0 }?.let { kbps ->
                        formatNerdBitrate(kbps.toLong() * 1000L)?.let { add(stringResource(R.string.player_nerd_source_bitrate) to it) }
                    }
                    add(stringResource(R.string.player_info_video_output) to DirectAvDiagnostics.video(
                        context, state.videoCodec, state.videoDynamicRange, state.videoBitDepth, state.videoProfile, "VLC",
                    ))
                    val runtimeVideo = snapshot.runtimeStats
                    if (snapshot.hasVideo) {
                        listOfNotNull(
                            runtimeVideo?.let { stats -> if (stats.videoWidth != null && stats.videoHeight != null) "${stats.videoWidth}×${stats.videoHeight}" else null },
                            formatNerdFps(runtimeVideo?.videoFps),
                            (runtimeVideo?.videoCodec ?: state.videoCodec)?.takeIf { it.isNotBlank() }?.uppercase(),
                        ).joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_video) to it) }
                        formatNerdBitrate(runtimeVideo?.videoBitrateBitsPerSecond)?.let { add(stringResource(R.string.player_nerd_video_bitrate) to it) }
                        add(stringResource(R.string.player_nerd_scaler_configured) to controller.currentScalingFilter())
                        add(stringResource(R.string.player_nerd_image_processing_configured) to if (controller.imageOptions.softwareProcessing) stringResource(R.string.player_nerd_image_processing_software) else stringResource(R.string.player_nerd_image_processing_hardware))
                        (context as? android.app.Activity)?.let(androidx.core.content.ContextCompat::getDisplayOrDefault)?.refreshRate?.takeIf { it > 0f }?.let { add(stringResource(R.string.player_nerd_display_refresh) to formatNerdHz(it.toDouble()).orEmpty()) }
                    }
                    formatHdrMetadata(state.videoDynamicRange, state.videoProfile)?.let { add(stringResource(R.string.player_nerd_hdr) to it) }
                    controller.currentDeinterlace()?.let { add(stringResource(R.string.player_nerd_deinterlace_configured) to it) }
                    snapshot.runtimeStats?.let { stats ->
                        stats.decodedVideo?.let { add(stringResource(R.string.player_nerd_decoded_video_frames) to it.toString()) }
                        stats.displayedPictures?.let { add(stringResource(R.string.player_nerd_displayed_frames) to it.toString()) }
                        stats.lostPictures?.let { add(stringResource(R.string.player_nerd_lost_frames) to it.toString()) }
                        stats.demuxCorrupted?.takeIf { it > 0 }?.let { add(stringResource(R.string.player_nerd_corrupted_demux) to it.toString()) }
                        stats.demuxDiscontinuity?.takeIf { it > 0 }?.let { add(stringResource(R.string.player_nerd_demux_discontinuities) to it.toString()) }
                        formatNerdBytes(stats.readBytes)?.let { add(stringResource(R.string.player_nerd_input_read) to it) }
                        formatNerdBytes(stats.demuxReadBytes)?.let { add(stringResource(R.string.player_nerd_demux_read) to it) }
                    }
                    add(stringResource(R.string.audio_route_output) to audioRouteSummaryText(controller.audioSession))
                    add(stringResource(R.string.player_info_audio_output) to DirectAvDiagnostics.audio(context, controller.bitstreamPermitted, state.audioCodec, planMode = controller.audioSession.outputPlan().mode))
                    audioConversionDiagnostic(context, state, controller.audioSession)?.let { add(stringResource(R.string.audio_conversion_location) to it) }
                    (snapshot.runtimeStats?.audioFormatLabel() ?: state.audioCodec?.uppercase())?.let { add(stringResource(R.string.player_audio) to it) }
                    snapshot.runtimeStats?.let { stats ->
                        formatNerdBitrate(stats.audioBitrateBitsPerSecond)?.let { add(stringResource(R.string.player_nerd_audio_bitrate) to it) }
                        stats.decodedAudio?.let { add(stringResource(R.string.player_nerd_decoded_audio_buffers) to it.toString()) }
                        stats.playedAudioBuffers?.let { add(stringResource(R.string.player_nerd_played_audio_buffers) to it.toString()) }
                        stats.lostAudioBuffers?.takeIf { it > 0 }?.let { add(stringResource(R.string.player_nerd_lost_audio_buffers) to it.toString()) }
                    }
                },
            ))
        }
    }
    }
}

private fun formatVlcPlexDuration(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)
