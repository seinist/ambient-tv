package com.ambient.tv.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerExitButton
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.PlayerSleepTimerButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.UnifiedBackendPlayerPanel
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.projectm.LocalProjectMBrowser
import com.ambient.tv.ui.common.projectm.ProjectMBrowser

/**
 * What the player panel shows, supplied by the active engine. The panel itself is drawn once by
 * [PersistentPlayerOverlays] above the engine, so it stays open, focused and unchanged while the
 * engine underneath is replaced (renderer switch). Fields mirror [UnifiedBackendPlayerPanel].
 */
internal data class PlayerPanelModel(
    /** Dim behind the panel; kept per engine as before. */
    val scrimAlpha: Float,
    /** Engine-specific extra work on a panel change (Media3 also keeps its OSD up); default: just open. */
    val onPanelChange: ((UnifiedPlayerPanel) -> Unit)? = null,
    val projectMBrowser: ProjectMBrowser?,
    val audioSession: AudioSession?,
    val hasVideo: Boolean,
    val isLive: Boolean,
    val scalingKey: String,
    val aspectKey: String,
    val zoomFactor: Float,
    val speed: Float,
    val onScalingChanged: ((String) -> Unit)?,
    val onAspectChanged: ((String) -> Unit)?,
    val onZoomChanged: ((Float) -> Unit)?,
    val onSpeedChanged: ((Float) -> Unit)?,
    val audioOptions: List<Pair<String, String>>,
    val selectedAudio: String,
    val onAudioSelected: (String) -> Unit,
    val subtitleOptions: List<Pair<String, String>>,
    val selectedSubtitle: String,
    val onSubtitleSelected: (String) -> Unit,
    val sleepTimer: SleepTimerConfig,
    val onSleepChanged: (SleepTimerConfig) -> Unit,
    val infoLines: List<Pair<String, String>>,
    val qualityOptions: List<Pair<String, String>> = emptyList(),
    val selectedQuality: String = "auto",
    val onQualitySelected: ((String) -> Unit)? = null,
    val rendererKey: String? = null,
    val onRendererSelected: ((String, Boolean) -> Unit)? = null,
    val showRenderer: Boolean = true,
    val showVideo: Boolean = true,
    val showAudio: Boolean = true,
    val showSubtitles: Boolean = true,
    val showVisualizer: Boolean = false,
    val settingsExtrasTitle: String? = null,
    val hasVideoExtras: Boolean = false,
    val videoExtrasTitle: String? = null,
    val audioExtrasTitle: String? = null,
    val onDeinterlaceChanged: ((String, String) -> Unit)? = null,
    val settingsExtras: @Composable ColumnScope.() -> Unit = {},
    val videoExtras: @Composable ColumnScope.() -> Unit = {},
    val audioExtras: @Composable ColumnScope.() -> Unit = {},
)

/**
 * Called by an engine where it used to draw the panel. Inside a persistent player chrome the
 * model is handed up to [PersistentPlayerOverlays]; without one the panel is drawn right here.
 */
@Composable
internal fun PublishPlayerPanel(model: PlayerPanelModel) {
    val chrome = LocalPlayerChromeState.current
    if (chrome == null) {
        Box(Modifier.fillMaxSize()) { PlayerPanelContent(model, LocalPlayerPanelNavigation.current) }
        return
    }
    SideEffect { chrome.panelModel.value = model }
    // Without an engine (between engines, or a failed start) nothing stale stays on screen. A
    // replacing engine publishes in the same frame: disposals run before its side effects.
    DisposableEffect(chrome) { onDispose { chrome.panelModel.value = null } }
}

/**
 * The engine's remote handling, so keys keep working while the focus is inside the OSD or the
 * panel (which are no longer part of the engine's own layout).
 */
@Composable
internal fun PublishPlayerPanelKeys(handler: (KeyEvent) -> Boolean) {
    val chrome = LocalPlayerChromeState.current ?: return
    SideEffect { chrome.panelKeys.value = handler }
    DisposableEffect(chrome) { onDispose { chrome.panelKeys.value = null } }
}

/**
 * Draws the OSD and the panel of the active engine above it; place after the engine's content.
 * Both stay composed while the engine underneath is replaced (renderer switch).
 */
