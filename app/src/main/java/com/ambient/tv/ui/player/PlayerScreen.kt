@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import com.ambient.tv.ui.theme.appControlSurface
import com.ambient.tv.ui.common.rememberPlayerPanelNavigation
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.mobile.deviceDp

import android.app.Activity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.ambient.tv.R
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.ui.theme.Gold
import com.ambient.tv.domain.playback.PlaybackMode
import com.ambient.tv.ui.common.AudioPlaybackVisualizer
import com.ambient.tv.ui.common.projectm.LocalProjectMBrowser
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.common.projectm.ProjectMAvailability
import com.ambient.tv.ui.common.projectm.ProjectMVisualizer
import com.ambient.tv.ui.common.projectm.rememberProjectMBrowser
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.filled.GraphicEq
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.PlayerTouchLayer
import com.ambient.tv.ui.common.LocalPlayerPlayPauseFocus
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerExitButton
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.PlayerSleepTimerButton
import com.ambient.tv.ui.common.PlayerPanelEntry
import com.ambient.tv.ui.common.RightSideLazyPanel
import com.ambient.tv.ui.common.RightPanelSectionTitle
import com.ambient.tv.ui.common.RightPanelChoice
import com.ambient.tv.ui.common.UnifiedBackendPlayerPanel
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.PLAYER_CONTROLS_TIMEOUT_MS
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.focusOnTvPointer
import com.ambient.tv.ui.common.onTvPointerScroll
import com.ambient.tv.ui.common.formatHdrMetadata
import com.ambient.tv.ui.common.rememberUhdScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

private const val POSITION_REFRESH_INTERVAL_MS = 500L
// OSD / D-pad / media rewind-FF all use state.seekJumpMs (settings SEEK).

internal data class PlayerTrackOption(
    val label: String,
    val language: String?,
    val group: Tracks.Group,
    val trackIndex: Int,
    val selected: Boolean,
    val forced: Boolean,
)

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val persistentVideoHost = LocalPersistentPlaybackVideoHost.current

    val onPlayNext: (String) -> Unit = { id ->
        val metadata = BackgroundPlaybackSession.snapshot.value.metadata
        (metadata?.nextUp ?: metadata?.companionNext)?.takeIf { it.mediaId == id }?.let(BackgroundPlaybackSession::recordManualNextUp)
        viewModel.playNext(id)
    }
    RegisterPlayerItemNavigation(viewModel, onPlayNext)
    PersistentPlayerChrome(viewModel, viewModel.changingItem) {
    Box(modifier = Modifier.fillMaxSize().background(if (persistentVideoHost) Color.Transparent else Color.Black)
        .onPreviewKeyEvent { viewModel.changingItem && it.key != Key.Back && it.key != Key.MediaStop }) {
        when (val state = uiState) {
            PlayerUiState.Loading -> PlayerBufferingSpinner(
                visible = true,
                modifier = Modifier.align(Alignment.Center),
            )
            is PlayerUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LaunchedEffect(state.message) {
                    val snapshot = BackgroundPlaybackSession.snapshot.value
                    if (snapshot.replacingSource && viewModel.ownsCompanionMedia(snapshot.metadata?.companionMedia))
                        BackgroundPlaybackSession.failSourceReplacement(snapshot.generation, state.message)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(state.message.ifBlank { stringResource(R.string.player_error) }, color = Color.White, fontSize = 18.sp)
                    // Retry first: after a short outage it is the action people want.
                    com.ambient.tv.ui.mobile.DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (viewModel.canTryAacAudioFallback()) {
                            TvActionButton(text = stringResource(R.string.player_try_aac), onClick = viewModel::tryAacAudioFallback)
                        }
                        TvActionButton(text = stringResource(R.string.home_retry), onClick = viewModel::retryPlayback)
                        TvActionButton(text = stringResource(R.string.back), onClick = onBack)
                    }
                }
            }
            is PlayerUiState.Ready -> PlaybackSessionContent(
                metadata = viewModel.metadata(state),
                timelineKey = "${state.sourceRatingKey}:${state.sourceMediaIndex}",
                timelineLoader = { viewModel.loadTimeline(state.sourceRatingKey, state.sourceMediaIndex) },
                onTimelineSeek = viewModel::seekTimeline,
                onStopped = onBack,
                onPlayNext = { candidate -> viewModel.playNext(candidate.mediaId) },
                onAutoplayChanged = { enabled -> viewModel.setNextUpAutoplay(state.mediaType, enabled) },
                onIntroSkipModeChanged = viewModel::setIntroSkipMode.takeIf { state.mediaType.equals("episode", ignoreCase = true) },
                onCreditsSkipModeChanged = viewModel::setCreditsSkipMode.takeIf {
                    state.mediaType.equals("episode", ignoreCase = true) || state.mediaType.equals("movie", ignoreCase = true)
                },
            ) {
                com.ambient.tv.companion.CompanionSourceControls(state.playbackRoute ?: viewModel.reopenRoute,
                    com.ambient.tv.companion.CompanionPlaybackControls.SourceControls(
                        quality = state.companionVersions.takeIf { it.size > 1 }.orEmpty().map { (key, label) -> com.ambient.tv.companion.CompanionOption(key, label.take(120), key == state.selectedVersionIndex.toString()) },
                        chooseQuality = viewModel::selectCompanionVersion,
                        seek = viewModel::seekTimeline,
                    ))
                PlayerContent(
                state = state,
                viewModel = viewModel,
                onBack = onBack,
                onPlayNext = onPlayNext,
            )
                // Outside the engine branch: a renderer switch replaces the engine, not OSD and panel.
                PersistentPlayerOverlays()
            }
        }
        PlayerBufferingSpinner(visible = viewModel.changingItem, modifier = Modifier.align(Alignment.Center))
    }
    }
}

