package com.ambient.tv.ui.radio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ambient.tv.R
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.data.radio.localizedLanguageNames
import com.ambient.tv.ui.common.AudioPlaybackVisualizer
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerExitButton
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.PlayerSleepTimerButton
import com.ambient.tv.ui.common.SystemLogo
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.projectm.ProjectMAvailability
import com.ambient.tv.ui.common.projectm.ProjectMVisualizer
import com.ambient.tv.ui.common.projectm.rememberProjectMBrowser
import com.ambient.tv.ui.common.rememberPlayerPanelNavigation
import com.ambient.tv.ui.common.systemName
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.OsdContent
import com.ambient.tv.ui.player.OsdKeyState
import com.ambient.tv.ui.player.OsdKeyTarget
import com.ambient.tv.ui.player.OsdSeekSteps
import com.ambient.tv.ui.player.PersistentPlayerChrome
import com.ambient.tv.ui.player.PersistentPlayerOverlays
import com.ambient.tv.ui.player.PlaybackArtworkCard
import com.ambient.tv.ui.player.PlayerOsdSpec
import com.ambient.tv.ui.player.PlayerOverlayVisibility
import com.ambient.tv.ui.player.PlayerPanelModel
import com.ambient.tv.ui.player.PlayerTechBadgeRow
import com.ambient.tv.ui.player.PublishPlayerOsd
import com.ambient.tv.ui.player.PublishPlayerPanel
import com.ambient.tv.ui.player.ReferencePlayerBottomContent
import com.ambient.tv.ui.player.ReferencePlayerClock
import com.ambient.tv.ui.player.ReferencePlayerHeader
import com.ambient.tv.ui.player.ReferencePlayerOverlayBackground
import com.ambient.tv.ui.player.TvPlayerSeekBar
import com.ambient.tv.ui.player.RequestPlayerOverlayFocus
import com.ambient.tv.ui.player.handlePlayerOsdKey
import com.ambient.tv.ui.player.rememberPlayerControlsVisible
import com.ambient.tv.ui.player.rememberPlayerEngineFocus
import com.ambient.tv.ui.player.rememberPlayerPlayFocus
import com.ambient.tv.ui.player.rememberPlayerSeekFocus
import com.ambient.tv.ui.mobile.deviceDp

@Composable
fun RadioPlayerScreen(
    onBack: () -> Unit,
    viewModel: RadioPlayerViewModel = hiltViewModel(),
) {
    PersistentPlayerChrome(viewModel) {
        RadioPlayerContent(onBack, viewModel)
    }
}