@Composable
internal fun PersistentPlayerOverlays() {
    val chrome = LocalPlayerChromeState.current ?: return
    val navigation = chrome.panels
    // Inside the engine's layout, a hiding OSD handed the focus back to the engine's focusable
    // root (its parent). Up here the OSD has no such parent, so hand it back explicitly.
    var hadFocus by remember { mutableStateOf(false) }
    var focusLeft by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusLeft) {
        if (focusLeft == 0) return@LaunchedEffect
        withFrameNanos { }
        // An open panel owns recovery when a capability/track row is replaced.
        // Returning to the engine here strands the still-visible modal.
        if (navigation.panel == UnifiedPlayerPanel.NONE && !chrome.hasFocus)
            runCatching { chrome.engineFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().onFocusChanged { state ->
        if (hadFocus && !state.hasFocus) focusLeft++
        hadFocus = state.hasFocus
    }.onPreviewKeyEvent { event ->
        // Keys reach this only while the focus is in the OSD or the panel: the engine's remote
        // handling decides, exactly as when both were part of the engine's own layout.
        chrome.panelKeys.value?.invoke(event) == true
    }) {
        CompositionLocalProvider(LocalPlayerPanelNavigation provides navigation) {
            chrome.osdModel.value?.Content(chrome.visible.value, navigation.panel)
            chrome.panelModel.value?.let { PlayerPanelContent(it, navigation) }
        }
    }
}

private data class PlayerPanelVisualTarget(val panel: UnifiedPlayerPanel, val depth: Int)

@Composable
private fun PlayerPanelContent(model: PlayerPanelModel, navigation: com.ambient.tv.ui.common.PlayerPanelNavigation?) {
    val panel = navigation?.panel ?: UnifiedPlayerPanel.NONE
    val session = model.audioSession
    DisposableEffect(session) {
        activeDiagnosticsSession = session
        onDispose { if (activeDiagnosticsSession === session) activeDiagnosticsSession = null }
    }
    CompositionLocalProvider(LocalProjectMBrowser provides model.projectMBrowser) {
        PlayerPanelVisibility(panel) { displayedPanel ->
            val motion = LocalPlayerPanelMotion.current
            val outerInteractive = LocalPlayerPanelInteractive.current
            val stateHolder = LocalPlayerPanelStateHolder.current
            val offsetPx = with(LocalDensity.current) { PLAYER_PANEL_CONTENT_OFFSET_DP.dp.roundToPx() }
            val target = PlayerPanelVisualTarget(displayedPanel, motion.visualDepth)
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = model.scrimAlpha * motion.scrimProgress.value }
                        .background(Color.Black),
                )
                AnimatedContent(
                    targetState = target,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        if (motion.reducedEffects) {
                            fadeIn(tween(PLAYER_PANEL_REDUCED_MS)) togetherWith
                                fadeOut(tween(PLAYER_PANEL_REDUCED_MS))
                        } else {
                            when (playerPanelContentMotion(initialState.depth, targetState.depth)) {
                                PlayerPanelContentMotion.FORWARD ->
                                    (fadeIn(tween(PLAYER_PANEL_CONTENT_FADE_MS)) +
                                        slideInHorizontally(
                                            tween(PLAYER_PANEL_CONTENT_MS, easing = FastOutSlowInEasing),
                                        ) { offsetPx }) togetherWith
                                        (fadeOut(tween(PLAYER_PANEL_CONTENT_FADE_MS)) +
                                            slideOutHorizontally(
                                                tween(PLAYER_PANEL_CONTENT_MS, easing = FastOutSlowInEasing),
                                            ) { -offsetPx / 2 })
                                PlayerPanelContentMotion.BACKWARD ->
                                    (fadeIn(tween(PLAYER_PANEL_CONTENT_FADE_MS)) +
                                        slideInHorizontally(
                                            tween(PLAYER_PANEL_CONTENT_MS, easing = FastOutSlowInEasing),
                                        ) { -offsetPx }) togetherWith
                                        (fadeOut(tween(PLAYER_PANEL_CONTENT_FADE_MS)) +
                                            slideOutHorizontally(
                                                tween(PLAYER_PANEL_CONTENT_MS, easing = FastOutSlowInEasing),
                                            ) { offsetPx / 2 })
                                PlayerPanelContentMotion.REPLACE ->
                                    fadeIn(tween(PLAYER_PANEL_CONTENT_FADE_MS)) togetherWith
                                        fadeOut(tween(PLAYER_PANEL_CONTENT_FADE_MS))
                            }
                        }
                    },
                    label = "playerPanelLevel",
                ) { visual ->
                    val childInteractive = outerInteractive && visual == target
                    CompositionLocalProvider(LocalPlayerPanelInteractive provides childInteractive) {
                    Box(Modifier.fillMaxSize().playerPanelInteractionGuard(childInteractive)) {
                        val drawPanel: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
                            UnifiedBackendPlayerPanel(
                                panel = visual.panel,
                                onPanelChange = { next -> model.onPanelChange?.invoke(next) ?: navigation?.open(next) },
                                hasVideo = model.hasVideo,
                                isLive = model.isLive,
                                scalingKey = model.scalingKey,
                                aspectKey = model.aspectKey,
                                zoomFactor = model.zoomFactor,
                                speed = model.speed,
                                onScalingChanged = model.onScalingChanged,
                                onAspectChanged = model.onAspectChanged,
                                onZoomChanged = model.onZoomChanged,
                                onSpeedChanged = model.onSpeedChanged,
                                audioOptions = model.audioOptions,
                                selectedAudio = model.selectedAudio,
                                onAudioSelected = model.onAudioSelected,
                                subtitleOptions = model.subtitleOptions,
                                selectedSubtitle = model.selectedSubtitle,
                                onSubtitleSelected = model.onSubtitleSelected,
                                sleepTimer = model.sleepTimer,
                                onSleepChanged = model.onSleepChanged,
                                infoLines = model.infoLines,
                                qualityOptions = model.qualityOptions,
                                selectedQuality = model.selectedQuality,
                                onQualitySelected = model.onQualitySelected,
                                rendererKey = model.rendererKey,
                                onRendererSelected = model.onRendererSelected,
                                showRenderer = model.showRenderer,
                                showVideo = model.showVideo,
                                showAudio = model.showAudio,
                                showSubtitles = model.showSubtitles,
                                showVisualizer = model.showVisualizer,
                                settingsExtrasTitle = model.settingsExtrasTitle,
                                hasVideoExtras = model.hasVideoExtras,
                                videoExtrasTitle = model.videoExtrasTitle,
                                audioExtrasTitle = model.audioExtrasTitle,
                                audioSession = model.audioSession,
                                onDeinterlaceChanged = model.onDeinterlaceChanged,
                                settingsExtras = model.settingsExtras,
                                videoExtras = model.videoExtras,
                                audioExtras = model.audioExtras,
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                        if (stateHolder != null) {
                            stateHolder.SaveableStateProvider(visual.panel.name) { drawPanel() }
                        } else {
                            drawPanel()
                        }
                    }
                    }
                }
            }
        }
    }
}

