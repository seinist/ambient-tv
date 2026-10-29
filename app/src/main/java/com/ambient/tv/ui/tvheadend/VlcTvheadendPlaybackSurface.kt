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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.tvheadend.domain.TvhPlaybackSource
import com.ambient.tv.tvheadend.domain.TvhEpgEvent
import com.ambient.tv.tvheadend.domain.TvhChannel
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.UnifiedBackendPlayerPanel
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.PlayerTouchLayer
import com.ambient.tv.ui.player.PlayerBufferingSpinner
import com.ambient.tv.ui.player.waitingForPlaybackPicture
import com.ambient.tv.ui.player.PlaybackLanguagePolicy
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.player.SleepTimerConfig
import com.ambient.tv.ui.player.SleepTimerKind
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.LocalPersistentPlaybackVideoHost
import com.ambient.tv.ui.player.TvPlayerSeekBar
import com.ambient.tv.ui.player.RequestPlayerOverlayFocus
import com.ambient.tv.ui.player.backend.VlcVideoSurface
import com.ambient.tv.ui.player.backend.rememberVlcPlaybackController
import com.ambient.tv.ui.player.backend.audioFormatLabel
import com.ambient.tv.ui.player.backend.videoSizeLabel
import com.ambient.tv.ui.player.backend.rememberVlcPlaybackSnapshot
import com.ambient.tv.ui.player.PlaybackFpsOverlay
import com.ambient.tv.ui.player.PlaybackPerformanceNotice
import com.ambient.tv.ui.player.PlaybackNerdStatsOverlay
import com.ambient.tv.ui.player.rememberDisplayRefreshHz
import com.ambient.tv.ui.player.rememberVlcNerdOverlayModel
import com.ambient.tv.ui.player.vlcLoadHint
import com.ambient.tv.ui.player.formatNerdBitrate
import com.ambient.tv.ui.player.formatNerdBytes
import com.ambient.tv.ui.player.formatNerdFps
import com.ambient.tv.ui.player.formatNerdHz
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.text.font.FontWeight
import com.ambient.tv.ui.player.handlePlayerOsdKey
import com.ambient.tv.ui.player.OsdKeyState
import com.ambient.tv.ui.player.OsdContent
import com.ambient.tv.ui.player.OsdSeekSteps
import com.ambient.tv.ui.player.OsdKeyTarget