@Composable
private fun RadioPlayerContent(
    onBack: () -> Unit,
    viewModel: RadioPlayerViewModel,
) {
    val session by BackgroundPlaybackSession.snapshot.collectAsState()
    val metadata = session.metadata
    val station = viewModel.currentStation()
    val panelNavigation = rememberPlayerPanelNavigation()
    var panel by panelNavigation
    var controlsVisible by rememberPlayerControlsVisible()
    val osd = com.ambient.tv.ui.player.rememberPlayerOsdController()
    val rootFocusRequester = rememberPlayerEngineFocus()
    val playFocusRequester = rememberPlayerPlayFocus()
    val audioSession = BackgroundPlaybackSession.currentAudioSession()
    val radioSeekable = BackgroundPlaybackSession.canSeekCurrentRadioStream()
    val projectMBrowser = rememberProjectMBrowser(viewModel.projectmPresetCategoryKey)

    LaunchedEffect(metadata?.source) {
        if (metadata != null && metadata.source != "radio") onBack()
    }

    BackHandler(enabled = panel == UnifiedPlayerPanel.NONE) {
        if (controlsVisible) {
            runCatching { rootFocusRequester.requestFocus() }
            controlsVisible = false
        } else {
            onBack()
        }
    }

    com.ambient.tv.ui.player.PlayerControlsAutoHide(
        controlsVisible,
        session.playing,
        panel != UnifiedPlayerPanel.NONE,
    ) { visible ->
        if (!visible) runCatching { rootFocusRequester.requestFocus() }
        controlsVisible = visible
    }

    RequestPlayerOverlayFocus(
        requester = playFocusRequester,
        enabled = controlsVisible && panel == UnifiedPlayerPanel.NONE,
        playbackStarted = session.playing,
        panelNavigation = panelNavigation,
    )

    CompositionLocalProvider(LocalPlayerPanelNavigation provides panelNavigation) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocusRequester)
                .onPreviewKeyEvent { event ->
                    handlePlayerOsdKey(
                        event.key,
                        event.type == KeyEventType.KeyDown,
                        OsdKeyState(
                            content = OsdContent.LIVE,
                            osdVisible = controlsVisible,
                            panelOpen = panel != UnifiedPlayerPanel.NONE,
                            seekFeedbackVisible = false,
                            canSeekForward = false,
                        ),
                        OsdSeekSteps(0L),
                        object : OsdKeyTarget {
                            override fun isPlaying() = session.playing
                            override fun togglePlayback() = BackgroundPlaybackSession.userToggle()
                            override fun play() = BackgroundPlaybackSession.userPlay()
                            override fun pause() = BackgroundPlaybackSession.userPause()
                            override fun stop() {
                                BackgroundPlaybackSession.stop()
                                onBack()
                            }
                            override fun seek(deltaMs: Long, revealOsd: Boolean) = Unit
                            override fun showOsd() { controlsVisible = true }
                            override fun hideSeekFeedback() { controlsVisible = true }
                            override fun restart() = Unit
                            override fun next() = viewModel.next()
                            override fun previousTrack() = viewModel.previous()
                            override fun zap(delta: Int) {
                                if (delta > 0) viewModel.next() else viewModel.previous()
                            }
                        },
                    )
                }
                .focusable(),
        ) {
            val analyzer = BackgroundPlaybackSession.currentAudioSpectrum()
            when {
                metadata == null || metadata.source != "radio" -> Unit
                viewModel.visualizerMode == "projectm" && ProjectMAvailability.isAvailable -> ProjectMVisualizer(
                    analyzer = analyzer,
                    intervalSeconds = viewModel.projectmPresetIntervalSeconds,
                    transitionSeconds = viewModel.projectmTransitionSeconds,
                    qualityKey = viewModel.projectmQualityKey,
                    categoryKey = viewModel.projectmPresetCategoryKey,
                    showControls = false,
                    browser = projectMBrowser,
                    modifier = Modifier.fillMaxSize(),
                )
                viewModel.visualizerMode != "off" && viewModel.visualizerMode != "cover" -> AudioPlaybackVisualizer(
                    modifier = Modifier.fillMaxSize(),
                    analyzer = analyzer,
                )
                else -> {
                    if (!metadata.artworkUrl.isNullOrBlank()) {
                        PlaybackArtworkCard(
                            artworkUrl = metadata.artworkUrl,
                            fallbackUrls = metadata.artworkFallbackUrls,
                            title = metadata.title,
                            headers = metadata.artworkHeaders,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .align(androidx.compose.ui.Alignment.Center)
                                .size(280.dp),
                        )
                    } else {
                        SystemLogo(
                            MediaSystem.RADIO,
                            Modifier.align(androidx.compose.ui.Alignment.Center),
                            size = 120.dp,
                        )
                    }
                }
            }

            if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
                com.ambient.tv.ui.common.PlayerTouchLayer(
                    onToggleControls = {
                        if (panel == UnifiedPlayerPanel.NONE) {
                            if (!controlsVisible || osd.closing) osd.reveal() else osd.close()
                        }
                    },
                    onTogglePlayback = BackgroundPlaybackSession::userToggle,
                )
            }

            if (metadata != null && metadata.source == "radio") {
                PublishPlayerOsd(
                    RadioOsdModel(
                        station = station,
                        stationName = metadata.title,
                        nowPlaying = metadata.subtitle,
                        artworkUrl = metadata.artworkUrl,
                        artworkFallbackUrls = metadata.artworkFallbackUrls,
                        artworkHeaders = metadata.artworkHeaders,
                        playing = session.playing,
                        positionMs = session.status.positionMs,
                        durationMs = session.status.durationMs,
                        seekable = radioSeekable,
                        favorite = viewModel.favorite,
                        hasPrevious = viewModel.hasPrevious(),
                        hasNext = viewModel.hasNext(),
                        showAudio = audioSession != null,
                        onToggle = BackgroundPlaybackSession::userToggle,
                        onSeekBy = { deltaMs ->
                            // A duration without a seekable Media3 window is not permission to seek.
                            if (BackgroundPlaybackSession.canSeekCurrentRadioStream()) {
                                val current = BackgroundPlaybackSession.snapshot.value.status
                                BackgroundPlaybackSession.seekTo(
                                    radioSeekTarget(current.positionMs, current.durationMs, deltaMs),
                                )
                            }
                        },
                        onPrevious = viewModel::previous,
                        onNext = viewModel::next,
                        onFavorite = viewModel::toggleFavorite,
                        onStop = {
                            BackgroundPlaybackSession.stop()
                            onBack()
                        },
                        onBack = onBack,
                        onOpenAudio = { panel = UnifiedPlayerPanel.AUDIO },
                        onOpenVisualizer = { panel = UnifiedPlayerPanel.VISUALIZER },
                        onOpenSleep = { panel = UnifiedPlayerPanel.SLEEP },
                        onOpenSettings = { panel = UnifiedPlayerPanel.SETTINGS },
                    ),
                    visible = controlsVisible,
                )

                val infoLines = buildList {
                    add(stringResource(R.string.player_nerd_backend) to "Media3")
                    station?.let { current ->
                        current.codec?.takeIf { it.isNotBlank() }?.let {
                            add(stringResource(R.string.player_audio) to it)
                        }
                        current.bitrateKbps?.let {
                            add(stringResource(R.string.player_nerd_audio_bitrate) to "$it kbps")
                        }
                        radioStationSubtitle(
                            current,
                            LocalContext.current.resources.configuration.locales[0],
                        ).takeIf { it.isNotBlank() }?.let {
                            add(stringResource(R.string.player_playback_info) to it)
                        }
                    }
                }
                PublishPlayerPanel(
                    PlayerPanelModel(
                        scrimAlpha = 0.32f,
                        projectMBrowser = projectMBrowser,
                        audioSession = audioSession,
                        hasVideo = false,
                        isLive = true,
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
                        sleepTimer = BackgroundPlaybackSession.sleepTimer,
                        onSleepChanged = BackgroundPlaybackSession::setSleepTimer,
                        infoLines = infoLines,
                        showRenderer = false,
                        showVideo = false,
                        showAudio = audioSession != null,
                        showSubtitles = false,
                        showVisualizer = true,
                    ),
                )
            }

            PersistentPlayerOverlays()
        }
    }
}