/**
 * An OSD layout drawn by [PersistentPlayerOverlays]. Each layout is one implementation, so the
 * engines sharing it keep one composed OSD across a renderer switch.
 */
internal interface PlayerOsdSpec {
    /** [visible]: the shared OSD visibility; [panel]: the open panel. */
    @Composable
    fun Content(visible: Boolean, panel: UnifiedPlayerPanel)
}

/**
 * The OSD of films, episodes, files and DLNA (Media3, VLC and MPV): the active engine supplies its
 * values and actions, [PersistentPlayerOverlays] draws it. Fields keep each engine's former details.
 */
internal data class PlayerOsdModel(
    val state: PlayerUiState.Ready,
    val positionMs: Long,
    val durationMs: Long,
    /** Media3 shows the time line also without a known duration; VLC/MPV only with one. */
    val timelineAlways: Boolean,
    val clockEndPadding: androidx.compose.ui.unit.Dp,
    val isPlaying: Boolean,
    val loading: Boolean,
    val seekState: PlaybackSeekState,
    val onSeekBarSeek: (Long) -> Unit,
    val onSeekBarInteraction: () -> Unit,
    val onSeekBarToggle: () -> Unit,
    val onSeekBarPlay: () -> Unit,
    /** The rewind/forward buttons (delta in ms). */
    val onSeekButton: (Long) -> Unit,
    val onPlayPauseButton: () -> Unit,
    val onPlayNext: (String) -> Unit,
    val onBack: () -> Unit,
    val onPanelChange: (UnifiedPlayerPanel) -> Unit,
    /** Audio-only Media3: the visualizer takes the video button's place. */
    val visualizerButton: Boolean = false,
    /** The engine's technical badges (engine, mode, resolution, codecs). */
    val badges: @Composable () -> Unit,
) : PlayerOsdSpec {
    @Composable
    override fun Content(visible: Boolean, panel: UnifiedPlayerPanel) = PlayerOsdContent(this, visible, panel)
}

/** Called by an engine where it used to draw its OSD; see [PublishPlayerPanel]. */
@Composable
internal fun PublishPlayerOsd(model: PlayerOsdSpec, visible: Boolean) {
    val chrome = LocalPlayerChromeState.current
    if (chrome == null) {
        // No persistent chrome: drawn right here with the engine's own visibility.
        Box(Modifier.fillMaxSize()) {
            model.Content(visible, LocalPlayerPanelNavigation.current?.panel ?: UnifiedPlayerPanel.NONE)
        }
        return
    }
    SideEffect { chrome.osdModel.value = model }
    DisposableEffect(chrome) { onDispose { chrome.osdModel.value = null } }
}

