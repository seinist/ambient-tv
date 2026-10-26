package com.ambient.tv.ui.spotify

import com.ambient.tv.ui.player.PlayerPanelVisibility
import com.ambient.tv.ui.player.PlayerOverlayVisibility
import com.ambient.tv.ui.common.rememberPlayerPanelNavigation
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.mobile.deviceDp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.focusable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.player.PlayerTechBadgeRow
import com.ambient.tv.ui.common.ScreensaverArtworkRegistry
import com.ambient.tv.ui.common.AudioPlaybackVisualizer
import com.ambient.tv.ui.common.projectm.LocalProjectMBrowser
import com.ambient.tv.ui.common.projectm.ProjectMVisualizer
import com.ambient.tv.ui.common.projectm.ProjectMAvailability
import com.ambient.tv.ui.common.projectm.rememberProjectMBrowser
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.PlayerExitButton
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.PlayerPanelEntry
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.UnifiedBackendPlayerPanel
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.PLAYER_CONTROLS_TIMEOUT_MS
import com.ambient.tv.ui.player.TvPlayerSeekBar
import com.ambient.tv.ui.player.PlayerSeekModeBar
import com.ambient.tv.ui.player.SleepTimerConfig
import com.ambient.tv.ui.player.ReferencePlayerBottomContent
import com.ambient.tv.ui.player.ReferencePlayerClock
import com.ambient.tv.ui.player.ReferencePlayerHeader
import com.ambient.tv.ui.player.ReferencePlayerOverlayBackground
import com.ambient.tv.ui.player.RequestPlayerOverlayFocus
import com.ambient.tv.ui.player.handlePlayerOsdKey
import com.ambient.tv.ui.player.OsdKeyState
import com.ambient.tv.ui.player.OsdContent
import com.ambient.tv.ui.player.OsdSeekSteps
import com.ambient.tv.ui.player.OsdKeyTarget