/** Station streams are usually unbounded; only actual seekable windows get interactive controls. */
internal enum class RadioTimelineMode { LIVE, PROGRESS, SEEK }

internal fun radioTimelineMode(durationMs: Long, seekable: Boolean): RadioTimelineMode = when {
    durationMs <= 0L -> RadioTimelineMode.LIVE
    seekable -> RadioTimelineMode.SEEK
    else -> RadioTimelineMode.PROGRESS
}

internal fun radioTimelineFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0L) (positionMs.coerceIn(0L, durationMs).toDouble() / durationMs.toDouble()).toFloat()
    else 0f

/** Saturate before addition to avoid overflow from large seek deltas. */
internal fun radioSeekTarget(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
    if (durationMs <= 0L) return 0L
    val position = positionMs.coerceIn(0L, durationMs)
    return if (deltaMs >= 0L) position + deltaMs.coerceAtMost(durationMs - position)
    else position + deltaMs.coerceAtLeast(-position)
}

internal data class RadioOsdModel(
    val station: RadioStation?,
    val stationName: String,
    val nowPlaying: String?,
    val artworkUrl: String?,
    val artworkFallbackUrls: List<String>,
    val artworkHeaders: Map<String, String>,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val seekable: Boolean,
    val favorite: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val showAudio: Boolean,
    val onToggle: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onFavorite: () -> Unit,
    val onStop: () -> Unit,
    val onBack: () -> Unit,
    val onOpenAudio: () -> Unit,
    val onOpenVisualizer: () -> Unit,
    val onOpenSleep: () -> Unit,
    val onOpenSettings: () -> Unit,
) : PlayerOsdSpec {
    @Composable
    override fun Content(visible: Boolean, panel: UnifiedPlayerPanel) {
        val playFocusRequester = rememberPlayerPlayFocus()
        val seekFocusRequester = rememberPlayerSeekFocus()
        val osd = com.ambient.tv.ui.player.rememberPlayerOsdController()
        val header = radioHeaderPresentation(stationName, nowPlaying, systemName(MediaSystem.RADIO))
        PlayerOverlayVisibility(visible = visible) {
            ReferencePlayerOverlayBackground()
            ReferencePlayerClock(
                liveLabel = "LIVE",
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopEnd)
                    .padding(top = 34.dp, end = 48.dp),
            )
            ReferencePlayerBottomContent(onTogglePlayback = onToggle) {
                ReferencePlayerHeader(
                    // Radio matches TVHeadend: station and live status are context,
                    // the current track is the headline and the artist is its subtitle.
                    modifier = Modifier.heightIn(min = deviceDp(104.dp, 56.dp)),
                    title = header.title,
                    contextLine = header.contextLine + "  •  " + stringResource(R.string.tvh_live).uppercase(),
                    subtitle = header.subtitle,
                    artworkUrl = artworkUrl,
                    artworkFallbackUrls = artworkFallbackUrls,
                    artworkHeaders = artworkHeaders,
                    artworkAspectRatio = 1f,
                )
                // Match Live-TV radio: the timeline is always present under the header.
                // No EPG/duration for an ordinary internet radio stream means a passive
                // live indicator, not a fake zero-length, interactive seek control.
                if (radioTimelineMode(durationMs, seekable) == RadioTimelineMode.SEEK) {
                    TvPlayerSeekBar(
                        positionMs = positionMs.coerceIn(0L, durationMs),
                        durationMs = durationMs,
                        onSeekBy = onSeekBy,
                        onInteraction = osd::interact,
                        onTogglePlayback = onToggle,
                        onPlay = { if (!playing) BackgroundPlaybackSession.userPlay() },
                        showEndTime = false,
                        modifier = Modifier.focusRequester(seekFocusRequester),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { radioTimelineFraction(positionMs, durationMs) },
                        color = com.ambient.tv.ui.theme.LocalPlaybackAccent.current.indicator,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PlayerTechBadgeRow {
                    MetaBadge(systemName(MediaSystem.RADIO), emphasized = true)
                    MetaBadge("LIVE", emphasized = true)
                    station?.codec?.takeIf { it.isNotBlank() }?.let { MetaBadge(it) }
                    station?.bitrateKbps?.let { MetaBadge("$it kbps") }
                    station?.countryCode?.takeIf { it.isNotBlank() }?.let { MetaBadge(it) }
                    station?.localizedLanguageNames(
                        LocalContext.current.resources.configuration.locales[0],
                    )?.firstOrNull()?.let { MetaBadge(it) }
                }
                PlayerControlBar(
                    player = com.ambient.tv.ui.player.OsdPlayer.RADIO,
                    leftContent = {
                        PlayerIconButton(
                            Icons.Filled.SkipPrevious,
                            stringResource(R.string.companion_previous),
                            onPrevious,
                            enabled = hasPrevious,
                            osd = com.ambient.tv.ui.player.OsdButton.NEXT,
                        )
                        PlayerIconButton(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            stringResource(if (playing) R.string.player_pause else R.string.player_play),
                            onToggle,
                            modifier = Modifier.focusRequester(playFocusRequester),
                            primary = true,
                        )
                        PlayerIconButton(
                            Icons.Filled.Stop,
                            stringResource(R.string.player_stop),
                            onStop,
                            osd = com.ambient.tv.ui.player.OsdButton.STOP,
                        )
                        PlayerIconButton(
                            Icons.Filled.SkipNext,
                            stringResource(R.string.companion_next),
                            onNext,
                            enabled = hasNext,
                            osd = com.ambient.tv.ui.player.OsdButton.NEXT,
                        )
                    },
                    rightContent = {
                        PlayerExitButton(onBack)
                        PlayerIconButton(
                            if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            stringResource(if (favorite) R.string.radio_remove_favorite else R.string.radio_add_favorite),
                            onFavorite,
                            selected = favorite,
                            osd = com.ambient.tv.ui.player.OsdButton.FAVORITE,
                        )
                        if (showAudio) {
                            PlayerIconButton(
                                Icons.AutoMirrored.Filled.VolumeUp,
                                stringResource(R.string.player_audio),
                                onOpenAudio,
                                selected = panel.isAudio,
                                osd = com.ambient.tv.ui.player.OsdButton.AUDIO,
                            )
                        }
                        PlayerIconButton(
                            Icons.Filled.GraphicEq,
                            stringResource(R.string.player_visualizer),
                            onOpenVisualizer,
                            selected = panel.isVisualizer,
                            osd = com.ambient.tv.ui.player.OsdButton.VISUALIZER,
                        )
                        PlayerSleepTimerButton(onOpenSleep, selected = panel == UnifiedPlayerPanel.SLEEP)
                        PlayerIconButton(
                            Icons.Filled.Settings,
                            stringResource(R.string.player_playback_settings),
                            onOpenSettings,
                            selected = panel != UnifiedPlayerPanel.NONE,
                        )
                    },
                )
            }
        }
    }
}