@Composable
private fun PlayerOsdContent(model: PlayerOsdModel, visible: Boolean, panel: UnifiedPlayerPanel) {
    val seekFocusRequester = rememberPlayerSeekFocus()
    val playFocusRequester = rememberPlayerPlayFocus()
    PlayerOverlayVisibility(visible = visible) {
        ReferencePlayerOverlayBackground()
        ReferencePlayerClock(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = model.clockEndPadding),
        )
        ReferencePlayerBottomContent(onTogglePlayback = model.onSeekBarToggle) {
            PlayerNowPlayingHeader(model.state)
            if (model.timelineAlways || model.durationMs > 0L) {
                TvPlayerSeekBar(
                    positionMs = model.seekState.positionMs,
                    seekState = model.seekState,
                    durationMs = model.durationMs,
                    onSeekBy = model.onSeekBarSeek,
                    onInteraction = model.onSeekBarInteraction,
                    onTogglePlayback = model.onSeekBarToggle,
                    onPlay = model.onSeekBarPlay,
                    modifier = Modifier.focusRequester(seekFocusRequester),
                )
            }
            model.badges()
            val state = model.state
            val nextItem = state.nextAudioTrack ?: state.nextVideoItem
            PlayerControlBar(
                player = com.ambient.tv.ui.player.OsdPlayer.MEDIA,
                leftContent = {
                    val seekJumpMs = BackgroundPlaybackSession.seekJumpMs(state.seekJumpMs)
                    val seekJumpSeconds = (seekJumpMs / 1000L).toInt().coerceAtLeast(1)
                    PlayerIconButton(Icons.Filled.FastRewind, stringResource(R.string.player_seek_back, seekJumpSeconds), { model.onSeekButton(-BackgroundPlaybackSession.seekJumpMs(state.seekJumpMs)) }, osd = com.ambient.tv.ui.player.OsdButton.SEEK)
                    PlayerIconButton(
                        if (model.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (model.isPlaying) R.string.player_pause else R.string.player_play),
                        model.onPlayPauseButton,
                        modifier = Modifier.focusRequester(playFocusRequester),
                        primary = true,
                        loading = model.loading,
                    )
                    PlayerIconButton(Icons.Filled.Stop, stringResource(R.string.player_stop), BackgroundPlaybackSession::stop, osd = com.ambient.tv.ui.player.OsdButton.STOP)
                    PlayerIconButton(Icons.Filled.FastForward, stringResource(R.string.player_seek_forward, seekJumpSeconds), { model.onSeekButton(BackgroundPlaybackSession.seekJumpMs(state.seekJumpMs)) }, osd = com.ambient.tv.ui.player.OsdButton.SEEK)
                    nextItem?.let { next ->
                        PlayerIconButton(
                            Icons.Filled.SkipNext,
                            stringResource(if (state.nextEpisode != null) R.string.player_next_episode else R.string.player_next_item),
                            { model.onPlayNext(next.ratingKey) },
                            osd = com.ambient.tv.ui.player.OsdButton.NEXT,
                        )
                    }
                },
                rightContent = {
                    PlayerExitButton(model.onBack)
                    PlayerIconButton(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.player_audio), { model.onPanelChange(UnifiedPlayerPanel.AUDIO) }, selected = panel.isAudio, osd = com.ambient.tv.ui.player.OsdButton.AUDIO)
                    PlayerIconButton(Icons.Filled.Subtitles, stringResource(R.string.player_subtitles), { model.onPanelChange(UnifiedPlayerPanel.SUBTITLES) }, selected = panel == UnifiedPlayerPanel.SUBTITLES, osd = com.ambient.tv.ui.player.OsdButton.SUBTITLES)
                    // Audio-only: MilkDrop takes the video slot; the renderer lives under settings.
                    if (model.visualizerButton) {
                        PlayerIconButton(Icons.Filled.GraphicEq, stringResource(R.string.player_visualizer), { model.onPanelChange(UnifiedPlayerPanel.VISUALIZER) }, selected = panel.isVisualizer, osd = com.ambient.tv.ui.player.OsdButton.VISUALIZER)
                    } else {
                        PlayerIconButton(Icons.Filled.Movie, stringResource(R.string.player_video), { model.onPanelChange(UnifiedPlayerPanel.VIDEO) }, selected = panel.isVideo, osd = com.ambient.tv.ui.player.OsdButton.VIDEO)
                    }
                    PlayerSleepTimerButton({ model.onPanelChange(UnifiedPlayerPanel.SLEEP) }, selected = panel == UnifiedPlayerPanel.SLEEP)
                    com.ambient.tv.ui.common.PlayerDiagnosticsButtons()
                    com.ambient.tv.ui.common.PlayerToolButtons()
                    PlayerIconButton(Icons.Filled.Settings, stringResource(R.string.player_playback_settings), { model.onPanelChange(UnifiedPlayerPanel.SETTINGS) }, selected = panel != UnifiedPlayerPanel.NONE)
                },
            )
        }
    }
}