@Composable
private fun PlayerContent(
    state: PlayerUiState.Ready,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onPlayNext: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val requestDisplayMode = rememberDisplayModeRequest(
        activity = activity,
        playbackKey = "${state.streamUrl}:${state.sourceMediaIndex}",
        adjustmentKey = viewModel.displayRefreshAdjustmentKey(),
        preferredModeKey = viewModel.preferredDisplayModeKey().also { com.ambient.tv.ui.player.PlaybackDisplayModeRevision.tick },
        whitelist = viewModel.displayModeWhitelist(),
        allowDoubleRate = viewModel.allowDoubleRefreshRate(),
        allowThreeTwoPulldown = viewModel.allowThreeTwoPulldown(),
        switchPauseMs = viewModel.displaySwitchPauseMs(),
    )
    val audioOnlyHint = audioOnlyPlaybackHint(state.sourceRatingKey, state.mediaType, state.videoCodec)
    val requestedBackend = com.ambient.tv.ui.player.backend.PlaybackBackendResolver.resolve(state.playbackBackendKey)
    // AUTO already starts on Media3, so projectM is available there. If playback falls back to
    // VLC/MPV (or the user selected one explicitly), never force Media3 again: that would create
    // a Media3 -> fallback -> Media3 loop. Those backends also render projectM using their own decoded PCM.
    val resolvedBackend = requestedBackend
    if (resolvedBackend == com.ambient.tv.ui.player.backend.PlaybackBackend.VLC) {
        VlcPlayerContent(state = state, viewModel = viewModel, onBack = onBack, onPlayNext = onPlayNext, onVideoFormatChanged = requestDisplayMode)
        return
    }
    if (resolvedBackend == com.ambient.tv.ui.player.backend.PlaybackBackend.MPV) {
        MpvPlayerContent(state = state, viewModel = viewModel, onBack = onBack, onPlayNext = onPlayNext, onVideoFormatChanged = requestDisplayMode)
        return
    }

    val metadata = LocalPlaybackMetadata.current
    val exoPlayer = remember(state.streamUrl, state.startPositionMs, state.audioPassthroughEnabled) {
            PlaybackEngineFactory.create(
                context = context,
                streamUrl = state.streamUrl,
                startPositionMs = state.startPositionMs,
                subtitleTracks = if (state.playbackMode == PlaybackMode.DIRECT_PLAY) state.subtitleTracks else emptyList(),
                streamHeaders = state.streamHeaders,
                audioPassthroughEnabled = state.audioPassthroughEnabled,
                metadata = metadata,
                mpvAudioAllowed = viewModel.mpvAudioAllowed(state),
            )
    }

    ObserveMedia3DisplayFormat(exoPlayer, requestDisplayMode, viewModel::applySourceHdrFromRuntime)

    val audioDecoderErrorMessage = stringResource(R.string.player_error_audio_decoder)
    val videoDecoderErrorMessage = stringResource(R.string.player_error_video_decoder)
    val noUsableAudioMessage = stringResource(R.string.player_error_direct_play_no_audio)

    var controlsVisible by com.ambient.tv.ui.player.rememberPlayerControlsVisible()
    val changingItem = com.ambient.tv.ui.player.LocalPlayerChangingItem.current
    var seekFeedbackVisible by remember(exoPlayer) { mutableStateOf(false) }
    val panelNavigation = rememberPlayerPanelNavigation(exoPlayer)
    var panel by panelNavigation
    val audioSession = com.ambient.tv.ui.player.Media3AudioProcessing.session(exoPlayer)
    DisposableEffect(audioSession, exoPlayer) {
        audioSession?.let(viewModel::restoreReloadedAudio)
        audioSession?.conversionLocationChanged = { viewModel.audioConversionLocationChanged(it, exoPlayer.currentPosition) }
        onDispose { audioSession?.conversionLocationChanged = { false } }
    }
    DisposableEffect(audioSession, viewModel.mpvAudioAllowed(state)) {
        audioSession?.updateMpvAllowed(viewModel.mpvAudioAllowed(state))
        onDispose { }
    }
    val nerdStats = rememberMedia3NerdStats(
        exoPlayer,
        audioSession?.performanceHint == true || audioSession?.showFpsOverlay == true,
    )
    LaunchedEffect(exoPlayer) { audioSession?.refreshRoute(context, state.audioCodec) }
    RestoreMedia3PlaybackProfile(exoPlayer, metadata)
    LaunchedEffect(exoPlayer, state.sourceWidth, state.sourceHeight) {
        if (state.sourceWidth != null && state.sourceHeight != null) return@LaunchedEffect
        if (audioOnlyPlaybackHint(state.sourceRatingKey, state.mediaType, state.videoCodec)) return@LaunchedEffect
        while (true) {
            val format = exoPlayer.videoFormat
            if (format != null && format.width > 0 && format.height > 0) {
                viewModel.applyResolutionProfileFromRuntime(format.width, format.height, exoPlayer.currentPosition,
                    detectedDolbyVision = HdrSupport.sourceStandard(format) == HdrStandard.DOLBY_VISION)
                break
            }
            kotlinx.coroutines.delay(350)
        }
    }
    var currentTracks by remember(exoPlayer) { mutableStateOf(exoPlayer.currentTracks) }
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
        key = exoPlayer,
        enginePositionMs = { exoPlayer.currentPosition.coerceAtLeast(0L) + state.timelineOffsetMs },
        commit = viewModel::seekTimeline,
        pollIntervalMs = POSITION_REFRESH_INTERVAL_MS,
        pollEnabled = !performance.pollOsdPositionOnlyWhenChromeVisible || chromeVisible,
    )
    val positionMs = seek.positionMs
    var durationMs by remember(exoPlayer) { mutableLongStateOf(state.durationMs ?: exoPlayer.duration.coerceAtLeast(0)) }
    var isPlaying by remember(exoPlayer) { mutableStateOf(exoPlayer.isPlaying) }
    var playbackState by remember(exoPlayer) { mutableIntStateOf(exoPlayer.playbackState) }
    var playWhenReady by remember(exoPlayer) { mutableStateOf(exoPlayer.playWhenReady) }
    var controlsGeneration by remember(exoPlayer) { mutableIntStateOf(0) }
    var preferencesApplied by remember(exoPlayer) { mutableStateOf(exoPlayer.currentTracks.groups.isNotEmpty()) }
    var playbackEnded by remember(exoPlayer) { mutableStateOf(exoPlayer.playbackState == Player.STATE_ENDED) }
    val initialVideoPresentation = remember(exoPlayer) { BackgroundPlaybackSession.videoPresentation.value }
    var playerResizeMode by remember(exoPlayer) { mutableStateOf(initialVideoPresentation.playerResizeMode()) }
    var aspectPreset by remember(exoPlayer) { mutableStateOf(initialVideoPresentation.playerAspectPreset()) }
    var videoZoom by remember(exoPlayer) { mutableStateOf(initialVideoPresentation.zoom) }
    var seekFeedbackVersion by remember(exoPlayer) { mutableIntStateOf(0) }
    var sleepTimer by com.ambient.tv.ui.player.rememberPlaybackSleepTimer(exoPlayer)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(exoPlayer, playerResizeMode, aspectPreset, videoZoom) {
        (BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)
            ?.takeIf { it.player === exoPlayer }
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

    fun revealControls() {
        controlsVisible = true
        controlsGeneration++
    }

    // Back is owned by the dispatcher, so nested panels/popups close one level per press.
    val handheldUi = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    // INTENTIONAL: remote Back with OSD hidden stops playback.
    // PlayerExitButton ("Minimize (keep playing)") pops and keeps background playback —
    // do not unify these behaviors; Stop remains the explicit stop control in the OSD.
    fun exitOnBack() {
        if (backLeavesPlaybackRunning(audioOnlyHint, handheldUi)) onBack() else BackgroundPlaybackSession.stop()
    }
    androidx.activity.compose.BackHandler(enabled = panel == UnifiedPlayerPanel.NONE) {
        // Handheld: swipe Back leaves the player instead of only hiding the OSD.
        if (controlsVisible && !handheldUi) controlsVisible = false else exitOnBack()
    }

    fun seekBy(deltaMs: Long, reveal: Boolean = true) {
        val absoluteMax = when {
            state.durationMs != null && state.durationMs > 0 -> state.durationMs
            exoPlayer.duration > 0 -> exoPlayer.duration + state.timelineOffsetMs
            else -> Long.MAX_VALUE
        }
        seek.seekBy(deltaMs, absoluteMax)
        if (reveal) revealControls() else seekFeedbackVersion++
    }

    val seekAcceleration = remember(seek) { SeekKeyAcceleration() }
    fun heldSeekStep(baseMs: Long, event: androidx.compose.ui.input.key.KeyEvent): Long {
        val durationMs = state.durationMs?.takeIf { it > 0 } ?: (exoPlayer.duration.takeIf { it > 0 }?.plus(state.timelineOffsetMs) ?: Long.MAX_VALUE)
        return seekAcceleration.step(baseMs, event.nativeKeyEvent.keyCode, event.nativeKeyEvent.eventTime, event.nativeKeyEvent.repeatCount, durationMs)
    }

    LaunchedEffect(seekFeedbackVersion) { if (seekFeedbackVersion > 0) { seekFeedbackVisible = true; delay(PLAYER_CONTROLS_TIMEOUT_MS); seekFeedbackVisible = false } }

    Media3VideoTrackGuard(exoPlayer) {
        viewModel.requestVideoFallback(exoPlayer.currentPosition, videoDecoderErrorMessage)
    }
    DisposableEffect(exoPlayer) {
        val displayHdr = HdrSupport.isDisplayHdrCapable(context)
        Timber.d("Display HDR capable: $displayHdr")
        // Media3 hands HDR to the decoder as is; whether it is tone-mapped for an SDR panel depends
        // on the device, and often it is not (pale, flat picture). Say so once and name the fix.
        val sourceHdr = HdrSupport.sourceStandard(state.videoCodec, state.videoDynamicRange)
        if (!displayHdr && sourceHdr != null && state.playbackMode != PlaybackMode.TRANSCODE) {
            com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
                "playback:hdr-on-sdr", R.string.notice_hdr_on_sdr, subject = sourceHdr.label,
                kind = com.ambient.tv.notifications.NoticeKind.PLAYBACK,
            ))
        }
        Timber.d(
            "Playback mode=${state.playbackMode} reason=${state.playbackReason}; " +
                "source videoCodec=${state.videoCodec} audioCodec=${state.audioCodec} " +
                "container=${state.container}; hwVideo=${state.hardwareVideoDecoder} " +
                "hwAudio=${state.hardwareAudioDecoder}; metadataAudioTracks=${state.audioTracks.size} " +
                "metadataSubtitleTracks=${state.subtitleTracks.size}",
        )

        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val active = BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle
                if (active?.player !== exoPlayer || !viewModel.acceptsPlaybackEvents("media3")) return
                Timber.e(error, "Playback error")
                val rejectedConversion = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()?.responseCode in setOf(400, 406, 415, 422)
                if (state.playbackMode == PlaybackMode.DIRECT_STREAM && viewModel.isServerAc3FallbackActive() && rejectedConversion) {
                    viewModel.requestAudioFallback(exoPlayer.currentPosition, error.message.orEmpty())
                    return
                }
                // Retry a failed local encoder/output as local PCM before considering another
                // video backend. Never request a Plex audio transcode for this recovery.
                if ((error as? ExoPlaybackException)?.rendererName == "LocalAudioRenderer" &&
                    audioSession?.localEncoderFailed == false) {
                    audioSession.localEncoderFailed = true
                    val playing = exoPlayer.playWhenReady
                    val position = exoPlayer.currentPosition
                    exoPlayer.stop()
                    exoPlayer.seekTo(position)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = playing
                    return
                }
                // Network / EOF / timeout against local media servers get a clear unreachable UX
                // (and one optional WoL retry) before decoder fallback logic.
                if (com.ambient.tv.connectivity.PlaybackServerUnreachable.isNetworkish(error)) {
                    viewModel.reportPlaybackException(error)
                    return
                }
                if (state.playbackMode == PlaybackMode.DIRECT_STREAM && viewModel.isServerAc3FallbackActive()) {
                    viewModel.requestAudioFallback(exoPlayer.currentPosition, error.message.orEmpty())
                    return
                }
                val rendererName = (error as? ExoPlaybackException)?.rendererName.orEmpty()
                val message = error.message.orEmpty()
                val failure = playbackFailureKind(rendererName, (error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType, message)

                when {
                    failure == PlaybackFailureKind.AUDIO ->
                        viewModel.requestAudioFallback(exoPlayer.currentPosition, message.ifBlank { audioDecoderErrorMessage })
                    failure == PlaybackFailureKind.VIDEO ->
                        viewModel.requestVideoFallback(exoPlayer.currentPosition, message.ifBlank { videoDecoderErrorMessage })
                    else -> viewModel.reportPlaybackError(message, error)
                }
            }

            override fun onPlaybackStateChanged(newPlaybackState: Int) {
                playbackState = newPlaybackState
                if (newPlaybackState == Player.STATE_READY) {
                    exoPlayer.videoFormat?.let { format ->
                        requestDisplayMode(format.frameRate.takeIf { it > 0f } ?: 0f, format.width.takeIf { it > 0 }, format.height.takeIf { it > 0 })
                    }
                    durationMs = (state.durationMs ?: exoPlayer.duration.takeIf { it > 0 }?.plus(state.timelineOffsetMs) ?: 0L)
                        .coerceAtLeast(0L)
                    Timber.d(
                        "Playing: decoded audioFormat=${exoPlayer.audioFormat} " +
                            "videoFormat=${exoPlayer.videoFormat} volume=${exoPlayer.volume} " +
                            "deviceMuted=${exoPlayer.isDeviceMuted}",
                    )
                    // Tracks can be transiently empty while Media3 is preparing. Check only after
                    // READY has remained stable for a moment; this avoids false AAC fallbacks,
                    // especially on the Android emulator.
                    if ((state.playbackMode == PlaybackMode.DIRECT_PLAY || viewModel.isServerAudioFallbackActive()) && state.audioCodec != null) {
                        coroutineScope.launch {
                            delay(MEDIA3_AUDIO_CHECK_DELAY_MS)
                            if (exoPlayer.playbackState == Player.STATE_READY &&
                                (BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)?.player === exoPlayer) {
                                if (media3AudioUnusable(exoPlayer.currentTracks, exoPlayer.audioFormat, expectAudioWithoutTracks = true)) {
                                    viewModel.requestAudioFallback(
                                        exoPlayer.currentPosition,
                                        noUsableAudioMessage,
                                    )
                                }
                            }
                        }
                    }
                }
                if (newPlaybackState == Player.STATE_ENDED) {
                    playbackEnded = true
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val format = exoPlayer.videoFormat
                requestDisplayMode(
                    format?.frameRate?.takeIf { it > 0f } ?: 0f,
                    videoSize.width.takeIf { it > 0 },
                    videoSize.height.takeIf { it > 0 },
                )
            }

            override fun onTracksChanged(tracks: Tracks) {
                currentTracks = tracks
                exoPlayer.videoFormat?.let { format ->
                    requestDisplayMode(format.frameRate.takeIf { it > 0f } ?: 0f, format.width.takeIf { it > 0 }, format.height.takeIf { it > 0 })
                }
                if (!preferencesApplied && tracks.groups.isNotEmpty()) {
                    preferencesApplied = applySavedTrackPreferences(
                        player = exoPlayer,
                        tracks = tracks,
                        audioLanguage = state.preferredAudioLanguage,
                        subtitleLanguage = state.preferredSubtitleLanguage,
                        subtitlesEnabled = state.subtitlesEnabled,
                        forcedSubtitlesEnabled = state.forcedSubtitlesEnabled,
                        onUnsupportedAudio = { option ->
                            viewModel.requestAudioFallback(exoPlayer.currentPosition, "Preferred main audio track is unsupported")
                        },
                    )
                }
                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val selectedAudioFormat = audioGroups.firstNotNullOfOrNull { group ->
                    (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
                }
                audioSession?.let { session ->
                    // No selected track yet (still preparing): keep the codec known from metadata.
                    if (selectedAudioFormat != null) {
                        session.sourceCodec = Media3AudioProcessing.codecKey(selectedAudioFormat.sampleMimeType)
                            ?: normalizeSourceCodec(selectedAudioFormat.codecs) ?: selectedAudioFormat.sampleMimeType
                        session.sourceChannels = selectedAudioFormat.channelCount.takeIf { it > 0 }
                    }
                    session.refreshRoute(context)
                }
                if (audioGroups.isEmpty()) {
                    Timber.d("Audio track groups are currently empty (may still be preparing)")
                }
                audioGroups.forEach { group ->
                    for (i in 0 until group.length) {
                        Timber.d(
                            "Audio track: format=${group.getTrackFormat(i)} " +
                                "supported=${group.isTrackSupported(i)} selected=${group.isTrackSelected(i)}",
                        )
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                playWhenReady = ready
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            if (metadata == null) {
                Media3ImageProcessing.release(exoPlayer)
                exoPlayer.release()
            }
        }
    }

    LaunchedEffect(exoPlayer, state.timelineOffsetMs) {
        while (isActive) {
            if (state.durationMs != null && state.durationMs > 0) {
                durationMs = state.durationMs
            } else if (exoPlayer.duration > 0) {
                durationMs = exoPlayer.duration + state.timelineOffsetMs
            }
            delay(POSITION_REFRESH_INTERVAL_MS)
        }
    }

    com.ambient.tv.ui.player.PlayerControlsAutoHide(
        controlsVisible, isPlaying, panel != UnifiedPlayerPanel.NONE, changingItem, interactionVersion = controlsGeneration,
    ) { controlsVisible = it }

    val persistentVideoHost = LocalPersistentPlaybackVideoHost.current
    val projectMBrowser = rememberProjectMBrowser(state.projectmPresetCategoryKey)
    // One remote handling for the engine and, while the focus is in the panel above it, the panel host.
    val chapterNavigation = com.ambient.tv.ui.player.LocalPlaybackNavigation.current
    val osdKeys: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        // Shared remote handling (PlayerOsdKeys); only the actions below are Media3's own.
        val restartTitle = { seek.seekTo(0L) }
        handlePlayerOsdKey(
            event.key, event.type == KeyEventType.KeyDown,
            OsdKeyState(OsdContent.VIDEO, controlsVisible, panel != UnifiedPlayerPanel.NONE, seekFeedbackVisible),
            OsdSeekSteps(state.seekJumpMs, held = { heldSeekStep(it, event) }),
            object : OsdKeyTarget {
                override fun chapterSeekDelta(direction: Int) = chapterNavigation?.chapterStep(seek.positionMs, direction)
                // Buffering counts as playing for the explicit play/pause action.
                override fun isPlaying() = media3PauseOnToggle(exoPlayer.playWhenReady, exoPlayer.playbackState)
                override fun togglePlayback() {
                    if (media3PauseOnToggle(exoPlayer.playWhenReady, exoPlayer.playbackState)) exoPlayer.pause() else exoPlayer.play()
                }
                override fun play() { exoPlayer.play() }
                override fun pause() { exoPlayer.pause() }
                override fun stop() { BackgroundPlaybackSession.stop() }
                override fun seek(deltaMs: Long, revealOsd: Boolean) { seekBy(deltaMs, reveal = revealOsd) }
                override fun showOsd() { revealControls() }
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
        modifier = Modifier
            .fillMaxSize()
            .background(if (persistentVideoHost) Color.Transparent else Color.Black)
            .onPreviewKeyEvent(osdKeys)
            .focusRequester(rememberPlayerEngineFocus())
            .focusable(),
    ) {
        if (!persistentVideoHost) Media3VideoTexture(
            player = exoPlayer,
            resizeMode = when (playerResizeMode) {
                PlayerResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                PlayerResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                PlayerResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            },
            aspectRatio = aspectPreset.ratio,
            zoom = videoZoom,
            modifier = Modifier.fillMaxSize(),
            sourceAspectRatioHint = state.sourceAspectRatioHint,
        )

        if (audioOnlyHint) {
            when {
                state.audioVisualizerModeKey == "projectm" && ProjectMAvailability.isAvailable -> ProjectMVisualizer(
                    analyzer = PlaybackEngineFactory.audioSpectrum(exoPlayer),
                    intervalSeconds = state.projectmPresetIntervalSeconds,
                    transitionSeconds = state.projectmTransitionSeconds,
                    qualityKey = state.projectmQualityKey,
                    categoryKey = state.projectmPresetCategoryKey,
                    showControls = controlsVisible,
                    browser = projectMBrowser,
                    modifier = Modifier.fillMaxSize(),
                )
                state.audioVisualizerModeKey != "off" -> AudioPlaybackVisualizer(Modifier.fillMaxSize(), PlaybackEngineFactory.audioSpectrum(exoPlayer))
            }
        }

        PlayerTouchLayer(
            onToggleControls = {
                // A tap on the picture beside an open panel closes the panel first.
                if (panel != UnifiedPlayerPanel.NONE) panelNavigation.close()
                else {
                    controlsVisible = !controlsVisible
                    if (controlsVisible) controlsGeneration++
                }
            },
            onTogglePlayback = {
                if (media3PauseOnToggle(exoPlayer.playWhenReady, exoPlayer.playbackState)) exoPlayer.pause() else exoPlayer.play()
                revealControls()
            },
            onSeekBack = { seekBy(-state.seekJumpMs); revealControls() },
            onSeekForward = { seekBy(state.seekJumpMs); revealControls() },
        )

        if (!controlsVisible && seekFeedbackVisible) {
            PlayerSeekModeBar(positionMs, durationMs, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
        }

        PlayerBufferingSpinner(
            visible = media3WaitingForPlaybackPicture(playbackState, playWhenReady),
            modifier = Modifier.align(Alignment.Center),
        )

        val media3Plan = audioSession?.outputPlan()
        LaunchedEffect(media3Plan?.needsMpv, media3Plan?.mode) {
            if (media3Plan?.needsMpv == true) viewModel.followAudioPlan(exoPlayer.currentPosition)
        }
        PlaybackPerformanceNotice(media3LoadHint(exoPlayer, nerdStats))
        val showNerdOverlay = audioSession?.performanceHint == true
        val showFpsOverlay = audioSession?.showFpsOverlay == true
        PlaybackNerdStatsOverlay(
            audioConversion = audioConversionDiagnostic(context, state, audioSession, exoPlayer.audioFormat?.sampleMimeType, exoPlayer.audioFormat?.channelCount),
            audioRoute = audioSession?.let { audioRouteSummaryText(it) },
            enabled = showNerdOverlay,
            model = rememberMedia3NerdOverlayModel(
                player = exoPlayer,
                stats = nerdStats,
                enabled = showNerdOverlay,
                audioOutputMode = audioSession?.outputPlan()?.mode,
            ),
        )
        PlaybackFpsOverlay(
            enabled = showFpsOverlay,
            fps = rememberMedia3PlaybackFps(exoPlayer, showFpsOverlay && !showNerdOverlay),
            cadenceFps = exoPlayer.videoFormat?.frameRate?.toDouble(),
            refreshHz = rememberDisplayRefreshHz(),
            nerdStatsVisible = showNerdOverlay,
        )

        // Always composed: it hands the OSD and the panel up to PersistentPlayerOverlays, which
        // shows the OSD while controlsVisible (the same shared state).
        PlayerControlsOverlay(
            controlsVisible = controlsVisible,
            state = state,
            hasVideo = !audioOnlyHint,
            showVisualizer = audioOnlyHint,
            exoPlayer = exoPlayer,
            currentTracks = currentTracks,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isBuffering = media3WaitingForPlaybackPicture(playbackState, playWhenReady),
            nerdStats = nerdStats,
            panel = panel,
            onPanelChange = { target ->
                panel = target
                revealControls()
            },
            resizeMode = playerResizeMode,
            aspectPreset = aspectPreset,
            videoZoom = videoZoom,
            onResizeModeChange = { playerResizeMode = it },
            onAspectPresetChange = { aspectPreset = it },
            onVideoZoomChange = { videoZoom = it },
            sleepTimer = sleepTimer,
            onSleepTimerChange = { sleepTimer = it },
            onTogglePlayback = {
                if (media3PauseOnToggle(exoPlayer.playWhenReady, exoPlayer.playbackState)) exoPlayer.pause() else exoPlayer.play()
                revealControls()
            },
            seekState = seek,
            onSeekBy = { deltaMs -> seekBy(deltaMs) },
            onInteraction = ::revealControls,
            sourceAudioSelectionEnabled = viewModel.serverAudioSelectionEnabled(),
            onSourceAudioSelected = { streamId -> viewModel.requestServerAudioTrack(streamId, exoPlayer.currentPosition) },
            onAudioSelected = { option ->
                if (option != null && !option.group.isTrackSupported(option.trackIndex)) {
                    viewModel.rememberAudioLanguage(option.language)
                    viewModel.requestAudioFallback(exoPlayer.currentPosition, "Selected audio track is unsupported")
                } else {
                    selectTrack(exoPlayer, C.TRACK_TYPE_AUDIO, option)
                    viewModel.rememberAudioLanguage(option?.language)
                }
            },
            onSubtitleSelected = { option ->
                selectTrack(exoPlayer, C.TRACK_TYPE_TEXT, option)

            },
            onRendererSelected = { key, global ->
                viewModel.switchPlaybackBackend(
                    key = key,
                    positionMs = exoPlayer.currentPosition,
                    makeGlobal = global,
                    detectedDolbyVision = HdrSupport.sourceStandard(exoPlayer.videoFormat) ==
                        HdrStandard.DOLBY_VISION,
                )
            },
            onBack = onBack,
            onPlayNext = onPlayNext,
        )

    }
    }
}

@Composable
private fun PlayerControlsOverlay(
    controlsVisible: Boolean,
    state: PlayerUiState.Ready,
    hasVideo: Boolean,
    showVisualizer: Boolean = false,
    exoPlayer: ExoPlayer,
    currentTracks: Tracks,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isBuffering: Boolean,
    nerdStats: Media3NerdStats,
    panel: UnifiedPlayerPanel,
    onPanelChange: (UnifiedPlayerPanel) -> Unit,
    resizeMode: PlayerResizeMode,
    aspectPreset: PlayerAspectPreset,
    videoZoom: Float,
    onResizeModeChange: (PlayerResizeMode) -> Unit,
    onAspectPresetChange: (PlayerAspectPreset) -> Unit,
    onVideoZoomChange: (Float) -> Unit,
    sleepTimer: SleepTimerConfig,
    onSleepTimerChange: (SleepTimerConfig) -> Unit,
    onTogglePlayback: () -> Unit,
    seekState: PlaybackSeekState,
    onSeekBy: (Long) -> Unit,
    onInteraction: () -> Unit,
    onAudioSelected: (PlayerTrackOption?) -> Unit,
    onSourceAudioSelected: (Long) -> Unit,
    sourceAudioSelectionEnabled: Boolean,
    onSubtitleSelected: (PlayerTrackOption?) -> Unit,
    onRendererSelected: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    onPlayNext: (String) -> Unit,
) {
    val context = LocalContext.current
    val playFocusRequester = rememberPlayerPlayFocus()
    val displayLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val audioOptions = remember(currentTracks, displayLocale) { trackOptions(currentTracks, C.TRACK_TYPE_AUDIO, displayLocale) }
    val subtitleOptions = remember(currentTracks) { trackOptions(currentTracks, C.TRACK_TYPE_TEXT) }
    var speed by remember(exoPlayer) { mutableStateOf(exoPlayer.playbackParameters.speed) }
    LaunchedEffect(com.ambient.tv.ui.player.Media3AudioProcessing.session(exoPlayer)?.bitstreamPermitted) { speed = exoPlayer.playbackParameters.speed }
    // This used to be composed afresh with every OSD showing, re-reading the speed each time.
    LaunchedEffect(controlsVisible) { if (controlsVisible) speed = exoPlayer.playbackParameters.speed }

    RequestPlayerOverlayFocus(
        requester = playFocusRequester,
        enabled = controlsVisible && panel == UnifiedPlayerPanel.NONE,
        playbackStarted = isPlaying || !isBuffering,
    )

    // Drawn by PersistentPlayerOverlays above the engine, so it survives a renderer switch.
    PublishPlayerOsd(PlayerOsdModel(
        state = state,
        positionMs = positionMs,
        durationMs = durationMs,
        timelineAlways = true,
        clockEndPadding = deviceDp(48.dp, 16.dp),
        isPlaying = isPlaying,
        loading = isBuffering,
        seekState = seekState,
        onSeekBarSeek = { deltaMs -> onSeekBy(deltaMs); onInteraction() },
        onSeekBarInteraction = onInteraction,
        onSeekBarToggle = onTogglePlayback,
        onSeekBarPlay = { exoPlayer.play() },
        onSeekButton = { deltaMs -> onSeekBy(deltaMs); onInteraction() },
        onPlayPauseButton = { onTogglePlayback(); onInteraction() },
        onPlayNext = onPlayNext,
        onBack = onBack,
        onPanelChange = onPanelChange,
        visualizerButton = showVisualizer,
        badges = { if (playerTechBadgesVisible()) PlaybackMediaFlags(state, exoPlayer) },
    ), visible = controlsVisible)
    // The panel content used to be composed only with the OSD on screen; keep that cost.
    if (controlsVisible || panel != UnifiedPlayerPanel.NONE) {
        val serverTracks = state.audioTracks.takeIf { state.serverAudioStreamId != null || sourceAudioSelectionEnabled }
        val audioPairs = serverTracks?.mapNotNull { track -> track.id?.let {
            it.toString() to audioTrackLabel(track.title, track.language, track.channels, track.codec)
        } } ?: audioOptions.mapIndexed { index, option -> index.toString() to option.label }
        val subtitlePairs = listOf("off" to stringResource(R.string.player_subtitles_off)) +
            subtitleOptions.mapIndexed { index, option -> index.toString() to option.label }
        val audioSession = com.ambient.tv.ui.player.Media3AudioProcessing.session(exoPlayer)
        // Drawn by PersistentPlayerOverlays above the engine, so it survives a renderer switch.
        PublishPlayerPanel(PlayerPanelModel(
            scrimAlpha = 0.24f,
            projectMBrowser = LocalProjectMBrowser.current,
            audioSession = audioSession,
            onPanelChange = onPanelChange,
            hasVideo = hasVideo,
            isLive = false,
            scalingKey = when (resizeMode) {
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
                onResizeModeChange(when (key) {
                    "zoom" -> PlayerResizeMode.ZOOM
                    "stretch" -> PlayerResizeMode.STRETCH
                    else -> PlayerResizeMode.FIT
                })
            },
            onAspectChanged = { key ->
                onAspectPresetChange(when (key) {
                    "16:9" -> PlayerAspectPreset.RATIO_16_9
                    "4:3" -> PlayerAspectPreset.RATIO_4_3
                    "21:9" -> PlayerAspectPreset.RATIO_21_9
                    else -> PlayerAspectPreset.AUTO
                })
            },
            onZoomChanged = onVideoZoomChange,
            onSpeedChanged = if ((com.ambient.tv.ui.player.Media3AudioProcessing.session(exoPlayer)?.bitstreamPermitted ?: state.audioPassthroughEnabled)) null else ({ value -> speed = value; exoPlayer.setPlaybackSpeed(value) }),
            audioOptions = audioPairs.ifEmpty { listOf("default" to stringResource(R.string.player_default_track)) },
            selectedAudio = state.serverAudioStreamId?.toString()
                ?: audioOptions.indexOfFirst { it.selected }.takeIf { it >= 0 }?.toString() ?: "default",
            onAudioSelected = { key ->
                if (serverTracks != null) key.toLongOrNull()?.let(onSourceAudioSelected)
                else key.toIntOrNull()?.let(audioOptions::getOrNull)?.let(onAudioSelected)
                onInteraction()
            },
            subtitleOptions = subtitlePairs,
            selectedSubtitle = subtitleOptions.indexOfFirst { it.selected }.takeIf { it >= 0 }?.toString() ?: "off",
            onSubtitleSelected = { key ->
                onSubtitleSelected(key.toIntOrNull()?.let(subtitleOptions::getOrNull))
                onInteraction()
            },
            sleepTimer = sleepTimer,
            onSleepChanged = onSleepTimerChange,
            rendererKey = state.playbackBackendKey,
            onRendererSelected = onRendererSelected,
            showVisualizer = showVisualizer,
            infoLines = buildList {
                add(stringResource(R.string.player_nerd_image_pipeline) to com.ambient.tv.ui.player.Media3ImageProcessing.get(context, exoPlayer).diagnosticSummary())
                add(stringResource(R.string.player_nerd_backend) to "Media3")
                add(stringResource(R.string.player_stream_mode) to playbackModeLabel(state.playbackMode))
                state.container?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_container) to it.uppercase()) }
                state.sourceBitrateKbps?.takeIf { it > 0 }?.let { kbps ->
                    formatNerdBitrate(kbps.toLong() * 1000L)?.let { add(stringResource(R.string.player_nerd_source_bitrate) to it) }
                }
                add(stringResource(R.string.player_info_video_output) to DirectAvDiagnostics.video(context, exoPlayer.videoFormat, "Media3"))
                audioSession?.let { add(stringResource(R.string.audio_route_output) to audioRouteSummaryText(it)) }
                add(stringResource(R.string.player_info_audio_output) to DirectAvDiagnostics.audio(context, (audioSession?.bitstreamPermitted ?: state.audioPassthroughEnabled), state.audioCodec, planMode = audioSession?.outputPlan()?.mode))
                audioConversionDiagnostic(context, state, audioSession, exoPlayer.audioFormat?.sampleMimeType, exoPlayer.audioFormat?.channelCount)?.let { add(stringResource(R.string.audio_conversion_location) to it) }
                exoPlayer.videoFormat?.let { format ->
                    listOfNotNull(
                        if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else null,
                        format.frameRate.takeIf { it > 0f }?.let { "%.3f fps".format(java.util.Locale.US, it) },
                        state.videoCodec?.takeIf { it.isNotBlank() }?.let(::friendlyCodec),
                        state.videoBitDepth?.takeIf { it > 0 }?.let { "$it-bit" },
                    ).joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_video) to it) }
                    formatNerdBitrate(format.bitrate.takeIf { it > 0 }?.toLong())?.let { add(stringResource(R.string.player_nerd_video_bitrate) to it) }
                }
                val hdrLabel = formatHdrMetadata(state.videoDynamicRange, state.videoProfile)
                    ?: HdrSupport.sourceStandard(exoPlayer.videoFormat)?.label?.uppercase()
                hdrLabel?.let { add(stringResource(R.string.player_nerd_hdr) to it) }
                nerdStats.videoDecoderName?.let { add(stringResource(R.string.player_nerd_video_decoder) to it) }
                nerdStats.surfaceSize?.let { add(stringResource(R.string.player_nerd_surface) to it) }
                (context as? Activity)?.let(androidx.core.content.ContextCompat::getDisplayOrDefault)?.refreshRate?.takeIf { it > 0f }?.let { add(stringResource(R.string.player_nerd_display_refresh) to formatNerdHz(it.toDouble()).orEmpty()) }
                nerdStats.renderedVideoFrames?.let { add(stringResource(R.string.player_nerd_rendered_frames) to it.toString()) }
                nerdStats.droppedVideoFrames?.let { add(stringResource(R.string.player_nerd_dropped_frames) to it.toString()) }
                nerdStats.skippedVideoFrames?.takeIf { it > 0 }?.let { add(stringResource(R.string.player_nerd_skipped_frames) to it.toString()) }
                nerdStats.maxConsecutiveDroppedFrames?.takeIf { it > 0 }?.let { add(stringResource(R.string.player_nerd_max_consecutive_drops) to it.toString()) }
                nerdStats.averageVideoProcessingOffsetUs?.let { add(stringResource(R.string.player_nerd_frame_processing_offset) to "%.2f ms".format(java.util.Locale.US, it / 1000.0)) }
                exoPlayer.audioFormat?.let { format ->
                    listOfNotNull(
                        state.audioCodec?.takeIf { it.isNotBlank() }?.let(::friendlyCodec),
                        format.channelCount.takeIf { it > 0 }?.let(::formatChannelCount),
                        format.sampleRate.takeIf { it > 0 }?.let { "%.1f kHz".format(java.util.Locale.US, it / 1000.0) },
                    ).joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_audio) to it) }
                    formatNerdBitrate(format.bitrate.takeIf { it > 0 }?.toLong())?.let { add(stringResource(R.string.player_nerd_audio_bitrate) to it) }
                }
                nerdStats.audioDecoderName?.let { add(stringResource(R.string.player_nerd_audio_decoder) to it) }
                nerdStats.audioUnderruns.takeIf { it > 0 }?.let { add(stringResource(R.string.player_nerd_audio_underruns) to it.toString()) }
                formatNerdDurationMs(nerdStats.bufferDurationMs)?.let { add(stringResource(R.string.player_nerd_buffer_health) to it) }
                formatNerdBitrate(nerdStats.bandwidthEstimateBitsPerSecond)?.let { add(stringResource(R.string.player_nerd_network_throughput) to it) }
            },
        ))
    }
}