@Composable
fun SpotifyPlayerScreen(onBack: () -> Unit, viewModel: SpotifyPlayerViewModel = hiltViewModel()) {
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsState()
    val operationFeedback by viewModel.operationFeedback.collectAsState()
    val track = state.current
    val art = track?.album?.images?.firstOrNull()?.url
    var configuredVisualizer by remember { mutableStateOf(viewModel.visualizerMode) }
    val audioReactive = configuredVisualizer == "spectrum" || configuredVisualizer == "projectm"
    var pcmTapAvailable by remember(configuredVisualizer) { mutableStateOf(true) }
    LaunchedEffect(art) { art?.let { ScreensaverArtworkRegistry.offer(it) } }
    DisposableEffect(audioReactive, lifecycleOwner) {
        fun updatePcm(enabled: Boolean) {
            if (audioReactive) pcmTapAvailable = if (enabled) viewModel.setAudioReactiveVisualsEnabled(true) else {
                viewModel.setAudioReactiveVisualsEnabled(false)
                pcmTapAvailable
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> updatePcm(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> updatePcm(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) updatePcm(true)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (audioReactive) viewModel.setAudioReactiveVisualsEnabled(false)
        }
    }
    val visualizerMode = when {
        configuredVisualizer == "projectm" && !ProjectMAvailability.isAvailable -> "cover"
        audioReactive && !pcmTapAvailable -> "cover"
        else -> configuredVisualizer
    }
    var controlsVisible by remember { mutableStateOf(true) }
    com.ambient.tv.ui.player.SleepWarningKeyGate.ReportPlayerChrome(controlsVisible)
    var seekModeVersion by remember { mutableIntStateOf(0) }
    var seekModeVisible by remember { mutableStateOf(false) }
    val panelNavigation = rememberPlayerPanelNavigation()
    var panel by panelNavigation
    com.ambient.tv.ui.common.OperationFeedbackScope(
        viewModel.feedback, "spotify:player", controlsVisible && panel == UnifiedPlayerPanel.NONE,
    )
    val seekFocusRequester = remember { FocusRequester() }
    val primaryFocusRequester = remember { FocusRequester() }
    val rootFocusRequester = remember { FocusRequester() }
    BackHandler(enabled = panel == UnifiedPlayerPanel.NONE) {
        when {
            handheld -> onBack()
            controlsVisible -> {
                runCatching { rootFocusRequester.requestFocus() }
                controlsVisible = false
            }
            else -> onBack()
        }
    }
    com.ambient.tv.ui.player.PlayerControlsAutoHide(
        controlsVisible, state.playing, panel != UnifiedPlayerPanel.NONE,
    ) { visible ->
        if (!visible) runCatching { rootFocusRequester.requestFocus() }
        controlsVisible = visible
    }
    RequestPlayerOverlayFocus(
        requester = primaryFocusRequester,
        enabled = controlsVisible && panel == UnifiedPlayerPanel.NONE,
        playbackStarted = state.playing,
        panelNavigation = panelNavigation,
    )
    fun heldSeekStep(baseMs: Long, event: androidx.compose.ui.input.key.KeyEvent) =
        com.ambient.tv.ui.player.acceleratedSeekStepMs(baseMs, event.nativeKeyEvent.repeatCount, state.durationMs)
    fun seekInMode(deltaMs: Long) {
        viewModel.seek(deltaMs)
        seekModeVersion++
    }
    LaunchedEffect(seekModeVersion) {
        if (seekModeVersion > 0) {
            seekModeVisible = true
            kotlinx.coroutines.delay(PLAYER_CONTROLS_TIMEOUT_MS)
            seekModeVisible = false
        }
    }
    val projectMBrowser = rememberProjectMBrowser(viewModel.projectmPresetCategoryKey)
    CompositionLocalProvider(LocalProjectMBrowser provides projectMBrowser, LocalPlayerPanelNavigation provides panelNavigation) {
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocusRequester).onPreviewKeyEvent { event ->
        // Shared remote handling (PlayerOsdKeys) for music: −10 s / +30 s jumps, tracks for next/previous.
        handlePlayerOsdKey(
            event.key, event.type == KeyEventType.KeyDown,
            OsdKeyState(OsdContent.MUSIC, controlsVisible, panel != UnifiedPlayerPanel.NONE, seekModeVisible),
            OsdSeekSteps(viewModel.seekJumpMs, backMs = 10_000, forwardMs = 30_000, held = { heldSeekStep(it, event) }),
            object : OsdKeyTarget {
                override fun isPlaying() = state.playing
                override fun togglePlayback() { viewModel.toggle() }
                override fun play() { viewModel.play() }
                override fun pause() { viewModel.pause() }
                // Without a picture there is nothing left to show: Stop also leaves the player.
                override fun stop() { viewModel.stop(); onBack() }
                override fun seek(deltaMs: Long, revealOsd: Boolean) {
                    if (revealOsd) { viewModel.seek(deltaMs); controlsVisible = true } else seekInMode(deltaMs)
                }
                override fun showOsd() { controlsVisible = true }
                override fun hideSeekFeedback() { seekModeVisible = false }
                override fun restart() = Unit
                override fun next() { viewModel.next() }
                override fun previousTrack() { viewModel.previous() }
                override fun zap(delta: Int) = Unit
            },
        )
    }.focusable()) {
        when (visualizerMode) {
            "spectrum" -> AudioPlaybackVisualizer(Modifier.fillMaxSize(), analyzer = viewModel.analyzer)
            "projectm" -> ProjectMVisualizer(
                analyzer = viewModel.analyzer,
                intervalSeconds = viewModel.projectmPresetIntervalSeconds,
                transitionSeconds = viewModel.projectmTransitionSeconds,
                qualityKey = viewModel.projectmQualityKey,
                categoryKey = viewModel.projectmPresetCategoryKey,
                showControls = false,
                browser = projectMBrowser,
                modifier = Modifier.fillMaxSize(),
            )
            "cover" -> if (art != null) {
                val coverMod = if (LocalPerformanceProfile.current.allowBlur) {
                    Modifier.fillMaxSize().blur(42.dp).alpha(0.30f)
                } else {
                    Modifier.fillMaxSize().alpha(0.30f)
                }
                ArtworkImage(art, track.name.orEmpty(), coverMod, contentScale = ContentScale.Crop)
            }
        }
        if (visualizerMode == "spectrum" || visualizerMode == "projectm") {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
        }
        if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
            com.ambient.tv.ui.common.PlayerTouchLayer(
                onToggleControls = { if (panel == UnifiedPlayerPanel.NONE) controlsVisible = !controlsVisible },
                onTogglePlayback = viewModel::toggle,
                onSeekBack = { viewModel.seek(-viewModel.seekJumpMs) },
                onSeekForward = { viewModel.seek(viewModel.seekJumpMs) },
            )
        }
        if (!controlsVisible && seekModeVisible && state.durationMs > 0L) {
            PlayerSeekModeBar(state.positionMs, state.durationMs, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
        }
        PlayerOverlayVisibility(visible = controlsVisible) {
            ReferencePlayerOverlayBackground()
            ReferencePlayerClock(
                liveLabel = null,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = deviceDp(48.dp, 16.dp)),
            )
            ReferencePlayerBottomContent {
                ReferencePlayerHeader(
                    title = track?.name ?: stringResource(R.string.spotify_connect_default_title),
                    artworkUrl = art,
                    contextLine = listOfNotNull(
                        com.ambient.tv.ui.common.systemName(com.ambient.tv.ui.common.MediaSystem.SPOTIFY),
                        (if (state.connected) state.deviceName else stringResource(R.string.spotify_connect_wait)).takeIf { it.isNotBlank() },
                    ).joinToString("  •  "),
                    subtitle = track?.artists?.joinToString(", ") { it.name },
                    artworkAspectRatio = 1f,
                    artworkContentScale = ContentScale.Crop,
                )
                if (state.durationMs > 0L) {
                    TvPlayerSeekBar(
                        state.positionMs,
                        state.durationMs,
                        onSeekBy = viewModel::seek,
                        onInteraction = { controlsVisible = true },
                        onTogglePlayback = viewModel::toggle,
                        onPlay = viewModel::play,
                        modifier = Modifier.focusRequester(seekFocusRequester),
                    )
                }
                PlayerTechBadgeRow {
                    MetaBadge(com.ambient.tv.ui.common.systemName(com.ambient.tv.ui.common.MediaSystem.SPOTIFY), emphasized = true)
                    MetaBadge("NATIVE")
                    if (state.connected) MetaBadge(state.deviceName)
                }
                PlayerControlBar(
                    leftContent = {
                        PlayerIconButton(Icons.Default.SkipPrevious, stringResource(R.string.spotify_previous), viewModel::previous)
                        PlayerIconButton(
                            if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            stringResource(if (state.playing) R.string.spotify_pause else R.string.player_play),
                            viewModel::toggle,
                            modifier = Modifier.focusRequester(primaryFocusRequester),
                            primary = true,
                        )
                        PlayerIconButton(Icons.Default.Stop, stringResource(R.string.player_stop), { viewModel.stop(); onBack() })
                        PlayerIconButton(Icons.Default.SkipNext, stringResource(R.string.spotify_next), viewModel::next)
                    },
                    rightContent = {
                        PlayerExitButton(onBack)
                        com.ambient.tv.ui.common.PlayerSleepTimerButton({ panel = UnifiedPlayerPanel.SLEEP }, selected = panel == UnifiedPlayerPanel.SLEEP)
                        PlayerIconButton(Icons.Default.Shuffle, stringResource(R.string.spotify_shuffle), viewModel::shuffle)
                        PlayerIconButton(Icons.Default.Repeat, stringResource(R.string.spotify_repeat, state.repeatMode), viewModel::repeat)
                        PlayerIconButton(Icons.AutoMirrored.Filled.VolumeDown, stringResource(R.string.spotify_volume_down), { viewModel.volume(-5) })
                        PlayerIconButton(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.spotify_volume_up), { viewModel.volume(5) })
                        PlayerIconButton(Icons.Default.GraphicEq, stringResource(R.string.player_visualizer), { panel = UnifiedPlayerPanel.VISUALIZER }, selected = panel.isVisualizer)
                        PlayerIconButton(Icons.Default.Settings, stringResource(R.string.player_playback_settings), { panel = UnifiedPlayerPanel.SETTINGS }, selected = panel != UnifiedPlayerPanel.NONE && !panel.isVisualizer)
                    },
                )
                val commandError = operationFeedback ?: state.error?.let {
                    com.ambient.tv.notifications.OperationFeedback("spotify:engine", "spotify:player",
                        R.string.feedback_playback_command_failed)
                }
                commandError?.let { com.ambient.tv.ui.common.OperationFeedbackContent(it) }
            }
        }
        PlayerPanelVisibility(panel) { displayedPanel ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
            UnifiedBackendPlayerPanel(
                panel = displayedPanel,
                onPanelChange = { panel = it },
                hasVideo = false,
                isLive = false,
                scalingKey = "fit",
                aspectKey = "auto",
                zoomFactor = 1f,
                speed = 1f,
                onScalingChanged = null,
                onAspectChanged = null,
                onZoomChanged = null,
                onSpeedChanged = null,
                audioOptions = emptyList(),
                selectedAudio = "",
                onAudioSelected = {},
                subtitleOptions = emptyList(),
                selectedSubtitle = "",
                onSubtitleSelected = {},
                sleepTimer = com.ambient.tv.ui.player.SleepSession.config,
                onSleepChanged = com.ambient.tv.ui.player.SleepSession::set,
                infoLines = buildList {
                    add(stringResource(R.string.player_nerd_backend) to "Spotify Native")
                    track?.artists?.joinToString(", ") { it.name }?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_audio) to it) }
                    (if (state.connected) state.deviceName else stringResource(R.string.spotify_connect_wait)).takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_playback_info) to it) }
                },
                showRenderer = false,
                showVideo = false,
                showAudio = false,
                showSubtitles = false,
                showVisualizer = true,
                settingsExtras = {
                    Text(stringResource(R.string.audio_spotify_native_hint), style = MaterialTheme.typography.bodySmall)
                    PlayerPanelEntry(Icons.Default.GraphicEq, stringResource(R.string.spotify_visual, configuredVisualizer), configuredVisualizer, onClick = {
                        configuredVisualizer = when (configuredVisualizer) { "cover" -> "spectrum"; "spectrum" -> "projectm"; "projectm" -> "off"; else -> "cover" }
                        viewModel.setVisualizerMode(configuredVisualizer)
                    })
                    PlayerPanelEntry(Icons.Default.Shuffle, stringResource(R.string.spotify_shuffle), if (state.shuffle) stringResource(R.string.player_on) else stringResource(R.string.player_off), onClick = viewModel::shuffle)
                    PlayerPanelEntry(Icons.Default.Repeat, stringResource(R.string.spotify_repeat, state.repeatMode), state.repeatMode.toString(), onClick = viewModel::repeat)
                    PlayerPanelEntry(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.spotify_volume_up), "${state.volume}%", onClick = { viewModel.volume(5) })
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
    }
}
private fun formatMs(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)