@Composable
internal fun VlcTvheadendPlaybackSurface(
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
    initialScalingFilter: String,
    seekJumpMs: Long,
    rendererKey: String,
    onRendererSelected: (String, Long, Boolean) -> Unit,
    audioVisualizerModeKey: String,
    audioPassthroughEnabled: Boolean,
    preferredAudioLanguage: String?,
    forcedSubtitlesEnabled: Boolean,
    onVideoFormatChanged: (Float, Int?, Int?) -> Unit,
) {
    val context = LocalContext.current
    val controller = rememberVlcPlaybackController(
        context, source.url, source.startPositionMs, source.headers,
        initialDeinterlaceMode = initialDeinterlaceMode,
        initialDeinterlaceFilter = initialDeinterlaceFilter,
        initialScalingFilter = initialScalingFilter,
        initialAudioPassthrough = audioPassthroughEnabled,
    )
    if (controller == null) {
        // libvlc failed to initialize on this device/build - report it like any other
        // playback error instead of crashing, since the backend is already "vlc" here and
        // there is nowhere left to auto-fall-back to.
        val unavailable = stringResource(R.string.player_vlc_unavailable)
        LaunchedEffect(unavailable) { onPlaybackError(unavailable) }
        return
    }
    val playbackKey = com.ambient.tv.ui.player.LocalPlaybackMetadata.current?.route ?: source.url
    val panelNavigation = rememberPlayerPanelNavigation(playbackKey)
    var panel by panelNavigation
    // Row key of the video panel's Player area, focused when Back leaves that area.
    val rendererEntryTitle = stringResource(R.string.player_renderer)
    com.ambient.tv.ui.player.VlcRenderOverloadDialog(controller, panelNavigation) { panelNavigation.openVideoSettings() }
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
            val signature = "$fps:$width:$height"
            if ((fps > 0f || width != null || height != null) && signature != lastFormat) {
                onVideoFormatChanged(fps, width, height)
                lastFormat = signature
            }
            delay(750)
        }
    }
    LaunchedEffect(controller) {
        // TVHeadend does not carry a reliable codec in TvhPlaybackSource. Wait until VLC exposes
        // its ES metadata and then bind the selected track codec into the shared audio session.
        repeat(24) { attempt ->
            if (attempt > 0) delay(250)
            if (controller.audioTracks().isNotEmpty()) {
                controller.refreshAudioCodec()
                return@LaunchedEffect
            }
        }
    }
    val persistentVideoHost = LocalPersistentPlaybackVideoHost.current
    val projectMBrowser = rememberProjectMBrowser(com.ambient.tv.ui.player.videoImagePreferences(context).projectmPresetCategoryKey)
    val audioOnlyContent = controller.audioOnly || (snapshot.hasAudio && !snapshot.hasVideo)
    val showProjectM = audioOnlyContent && audioVisualizerModeKey == "projectm" && ProjectMAvailability.isAvailable
    LaunchedEffect(controller, audioOnlyContent) {
        if (audioOnlyContent) controller.audioSession.updateAudioOnly(true)
    }
    LaunchedEffect(controller.bitstreamPermitted, snapshot.hasVideo, snapshot.hasAudio, snapshot.durationMs, audioVisualizerModeKey) {
        // A freshly opened stream reports no tracks at all until VLC has parsed it. Treating that
        // as audio-only reopened a live TV channel twice (visualizer on, then off again) on every
        // start and renderer hand-off, each time with a new TVHeadend subscription.
        if (!snapshot.hasVideo && !snapshot.hasAudio) return@LaunchedEffect
        val audioOnly = !snapshot.hasVideo && (snapshot.durationMs > 0L || source.isLive)
        val shouldVisualize = !controller.bitstreamPermitted && audioOnly && audioVisualizerModeKey !in setOf("off", "projectm")
        if (shouldVisualize != controller.audioVisualizationEnabled) {
            controller.setAudioVisualizationEnabled(shouldVisualize, preservePosition = !source.isLive)
        }
    }
    val osd = com.ambient.tv.ui.player.rememberPlayerOsdController()
    var controlsVisible by com.ambient.tv.ui.player.rememberPlayerControlsVisible()
    val chromeState = com.ambient.tv.ui.player.LocalPlayerChromeState.current
    val changingItem = com.ambient.tv.ui.player.LocalPlayerChangingItem.current
    // Back hides the OSD, then stops - radio included. Only the exit-fullscreen button keeps
    // playing in the background; on a handheld, leaving an audio-only screen still does.
    val handheldUi = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val backMinimizes = com.ambient.tv.ui.player.rememberBackMinimizesPlayback()
    fun exitOnBack() {
        if (com.ambient.tv.ui.player.backLeavesPlaybackRunning(snapshot.hasAudio && !snapshot.hasVideo, handheldUi, backMinimizes())) onBack()
        else BackgroundPlaybackSession.stop()
    }
    androidx.activity.compose.BackHandler(enabled = panel == UnifiedPlayerPanel.NONE) {
        // Handheld: swipe Back leaves the player instead of only hiding the OSD.
        if (controlsVisible && !handheldUi) controlsVisible = false else exitOnBack()
    }
    fun userSeekBy(deltaMs: Long) {
        BackgroundPlaybackSession.userSeek()
        controller.seekBy(deltaMs)
    }
    fun heldSeekStep(baseMs: Long, event: androidx.compose.ui.input.key.KeyEvent) =
        com.ambient.tv.ui.player.acceleratedSeekStepMs(baseMs, event.nativeKeyEvent.repeatCount, snapshot.durationMs)
    var trackMessage by remember(controller) { mutableStateOf<String?>(null) }
    val performance = LocalPerformanceProfile.current
    var appliedVideoVisualGate by remember(controller) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(performance.gateExpensiveVideoVisuals, controller) {
        val gate = performance.gateExpensiveVideoVisuals
        if (appliedVideoVisualGate == null) {
            appliedVideoVisualGate = gate
            return@LaunchedEffect
        }
        if (appliedVideoVisualGate == gate) return@LaunchedEffect
        appliedVideoVisualGate = gate
        controller.refreshEffectiveOverrides(preservePosition = !source.isLive)
    }
    var resizeKey by remember(playbackKey) { mutableStateOf("fit") }
    var aspectKey by remember(playbackKey) { mutableStateOf("auto") }
    var zoomFactor by remember(playbackKey) { mutableStateOf(1f) }
    var speed by remember(playbackKey) { mutableStateOf(1f) }
    LaunchedEffect(controller.bitstreamPermitted) { if (controller.bitstreamPermitted) speed = 1f }
    var sleepTimer by com.ambient.tv.ui.player.rememberPlaybackSleepTimer(controller)
    var selectedAudio by remember(playbackKey) { mutableStateOf(controller.currentAudioTrackId().toString()) }
    val selectedSubtitle = controller.currentSubtitleTrackId().toString()
    val playFocusRequester = com.ambient.tv.ui.player.rememberPlayerPlayFocus()
    val audioOptions = controller.audioTracks().map { it.first.toString() to it.second }.ifEmpty { listOf("default" to stringResource(R.string.player_default_track)) }
    val subtitleOptions = listOf("-1" to stringResource(R.string.player_subtitles_off)) + controller.subtitleTracks().map { it.first.toString() to it.second }

    LaunchedEffect(controller, preferredAudioLanguage, forcedSubtitlesEnabled) {
        if (controller.restoredOutputAudioSelection) return@LaunchedEffect
        repeat(24) { attempt ->
            if (attempt > 0) delay(250)
            val audioTracks = controller.audioTracks()
            if (audioTracks.isEmpty()) return@repeat
            val preferredAudio = controller.preferredAudioTrack(preferredAudioLanguage) ?: audioTracks.firstOrNull {
                PlaybackLanguagePolicy.labelMatchesLanguage(it.second, preferredAudioLanguage)
            }
            if (preferredAudio == null) return@repeat
            controller.selectAudioTrack(preferredAudio.first)
            return@LaunchedEffect
        }
    }

    LaunchedEffect(controller.errorMessage) {
        val message = controller.errorMessage ?: return@LaunchedEffect
        // Ignore errors from a controller that BackgroundPlaybackSession already replaced
        // during a renderer switch (Auto→VLC / VLC→Media3).
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

    com.ambient.tv.ui.player.PlayerControlsAutoHide(
        controlsVisible, snapshot.isPlaying, panel != UnifiedPlayerPanel.NONE, changingItem,
    ) { controlsVisible = it }


    // One remote handling for the engine and, while the focus is in the OSD or panel above it, the host.
    val chapterNavigation = com.ambient.tv.ui.player.LocalPlaybackNavigation.current
    val osdKeys: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = osdKeys@{ event ->
        // Timeshift owns Rewind/Forward while it buffers a live channel; media keys act with a panel open too.
        if (source.isLive && handleTimeshiftMediaKey(event, snapshot.positionMs, seekJumpMs) { osd.reveal() }) return@osdKeys true
        // Shared remote handling (PlayerOsdKeys); only the actions below are VLC's own.
        handlePlayerOsdKey(
            event.key, event.type == KeyEventType.KeyDown,
            OsdKeyState(
                content = if (source.isLive) OsdContent.LIVE else OsdContent.RECORDING,
                osdVisible = controlsVisible && !osd.seekFeedback && !osd.closing,
                panelOpen = panel != UnifiedPlayerPanel.NONE,
                seekFeedbackVisible = osd.seekFeedback,
                zapOsd = chromeState?.shownByZap?.value == true,
                canSeekForward = true,
                closing = osd.closing,
            ),
            OsdSeekSteps(BackgroundPlaybackSession.seekJumpMs(seekJumpMs), held = { heldSeekStep(it, event) }),
            object : OsdKeyTarget {
                override fun chapterSeekDelta(direction: Int) = chapterNavigation?.chapterStep(controller.status().positionMs, direction)
                override fun isPlaying() = snapshot.isPlaying
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
                override fun restart() { BackgroundPlaybackSession.userSeek(); controller.seekTo(0L) }
                override fun next() = Unit
                override fun previousTrack() = Unit
                override fun zap(delta: Int) { onZap(delta) }
            },
        )
    }
    com.ambient.tv.ui.player.PublishPlayerPanelKeys(osdKeys)
    CompositionLocalProvider(LocalProjectMBrowser provides projectMBrowser, LocalPlayerPanelNavigation provides panelNavigation) {
    Box(
        Modifier
            .fillMaxSize()
            .background(if (persistentVideoHost) Color.Transparent else Color.Black)
            .onPreviewKeyEvent(osdKeys)
            // Without this, the embedded VLC video surface can end up holding system focus
            // instead of this Box, so onPreviewKeyEvent above stops receiving D-pad/OK presses
            // entirely - every on-screen button then looks dead.
            .focusRequester(com.ambient.tv.ui.player.rememberPlayerEngineFocus()).focusable(),
    ) {
        if (!persistentVideoHost && (!audioOnlyContent || controller.audioVisualizationEnabled)) VlcVideoSurface(controller, Modifier.fillMaxSize())
        if (showProjectM) {
            androidx.compose.runtime.key(controller) {
                NativeProjectMVisualizer(projectMBrowser, 1, controlsVisible, Modifier.fillMaxSize())
            }
        }
        PlayerBufferingSpinner(
            visible = waitingForPlaybackPicture(snapshot.wantsToPlay, snapshot.isPlaying),
            modifier = Modifier.align(Alignment.Center),
        )
        PlayerTouchLayer(
            onToggleControls = { if (panel == UnifiedPlayerPanel.NONE) controlsVisible = !controlsVisible },
            onTogglePlayback = { BackgroundPlaybackSession.userToggle(); controlsVisible = true },
            onSeekBack = if (source.isLive) null else ({ userSeekBy(-BackgroundPlaybackSession.seekJumpMs(seekJumpMs)); controlsVisible = true }),
            onSeekForward = if (source.isLive) null else ({ userSeekBy(BackgroundPlaybackSession.seekJumpMs(seekJumpMs)); controlsVisible = true }),
        )
        val tvhVlcPlan = controller.audioSession.outputPlan()
        LaunchedEffect(tvhVlcPlan.needsMpv, tvhVlcPlan.mode) {
            if (tvhVlcPlan.needsMpv && com.ambient.tv.ui.player.AudioEngineRedirectGuard.once(source.url)) {
                onRendererSelected("mpv", snapshot.positionMs, false)
            }
        }
        PlaybackPerformanceNotice(vlcLoadHint(controller, snapshot, controller.deinterlaceMode != "off"))
        val showNerdOverlay = controller.audioSession.performanceHint
        val showFpsOverlay = controller.audioSession.showFpsOverlay
        PlaybackNerdStatsOverlay(
            audioConversion = controller.audioSession.activeLocalConversion?.let { "${stringResource(com.ambient.tv.R.string.audio_conversion_local)} · $it" },
            enabled = showNerdOverlay,
            model = rememberVlcNerdOverlayModel(
                snapshot = snapshot,
                enabled = showNerdOverlay,
                audioCodec = snapshot.runtimeStats?.audioCodec,
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
        val selectedAudioLabel = controller.audioTracks()
            .firstOrNull { it.first == controller.currentAudioTrackId() }
            ?.second
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
            backendLabel = "VLC",
            mediaBadges = listOfNotNull(
                controller.sourceHdrStandard?.label,
                snapshot.runtimeStats?.videoSizeLabel(),
                formatNerdFps(snapshot.runtimeStats?.videoFps),
                snapshot.runtimeStats?.videoCodec,
                snapshot.runtimeStats?.audioFormatLabel() ?: selectedAudioLabel,
            ),
            seekJumpMs = seekJumpMs,
            svpBadge = com.ambient.tv.ui.player.backend.svpBadgeLabel(snapshot.svpState),
            audioBadge = com.ambient.tv.ui.player.audioOutputBadge(controller.audioSession),
            onTogglePlayback = BackgroundPlaybackSession::userToggle,
            onSeekBy = { delta -> userSeekBy(delta); controlsVisible = true },
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
            message = trackMessage,
        ), visible = controlsVisible)

        // Drawn by PersistentPlayerOverlays above the engine, so it survives a renderer switch.
        // Its content is only built with the OSD or a panel on screen, as when it was drawn here.
        if (controlsVisible || panel != UnifiedPlayerPanel.NONE) {
            com.ambient.tv.ui.player.PublishPlayerPanel(com.ambient.tv.ui.player.PlayerPanelModel(
                scrimAlpha = 0.32f,
                projectMBrowser = LocalProjectMBrowser.current,
                audioSession = controller.audioSession,
                showVisualizer = showProjectM,
                hasVideo = snapshot.hasVideo,
                isLive = source.isLive,
                scalingKey = resizeKey,
                aspectKey = aspectKey,
                zoomFactor = zoomFactor,
                speed = speed,
                onScalingChanged = { resizeKey = it; controller.setVideoPresentation(resizeKey, aspectKey.takeUnless { value -> value == "auto" }, zoomFactor) },
                onAspectChanged = { aspectKey = it; controller.setVideoPresentation(resizeKey, it.takeUnless { value -> value == "auto" }, zoomFactor) },
                onZoomChanged = { zoomFactor = it; controller.setVideoPresentation(resizeKey, aspectKey.takeUnless { value -> value == "auto" }, zoomFactor) },
                onSpeedChanged = if (source.isLive || controller.bitstreamPermitted) null else ({ v -> speed = v; controller.setSpeed(v) }),
                audioOptions = audioOptions,
                selectedAudio = selectedAudio,
                onAudioSelected = { selectedAudio = it; it.toIntOrNull()?.let(controller::selectAudioTrack) },
                subtitleOptions = subtitleOptions,
                selectedSubtitle = selectedSubtitle,
                onSubtitleSelected = { controller.selectSubtitleTrack(it.toIntOrNull()?.takeIf { id -> id >= 0 }) },
                sleepTimer = sleepTimer,
                onSleepChanged = { sleepTimer = it },
                rendererKey = rendererKey,
                onRendererSelected = { key, global -> onRendererSelected(key, snapshot.positionMs, global) },
                infoLines = buildList {
                    add(stringResource(R.string.player_nerd_backend) to "VLC")
                    add(stringResource(R.string.player_playback_info) to (source.streamProfile ?: if (source.isLive) "Live TV" else "Recording"))
                    add(stringResource(R.string.player_info_video_output) to stringResource(R.string.player_info_vlc_tvh_output))
                    if (snapshot.hasVideo) {
                        val runtimeVideo = snapshot.runtimeStats
                        listOfNotNull(
                            runtimeVideo?.let { stats -> if (stats.videoWidth != null && stats.videoHeight != null) "${stats.videoWidth}×${stats.videoHeight}" else null },
                            formatNerdFps(runtimeVideo?.videoFps),
                            runtimeVideo?.videoCodec?.takeIf { it.isNotBlank() }?.uppercase(),
                        ).joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_video) to it) }
                        formatNerdBitrate(runtimeVideo?.videoBitrateBitsPerSecond)?.let { add(stringResource(R.string.player_nerd_video_bitrate) to it) }
                        add(stringResource(R.string.player_nerd_scaler_configured) to controller.currentScalingFilter())
                        add(stringResource(R.string.player_nerd_image_processing_configured) to if (controller.imageOptions.softwareProcessing) "Software / SDR" else "Hardware")
                        (context as? android.app.Activity)?.let(androidx.core.content.ContextCompat::getDisplayOrDefault)?.refreshRate?.takeIf { it > 0f }?.let { add(stringResource(R.string.player_nerd_display_refresh) to formatNerdHz(it.toDouble()).orEmpty()) }
                    }
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
                    add(stringResource(R.string.player_info_audio_output) to if (controller.bitstreamPermitted) "HDMI Passthrough angefordert" else "PCM")
                    (snapshot.runtimeStats?.audioFormatLabel() ?: audioOptions.firstOrNull { it.first == selectedAudio }?.second)?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_audio) to it) }
                    snapshot.runtimeStats?.let { stats ->
                        formatNerdBitrate(stats.audioBitrateBitsPerSecond)?.let { add(stringResource(R.string.player_nerd_audio_bitrate) to it) }
                        stats.decodedAudio?.let { add(stringResource(R.string.player_nerd_decoded_audio_buffers) to it.toString()) }
                        stats.playedAudioBuffers?.let { add(stringResource(R.string.player_nerd_played_audio_buffers) to it.toString()) }
                        stats.lostAudioBuffers?.takeIf { it > 0 }?.let { add(stringResource(R.string.player_nerd_lost_audio_buffers) to it.toString()) }
                    }
                },
                onDeinterlaceChanged = onDeinterlaceChanged,
            ))
        }
    }
    }
}

private fun formatVlcClock(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

private fun formatVlcDuration(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)