@Composable
internal fun PlayerNowPlayingHeader(state: PlayerUiState.Ready) {
    val contextLine = when (state.mediaType) {
        "episode" -> buildList {
            state.grandparentTitle?.takeIf { it.isNotBlank() }?.let(::add)
            val episodeCode = listOfNotNull(
                state.seasonIndex?.let { "S${it.toString().padStart(2, '0')}" },
                state.episodeIndex?.let { "E${it.toString().padStart(2, '0')}" },
            ).joinToString("")
            if (episodeCode.isNotBlank()) add(episodeCode)
        }.joinToString("  •  ")
        else -> state.year?.toString().orEmpty()
    }
    ReferencePlayerHeader(
        title = state.title,
        artworkUrl = if (state.mediaType.equals("episode", ignoreCase = true)) {
            state.posterArtUrl?.takeIf { it.isNotBlank() }
        } else state.artworkUrl,
        contextLine = contextLine,
        subtitle = state.tagline,
        artworkHeaders = state.streamHeaders,
        artworkAspectRatio = if (state.mediaType.equals("episode", ignoreCase = true)) 2f / 3f
        else playbackArtworkAspectRatio(state.mediaType),
    )
}

@Composable
internal fun TvPlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeekBy: (Long) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    seekState: PlaybackSeekState? = null,
    onTogglePlayback: () -> Unit = { BackgroundPlaybackSession.toggle() },
    onPlay: () -> Unit = { BackgroundPlaybackSession.play() },
    timelineContext: (@Composable (Long) -> Unit)? = null,
    showEndTime: Boolean = true,
) {
    val seekable = interactive && durationMs > 0L
    val playPauseFocus = LocalPlayerPlayPauseFocus.current
    val controlFocus = LocalPlayerControlFocus.current
    val timelineFocus = remember { FocusRequester() }
    val timelineOwner = remember { Any() }
    androidx.compose.runtime.DisposableEffect(controlFocus, seekable) {
        if (seekable) controlFocus?.register("seek-bar", timelineFocus, timelineOwner)
        onDispose { if (seekable) controlFocus?.unregister("seek-bar", timelineOwner) }
    }
    // Resolve on each focus search so moving onto the timeline does not lose the originating button.
    val verticalReturn = if (seekable && playPauseFocus != null) {
        Modifier.focusProperties {
            val button = controlFocus?.buttonTarget(playPauseFocus) ?: playPauseFocus
            up = button
            down = button
        }
    } else {
        Modifier
    }
    if (com.ambient.tv.ui.mobile.LocalHandheldUi.current && seekable) {
        com.ambient.tv.ui.mobile.HandheldSeekBar(positionMs, durationMs, onSeekBy, onInteraction, modifier, showEndTime)
        return
    }
    val navigation = LocalPlaybackNavigation.current
    // Pointer gestures read the latest values; keying them on position restarted a drag every tick.
    val latestSeekState by androidx.compose.runtime.rememberUpdatedState(seekState)
    val latestPositionMs by androidx.compose.runtime.rememberUpdatedState(positionMs)
    val latestDurationMs by androidx.compose.runtime.rememberUpdatedState(durationMs)
    val latestOnSeekBy by androidx.compose.runtime.rememberUpdatedState(onSeekBy)
    val latestOnInteraction by androidx.compose.runtime.rememberUpdatedState(onInteraction)
    // Input must read the live target: the persistent OSD model may be a frame behind.
    fun currentPosition() = latestSeekState?.positionMs ?: latestPositionMs
    fun submitTarget(target: Long) {
        latestSeekState?.seekTo(target) ?: latestOnSeekBy(target - currentPosition())
    }
    val seekAcceleration = remember(seekState) { SeekKeyAcceleration() }
    var pendingTarget by remember { mutableStateOf<Long?>(null) }
    var pointerTarget by remember { mutableStateOf<Long?>(null) }
    var lastPreview by remember { mutableStateOf<Long?>(null) }
    var previewVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(previewVersion) { if (pendingTarget == null) { kotlinx.coroutines.delay(1_100); lastPreview = null } }
    val displayedPosition = pointerTarget ?: pendingTarget ?: positionMs
    // Seek feedback: the preview only accompanies a jump. It opens with the bar (a jump showed it),
    // reopens on every further jump and closes 1.1 s after the last one; the bar itself stays.
    var feedbackPreview by remember { mutableStateOf(!interactive) }
    var feedbackJumps by remember { mutableIntStateOf(0) }
    var feedbackAnchor by remember { mutableLongStateOf(positionMs) }
    if (!interactive && kotlin.math.abs(positionMs - feedbackAnchor) > SEEK_FEEDBACK_JUMP_MS) { feedbackPreview = true; feedbackJumps++ }
    feedbackAnchor = positionMs
    LaunchedEffect(feedbackPreview, feedbackJumps) {
        if (feedbackPreview) { kotlinx.coroutines.delay(1_100); feedbackPreview = false }
    }
    val stowing = com.ambient.tv.ui.player.LocalPlayerOsdStow.current?.closing == true
    var focused by remember { mutableStateOf(false) }
    val progress = if (durationMs > 0) (displayedPosition.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val remaining = (durationMs - displayedPosition).coerceAtLeast(0L)
    val highlighted = focused || pointerTarget != null || !interactive

    val interactionModifier = if (seekable) {
        Modifier
            .focusRequester(timelineFocus)
            .onFocusChanged {
                focused = it.isFocused
                controlFocus?.focused("seek-bar", timelineOwner, it.isFocused)
                if (!it.isFocused) { pendingTarget = null; lastPreview = null }
            }
            .onPreviewKeyEvent { event ->
                val confirmKey = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter
                if (confirmKey) {
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                        navigation?.interact(); onInteraction()
                        val confirm = confirmSeekBarOk(pendingTarget, lastPreview, currentPosition())
                        confirm.seekDeltaMs?.let { submitTarget(currentPosition() + it) }
                        pendingTarget = null
                        lastPreview = null
                        if (confirm.resumePlayback) onPlay()
                        if (confirm.togglePlayback) onTogglePlayback()
                        previewVersion++
                    }
                    return@onPreviewKeyEvent true
                }
                val direction = when (event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> 0 }
                if (direction == 0 || durationMs <= 0) return@onPreviewKeyEvent false
                navigation?.interact(); onInteraction()
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        val base = pendingTarget ?: currentPosition()
                        pendingTarget = (base + direction * seekAcceleration.step(10_000L, event.nativeKeyEvent.keyCode, event.nativeKeyEvent.eventTime, event.nativeKeyEvent.repeatCount, durationMs)).coerceIn(0, durationMs)
                        lastPreview = pendingTarget
                    }
                    KeyEventType.KeyUp -> {
                        pendingTarget?.let(::submitTarget)
                        pendingTarget = null
                    }
                }
                previewVersion++
                true
            }
            .focusOnTvPointer()
            .onTvPointerScroll { delta ->
                val scroll = delta.let { if (kotlin.math.abs(it.y) >= kotlin.math.abs(it.x)) it.y else it.x }
                if (scroll == 0f || durationMs <= 0L) return@onTvPointerScroll false
                navigation?.interact(); onInteraction()
                onSeekBy(if (scroll < 0f) 10_000L else -10_000L)
                true
            }
            .pointerInput(Unit) {
                detectTapGestures { point ->
                    if (latestDurationMs <= 0L) return@detectTapGestures
                    navigation?.interact(); latestOnInteraction()
                    val target = seekPositionFromPointer(point.x, size.width, latestDurationMs)
                    submitTarget(target)
                    lastPreview = target
                    previewVersion++
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { point ->
                        if (latestDurationMs > 0L) {
                            navigation?.interact(); latestOnInteraction()
                            pointerTarget = seekPositionFromPointer(point.x, size.width, latestDurationMs)
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        if (latestDurationMs > 0L) {
                            change.consume()
                            pointerTarget = seekPositionFromPointer(change.position.x, size.width, latestDurationMs)
                        }
                    },
                    onDragEnd = {
                        pointerTarget?.let { target ->
                            submitTarget(target)
                            lastPreview = target
                            previewVersion++
                        }
                        pointerTarget = null
                    },
                    onDragCancel = { pointerTarget = null },
                )
            }
            .focusable()
    } else Modifier

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(verticalReturn)
            .then(interactionModifier),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        timelineContext?.invoke(displayedPosition)
        SeekPreview(if (stowing) null else if (!interactive) positionMs.takeIf { feedbackPreview } else if (focused || pointerTarget != null) pointerTarget ?: pendingTarget ?: lastPreview else null, durationMs)
        if (timelineContext == null) CurrentChapterLabel(displayedPosition)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (highlighted) 7.dp else 6.dp),
        ) {
            // Offscreen so chapter gaps can be cut out of track and progress alike.
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .background(Color.White.copy(alpha = 0.24f), RoundedCornerShape(50)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(com.ambient.tv.ui.theme.LocalPlaybackAccent.current.indicator, RoundedCornerShape(50)),
                )
                if (durationMs > 0 && navigation != null) {
                    val timeline = navigation.timeline
                    val current = timeline.chapterAt(displayedPosition)
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        fun x(ms: Long) = (ms.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) * size.width
                        timeline.segments.forEach { segment ->
                            val end = if (segment.endMs < 0) durationMs else segment.endMs
                            drawRect(Color(0xFFFFD54F).copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(x(segment.startMs), 0f),
                                androidx.compose.ui.geometry.Size(x(end) - x(segment.startMs), size.height))
                        }
                        if (highlighted && current != null) {
                            val next = timeline.chapters.firstOrNull { it.startMs > current.startMs }?.startMs ?: durationMs
                            val end = current.endMs?.takeIf { it > current.startMs }?.coerceAtMost(next) ?: next
                            drawRect(Color.White.copy(alpha = 0.18f), androidx.compose.ui.geometry.Offset(x(current.startMs), 0f),
                                androidx.compose.ui.geometry.Size(x(end) - x(current.startMs), size.height))
                        }
                        timeline.chapters.forEach { chapter ->
                            if (chapter.startMs <= 0) return@forEach
                            val cx = x(chapter.startMs)
                            drawLine(Color.Black, androidx.compose.ui.geometry.Offset(cx, 0f), androidx.compose.ui.geometry.Offset(cx, size.height),
                                2.5.dp.toPx(), blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
                        }
                    }
                }
            }
            if (highlighted && durationMs > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(progress.coerceAtLeast(0.001f))
                        .height(1.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(15.dp)
                            .background(com.ambient.tv.ui.theme.LocalPlaybackAccent.current.indicator, CircleShape)
                            .border(2.5.dp, Color.White, CircleShape),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (durationMs > 0L) "${formatDuration(displayedPosition)} / ${formatDuration(durationMs)}" else formatDuration(displayedPosition),
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 13.sp,
            )
            val endsAt = if (showEndTime && durationMs > 0L) {
                java.time.LocalTime.now().plusSeconds(remaining / 1000L).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            } else null
            Text(
                endsAt?.let { "−${formatDuration(remaining)} · ${stringResource(R.string.player_ends_at, it)}" } ?: "−${formatDuration(remaining)}",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 13.sp,
            )
        }
    }

    LaunchedEffect(focused, interactive) { if (seekable && focused) onInteraction() }
}

@Composable
internal fun PlayerSeekModeBar(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    TvPlayerSeekBar(
        positionMs = positionMs,
        durationMs = durationMs,
        onSeekBy = {},
        onInteraction = {},
        modifier = modifier.padding(horizontal = 58.dp),
        interactive = false,
    )
}

@Composable
private fun OsdIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    loading: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val uiScale = rememberUhdScale()
    val width = (if (primary) 58.dp else 48.dp) * uiScale
    val height = 40.dp * uiScale
    val elevation by animateDpAsState(if (focused) 7.dp else 1.dp, tween(120), label = "osdButtonElevation")
    val background = when {
        primary && (focused || selected) -> Gold
        primary -> Color.White.copy(alpha = 0.94f)
        focused -> Color.White.copy(alpha = 0.98f)
        selected -> Gold.copy(alpha = 0.90f)
        else -> Color(0xD9181A1E)
    }
    val foreground = if (focused || selected || primary) Color.Black else Color.White
    val shape = RoundedCornerShape(6.dp * uiScale)

    Row(
        modifier = modifier
            .width(width)
            .height(height)
            .shadow(elevation, shape)
            .onFocusChanged { focused = it.isFocused }
            .background(background, shape)
            .border(1.dp, if (focused) Gold else Color.White.copy(alpha = 0.13f), shape)
            .activateOnTvKey(onClick = onClick)
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 8.dp * uiScale),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = foreground, modifier = Modifier.size(21.dp * uiScale))
        } else {
            Icon(icon, contentDescription = label, tint = foreground, modifier = Modifier.size((if (primary) 23.dp else 20.dp) * uiScale))
        }
    }
}

@Composable
private fun VideoSettingsPanel(
    player: ExoPlayer,
    resizeMode: PlayerResizeMode,
    aspectPreset: PlayerAspectPreset,
    videoZoom: Float,
    onResizeModeChange: (PlayerResizeMode) -> Unit,
    onAspectPresetChange: (PlayerAspectPreset) -> Unit,
    onVideoZoomChange: (Float) -> Unit,
    onBack: () -> Unit,
) {
    var speed by remember(player) { mutableStateOf(player.playbackParameters.speed) }

    PlayerSidePanel(title = stringResource(R.string.player_video_settings), onClose = onBack) {
        if (aspectPreset == PlayerAspectPreset.AUTO) {
            item { RightPanelSectionTitle(stringResource(R.string.player_scaling_mode)) }
            item {
                OsdChoiceRow(stringResource(R.string.player_fit_screen), stringResource(R.string.player_fit_screen_hint), resizeMode == PlayerResizeMode.FIT) { onResizeModeChange(PlayerResizeMode.FIT) }
            }
            item {
                OsdChoiceRow(stringResource(R.string.player_fill_screen), stringResource(R.string.player_fill_screen_hint), resizeMode == PlayerResizeMode.ZOOM) { onResizeModeChange(PlayerResizeMode.ZOOM) }
            }
            item {
                OsdChoiceRow(stringResource(R.string.player_stretch), stringResource(R.string.player_stretch_hint), resizeMode == PlayerResizeMode.STRETCH) { onResizeModeChange(PlayerResizeMode.STRETCH) }
            }
        }
        item {
            PlayerDropUpSelector(
                label = stringResource(R.string.player_aspect_ratio),
                value = when (aspectPreset) {
                    PlayerAspectPreset.AUTO -> stringResource(R.string.player_aspect_auto)
                    PlayerAspectPreset.RATIO_16_9 -> "16:9"
                    PlayerAspectPreset.RATIO_4_3 -> "4:3"
                    PlayerAspectPreset.RATIO_21_9 -> "21:9"
                },
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

@Composable
private fun OsdChoiceRow(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    RightPanelChoice(
        text = title,
        subtitle = detail,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.focusRequester(requester),
    )
}

@Composable
private fun OsdSpeedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.width(70.dp).height(40.dp).onFocusChanged { focused = it.isFocused }
            .appControlSurface(focused = focused, selected = selected, shape = RoundedCornerShape(6.dp))
            .activateOnTvKey(onClick = onClick).clickable(onClick = onClick).focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected && !focused) Gold else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun videoSettingSummary(player: ExoPlayer, resizeMode: PlayerResizeMode = PlayerResizeMode.FIT, aspectPreset: PlayerAspectPreset = PlayerAspectPreset.AUTO, videoZoom: Float = 1f): String {
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
private fun PlaybackSettingsPanel(
    state: PlayerUiState.Ready,
    player: ExoPlayer,
    currentTracks: Tracks,
    resizeMode: PlayerResizeMode,
    aspectPreset: PlayerAspectPreset,
    videoZoom: Float,
    onOpenRenderer: () -> Unit,
    onOpenVideo: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenInfo: () -> Unit,
    sleepTimer: SleepTimerConfig,
    onClose: () -> Unit,
) {
    val displayLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val audio = trackOptions(currentTracks, C.TRACK_TYPE_AUDIO, displayLocale).firstOrNull { it.selected }?.label
        ?: stringResource(R.string.player_default_track)
    val subtitle = trackOptions(currentTracks, C.TRACK_TYPE_TEXT).firstOrNull { it.selected }?.label
        ?: stringResource(R.string.player_off)

    PlayerSidePanel(title = stringResource(R.string.player_playback_settings), onClose = onClose) {
        item {
            OsdSettingsRow(
                icon = Icons.Filled.Movie,
                title = stringResource(R.string.player_renderer),
                value = rendererLabelForUi(state.playbackBackendKey),
                onClick = onOpenRenderer,
            )
        }
        item {
            OsdSettingsRow(
                icon = Icons.Filled.Movie,
                title = stringResource(R.string.player_video),
                value = videoSettingSummary(player, resizeMode, aspectPreset, videoZoom),
                onClick = onOpenVideo,
            )
        }
        item {
            OsdSettingsRow(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = stringResource(R.string.player_audio),
                value = audio,
                onClick = onOpenAudio,
            )
        }
        item {
            OsdSettingsRow(
                icon = Icons.Filled.Subtitles,
                title = stringResource(R.string.player_subtitles),
                value = subtitle,
                onClick = onOpenSubtitles,
            )
        }
        item {
            OsdSettingsRow(
                icon = Icons.Filled.Bedtime,
                title = stringResource(R.string.player_sleep_timer),
                value = sleepTimerSummary(sleepTimer),
                onClick = onOpenSleep,
            )
        }
        item {
            OsdSettingsRow(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.player_playback_info),
                value = playbackModeShortLabel(state.playbackMode),
                onClick = onOpenInfo,
            )
        }
    }
}

@Composable
private fun RendererSettingsPanel(
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
    PlayerSidePanel(title = stringResource(R.string.player_renderer), onClose = onBack) {
        item {
            PlayerDropUpSelector(
                label = stringResource(R.string.player_renderer_current),
                value = rendererLabelForUi(currentKey),
                options = options,
                selectedKey = currentKey,
                onSelected = { onSelected(it, false) },
            )
        }
    }
}

@Composable
private fun rendererLabelForUi(key: String): String = when (key) {
    "media3" -> "Media3"
    "vlc" -> "VLC"
    "mpv" -> "MPV"
    else -> stringResource(R.string.player_automatic)
}

@Composable
private fun SleepTimerPanel(
    config: SleepTimerConfig,
    onChange: (SleepTimerConfig) -> Unit,
    onBack: () -> Unit,
) {
    PlayerSidePanel(title = stringResource(R.string.player_sleep_timer), onClose = onBack) {
        item { RightPanelSectionTitle(stringResource(R.string.player_sleep_after_time)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(15, 30, 45, 60, 90).forEach { minutes ->
                    OsdSpeedChip(
                        label = if (minutes < 60) "${minutes}m" else if (minutes == 60) "1h" else "90m",
                        selected = config.kind == SleepTimerKind.DURATION && config.minutes == minutes,
                    ) {
                        onChange(SleepTimerConfig(SleepTimerKind.DURATION, minutes, android.os.SystemClock.elapsedRealtime() + minutes * 60_000L))
                    }
                }
            }
        }
        item { RightPanelSectionTitle(stringResource(R.string.player_sleep_behavior)) }
        item {
            OsdChoiceRow(stringResource(R.string.player_sleep_end_item), stringResource(R.string.player_sleep_end_item_hint), config.kind == SleepTimerKind.END_OF_ITEM) {
                onChange(SleepTimerConfig(SleepTimerKind.END_OF_ITEM))
            }
        }
        pauseSleepTimerMinutes.forEach { minutes ->
            item {
                OsdChoiceRow(stringResource(R.string.player_sleep_pause_summary, minutes),
                    stringResource(R.string.player_sleep_pause_hint), config.kind == SleepTimerKind.PAUSE_INACTIVITY && config.minutes == minutes) {
                    onChange(SleepTimerConfig(SleepTimerKind.PAUSE_INACTIVITY, minutes))
                }
            }
        }
        listOf(30, 60, 90, 120).forEach { minutes ->
            item {
                OsdChoiceRow(stringResource(R.string.player_sleep_input_summary, minutes),
                    stringResource(R.string.player_sleep_input_hint), config.kind == SleepTimerKind.USER_INACTIVITY && config.minutes == minutes) {
                    onChange(SleepTimerConfig(SleepTimerKind.USER_INACTIVITY, minutes))
                }
            }
        }
        if (config.kind != SleepTimerKind.OFF) {
            item {
                OsdChoiceRow(stringResource(R.string.player_sleep_off), stringResource(R.string.player_sleep_off_hint), false) { onChange(SleepTimerConfig()) }
            }
        }
    }
}

@Composable
private fun sleepTimerSummary(config: SleepTimerConfig): String = when (config.kind) {
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
private fun PlayerSidePanel(
    title: String,
    onClose: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    RightSideLazyPanel(
        title = title,
        onBack = onClose,
        width = 500.dp,
        requestHeaderFocus = true,
        content = content,
    )
}

@Composable
private fun OsdSettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    PlayerPanelEntry(
        icon = icon,
        title = title,
        value = value,
        onClick = onClick,
        modifier = Modifier.focusRequester(requester),
    )
}

@Composable
private fun PlaybackMediaFlags(state: PlayerUiState.Ready, player: ExoPlayer) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val modeLabel = when (state.playbackMode) {
            PlaybackMode.DIRECT_PLAY -> "DIRECT"
            PlaybackMode.DIRECT_STREAM -> "STREAM"
            PlaybackMode.TRANSCODE -> "TRANSCODE"
        }
        PlayerMediaFlag(Icons.Filled.Movie, modeLabel, emphasized = state.playbackMode != PlaybackMode.DIRECT_PLAY)
        val format = player.videoFormat
        PlayerVideoBadges(
            width = format?.width,
            height = format?.height,
            hdrLabel = HdrSupport.sourceStandard(format)?.label
                ?: formatHdrMetadata(state.videoDynamicRange, state.videoProfile),
            hasVideo = format != null,
        )
        formatNerdFps(format?.frameRate?.toDouble())?.let { PlayerMediaFlag(Icons.Filled.Movie, it) }
        state.videoCodec?.takeIf { it.isNotBlank() }?.let { PlayerMediaFlag(Icons.Filled.Movie, playerCodecLabel(it)) }
        state.audioCodec?.takeIf { it.isNotBlank() }?.let { codec ->
            val channels = player.audioFormat?.channelCount?.takeIf { it > 0 }?.let(::formatChannelCount)
            PlayerMediaFlag(Icons.AutoMirrored.Filled.VolumeUp, listOfNotNull(playerCodecLabel(codec), channels).joinToString(" "))
        }
        audioOutputBadge(Media3AudioProcessing.session(player), state.serverAudioConversionCodec)
            ?.let { PlayerMediaFlag(Icons.AutoMirrored.Filled.VolumeUp, it) }
    }
}

@Composable
internal fun PlayerMediaFlag(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    emphasized: Boolean = false,
) {
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.44f), RoundedCornerShape(5.dp))
            .border(1.dp, if (emphasized) Gold.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.14f), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = if (emphasized) Gold else Color.White.copy(alpha = 0.82f))
        Text(label, color = if (emphasized) Gold else Color.White.copy(alpha = 0.82f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun TrackPanel(
    title: String,
    options: List<PlayerTrackOption>,
    includeOff: Boolean,
    onSelect: (PlayerTrackOption?) -> Unit,
    onBack: () -> Unit,
) {
    val selectedIndex = options.indexOfFirst { it.selected }
    val focusOff = includeOff && selectedIndex < 0
    val focusOptionIndex = if (!includeOff && selectedIndex < 0 && options.isNotEmpty()) 0 else selectedIndex
    PlayerSidePanel(title = title, onClose = onBack) {
        if (includeOff) {
            item {
                TrackOptionRow(
                    label = stringResource(R.string.player_off),
                    selected = selectedIndex < 0,
                    onClick = { onSelect(null); onBack() },
                )
            }
        }
        options.forEachIndexed { index, option ->
            item(key = "$index:${option.label}") {
                TrackOptionRow(
                    label = option.label,
                    selected = option.selected,
                    onClick = { onSelect(option); onBack() },
                )
            }
        }
        if (options.isEmpty()) {
            item { Text(stringResource(R.string.player_no_track), color = Color.White.copy(alpha = 0.64f), fontSize = 15.sp) }
        }
    }
}

@Composable
private fun TrackOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    RightPanelChoice(
        text = label,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.focusRequester(requester),
    )
}

@Composable
private fun playbackModeShortLabel(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.DIRECT_PLAY -> stringResource(R.string.player_direct_play_short)
    PlaybackMode.DIRECT_STREAM -> stringResource(R.string.player_direct_stream_short)
    PlaybackMode.TRANSCODE -> stringResource(R.string.player_transcode_short)
}

private fun friendlyCodec(codec: String): String = when (codec.lowercase()) {
    "hevc", "h265", "video/hevc" -> "HEVC"
    "h264", "avc", "video/avc" -> "H.264"
    "eac3", "e-ac-3", "audio/eac3" -> "E-AC3"
    "ac3", "ac-3", "audio/ac3" -> "AC3"
    "truehd", "audio/true-hd" -> "TRUEHD"
    "dca", "dts", "audio/vnd.dts" -> "DTS"
    "aac", "audio/mp4a-latm" -> "AAC"
    else -> codec.uppercase().substringAfterLast('/')
}

private fun formatChannelCount(count: Int): String = audioChannelLabel(count)

internal fun trackOptions(tracks: Tracks, trackType: Int, displayLocale: java.util.Locale = java.util.Locale.getDefault()): List<PlayerTrackOption> =
    tracks.groups
        .filter { it.type == trackType }
        .flatMap { group ->
            (0 until group.length)
                .filter { trackType == C.TRACK_TYPE_AUDIO || group.isTrackSupported(it) }
                .map { index ->
                    val format = group.getTrackFormat(index)
                    val details = buildList {
                        val language = format.language
                            ?.takeIf { it.isNotBlank() && it != "und" }
                            ?.let { audioLanguageDisplayName(it, displayLocale) ?: displayLanguage(it, displayLocale) }
                        language?.let(::add)
                        format.label
                            ?.takeIf { it.isNotBlank() && !it.equals(format.language, ignoreCase = true) && !it.equals(language, ignoreCase = true) }
                            ?.let { add(if (trackType == C.TRACK_TYPE_AUDIO) expandAudioLanguageCodes(it, displayLocale) else it) }
                        if (trackType == C.TRACK_TYPE_AUDIO && format.channelCount > 0) add(formatChannelCount(format.channelCount))
                        (format.codecs ?: format.sampleMimeType)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { if (trackType == C.TRACK_TYPE_AUDIO) audioCodecLabel(it) else friendlyCodec(it) }
                            ?.let(::add)
                    }.distinct()
                    PlayerTrackOption(
                        label = details.joinToString("  ·  ").ifBlank { "#${index + 1}" },
                        language = format.language,
                        group = group,
                        trackIndex = index,
                        selected = group.isTrackSelected(index),
                        forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                    )
                }
        }

private fun displayLanguage(code: String, displayLocale: java.util.Locale): String = runCatching {
    java.util.Locale.forLanguageTag(code).getDisplayLanguage(displayLocale)
        .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
        ?: code.lowercase(java.util.Locale.ROOT)
}.getOrDefault(code.uppercase())

private fun selectTrack(player: ExoPlayer, trackType: Int, option: PlayerTrackOption?) {
    val builder = player.trackSelectionParameters.buildUpon()
    if (option == null) {
        builder.setTrackTypeDisabled(trackType, true)
        builder.clearOverridesOfType(trackType)
    } else {
        builder.setTrackTypeDisabled(trackType, false)
        builder.setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex))
    }
    player.trackSelectionParameters = builder.build()
}


private fun applySavedTrackPreferences(
    player: ExoPlayer,
    tracks: Tracks,
    audioLanguage: String?,
    subtitleLanguage: String?,
    subtitlesEnabled: Boolean,
    forcedSubtitlesEnabled: Boolean,
    onUnsupportedAudio: (PlayerTrackOption) -> Unit,
): Boolean {
    fun find(trackType: Int, language: String?): PlayerTrackOption? {
        if (language.isNullOrBlank()) return null
        return trackOptions(tracks, trackType).firstOrNull {
            LanguagePreferences.matches(it.language, language)
        }
    }

    val audioOptions = trackOptions(tracks, C.TRACK_TYPE_AUDIO)
    val preferredAudio = chooseMedia3AudioTrack(player, audioOptions, audioLanguage) { it.group.getTrackFormat(it.trackIndex) } ?: find(C.TRACK_TYPE_AUDIO, audioLanguage)
    preferredAudio?.let {
        if (it.group.isTrackSupported(it.trackIndex)) selectTrack(player, C.TRACK_TYPE_AUDIO, it)
        else onUnsupportedAudio(it)
    }
    return preferredAudio != null
}

@Composable
private fun playbackReasonLabel(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.DIRECT_PLAY -> stringResource(R.string.player_reason_direct)
    PlaybackMode.DIRECT_STREAM -> stringResource(R.string.player_reason_audio_fallback)
    PlaybackMode.TRANSCODE -> stringResource(R.string.player_reason_video_fallback)
}

@Composable
private fun playbackModeLabel(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.DIRECT_PLAY -> stringResource(R.string.player_direct_play)
    PlaybackMode.DIRECT_STREAM -> stringResource(R.string.player_direct_stream)
    PlaybackMode.TRANSCODE -> stringResource(R.string.player_transcode)
}

private fun formatDuration(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)

/**
 * Media3 play/pause toggle: while buffering `isPlaying` is false although playback is requested,
 * so the request (`playWhenReady`) decides. Ended/idle players resume instead.
 */
internal fun media3PauseOnToggle(playWhenReady: Boolean, playbackState: Int): Boolean =
    playWhenReady && playbackState != androidx.media3.common.Player.STATE_ENDED &&
        playbackState != androidx.media3.common.Player.STATE_IDLE

/** A position change larger than playback can make between two updates: a jump. */
private const val SEEK_FEEDBACK_JUMP_MS = 3_000L
