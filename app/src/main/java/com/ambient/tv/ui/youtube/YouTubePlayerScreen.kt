@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.youtube

import com.ambient.tv.ui.player.findActivity
import com.ambient.tv.ui.player.PlayerOverlayVisibility
import com.ambient.tv.ui.player.PlayerPanelVisibility
import com.ambient.tv.ui.common.rememberPlayerPanelNavigation
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import androidx.compose.runtime.CompositionLocalProvider
import com.ambient.tv.ui.mobile.deviceDp

import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.player.PlaybackSessionContent
import com.ambient.tv.ui.player.PlaybackItemControls
import com.ambient.tv.ui.player.LocalPlaybackItemControls
import com.ambient.tv.ui.player.LocalPlaybackMetadata
import com.ambient.tv.ui.player.Media3PlaybackHandle
import com.ambient.tv.ui.player.Media3VideoTexture
import com.ambient.tv.ui.player.LocalPersistentPlaybackVideoHost
import com.ambient.tv.ui.player.HdrSupport
import com.ambient.tv.ui.player.NextUpCandidate
import com.ambient.tv.ui.player.NextUpConfig
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.player.SleepTimerConfig
import com.ambient.tv.ui.player.rememberPlaybackSleepTimer
import com.ambient.tv.ui.player.PlaybackArtworkCard
import com.ambient.tv.ui.player.PlayerBufferingSpinner
import com.ambient.tv.ui.player.media3WaitingForPlaybackPicture
import com.ambient.tv.ui.player.waitingForPlaybackPicture
import com.ambient.tv.ui.player.ReferencePlayerBottomContent
import com.ambient.tv.ui.player.ReferencePlayerClock
import com.ambient.tv.ui.player.ReferencePlayerHeader
import com.ambient.tv.ui.player.ReferencePlayerOverlayBackground
import com.ambient.tv.ui.player.RequestPlayerOverlayFocus
import com.ambient.tv.ui.player.rememberMedia3NerdStats
import com.ambient.tv.ui.player.PlaybackPerformanceNotice
import com.ambient.tv.ui.player.PlaybackNerdStatsOverlay
import com.ambient.tv.ui.player.PlaybackFpsOverlay
import com.ambient.tv.ui.player.playbackOverlayFps
import com.ambient.tv.ui.player.rememberDisplayRefreshHz
import com.ambient.tv.ui.player.rememberMedia3PlaybackFps
import com.ambient.tv.ui.player.rememberMedia3NerdOverlayModel
import com.ambient.tv.ui.player.rememberMpvNerdOverlayModel
import com.ambient.tv.ui.player.rememberVlcNerdOverlayModel
import com.ambient.tv.ui.player.media3LoadHint
import com.ambient.tv.ui.player.mpvLoadHint
import com.ambient.tv.ui.player.vlcLoadHint
import com.ambient.tv.ui.player.formatNerdBitrate
import com.ambient.tv.ui.player.formatNerdDurationMs
import com.ambient.tv.ui.player.formatNerdByteRate
import com.ambient.tv.ui.player.formatNerdSeconds
import com.ambient.tv.ui.player.formatNerdFps
import com.ambient.tv.ui.player.formatNerdHz
import com.ambient.tv.ui.player.formatMpvScalerName
import com.ambient.tv.ui.player.formatMpvUpscalerForStats
import com.ambient.tv.ui.player.formatMpvScalerStat
import com.ambient.tv.ui.player.formatMpvDebandStat
import com.ambient.tv.ui.player.formatNerdBytes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import com.ambient.tv.ui.player.PLAYER_SETTINGS_PANEL_Z_INDEX
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Subscriptions
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.player.PlayerTechBadgeRow
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.PlayerTouchLayer
import com.ambient.tv.ui.common.PlayerPinchZoom
import com.ambient.tv.ui.common.pinchVideoAspectRatio
import com.ambient.tv.ui.player.VideoPresentation
import com.ambient.tv.ui.player.videoAspectRatio
import com.ambient.tv.ui.player.videoResizeMode
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.PlayerExitButton
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.PlayerSleepTimerButton
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.UnifiedBackendPlayerPanel
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.player.PlaybackSeekCoordinator
import com.ambient.tv.youtube.lateYouTubeResumeTarget
import com.ambient.tv.ui.player.TvPlayerSeekBar
import com.ambient.tv.ui.player.backend.MpvBackendAvailability
import com.ambient.tv.ui.player.backend.MpvVideoSurface
import com.ambient.tv.ui.player.backend.PlaybackBackend
import com.ambient.tv.ui.player.backend.PlaybackBackendResolver
import com.ambient.tv.ui.player.backend.VlcVideoSurface
import com.ambient.tv.ui.player.backend.rememberMpvPlaybackController
import com.ambient.tv.ui.player.backend.rememberMpvPlaybackSnapshot
import com.ambient.tv.ui.player.backend.rememberVlcPlaybackController
import com.ambient.tv.ui.player.backend.rememberVlcPlaybackSnapshot
import com.ambient.tv.youtube.YouTubePlayback
import com.ambient.tv.youtube.qualityHeight
import com.ambient.tv.youtube.youtubeThumbnailUrl
import com.ambient.tv.youtube.youtubePanelThumbnailUrls
import com.ambient.tv.youtube.youtubeVlcPlaybackUri
import timber.log.Timber
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import com.ambient.tv.util.withoutUrlQueries
import okhttp3.OkHttpClient
import com.ambient.tv.ui.player.handlePlayerOsdKey
import com.ambient.tv.ui.player.OsdKeyState
import com.ambient.tv.ui.player.OsdContent
import com.ambient.tv.ui.player.OsdSeekSteps
import com.ambient.tv.ui.player.OsdKeyTarget

@Composable
fun YouTubePlayerScreen(
    onBack: () -> Unit,
    onPlayNext: ((String) -> Unit)? = null,
    /** Channel button of the OSD; null hides the channel actions. */
    onOpenChannel: ((com.ambient.tv.youtube.YouTubeChannelInfo) -> Unit)? = null,
    viewModel: YouTubePlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val advance = onPlayNext ?: viewModel::playNext
    com.ambient.tv.ui.player.RegisterPlayerItemNavigation(viewModel, advance)
    val isTrailer = viewModel.isTrailer
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) viewModel.setForeground(true)
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) viewModel.setForeground(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setForeground(false)
        }
    }
    val trailerRoute = state.playback?.videoId?.let(Destinations::trailerPlayer)
    DisposableEffect(lifecycleOwner, isTrailer, trailerRoute) {
        fun stopTrailer() {
            if (isTrailer && trailerRoute != null && BackgroundPlaybackSession.snapshot.value.metadata?.route == trailerRoute) {
                BackgroundPlaybackSession.stop()
            }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stopTrailer()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); stopTrailer() }
    }
    val context = LocalContext.current
    val prefs = remember { EntryPointAccessors.fromApplication(context.applicationContext, YouTubePrefsEntryPoint::class.java).prefs() }
    val persistentVideoHost = LocalPersistentPlaybackVideoHost.current
    val channelOsd = state.channel?.takeIf { !isTrailer && onOpenChannel != null }?.let { channel ->
        YouTubeChannelOsd(
            channel = channel,
            onOpen = {
                // Like SmartTube: the video waits paused while the channel page is open.
                BackgroundPlaybackSession.pause()
                onOpenChannel?.invoke(channel)
            },
            onToggleSubscription = viewModel::toggleSubscription,
        )
    }
    CompositionLocalProvider(LocalYouTubeChannelOsd provides channelOsd) {
    com.ambient.tv.ui.player.PersistentPlayerChrome(viewModel, state.loading) {
    Box(Modifier.fillMaxSize().background(if (persistentVideoHost) Color.Transparent else MaterialTheme.colorScheme.background)) {
        val playback = state.playback
        if (playback == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val error = state.error
                if (state.liveEvent != null) {
                    YouTubeLiveEventDialog(state, viewModel::toggleWaiting, viewModel::retry, onBack)
                } else if (error != null) {
                    LaunchedEffect(error) {
                        val snapshot = BackgroundPlaybackSession.snapshot.value
                        if (snapshot.replacingSource && viewModel.ownsCompanionMedia(snapshot.metadata?.companionMedia)) BackgroundPlaybackSession.failSourceReplacement(snapshot.generation, error)
                    }
                    Text(error)
                } else PlayerBufferingSpinner(visible = true)
            }
        } else {
            val requestDisplayMode = com.ambient.tv.ui.player.rememberDisplayModeRequest(
                activity = context.findActivity(),
                playbackKey = "youtube:${playback.videoId}",
                adjustmentKey = prefs.displayRefreshAdjustmentKey,
                preferredModeKey = prefs.preferredDisplayModeKey.also { com.ambient.tv.ui.player.PlaybackDisplayModeRevision.tick },
                whitelist = prefs.displayModeWhitelist,
                allowDoubleRate = prefs.allowDoubleRefreshRate,
                allowThreeTwoPulldown = prefs.allowThreeTwoPulldown,
                switchPauseMs = prefs.displaySwitchPauseMs,
            )
            val route = if (isTrailer) Destinations.trailerPlayer(playback.videoId) else Destinations.youtubePlayer(playback.videoId)
            val initialFormat = playback.adaptiveFormats.firstOrNull { it.url == playback.adaptiveVideoUri }
            val profileMetadata = PlaybackMetadata(route, playback.title, source = "youtube", mediaId = playback.videoId,
                sourceWidth = initialFormat?.width, sourceHeight = initialFormat?.height,
                isLive = playback.isLive, isTrailer = isTrailer)
            fun preferredRenderer(metadata: PlaybackMetadata): String = com.ambient.tv.ui.player.savedPlaybackRenderer(
                prefs.playbackBackendKey,
                com.ambient.tv.ui.player.TitleVideoSettingsStore.load(context, metadata),
                com.ambient.tv.ui.player.ResolutionVideoProfileStore.load(context, metadata),
                MpvBackendAvailability.isAvailable,
            )
            var rendererKey by remember(route) { mutableStateOf(
                BackgroundPlaybackSession.snapshot.value.takeIf { it.metadata?.route == route }?.backend
                    ?: preferredRenderer(profileMetadata)) }
            var runtimeDimensions by remember(route) { mutableStateOf<Pair<Int, Int>?>(null) }
            var rendererPinned by remember(route) { mutableStateOf(
                BackgroundPlaybackSession.snapshot.value.metadata?.route == route) }
            var runtimeProfileResolved by remember(route) { mutableStateOf(false) }
            var resumePosition by remember(playback.videoId) { mutableLongStateOf(playback.resumeMs) }
            // SmartTube's network engine and buffer size; the player panel changes them in place.
            var streamTuning by remember { mutableStateOf(com.ambient.tv.ui.player.Media3StreamTuning(prefs.youtubeNetworkEngine, prefs.youtubeBufferKey)) }
            // Watch-next may arrive late. Once this session has been handed off or its source
            // manually changed, old server progress must not overwrite its current position.
            var allowLateResume by remember(playback.videoId) { mutableStateOf(
                BackgroundPlaybackSession.snapshot.value.metadata?.route != route) }
            val enginePlayback = playback.copy(
                resumeMs = if (allowLateResume) playback.resumeMs else 0L,
                resumeResolved = playback.resumeResolved || !allowLateResume,
            )
            // VLC/MPV get one fixed stream pair; the panel's quality/language choice picks another.
            var videoChoice by remember(playback.videoId) { mutableStateOf(com.ambient.tv.youtube.YOUTUBE_STREAM_AUTO) }
            var audioChoice by remember(playback.videoId) { mutableStateOf(com.ambient.tv.youtube.YOUTUBE_STREAM_AUTO) }
            // Known HDR sources suspend VLC's CPU image filters for this session. Stored SDR
            // presets must not force a lower-quality stream or strip HDR before tone mapping.
            val hdrAllowed = PlaybackBackendResolver.resolve(rendererKey) in
                setOf(PlaybackBackend.MPV, PlaybackBackend.VLC)
            val externalPlayback = remember(enginePlayback, videoChoice, audioChoice, hdrAllowed) {
                youtubeWithStreamChoice(context, enginePlayback, videoChoice, audioChoice, prefs.youtubeVideoPreset, prefs.youtubeMaxVideoHeight, hdrAllowed)
            }
            val streamPanel = rememberYouTubeStreamPanel(playback, videoChoice, audioChoice, prefs.youtubeVideoPreset, prefs.youtubeMaxVideoHeight, hdrAllowed,
                onVideo = { BackgroundPlaybackSession.cancelResumeRewind(); allowLateResume = false; videoChoice = it },
                onAudio = { BackgroundPlaybackSession.cancelResumeRewind(); allowLateResume = false; audioChoice = it })
            fun switchRenderer(key: String, positionMs: Long, global: Boolean) {
                val normalized = key.takeIf { it in setOf("auto", "media3", "vlc", "mpv") } ?: "auto"
                if (normalized == "mpv" && !MpvBackendAvailability.isAvailable) return
                BackgroundPlaybackSession.cancelResumeRewind()
                val change = PlaybackBackendResolver.resolve(rendererKey) != PlaybackBackendResolver.resolve(normalized)
                if (change) allowLateResume = false
                if (change) resumePosition = com.ambient.tv.ui.player.rendererReopenPositionMs(playback.isLive,
                    com.ambient.tv.ui.player.rendererSwitchPositionMs(BackgroundPlaybackSession.currentStatus()?.positionMs, positionMs,
                        com.ambient.tv.ui.player.rendererZeroPositionReady(BackgroundPlaybackSession.activeHandle())))
                rendererPinned = true
                rendererKey = normalized
                if (global) prefs.playbackBackendKey = normalized
            }
            val formatChanged: (Float, Int?, Int?) -> Unit = { fps, width, height ->
                requestDisplayMode(fps, width, height)
                if (width != null && height != null && width > 0 && height > 0) {
                    runtimeDimensions = width to height
                    if (!runtimeProfileResolved) {
                        runtimeProfileResolved = true
                        if (!rendererPinned) {
                            val selected = preferredRenderer(profileMetadata.copy(sourceWidth = width, sourceHeight = height))
                            if (PlaybackBackendResolver.resolve(selected) != PlaybackBackendResolver.resolve(rendererKey)) {
                                switchRenderer(selected, BackgroundPlaybackSession.currentStatus()?.positionMs ?: resumePosition, false)
                            }
                        }
                    }
                }
            }
            if (PlaybackBackendResolver.resolve(rendererKey) != PlaybackBackend.MEDIA3) {
                streamPanel?.let { panel ->
                    com.ambient.tv.companion.CompanionSourceControls(route,
                        com.ambient.tv.companion.CompanionPlaybackControls.SourceControls(
                            quality = panel.qualityOptions.takeUnless { playback.isLive }.orEmpty().map { (k, v) -> com.ambient.tv.companion.CompanionOption(k, v.take(120), k == panel.selectedQuality) },
                            chooseQuality = { key -> resumePosition = BackgroundPlaybackSession.currentStatus()?.positionMs ?: resumePosition; panel.onQuality(key) },
                            audio = panel.audioOptions.map { (k, v) -> com.ambient.tv.companion.CompanionOption(k, v.take(120), k == panel.selectedAudio) },
                            chooseAudio = { key -> resumePosition = BackgroundPlaybackSession.currentStatus()?.positionMs ?: resumePosition; panel.onAudio(key) },
                        ))
                }
            }
            // The shared session owns EOF, including successor loading and hand-off guards.
            val onCompleted: () -> Unit = {}

            // Live streams have no final end time, so Next Up must stay off for them.
            val nextUp = state.nextVideo?.takeUnless { playback.isLive || isTrailer }?.let { next ->
                NextUpCandidate(
                    mediaId = next.videoId,
                    title = next.title,
                    subtitle = next.author ?: next.subtitle.orEmpty(),
                    artworkUrl = next.backgroundUrl ?: next.imageUrl ?: youtubeThumbnailUrl(next.videoId),
                    source = "youtube",
                    durationMs = next.durationMs.takeIf { it > 0 },
                )
            }
            val vlcSelected = PlaybackBackendResolver.resolve(rendererKey) == PlaybackBackend.VLC
            val sourceVideoUri = if (vlcSelected) com.ambient.tv.youtube.youtubeVlcVideoUri(externalPlayback)
                else externalPlayback.adaptiveVideoUri
            val sourceVideoFormat = externalPlayback.adaptiveFormats.firstOrNull { it.url == sourceVideoUri }
            val sourceVideoMimeType = if (vlcSelected) com.ambient.tv.youtube.youtubeVlcVideoMimeType(externalPlayback)
                else sourceVideoFormat?.mimeType
            // VLC/MPV: YouTube captions as local, centered WebVTT files. The shared subtitle policy loads
            // them into the player and applies the subtitle mode, language and fallback settings.
            // Media3 reads the same captions from the MPD and needs no copies.
            val captionsNeeded = PlaybackBackendResolver.resolve(rendererKey) != PlaybackBackend.MEDIA3 && playback.captions.isNotEmpty()
            var captionFiles by remember(playback.videoId) { mutableStateOf(emptyMap<String, String>()) }
            var captionFilesDone by remember(playback.videoId) { mutableStateOf(false) }
            LaunchedEffect(playback.videoId, captionsNeeded) {
                if (!captionsNeeded || captionFilesDone) return@LaunchedEffect
                // Tracks appear as they finish (in load order), not only after the last download.
                com.ambient.tv.youtube.YouTubeCaptionFiles.prepare(context, playback.videoId, playback.captions, playback.headers) {
                    captionFiles = it
                }
                captionFilesDone = true
            }
            val captionTracks = remember(playback.captions, captionFiles) {
                com.ambient.tv.youtube.youtubeCaptionTracks(playback.captions, captionFiles)
            }
            PlaybackSessionContent(
                timeline = playback.timeline,
                metadata = PlaybackMetadata(
                    route, playback.title, playback.author.orEmpty(),
                    "youtube", playback.videoId, durationMs = playback.durationMs,
                    companionMedia = com.ambient.tv.companion.CompanionMedia("youtube", playback.videoId, trailer = isTrailer),
                    artworkUrl = youtubePanelThumbnailUrls(playback.videoId).firstOrNull()
                        ?: playback.backgroundUrl ?: playback.imageUrl ?: youtubeThumbnailUrl(playback.videoId),
                    artworkFallbackUrls = (listOfNotNull(playback.backgroundUrl, playback.imageUrl) +
                        youtubePanelThumbnailUrls(playback.videoId).drop(1) + youtubeThumbnailUrl(playback.videoId))
                        .filter(String::isNotBlank).distinct(),
                    artworkAspectRatio = 16f / 9f,
                    // VLC/MPV report the track geometry only after the first frames; until then the
                    // surface filled the (20:9 phone) viewport and the picture looked squashed.
                    sourceWidth = runtimeDimensions?.first ?: sourceVideoFormat?.width,
                    sourceHeight = runtimeDimensions?.second ?: sourceVideoFormat?.height,
                    sourceAspectRatioHint = sourceVideoFormat
                        ?.let { com.ambient.tv.ui.player.sourceAspectRatioHint(it.width, it.height) },
                    handheldHdrStandard = when (com.ambient.tv.youtube.youtubeHdrKind(
                        sourceVideoMimeType)) {
                        "hdr10" -> com.ambient.tv.ui.player.HdrStandard.HDR10
                        "hlg" -> com.ambient.tv.ui.player.HdrStandard.HLG
                        else -> null
                    },
                    isLive = playback.isLive,
                    isTrailer = isTrailer,
                    subtitleTracks = captionTracks,
                    resumeState = playback.copy(resumeMs = resumePosition),
                    nextUp = nextUp,
                    successorsLoading = state.successorsLoading,
                    nextUpConfig = NextUpConfig(
                        enabled = prefs.nextUpEnabled && !isTrailer,
                        leadSeconds = prefs.nextUpLeadSeconds,
                        autoplay = prefs.youtubeAutoplayNextEnabled,
                        stillWatchingEvery = prefs.nextUpStillWatchingEpisodes,
                        showOverlay = prefs.nextUpOverlayEnabled,
                    ),
                ),
                onStopped = onBack,
                onPlayNext = { candidate -> advance(candidate.mediaId) },
                onPlayPrevious = state.previousVideoId?.let { previousId -> { viewModel.playPrevious(previousId) } },
                onAutoplayChanged = { enabled -> prefs.youtubeAutoplayNextEnabled = enabled },
            ) {
            when (PlaybackBackendResolver.resolve(rendererKey)) {
                PlaybackBackend.VLC -> {
                    // googlevideo through the loopback relay (OkHttp, like Media3); see ChunkedStreamRelay.
                    val vlcSource = remember(externalPlayback) {
                        com.ambient.tv.youtube.youtubeVlcSource(externalPlayback) { com.ambient.tv.youtube.YouTubeStreamRelay.url(it, playback.headers, playback.videoId) }
                    }
                    if (vlcSource != null) {
                        YouTubeVlcPlayback(
                            externalPlayback.copy(uri = vlcSource.uri, mimeType = null, headers = if (vlcSource.relayed) emptyMap() else playback.headers),
                            vlcSource.audioUri, streamPanel,
                            resumePosition, rendererKey, prefs.bigSeekJumpSeconds, prefs, onBack, onCompleted, ::switchRenderer, formatChanged,
                        )
                    } else {
                        val fallback = if (MpvBackendAvailability.isAvailable) "mpv" else "media3"
                        LaunchedEffect(playback.videoId, fallback) {
                            Timber.w("YouTube VLC needs a muxed URL; using %s", fallback)
                            switchRenderer(fallback, resumePosition, false)
                        }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PlayerBufferingSpinner(visible = true)
                        }
                    }
                }
                PlaybackBackend.MPV -> {
                    val mpvSource = remember(externalPlayback) {
                        com.ambient.tv.youtube.youtubeMpvSource(externalPlayback) { com.ambient.tv.youtube.YouTubeStreamRelay.url(it, playback.headers, playback.videoId) }
                    }
                    if (mpvSource != null) {
                        YouTubeMpvPlayback(
                            externalPlayback.copy(uri = mpvSource.uri, mimeType = null, headers = if (mpvSource.relayed) emptyMap() else playback.headers),
                            streamPanel, resumePosition, rendererKey, prefs.bigSeekJumpSeconds, prefs, onBack, onCompleted, ::switchRenderer, formatChanged,
                        )
                    } else {
                        LaunchedEffect(playback.videoId) { switchRenderer("media3", resumePosition, false) }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PlayerBufferingSpinner(visible = true) }
                    }
                }
                else -> {
                    // VLC/MPV sessions build the MPD in the background; Media3 opens it once ready.
                    val pendingDash = enginePlayback.deferredDash
                    val media3Playback by produceState<YouTubePlayback?>(enginePlayback.takeIf { pendingDash == null }, enginePlayback) {
                        value = pendingDash?.let { deferred ->
                            val mpd = runCatching { deferred.await() }.getOrNull()
                            if (mpd != null) enginePlayback.copy(uri = mpd, mimeType = "application/dash+xml", deferredDash = null)
                            else enginePlayback.copy(deferredDash = null)
                        } ?: enginePlayback
                    }
                    val media3 = media3Playback
                    if (media3 == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PlayerBufferingSpinner(visible = true) }
                    } else YouTubeMedia3Playback(
                    media3, resumePosition, rendererKey, prefs.bigSeekJumpSeconds, prefs.youtubeVideoPreset, prefs.youtubeMaxVideoHeight,
                    prefs.mediaAudioPassthroughEnabled,
                    streamTuning,
                    onBack, onCompleted, ::switchRenderer, formatChanged,
                    // Engine and buffer are fixed per player: reopen at the current position, saved as the default.
                    onStreamTuning = { tuning, positionMs ->
                        prefs.youtubeNetworkEngine = tuning.networkEngine
                        prefs.youtubeBufferKey = tuning.bufferKey
                        allowLateResume = false
                        resumePosition = positionMs
                        streamTuning = tuning
                    },
                    streams = streamPanel,
                    // Media3 plays a single-language stream (muxed or HLS): VLC/MPV open the chosen language.
                    onStreamAudio = { key, positionMs ->
                        audioChoice = key
                        switchRenderer(if (MpvBackendAvailability.isAvailable) "mpv" else "vlc", positionMs, false)
                    },
                )
                }
            }
            // Outside the engine branch: a renderer switch replaces the engine, not OSD and panel.
            com.ambient.tv.ui.player.PersistentPlayerOverlays()
            }
        }
        if (playback != null) {
            PlayerBufferingSpinner(visible = state.loading, modifier = Modifier.align(Alignment.Center))
            state.error?.let { message ->
                Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.9f)).padding(24.dp)) {
                    Text(message)
                    com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.home_retry), viewModel::retry)
                    com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.back), onBack)
                }
            }
        }
    }
    }
    }
}

/** Channel actions of the YouTube OSD; provided by the screen, read by every engine's controls. */
internal data class YouTubeChannelOsd(
    val channel: com.ambient.tv.youtube.YouTubeChannelInfo,
    val onOpen: () -> Unit,
    val onToggleSubscription: () -> Unit,
)

private val LocalYouTubeChannelOsd = androidx.compose.runtime.compositionLocalOf<YouTubeChannelOsd?> { null }

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface YouTubePrefsEntryPoint { fun prefs(): AppPreferences }

/** Quality and language choices for VLC/MPV, which play one fixed YouTube stream pair. */
private data class YouTubeStreamPanel(
    val qualityOptions: List<Pair<String, String>>,
    val selectedQuality: String,
    val onQuality: (String) -> Unit,
    val audioOptions: List<Pair<String, String>>,
    val selectedAudio: String,
    val onAudio: (String) -> Unit,
)

/** [playback] with the stream pair for the chosen quality/language; both automatic keeps the engine's pick. */
private fun youtubeWithStreamChoice(
    context: android.content.Context,
    playback: YouTubePlayback,
    videoKey: String,
    audioKey: String,
    presetKey: String,
    maxHeightPreference: Int,
    hdrAllowed: Boolean,
): YouTubePlayback {
    val auto = com.ambient.tv.youtube.YOUTUBE_STREAM_AUTO
    // The engine's pick is SDR; with HDR allowed "automatic" is chosen again here.
    if (playback.isLive || (videoKey == auto && audioKey == auto && !hdrAllowed)) return playback
    val decode = com.ambient.tv.youtube.YouTubeDeviceVideo.decodeCheck(context, hdrAllowed)
    val limit = com.ambient.tv.youtube.YouTubeDeviceVideo.displayLimit(context)
    val target = com.ambient.tv.youtube.YouTubeDeviceVideo.videoTarget(presetKey, maxHeightPreference, limit, com.ambient.tv.youtube.YouTubeDeviceVideo.decodeCheck())
    val video = com.ambient.tv.youtube.youtubePickVideo(playback.adaptiveFormats, videoKey, target, decode, limit)?.url
    val audio = com.ambient.tv.youtube.youtubePickAudio(playback.adaptiveFormats, audioKey)?.url
    return if (video != null && audio != null) playback.copy(adaptiveVideoUri = video, adaptiveAudioUri = audio) else playback
}

@Composable
private fun rememberYouTubeStreamPanel(
    playback: YouTubePlayback,
    videoKey: String,
    audioKey: String,
    presetKey: String,
    maxHeightPreference: Int,
    hdrAllowed: Boolean,
    onVideo: (String) -> Unit,
    onAudio: (String) -> Unit,
): YouTubeStreamPanel? {
    if (playback.isLive || playback.adaptiveFormats.isEmpty()) return null
    val context = LocalContext.current
    val displayLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val auto = com.ambient.tv.youtube.YOUTUBE_STREAM_AUTO
    val choices = remember(playback.adaptiveFormats, presetKey, maxHeightPreference, hdrAllowed) {
        val decode = com.ambient.tv.youtube.YouTubeDeviceVideo.decodeCheck(context, hdrAllowed)
        val limit = com.ambient.tv.youtube.YouTubeDeviceVideo.displayLimit(context)
        val target = com.ambient.tv.youtube.YouTubeDeviceVideo.videoTarget(presetKey, maxHeightPreference, limit, com.ambient.tv.youtube.YouTubeDeviceVideo.decodeCheck())
        Triple(
            com.ambient.tv.youtube.youtubePickVideo(playback.adaptiveFormats, auto, target, decode, limit),
            com.ambient.tv.youtube.youtubeVideoOptions(playback.adaptiveFormats, decode, limit),
            com.ambient.tv.youtube.youtubeAudioOptions(playback.adaptiveFormats),
        )
    }
    val (autoVideo, videoOptions, audioOptions) = choices
    val autoLabel = stringResource(R.string.player_quality_auto, autoVideo?.let {
        com.ambient.tv.youtube.YouTubeVideoOption("", it.qualityHeight(), it.fps, com.ambient.tv.youtube.youtubeVideoFamily(it.mimeType) ?: "",
            com.ambient.tv.youtube.youtubeHdrKind(it.mimeType)).label
    } ?: "–")
    val kinds = rememberYouTubeAudioKindNames()
    val fallback = stringResource(R.string.player_default_track)
    val audioLabels = audioOptions.map { it.key to com.ambient.tv.youtube.youtubeAudioLabel(it, displayLocale, kinds, fallback) }
    return YouTubeStreamPanel(
        qualityOptions = listOf(auto to autoLabel) + videoOptions.map { it.key to it.label },
        selectedQuality = videoKey,
        onQuality = onVideo,
        audioOptions = audioLabels,
        // Automatic plays the original language: show it as selected.
        selectedAudio = if (audioKey == auto) audioOptions.firstOrNull { it.original }?.key ?: audioOptions.firstOrNull()?.key ?: auto else audioKey,
        onAudio = onAudio,
    )
}

@Composable
private fun rememberYouTubeAudioKindNames(): com.ambient.tv.youtube.YouTubeAudioKindNames {
    val original = stringResource(R.string.youtube_audio_original)
    val dubbed = stringResource(R.string.youtube_audio_dubbed)
    val autoDubbed = stringResource(R.string.youtube_audio_auto_dubbed)
    val descriptive = stringResource(R.string.youtube_audio_descriptive)
    return remember(original, dubbed, autoDubbed, descriptive) {
        com.ambient.tv.youtube.YouTubeAudioKindNames(original, dubbed, autoDubbed, descriptive)
    }
}

private data class YtTrackChoice(
    val key: String,
    val label: String,
    val language: String?,
    val forced: Boolean,
    val group: Tracks.Group,
    val index: Int,
)

private data class YouTubePanelConfig(
    val hasVideo: Boolean,
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
    val audioSession: com.ambient.tv.ui.player.AudioSession? = null,
    val hasVideoExtras: Boolean = false,
    val videoExtras: @Composable ColumnScope.() -> Unit = {},
    val qualityOptions: List<Pair<String, String>> = emptyList(),
    val selectedQuality: String = "auto",
    val onQualitySelected: ((String) -> Unit)? = null,
)

@Composable
private fun YouTubeMedia3Playback(
    playback: YouTubePlayback,
    startPositionMs: Long,
    rendererKey: String,
    bigSeekSeconds: Int,
    presetKey: String,
    maxVideoHeight: Int,
    audioPassthroughEnabled: Boolean,
    streamTuning: com.ambient.tv.ui.player.Media3StreamTuning,
    onBack: () -> Unit,
    onCompleted: () -> Unit,
    onRenderer: (String, Long, Boolean) -> Unit,
    onVideoFormatChanged: (Float, Int?, Int?) -> Unit,
    streams: YouTubeStreamPanel? = null,
    onStreamAudio: ((String, Long) -> Unit)? = null,
    onStreamTuning: ((com.ambient.tv.ui.player.Media3StreamTuning, Long) -> Unit)? = null,
) {
    val context = LocalContext.current
    var controls by com.ambient.tv.ui.player.rememberPlayerControlsVisible()
    val osd = com.ambient.tv.ui.player.rememberPlayerOsdController()
    val changingItem = com.ambient.tv.ui.player.LocalPlayerChangingItem.current
    val panelNavigation = rememberPlayerPanelNavigation(playback.videoId)
    val panelOpen = panelNavigation.panel != UnifiedPlayerPanel.NONE
    var playing by remember { mutableStateOf(true) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_BUFFERING) }
    var playWhenReady by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(startPositionMs) }
    var duration by remember { mutableLongStateOf(playback.durationMs) }
    var speed by remember { mutableFloatStateOf(1f) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var aspectKey by remember { mutableStateOf("auto") }
    var zoomFactor by remember { mutableFloatStateOf(1f) }
    var error by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf<Tracks?>(null) }
    var defaultAudioTrackApplied by remember(playback.videoId) { mutableStateOf(false) }
    var completionPending by remember { mutableStateOf(false) }
    val seekCoordinator = remember(playback.videoId) { PlaybackSeekCoordinator() }
    var lateResumeApplied by remember(seekCoordinator) { mutableStateOf(startPositionMs > 0L) }
    var userSeeked by remember(playback.videoId) { mutableStateOf(false) }
    // A seek from the phone or the session controls is a user seek too: the late resume
    // position must not overwrite it.
    val externalSeekBase = remember(playback.videoId) { com.ambient.tv.ui.player.BackgroundPlaybackSession.externalSeekVersion }
    LaunchedEffect(com.ambient.tv.ui.player.BackgroundPlaybackSession.externalSeekVersion) {
        if (com.ambient.tv.ui.player.BackgroundPlaybackSession.externalSeekVersion != externalSeekBase) userSeeked = true
    }
    var seekCommitVersion by remember(playback.videoId) { mutableIntStateOf(0) }
    val metadata = checkNotNull(LocalPlaybackMetadata.current)
    val currentOnCompleted by rememberUpdatedState(onCompleted)
    val target = remember(presetKey, maxVideoHeight) {
        com.ambient.tv.youtube.YouTubeDeviceVideo.videoTarget(context, presetKey, maxVideoHeight)
    }
    val player = remember(playback.uri, playback.headers, startPositionMs, audioPassthroughEnabled, streamTuning) {
        BackgroundPlaybackSession.acquire(context, metadata, "media3", listOf(playback.uri, playback.headers, startPositionMs, audioPassthroughEnabled, streamTuning)) {
            val appContext = context.applicationContext
            // In the release gate like every player: after MPV/VLC, YouTube opens its stream only
            // once the previous player closed ("Vorheriger Player wird beendet…" on their side).
            com.ambient.tv.ui.player.PlaybackEngineFactory.gatedMedia3Handle { deferPrepare -> com.ambient.tv.ui.player.PlaybackEngineFactory.create(
                appContext, playback.uri, if (playback.isLive) 0 else startPositionMs,
                deferPrepare = deferPrepare,
                streamHeaders = playback.headers, mimeType = playback.mimeType,
                audioPassthroughEnabled = audioPassthroughEnabled,
                maxVideoHeight = target.height,
                preferredVideoMimeType = youtubeFamilyMimeType(target.family),
                maxVideoFrameRate = target.fps,
                preferOriginalAudio = true,
                centerYouTubeCaptions = true,
                streamTuning = streamTuning,
                streamUrlRenewer = { expired ->
                    val host = android.net.Uri.parse(expired).host.orEmpty()
                    if (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
                        com.ambient.tv.youtube.YouTubeStreamRelay.renewer?.invoke(playback.videoId, expired)
                    else null
                },
            ).apply {
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
            } }
        }.player
    }
    com.ambient.tv.ui.player.RestoreMedia3PlaybackProfile(player, metadata)
    com.ambient.tv.ui.player.Media3RenderOverloadDialog(player, panelNavigation) { panelNavigation.openVideoSettings() }
    com.ambient.tv.ui.player.ObserveMedia3DisplayFormat(player, onVideoFormatChanged)
    LaunchedEffect(com.ambient.tv.ui.player.Media3AudioProcessing.session(player)?.bitstreamPermitted) { speed = player.playbackParameters.speed }
    val ytAudio = com.ambient.tv.ui.player.Media3AudioProcessing.session(player)
    val nerdStats = rememberMedia3NerdStats(player, ytAudio?.performanceHint == true)
    val persistentVideoHost = LocalPersistentPlaybackVideoHost.current
    var sleepTimer by rememberPlaybackSleepTimer(player)
    val forcedAspectRatio = when (aspectKey) {
        "16:9" -> 16f / 9f
        "4:3" -> 4f / 3f
        "21:9" -> 21f / 9f
        else -> null
    }
    LaunchedEffect(player, resizeMode, forcedAspectRatio, zoomFactor) {
        (BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)
            ?.takeIf { it.player === player }
            ?.setVideoPresentation(resizeMode, forcedAspectRatio, zoomFactor)
    }
    val currentRenderer by rememberUpdatedState(rendererKey)
    val switchRenderer by rememberUpdatedState(onRenderer)
    val decoderErrorMessage = stringResource(R.string.player_error_video_decoder)
    com.ambient.tv.ui.player.Media3VideoTrackGuard(player) {
        val vlcPossible = (!playback.isLive && playback.adaptiveVideoUri != null && playback.adaptiveAudioUri != null) ||
            youtubeVlcPlaybackUri(playback.uri, playback.mimeType, playback.progressiveUri) != null
        when {
            currentRenderer == "auto" && vlcPossible -> switchRenderer("vlc", player.currentPosition, false)
            currentRenderer == "auto" && MpvBackendAvailability.isAvailable -> switchRenderer("mpv", player.currentPosition, false)
            else -> {
                error = decoderErrorMessage
                player.pause()
            }
        }
    }
    DisposableEffect(player) {
        val httpRetryHandler = android.os.Handler(player.applicationLooper)
        var httpRetryCount = 0
        playing = player.isPlaying
        playbackState = player.playbackState
        playWhenReady = player.playWhenReady
        tracks = player.currentTracks
        speed = player.playbackParameters.speed
        val listener = object : Player.Listener {
                override fun onIsPlayingChanged(value: Boolean) { playing = value }
                override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) { playWhenReady = ready }
                override fun onPlayerError(e: PlaybackException) {
                    // The outgoing listener survives until Compose disposes the screen. A
                    // surface/decoder teardown failure must not redirect the replacement engine.
                    if ((BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)?.player !== player) return
                    // A failing image effect chain continues without effects instead of an error.
                    if (com.ambient.tv.ui.player.Media3ImageProcessing.recoverFromEffectFailure(player, e)) return
                    android.util.Log.e(
                        "AmbientYouTube",
                        "Media3 ${e.errorCodeName} uri=${playback.uri.take(160)} cause=${e.cause?.javaClass?.simpleName}:${e.cause?.message}".withoutUrlQueries(),
                        e,
                    )
                    com.ambient.tv.diagnostics.DiagnosticLog.error("playback",
                        "YouTube Media3 ${e.errorCodeName} ${com.ambient.tv.diagnostics.diagnosticUrl(playback.uri)}", e)
                    error = listOfNotNull(e.errorCodeName, e.cause?.message ?: e.message).joinToString(": ")
                    val httpStatus = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                    if (httpStatus == 403 && httpRetryCount < 3) {
                        val resume = player.currentPosition.coerceAtLeast(0L)
                        val resumePlaying = player.playWhenReady
                        val backoffMs = 250L shl httpRetryCount++
                        error = null
                        httpRetryHandler.postDelayed({
                            if ((BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)?.player === player) {
                                player.seekTo(resume)
                                player.prepare()
                                player.playWhenReady = resumePlaying
                            }
                        }, backoffMs)
                        return
                    }
                    if (httpStatus != null) {
                        // VLC/MPV would request the same refused URL (VLC's DASH manager has crashed on it).
                        BackgroundPlaybackSession.stop()
                        com.ambient.tv.notifications.notifyStreamHttpError("youtube", playback.title, httpStatus)
                        return
                    }
                    if (currentRenderer == "auto") {
                        val vlcPossible = (!playback.isLive && playback.adaptiveVideoUri != null && playback.adaptiveAudioUri != null) ||
                            youtubeVlcPlaybackUri(playback.uri, playback.mimeType, playback.progressiveUri) != null
                        when {
                            vlcPossible -> switchRenderer("vlc", player.currentPosition, false)
                            MpvBackendAvailability.isAvailable -> switchRenderer("mpv", player.currentPosition, false)
                        }
                    }
                }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    val format = player.videoFormat
                    onVideoFormatChanged(format?.frameRate?.takeIf { it > 0f } ?: 0f, videoSize.width.takeIf { it > 0 }, videoSize.height.takeIf { it > 0 })
                }
                override fun onTracksChanged(value: Tracks) {
                    tracks = value
                    player.videoFormat?.let { format ->
                        onVideoFormatChanged(format.frameRate.takeIf { it > 0f } ?: 0f, format.width.takeIf { it > 0 }, format.height.takeIf { it > 0 })
                    }
                    if (!defaultAudioTrackApplied && value.groups.isNotEmpty()) {
                        applyYouTubeDefaultAudioTrack(player, value)
                        defaultAudioTrackApplied = true
                    }
                }
                override fun onPlaybackStateChanged(newPlaybackState: Int) {
                    playbackState = newPlaybackState
                    if (newPlaybackState == Player.STATE_READY) player.videoFormat?.let { format ->
                        onVideoFormatChanged(format.frameRate.takeIf { it > 0f } ?: 0f, format.width.takeIf { it > 0 }, format.height.takeIf { it > 0 })
                    }
                    if (!playback.isLive && newPlaybackState == Player.STATE_ENDED) {
                        completionPending = true
                    }
                }
        }
        // Adaptive tracks can change cadence without a size or track-group change.
        val formatListener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                onVideoFormatChanged(format.frameRate.takeIf { it > 0f } ?: 0f,
                    format.width.takeIf { it > 0 }, format.height.takeIf { it > 0 })
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(formatListener)
        onDispose {
            httpRetryHandler.removeCallbacksAndMessages(null)
            player.removeAnalyticsListener(formatListener)
            player.removeListener(listener)
        }
    }
    LaunchedEffect(completionPending) {
        if (completionPending) {
            completionPending = false
            currentOnCompleted()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            duration = player.duration.takeIf { it > 0 } ?: duration
            position = seekCoordinator.displayedPosition(player.currentPosition.coerceAtLeast(0))
            delay(250)
        }
    }
    com.ambient.tv.ui.player.PlayerControlsAutoHide(
        controls, playing, panelOpen, changingItem,
    ) { controls = it }
    fun seek(delta: Long) {
        if (playback.isLive) return
        BackgroundPlaybackSession.userSeek()
        osd.interact()
        userSeeked = true
        val limit = duration.takeIf { it > 0L } ?: playback.durationMs
        position = seekCoordinator.requestRelative(delta, position, limit)
        seekCoordinator.consumeCommit()?.let(player::seekTo)
        seekCommitVersion++
    }
    fun seekInMode(delta: Long) {
        if (playback.isLive) osd.reveal() else {
            seek(delta)
            osd.showSeekFeedback()
        }
    }
    fun heldSeekStep(baseMs: Long, e: androidx.compose.ui.input.key.KeyEvent) =
        com.ambient.tv.ui.player.acceleratedSeekStepMs(baseMs, e.nativeKeyEvent.repeatCount, duration.takeIf { it > 0L } ?: playback.durationMs)
    YouTubeSeekCommit(seekCommitVersion, seekCoordinator, player::seekTo)
    LaunchedEffect(playback.resumeMs, playback.resumeResolved) {
        if (!playback.resumeResolved) return@LaunchedEffect
        val target = lateYouTubeResumeTarget(playback.isLive, startPositionMs, playback.resumeMs, userSeeked,
            resumeAlreadyApplied = lateResumeApplied)
        // Zero is a resolved target too: later metadata must not deduct/apply the resume again.
        lateResumeApplied = true
        if (target == null) return@LaunchedEffect
        position = seekCoordinator.requestAbsolute(target)
        seekCoordinator.consumeCommit()?.let(player::seekTo)
    }
    val videoChoices = remember(tracks, context) { tracks?.let { ytTrackChoices(context, it, C.TRACK_TYPE_VIDEO) }.orEmpty() }
    val audioChoices = remember(tracks, context) { tracks?.let { ytTrackChoices(context, it, C.TRACK_TYPE_AUDIO) }.orEmpty() }
    // Languages, like Plex's audio tracks: one entry per language instead of one per codec/bitrate.
    val audioLanguages = remember(tracks) { tracks?.let(::ytAudioLanguages).orEmpty() }
    val streamAudio = streams?.takeIf { audioLanguages.size < 2 && it.audioOptions.size > 1 && onStreamAudio != null }
    val audioKinds = rememberYouTubeAudioKindNames()
    val audioFallback = stringResource(R.string.player_default_track)
    val audioLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    // Quality: Media3 adapts by itself ("automatic", bounded by the display); a choice pins one track.
    var pinnedVideo by remember(player) { mutableStateOf("auto") }
    val subtitleChoices = remember(tracks, context) { tracks?.let { ytTrackChoices(context, it, C.TRACK_TYPE_TEXT) }.orEmpty() }
    val vf = player.videoFormat
    val af = player.audioFormat
    // Handheld live playback keeps running on Back; TV and VOD use the minimize setting.
    val handheldUi = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val backMinimizes = com.ambient.tv.ui.player.rememberBackMinimizesPlayback()
    // Gate on !panelOpen so panel BackHandler is not order-fragile.
    BackHandler(enabled = !panelOpen) {
        if (controls && !handheldUi) controls = false
        else if (com.ambient.tv.ui.player.backLeavesPlaybackRunning(false, handheldUi, backMinimizes(), isLive = playback.isLive)) onBack()
        else BackgroundPlaybackSession.stop()
    }

    // One remote handling for the engine and, while the focus is in the OSD or panel above it, the host.
    val chapterNavigation = com.ambient.tv.ui.player.LocalPlaybackNavigation.current
    val itemControls = LocalPlaybackItemControls.current
    val osdKeys: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { e ->
        // Shared remote handling (PlayerOsdKeys); only the actions below are Media3's own.
        val seekRelative: (Long) -> Unit = { seek(it) }
        val seekWithBar: (Long) -> Unit = { seekInMode(it) }
        handlePlayerOsdKey(
            e.key, e.type == KeyEventType.KeyDown,
            OsdKeyState(OsdContent.VIDEO, controls && !osd.seekFeedback && !osd.closing, panelOpen, osd.seekFeedback, closing = osd.closing),
            OsdSeekSteps(BackgroundPlaybackSession.seekJumpMs(bigSeekSeconds * 1000L), held = { heldSeekStep(it, e) }),
            object : OsdKeyTarget {
                override fun chapterSeekDelta(direction: Int) = chapterNavigation?.chapterStep(position, direction)
                override fun isPlaying() = player.isPlaying
                override fun togglePlayback() { BackgroundPlaybackSession.userToggle() }
                override fun play() { BackgroundPlaybackSession.userPlay() }
                override fun pause() { BackgroundPlaybackSession.userPause() }
                override fun stop() { BackgroundPlaybackSession.stop() }
                override fun seek(deltaMs: Long, revealOsd: Boolean) {
                    if (revealOsd) { seekRelative(deltaMs); osd.reveal() } else seekWithBar(deltaMs)
                }
                override fun showOsd() { osd.reveal() }
                override fun hideSeekFeedback() { osd.reveal() }
                override fun restart() {
                    itemControls?.previous?.invoke()
                }
                override fun next() { itemControls?.next?.invoke() }
                override fun previousTrack() = Unit
                override fun zap(delta: Int) = Unit
            },
        )
    }
    com.ambient.tv.ui.player.PublishPlayerPanelKeys(osdKeys)
    Box(Modifier.fillMaxSize().background(if (persistentVideoHost) Color.Transparent else Color.Black).onPreviewKeyEvent(osdKeys).focusRequester(com.ambient.tv.ui.player.rememberPlayerEngineFocus()).focusable()) {
        if (!persistentVideoHost) Media3VideoTexture(
            player = player,
            resizeMode = resizeMode,
            aspectRatio = forcedAspectRatio,
            zoom = zoomFactor,
            modifier = Modifier.fillMaxSize(),
        )
        PlayerBufferingSpinner(
            visible = media3WaitingForPlaybackPicture(playbackState, playWhenReady),
            modifier = Modifier.align(Alignment.Center),
        )
        PlayerTouchLayer(
            onToggleControls = { if (!panelOpen) { if (!controls || osd.closing) osd.reveal() else osd.close() } },
            onTogglePlayback = { BackgroundPlaybackSession.userToggle() },
            onSeekBack = if (playback.isLive) null else {{ seek(-BackgroundPlaybackSession.seekJumpMs(bigSeekSeconds * 1000L)) }},
            onSeekForward = if (playback.isLive) null else {{ seek(BackgroundPlaybackSession.seekJumpMs(bigSeekSeconds * 1000L)) }},
            pinchZoom = PlayerPinchZoom(
                key = player,
                enabled = !panelOpen && ytAudio?.audioOnly != true,
                presentation = VideoPresentation(resizeMode, forcedAspectRatio, zoomFactor),
                sourceAspectRatio = { player.pinchVideoAspectRatio(metadata.sourceAspectRatioHint) },
                onChanged = {
                    resizeMode = it.resizeMode
                    aspectKey = "auto"
                    zoomFactor = it.zoom
                },
            ),
        )
        val ytPlan = ytAudio?.outputPlan()
        LaunchedEffect(ytPlan?.needsMpv, ytPlan?.mode) {
            if (ytPlan?.needsMpv == true && com.ambient.tv.ui.player.AudioEngineRedirectGuard.once(playback.uri)) {
                onRenderer("mpv", player.currentPosition.coerceAtLeast(0L), false)
            }
        }
        PlaybackPerformanceNotice(media3LoadHint(player, nerdStats))
        val showNerdOverlay = ytAudio?.performanceHint == true
        val showFpsOverlay = ytAudio?.showFpsOverlay == true
        PlaybackNerdStatsOverlay(
            audioConversion = ytAudio?.activeLocalConversion?.let { "${stringResource(com.ambient.tv.R.string.audio_conversion_local)} · $it" },
            enabled = showNerdOverlay,
            model = rememberMedia3NerdOverlayModel(
                player = player,
                stats = nerdStats,
                enabled = showNerdOverlay,
                audioOutputMode = ytAudio?.outputPlan()?.mode,
            ),
        )
        PlaybackFpsOverlay(
            enabled = showFpsOverlay,
            fps = rememberMedia3PlaybackFps(player, showFpsOverlay && !showNerdOverlay),
            cadenceFps = player.videoFormat?.frameRate?.toDouble(),
            refreshHz = rememberDisplayRefreshHz(),
            nerdStatsVisible = showNerdOverlay,
        )
        Box(Modifier.fillMaxSize().zIndex(if (panelOpen) PLAYER_SETTINGS_PANEL_Z_INDEX else 0f)) {
            YouTubeControls(
                controlsVisible = controls,
                playback = playback,
                backend = "Media3",
                position = position,
                duration = duration,
                playing = playing,
                loading = media3WaitingForPlaybackPicture(playbackState, playWhenReady),
                rendererKey = rendererKey,
                seekJumpMs = bigSeekSeconds * 1000L,
                onToggle = { BackgroundPlaybackSession.userToggle() },
                onSeek = ::seek,
                onBack = onBack,
                onRenderer = { key, global -> onRenderer(key, position, global) },
                info = listOfNotNull(
                    vf?.let { "${it.width}×${it.height}".takeIf { _ -> it.width > 0 && it.height > 0 } },
                    HdrSupport.sourceStandard(vf)?.label,
                    vf?.codecs ?: vf?.sampleMimeType?.substringAfter('/'),
                    af?.codecs ?: af?.sampleMimeType?.substringAfter('/'),
                ),
                panelConfig = YouTubePanelConfig(
                    hasVideo = true,
                    scalingKey = when (resizeMode) { AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "zoom"; AspectRatioFrameLayout.RESIZE_MODE_FILL -> "stretch"; else -> "fit" },
                    aspectKey = aspectKey,
                    zoomFactor = zoomFactor,
                    speed = speed,
                    onScalingChanged = { key -> resizeMode = when (key) { "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT } },
                    onAspectChanged = { aspectKey = it },
                    onZoomChanged = { zoomFactor = it },
                    onSpeedChanged = if (playback.isLive || com.ambient.tv.ui.player.Media3AudioProcessing.session(player)?.bitstreamPermitted == true) null else ({ value -> speed = value; player.setPlaybackSpeed(value) }),
                    qualityOptions = if (playback.isLive || videoChoices.size < 2) emptyList() else
                        listOf("auto" to stringResource(R.string.player_quality_auto, player.videoFormat?.height?.takeIf { it > 0 }?.let { "${it}p" } ?: "–")) +
                            videoChoices.sortedByDescending { it.group.getTrackFormat(it.index).height }.map { it.key to it.label },
                    selectedQuality = pinnedVideo,
                    onQualitySelected = { key -> pinnedVideo = key; applyTrackChoice(player, videoChoices, key, C.TRACK_TYPE_VIDEO) },
                    audioOptions = when {
                        audioLanguages.size > 1 -> audioLanguages.map { it.option.key to com.ambient.tv.youtube.youtubeAudioLabel(it.option, audioLocale, audioKinds, audioFallback) }
                        streamAudio != null -> streamAudio.audioOptions
                        else -> audioChoices.map { it.key to it.label }.ifEmpty { listOf("auto" to audioFallback) }
                    },
                    selectedAudio = when {
                        audioLanguages.size > 1 -> audioLanguages.firstOrNull { language -> language.groups.any { it.isSelected } }?.option?.key ?: "auto"
                        streamAudio != null -> streamAudio.selectedAudio
                        else -> audioChoices.firstOrNull { it.group.isTrackSelected(it.index) }?.key ?: "auto"
                    },
                    onAudioSelected = { key ->
                        when {
                            audioLanguages.size > 1 -> audioLanguages.firstOrNull { it.option.key == key }?.let { applyYtAudioLanguage(player, it) }
                            streamAudio != null -> if (key != streamAudio.selectedAudio) onStreamAudio?.invoke(key, player.currentPosition)
                            else -> applyTrackChoice(player, audioChoices, key, C.TRACK_TYPE_AUDIO)
                        }
                    },
                    subtitleOptions = listOf("off" to stringResource(R.string.player_subtitles_off)) + subtitleChoices.map { it.key to it.label },
                    selectedSubtitle = subtitleChoices.firstOrNull { it.group.isTrackSelected(it.index) }?.key ?: "off",
                    onSubtitleSelected = { key ->
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, key == "off").build()
                        if (key != "off") applyTrackChoice(player, subtitleChoices, key, C.TRACK_TYPE_TEXT)
                    },
                    sleepTimer = sleepTimer,
                    onSleepChanged = { sleepTimer = it },
                    infoLines = buildList {
                        add(stringResource(R.string.player_nerd_image_pipeline) to com.ambient.tv.ui.player.Media3ImageProcessing.get(context, player).diagnosticSummary())
                        add(stringResource(R.string.player_nerd_backend) to "Media3")
                        vf?.let { format ->
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
                        (context as? android.app.Activity)?.let(androidx.core.content.ContextCompat::getDisplayOrDefault)?.refreshRate?.takeIf { it > 0f }?.let { add(stringResource(R.string.player_nerd_display_refresh) to formatNerdHz(it.toDouble()).orEmpty()) }
                        nerdStats.renderedVideoFrames?.let { add(stringResource(R.string.player_nerd_rendered_frames) to it.toString()) }
                        nerdStats.droppedVideoFrames?.let { add(stringResource(R.string.player_nerd_dropped_frames) to it.toString()) }
                        nerdStats.skippedVideoFrames?.takeIf { it > 0 }?.let { add(stringResource(R.string.player_nerd_skipped_frames) to it.toString()) }
                        af?.let { format ->
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
                    audioSession = com.ambient.tv.ui.player.Media3AudioProcessing.session(player),
                    hasVideoExtras = videoChoices.isNotEmpty() || onStreamTuning != null,
                    videoExtras = {
                        if (videoChoices.isNotEmpty()) com.ambient.tv.ui.common.RightPanelSectionTitle(stringResource(R.string.settings_advanced_title))
                        if (videoChoices.isNotEmpty()) PlayerDropUpSelector(
                            stringResource(R.string.player_quality),
                            currentTrackLabel(videoChoices, stringResource(R.string.player_automatic)),
                            listOf("auto" to stringResource(R.string.player_automatic)) + videoChoices.map { it.key to it.label },
                            videoChoices.firstOrNull { it.group.isTrackSelected(it.index) }?.key ?: "auto",
                        ) { key -> applyTrackChoice(player, videoChoices, key, C.TRACK_TYPE_VIDEO) }
                        onStreamTuning?.let { change -> YouTubeStreamTuningControls(streamTuning) { change(it, player.currentPosition) } }
                    },
                ),
                panelNavigation = panelNavigation,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp)) }
    }
}

@Composable
private fun YouTubeVlcPlayback(playback: YouTubePlayback, audioUri: String?, streams: YouTubeStreamPanel?, startPositionMs: Long, rendererKey: String, bigSeekSeconds: Int, preferences: AppPreferences, onBack: () -> Unit, onCompleted: () -> Unit, onRenderer: (String, Long, Boolean) -> Unit, onVideoFormatChanged: (Float, Int?, Int?) -> Unit) {
    val context = LocalContext.current
    val controller = rememberVlcPlaybackController(
        context,
        playback.uri,
        startPositionMs,
        playback.headers,
        initialScalingFilter = preferences.vlcScalingFilterKey,
        initialAudioPassthrough = preferences.mediaAudioPassthroughEnabled,
        networkCache = true,
        audioSlaveUrl = audioUri,
    )
    if (controller == null) { LaunchedEffect(Unit) { onRenderer("media3", startPositionMs, false) }; return }
    val persistentVideoHost = LocalPersistentPlaybackVideoHost.current
    var controls by com.ambient.tv.ui.player.rememberPlayerControlsVisible()
    val osd = com.ambient.tv.ui.player.rememberPlayerOsdController()
    val changingItem = com.ambient.tv.ui.player.LocalPlayerChangingItem.current
    val panelNavigation = rememberPlayerPanelNavigation(playback.videoId)
    val panelOpen = panelNavigation.panel != UnifiedPlayerPanel.NONE
    val snapshot = rememberVlcPlaybackSnapshot(controller, controller.audioSession.performanceHint || controller.audioSession.showFpsOverlay)
    // A failure the VLC controller could not recover from itself: continue in Media3 at the
    // same position instead of leaving a black picture (YouTube always plays there).
    LaunchedEffect(controller.errorMessage) {
        val message = controller.errorMessage ?: return@LaunchedEffect
        if (!BackgroundPlaybackSession.owns(controller)) return@LaunchedEffect
        com.ambient.tv.diagnostics.DiagnosticLog.warn("playback", "YouTube VLC failed, continuing in Media3: $message")
        com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
            "playback:backend-switch", R.string.notice_switched_to_media3, subject = "VLC",
            kind = com.ambient.tv.notifications.NoticeKind.PLAYBACK, interruptPlayback = false,
        ))
        onRenderer("media3", snapshot.positionMs.takeIf { it > 0L } ?: startPositionMs, false)
    }
    // "Vorheriger Player wird beendet…" and, after a dialog, focus back on the panel row.
    com.ambient.tv.ui.player.VlcRenderOverloadDialog(controller, panelNavigation)
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
    val seekCoordinator = remember(controller) { PlaybackSeekCoordinator() }
    var lateResumeApplied by remember(seekCoordinator) { mutableStateOf(startPositionMs > 0L) }
    var userSeeked by remember(controller) { mutableStateOf(false) }
    // A seek from the phone or the session controls is a user seek too: the late resume
    // position must not overwrite it.
    val externalSeekBase = remember(controller) { com.ambient.tv.ui.player.BackgroundPlaybackSession.externalSeekVersion }
    LaunchedEffect(com.ambient.tv.ui.player.BackgroundPlaybackSession.externalSeekVersion) {
        if (com.ambient.tv.ui.player.BackgroundPlaybackSession.externalSeekVersion != externalSeekBase) userSeeked = true
    }
    var seekCommitVersion by remember(controller) { mutableIntStateOf(0) }
    var displayedPosition by remember(controller) { mutableLongStateOf(startPositionMs.coerceAtLeast(0L)) }
    var resize by remember { mutableStateOf("fit") }
    var aspect by remember { mutableStateOf("auto") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var speed by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(controller.bitstreamPermitted) { if (controller.bitstreamPermitted) speed = 1f }
    var sleepTimer by rememberPlaybackSleepTimer(controller)
    val videoTracks = controller.videoTracks()
    val audioTracks = controller.audioTracks()
    val subtitleTracks = controller.subtitleTracks()
    val displayLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.ROOT
    val enginePositionMs by rememberUpdatedState(snapshot.positionMs)
    LaunchedEffect(controller) {
        while (true) {
            displayedPosition = seekCoordinator.displayedPosition(enginePositionMs)
            delay(250)
        }
    }
    LaunchedEffect(controller) {
        repeat(24) { attempt ->
            if (attempt > 0) delay(250)
            val currentAudio = controller.audioTracks()
            if (currentAudio.isEmpty()) return@repeat
            val preferredAudio = currentAudio.firstOrNull { isYouTubeOriginalAudioTrack(null, it.second) }
            preferredAudio?.first?.let(controller::selectAudioTrack)
            return@LaunchedEffect
        }
    }
    LaunchedEffect(controller.ended) { if (!playback.isLive && controller.ended) onCompleted() }
    fun seek(delta: Long) {
        if (playback.isLive) return
        BackgroundPlaybackSession.userSeek()
        osd.interact()
        userSeeked = true
        val limit = snapshot.durationMs.takeIf { it > 0L } ?: playback.durationMs
        displayedPosition = seekCoordinator.requestRelative(delta, displayedPosition, limit)
        seekCoordinator.consumeCommit()?.let(controller::seekTo)
        seekCommitVersion++
    }
    fun seekInMode(delta: Long) {
        if (playback.isLive) osd.reveal() else {
            seek(delta)
            osd.showSeekFeedback()
        }
    }
    fun heldSeekStep(baseMs: Long, e: androidx.compose.ui.input.key.KeyEvent) =
        com.ambient.tv.ui.player.acceleratedSeekStepMs(baseMs, e.nativeKeyEvent.repeatCount, snapshot.durationMs.takeIf { it > 0L } ?: playback.durationMs)
    YouTubeSeekCommit(seekCommitVersion, seekCoordinator, controller::seekTo)
    LaunchedEffect(playback.resumeMs, playback.resumeResolved) {
        if (!playback.resumeResolved) return@LaunchedEffect
        val target = lateYouTubeResumeTarget(playback.isLive, startPositionMs, playback.resumeMs, userSeeked,
            resumeAlreadyApplied = lateResumeApplied)
        // Zero is a resolved target too: later metadata must not deduct/apply the resume again.
        lateResumeApplied = true
        if (target == null) return@LaunchedEffect
        displayedPosition = seekCoordinator.requestAbsolute(target)
        seekCoordinator.consumeCommit()?.let(controller::seekTo)
    }
    com.ambient.tv.ui.player.PlayerControlsAutoHide(
        controls, snapshot.isPlaying, panelOpen, changingItem,
    ) { controls = it }
    // Handheld live playback keeps running on Back; TV and VOD use the minimize setting.
    val handheldUi = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val backMinimizes = com.ambient.tv.ui.player.rememberBackMinimizesPlayback()
    // Gate on !panelOpen so panel BackHandler is not order-fragile.
    BackHandler(enabled = !panelOpen) {
        if (controls && !handheldUi) controls = false
        else if (com.ambient.tv.ui.player.backLeavesPlaybackRunning(false, handheldUi, backMinimizes(), isLive = playback.isLive)) onBack()
        else BackgroundPlaybackSession.stop()
    }
    // One remote handling for the engine and, while the focus is in the OSD or panel above it, the host.
    val chapterNavigation = com.ambient.tv.ui.player.LocalPlaybackNavigation.current
    val itemControls = LocalPlaybackItemControls.current
    val osdKeys: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { e ->
        // Shared remote handling (PlayerOsdKeys); only the actions below are VLC's own.
        val seekRelative: (Long) -> Unit = { seek(it) }
        val seekWithBar: (Long) -> Unit = { seekInMode(it) }
        handlePlayerOsdKey(
            e.key, e.type == KeyEventType.KeyDown,
            OsdKeyState(OsdContent.VIDEO, controls && !osd.seekFeedback && !osd.closing, panelOpen, osd.seekFeedback, closing = osd.closing),
            OsdSeekSteps(BackgroundPlaybackSession.seekJumpMs(bigSeekSeconds * 1000L), held = { heldSeekStep(it, e) }),
            object : OsdKeyTarget {
                override fun chapterSeekDelta(direction: Int) = chapterNavigation?.chapterStep(displayedPosition, direction)
                override fun isPlaying() = snapshot.isPlaying
                override fun togglePlayback() { BackgroundPlaybackSession.userToggle() }
                override fun play() { BackgroundPlaybackSession.userPlay() }
                override fun pause() { BackgroundPlaybackSession.userPause() }
                override fun stop() { BackgroundPlaybackSession.stop() }
                override fun seek(deltaMs: Long, revealOsd: Boolean) {
                    if (revealOsd) { seekRelative(deltaMs); osd.reveal() } else seekWithBar(deltaMs)
                }
                override fun showOsd() { osd.reveal() }
                override fun hideSeekFeedback() { osd.reveal() }
                override fun restart() {
                    itemControls?.previous?.invoke()
                }
                override fun next() { itemControls?.next?.invoke() }
                override fun previousTrack() = Unit
                override fun zap(delta: Int) = Unit
            },
        )
    }
    com.ambient.tv.ui.player.PublishPlayerPanelKeys(osdKeys)
    Box(Modifier.fillMaxSize().background(if (persistentVideoHost) Color.Transparent else Color.Black).onPreviewKeyEvent(osdKeys).focusRequester(com.ambient.tv.ui.player.rememberPlayerEngineFocus()).focusable()) {
        if (!persistentVideoHost) VlcVideoSurface(controller, Modifier.fillMaxSize(), LocalPlaybackMetadata.current?.sourceAspectRatioHint)
        PlayerBufferingSpinner(
            visible = waitingForPlaybackPicture(snapshot.wantsToPlay, snapshot.isPlaying),
            modifier = Modifier.align(Alignment.Center),
        )
        PlayerTouchLayer(
            onToggleControls = { if (!panelOpen) { if (!controls || osd.closing) osd.reveal() else osd.close() } },
            onTogglePlayback = { BackgroundPlaybackSession.userToggle() },
            onSeekBack = if (playback.isLive) null else {{ seek(-BackgroundPlaybackSession.seekJumpMs(bigSeekSeconds * 1000L)) }},
            onSeekForward = if (playback.isLive) null else {{ seek(BackgroundPlaybackSession.seekJumpMs(bigSeekSeconds * 1000L)) }},
            pinchZoom = if (controller.audioSession.audioOnly) null else PlayerPinchZoom(
                key = controller,
                enabled = !panelOpen,
                presentation = VideoPresentation(videoResizeMode(resize), videoAspectRatio(aspect), zoom),
                sourceAspectRatio = { controller.videoAspectRatio() },
                onChanged = {
                    resize = "fit"
                    aspect = "auto"
                    zoom = it.zoom
                    controller.setVideoPresentation(resize, null, zoom)
                },
            ),
        )
        val ytVlcPlan = controller.audioSession.outputPlan()
        LaunchedEffect(ytVlcPlan.needsMpv, ytVlcPlan.mode) {
            if (ytVlcPlan.needsMpv && com.ambient.tv.ui.player.AudioEngineRedirectGuard.once(playback.uri)) {
                onRenderer("mpv", snapshot.positionMs, false)
            }
        }
        PlaybackPerformanceNotice(vlcLoadHint(controller, snapshot))
        val showNerdOverlay = controller.audioSession.performanceHint
        val showFpsOverlay = controller.audioSession.showFpsOverlay
        PlaybackNerdStatsOverlay(
            audioConversion = controller.audioSession.activeLocalConversion?.let { "${stringResource(com.ambient.tv.R.string.audio_conversion_local)} · $it" },
            enabled = showNerdOverlay,
            model = rememberVlcNerdOverlayModel(
                snapshot = snapshot,
                enabled = showNerdOverlay,
                audioOutputMode = controller.audioSession.outputPlan().mode,
            ),
        )
        PlaybackFpsOverlay(
            enabled = showFpsOverlay,
            fps = snapshot.runtimeStats?.videoFps,
            refreshHz = rememberDisplayRefreshHz(),
            nerdStatsVisible = showNerdOverlay,
        )
        Box(Modifier.fillMaxSize().zIndex(if (panelOpen) PLAYER_SETTINGS_PANEL_Z_INDEX else 0f)) {
            YouTubeControls(
                controlsVisible = controls,
                playback, "VLC", displayedPosition, snapshot.durationMs, snapshot.isPlaying,
                waitingForPlaybackPicture(snapshot.wantsToPlay, snapshot.isPlaying),
                rendererKey, bigSeekSeconds * 1000L, BackgroundPlaybackSession::userToggle, ::seek, onBack,
                { key, global -> onRenderer(key, displayedPosition, global) },
                if (snapshot.hasVideo) listOfNotNull(
                    snapshot.runtimeStats?.let { stats ->
                        if (stats.videoWidth != null && stats.videoHeight != null) "${stats.videoWidth}×${stats.videoHeight}" else null
                    },
                    controller.sourceHdrStandard?.label,
                    formatNerdFps(snapshot.runtimeStats?.videoFps),
                    snapshot.runtimeStats?.videoCodec,
                    snapshot.runtimeStats?.audioCodec,
                ) else listOf(stringResource(R.string.player_audio_only)),
                YouTubePanelConfig(
                    hasVideo = snapshot.hasVideo,
                    scalingKey = resize,
                    aspectKey = aspect,
                    zoomFactor = zoom,
                    speed = speed,
                    onScalingChanged = { resize = it; controller.setVideoPresentation(resize, aspect.takeUnless { value -> value == "auto" }, zoom) },
                    onAspectChanged = { aspect = it; controller.setVideoPresentation(resize, it.takeUnless { value -> value == "auto" }, zoom) },
                    onZoomChanged = { zoom = it; controller.setVideoPresentation(resize, aspect.takeUnless { value -> value == "auto" }, zoom) },
                    onSpeedChanged = if (playback.isLive || controller.bitstreamPermitted) null else ({ value -> speed = value; controller.setSpeed(value) }),
                    audioOptions = streams?.audioOptions?.takeIf { it.size > 1 }
                        ?: audioTracks.map { it.first.toString() to youtubeTrackLabel(it.second, displayLocale) }.ifEmpty { listOf("auto" to stringResource(R.string.player_default_track)) },
                    selectedAudio = if ((streams?.audioOptions?.size ?: 0) > 1) streams!!.selectedAudio else controller.currentAudioTrackId().toString(),
                    onAudioSelected = { key -> if ((streams?.audioOptions?.size ?: 0) > 1) streams!!.onAudio(key) else key.toIntOrNull()?.let(controller::selectAudioTrack) },
                    qualityOptions = streams?.qualityOptions.orEmpty(),
                    selectedQuality = streams?.selectedQuality ?: "auto",
                    onQualitySelected = streams?.onQuality,
                    subtitleOptions = listOf("-1" to stringResource(R.string.player_subtitles_off)) + subtitleTracks.map { it.first.toString() to youtubeTrackLabel(it.second, displayLocale) },
                    selectedSubtitle = controller.currentSubtitleTrackId().toString(),
                    onSubtitleSelected = { controller.selectSubtitleTrack(it.toIntOrNull()?.takeIf { id -> id >= 0 }) },
                    sleepTimer = sleepTimer,
                    onSleepChanged = { sleepTimer = it },
                    infoLines = buildList {
                        add(stringResource(R.string.player_nerd_backend) to "VLC")
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
                        audioTracks.firstOrNull { it.first == controller.currentAudioTrackId() }?.second?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_audio) to it) }
                        snapshot.runtimeStats?.let { stats ->
                            stats.decodedAudio?.let { add(stringResource(R.string.player_nerd_decoded_audio_buffers) to it.toString()) }
                            stats.playedAudioBuffers?.let { add(stringResource(R.string.player_nerd_played_audio_buffers) to it.toString()) }
                            stats.lostAudioBuffers?.takeIf { it > 0 }?.let { add(stringResource(R.string.player_nerd_lost_audio_buffers) to it.toString()) }
                        }
                    },
                    audioSession = controller.audioSession,
                    hasVideoExtras = videoTracks.size > 1,
                    videoExtras = {
                        if (videoTracks.size > 1) com.ambient.tv.ui.common.RightPanelSectionTitle(stringResource(R.string.settings_advanced_title))
                        if (videoTracks.size > 1) PlayerDropUpSelector(
                            stringResource(R.string.player_quality),
                            videoTracks.firstOrNull { it.first == controller.currentVideoTrackId() }?.second ?: stringResource(R.string.player_automatic),
                            videoTracks.map { it.first.toString() to it.second },
                            controller.currentVideoTrackId().toString(),
                        ) { it.toIntOrNull()?.let(controller::selectVideoTrack) }
                    },
                ),
                panelNavigation = panelNavigation,
            )
        }
    }
}

@Composable
private fun YouTubeMpvPlayback(playback: YouTubePlayback, streams: YouTubeStreamPanel?, startPositionMs: Long, rendererKey: String, bigSeekSeconds: Int, preferences: AppPreferences, onBack: () -> Unit, onCompleted: () -> Unit, onRenderer: (String, Long, Boolean) -> Unit, onVideoFormatChanged: (Float, Int?, Int?) -> Unit) {
    val context = LocalContext.current
    val controller = rememberMpvPlaybackController(
        context, playback.uri, startPositionMs, playback.headers,
        audioPassthroughEnabled = preferences.mediaAudioPassthroughEnabled,
        videoOutputMode = preferences.mpvVideoOutputModeKey,
        networkCache = true,
    )
    if (controller == null) { LaunchedEffect(Unit) { onRenderer("media3", startPositionMs, false) }; return }
    val persistentVideoHost = LocalPersistentPlaybackVideoHost.current
    var controls by com.ambient.tv.ui.player.rememberPlayerControlsVisible()
    val osd = com.ambient.tv.ui.player.rememberPlayerOsdController()
    val changingItem = com.ambient.tv.ui.player.LocalPlayerChangingItem.current
    val panelNavigation = rememberPlayerPanelNavigation(playback.videoId)
    val panelOpen = panelNavigation.panel != UnifiedPlayerPanel.NONE
    val snapshot = rememberMpvPlaybackSnapshot(controller, controller.audioSession.performanceHint || controller.audioSession.showFpsOverlay)
    // A failure the MPV controller could not recover from itself: continue in Media3 at the
    // same position instead of leaving a black picture (YouTube always plays there).
    LaunchedEffect(controller.errorMessage) {
        val message = controller.errorMessage ?: return@LaunchedEffect
        if (!BackgroundPlaybackSession.owns(controller)) return@LaunchedEffect
        com.ambient.tv.diagnostics.DiagnosticLog.warn("playback", "YouTube MPV failed, continuing in Media3: $message")
        com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
            "playback:backend-switch", R.string.notice_switched_to_media3, subject = "MPV",
            kind = com.ambient.tv.notifications.NoticeKind.PLAYBACK, interruptPlayback = false,
        ))
        onRenderer("media3", snapshot.positionMs.takeIf { it > 0L } ?: startPositionMs, false)
    }
    LaunchedEffect(controller, snapshot.videoSize, snapshot.containerFps) {
        val dimensions = com.ambient.tv.ui.player.DisplayRefreshRateMatcher.parseVideoDimensions(snapshot.videoSize)
        val fps = snapshot.containerFps?.toFloat() ?: 0f
        if (fps > 0f || dimensions != null) onVideoFormatChanged(fps, dimensions?.first, dimensions?.second)
    }
    com.ambient.tv.ui.player.MpvRenderOverloadDialog(controller, panelNavigation)
    val seekCoordinator = remember(controller) { PlaybackSeekCoordinator() }
    var lateResumeApplied by remember(seekCoordinator) { mutableStateOf(startPositionMs > 0L) }
    var userSeeked by remember(controller) { mutableStateOf(false) }
    // A seek from the phone or the session controls is a user seek too: the late resume
    // position must not overwrite it.
    val externalSeekBase = remember(controller) { com.ambient.tv.ui.player.BackgroundPlaybackSession.externalSeekVersion }
    LaunchedEffect(com.ambient.tv.ui.player.BackgroundPlaybackSession.externalSeekVersion) {
        if (com.ambient.tv.ui.player.BackgroundPlaybackSession.externalSeekVersion != externalSeekBase) userSeeked = true
    }
    var seekCommitVersion by remember(controller) { mutableIntStateOf(0) }
    var displayedPosition by remember(controller) { mutableLongStateOf(startPositionMs.coerceAtLeast(0L)) }
    var resize by remember { mutableStateOf("fit") }
    var aspect by remember { mutableStateOf("auto") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var speed by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(controller.bitstreamPermitted) { if (controller.bitstreamPermitted) speed = 1f }
    var sleepTimer by rememberPlaybackSleepTimer(controller)
    val videoTracks = controller.videoTracks()
    val audioTracks = controller.audioTracks()
    val subtitleTracks = controller.subtitleTracks()
    val displayLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.ROOT
    val enginePositionMs by rememberUpdatedState(snapshot.positionMs)
    LaunchedEffect(controller) {
        while (true) {
            displayedPosition = seekCoordinator.displayedPosition(enginePositionMs)
            delay(250)
        }
    }
    LaunchedEffect(controller) {
        repeat(24) { attempt ->
            if (attempt > 0) delay(250)
            val currentAudio = controller.audioTracks()
            if (currentAudio.isEmpty()) return@repeat
            val audio = currentAudio.firstOrNull { isYouTubeOriginalAudioTrack(it.language, it.title) }
            if (audio != null && !audio.selected) controller.selectAudio(audio.id)
            return@LaunchedEffect
        }
    }
    LaunchedEffect(controller.ended) { if (!playback.isLive && controller.ended) onCompleted() }
    fun seek(delta: Long) {
        if (playback.isLive) return
        BackgroundPlaybackSession.userSeek()
        osd.interact()
        userSeeked = true
        val limit = snapshot.durationMs.takeIf { it > 0L } ?: playback.durationMs
        displayedPosition = seekCoordinator.requestRelative(delta, displayedPosition, limit)
        seekCoordinator.consumeCommit()?.let(controller::seekTo)
        seekCommitVersion++
    }
    fun seekInMode(delta: Long) {
        if (playback.isLive) osd.reveal() else {
            seek(delta)
            osd.showSeekFeedback()
        }
    }
    fun heldSeekStep(baseMs: Long, e: androidx.compose.ui.input.key.KeyEvent) =
        com.ambient.tv.ui.player.acceleratedSeekStepMs(baseMs, e.nativeKeyEvent.repeatCount, snapshot.durationMs.takeIf { it > 0L } ?: playback.durationMs)
    YouTubeSeekCommit(seekCommitVersion, seekCoordinator, controller::seekTo)
    LaunchedEffect(playback.resumeMs, playback.resumeResolved) {
        if (!playback.resumeResolved) return@LaunchedEffect
        val target = lateYouTubeResumeTarget(playback.isLive, startPositionMs, playback.resumeMs, userSeeked,
            resumeAlreadyApplied = lateResumeApplied)
        // Zero is a resolved target too: later metadata must not deduct/apply the resume again.
        lateResumeApplied = true
        if (target == null) return@LaunchedEffect
        displayedPosition = seekCoordinator.requestAbsolute(target)
        seekCoordinator.consumeCommit()?.let(controller::seekTo)
    }
    com.ambient.tv.ui.player.PlayerControlsAutoHide(
        controls, snapshot.isPlaying, panelOpen, changingItem,
    ) { controls = it }
    // Handheld live playback keeps running on Back; TV and VOD use the minimize setting.
    val handheldUi = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val backMinimizes = com.ambient.tv.ui.player.rememberBackMinimizesPlayback()
    // Gate on !panelOpen so panel BackHandler is not order-fragile.
    BackHandler(enabled = !panelOpen) {
        if (controls && !handheldUi) controls = false
        else if (com.ambient.tv.ui.player.backLeavesPlaybackRunning(false, handheldUi, backMinimizes(), isLive = playback.isLive)) onBack()
        else BackgroundPlaybackSession.stop()
    }
    // One remote handling for the engine and, while the focus is in the OSD or panel above it, the host.
    val chapterNavigation = com.ambient.tv.ui.player.LocalPlaybackNavigation.current
    val itemControls = LocalPlaybackItemControls.current
    val osdKeys: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { e ->
        // Shared remote handling (PlayerOsdKeys); only the actions below are MPV's own.
        val seekRelative: (Long) -> Unit = { seek(it) }
        val seekWithBar: (Long) -> Unit = { seekInMode(it) }
        handlePlayerOsdKey(
            e.key, e.type == KeyEventType.KeyDown,
            OsdKeyState(OsdContent.VIDEO, controls && !osd.seekFeedback && !osd.closing, panelOpen, osd.seekFeedback, closing = osd.closing),
            OsdSeekSteps(BackgroundPlaybackSession.seekJumpMs(bigSeekSeconds * 1000L), held = { heldSeekStep(it, e) }),
            object : OsdKeyTarget {
                override fun chapterSeekDelta(direction: Int) = chapterNavigation?.chapterStep(displayedPosition, direction)
                override fun isPlaying() = snapshot.isPlaying
                override fun togglePlayback() { BackgroundPlaybackSession.userToggle() }
                override fun play() { BackgroundPlaybackSession.userPlay() }
                override fun pause() { BackgroundPlaybackSession.userPause() }
                override fun stop() { BackgroundPlaybackSession.stop() }
                override fun seek(deltaMs: Long, revealOsd: Boolean) {
                    if (revealOsd) { seekRelative(deltaMs); osd.reveal() } else seekWithBar(deltaMs)
                }
                override fun showOsd() { osd.reveal() }
                override fun hideSeekFeedback() { osd.reveal() }
                override fun restart() {
                    itemControls?.previous?.invoke()
                }
                override fun next() { itemControls?.next?.invoke() }
                override fun previousTrack() = Unit
                override fun zap(delta: Int) = Unit
            },
        )
    }
    com.ambient.tv.ui.player.PublishPlayerPanelKeys(osdKeys)
    Box(Modifier.fillMaxSize().background(if (persistentVideoHost) Color.Transparent else Color.Black).onPreviewKeyEvent(osdKeys).focusRequester(com.ambient.tv.ui.player.rememberPlayerEngineFocus()).focusable()) {
        if (!persistentVideoHost) MpvVideoSurface(controller, Modifier.fillMaxSize(), LocalPlaybackMetadata.current?.sourceAspectRatioHint)
        PlayerBufferingSpinner(
            visible = waitingForPlaybackPicture(snapshot.wantsToPlay, snapshot.isPlaying),
            modifier = Modifier.align(Alignment.Center),
        )
        PlayerTouchLayer(
            onToggleControls = { if (!panelOpen) { if (!controls || osd.closing) osd.reveal() else osd.close() } },
            onTogglePlayback = { BackgroundPlaybackSession.userToggle() },
            onSeekBack = if (playback.isLive) null else {{ seek(-BackgroundPlaybackSession.seekJumpMs(bigSeekSeconds * 1000L)) }},
            onSeekForward = if (playback.isLive) null else {{ seek(BackgroundPlaybackSession.seekJumpMs(bigSeekSeconds * 1000L)) }},
            pinchZoom = if (controller.audioOnly) null else PlayerPinchZoom(
                key = controller,
                enabled = !panelOpen,
                presentation = VideoPresentation(videoResizeMode(resize), videoAspectRatio(aspect), zoom),
                sourceAspectRatio = { controller.videoAspectRatio() },
                onChanged = {
                    resize = "fit"
                    aspect = "auto"
                    zoom = it.zoom
                    controller.setVideoPresentation(resize, null, zoom)
                },
            ),
        )
        PlaybackPerformanceNotice(mpvLoadHint(controller, snapshot))
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
            refreshHz = snapshot.displayFps ?: displayRefreshHz,
            nerdStatsVisible = showNerdOverlay,
        )
        Box(Modifier.fillMaxSize().zIndex(if (panelOpen) PLAYER_SETTINGS_PANEL_Z_INDEX else 0f)) {
            YouTubeControls(
                controlsVisible = controls,
                playback, "MPV", displayedPosition, snapshot.durationMs, snapshot.isPlaying,
                waitingForPlaybackPicture(snapshot.wantsToPlay, snapshot.isPlaying),
                rendererKey, bigSeekSeconds * 1000L, BackgroundPlaybackSession::userToggle, ::seek, onBack,
                { key, global -> onRenderer(key, displayedPosition, global) },
                listOfNotNull(snapshot.videoSize, controller.sourceHdrStandard?.label, snapshot.videoCodec, snapshot.audioCodec, snapshot.decoder),
                YouTubePanelConfig(
                    hasVideo = com.ambient.tv.ui.player.mpvPanelHasVideo(snapshot.videoCodec, controller.audioOnly || snapshot.confirmedAudioOnly),
                    scalingKey = resize,
                    aspectKey = aspect,
                    zoomFactor = zoom,
                    speed = speed,
                    onScalingChanged = { resize = it; controller.setVideoPresentation(resize, aspect.takeUnless { value -> value == "auto" }, zoom) },
                    onAspectChanged = { aspect = it; controller.setVideoPresentation(resize, it.takeUnless { value -> value == "auto" }, zoom) },
                    onZoomChanged = { zoom = it; controller.setVideoPresentation(resize, aspect.takeUnless { value -> value == "auto" }, zoom) },
                    onSpeedChanged = if (playback.isLive || controller.bitstreamPermitted) null else ({ value -> speed = value; controller.setSpeed(value) }),
                    audioOptions = streams?.audioOptions?.takeIf { it.size > 1 }
                        ?: audioTracks.map { it.id.toString() to youtubeTrackLabel(it.label, displayLocale) }.ifEmpty { listOf("auto" to stringResource(R.string.player_default_track)) },
                    selectedAudio = if ((streams?.audioOptions?.size ?: 0) > 1) streams!!.selectedAudio else audioTracks.firstOrNull { it.selected }?.id?.toString() ?: "auto",
                    onAudioSelected = { key -> if ((streams?.audioOptions?.size ?: 0) > 1) streams!!.onAudio(key) else controller.selectAudio(key.toIntOrNull()) },
                    qualityOptions = streams?.qualityOptions.orEmpty(),
                    selectedQuality = streams?.selectedQuality ?: "auto",
                    onQualitySelected = streams?.onQuality,
                    subtitleOptions = listOf("off" to stringResource(R.string.player_subtitles_off)) + subtitleTracks.map { it.id.toString() to youtubeTrackLabel(it.label, displayLocale) },
                    selectedSubtitle = subtitleTracks.firstOrNull { it.selected }?.id?.toString() ?: "off",
                    onSubtitleSelected = { controller.selectSubtitle(it.toIntOrNull()) },
                    sleepTimer = sleepTimer,
                    onSleepChanged = { sleepTimer = it },
                    infoLines = buildList {
                        add(stringResource(R.string.player_nerd_backend) to "MPV")
                        snapshot.videoOutput?.let { add(stringResource(R.string.player_nerd_vo) to it) }
                        snapshot.audioOutputDriver?.let { add(stringResource(R.string.player_nerd_ao) to it) }
                        snapshot.audioOutputFormat?.let { add(stringResource(R.string.player_nerd_audio_output_format) to it) }
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
                        snapshot.activeVideoFilters?.let { add(stringResource(R.string.player_nerd_video_filters) to it) }
                        controller.loadedImageShaders().takeIf { it.isNotEmpty() }?.let { add("Shaders (loaded; conditional)" to it.joinToString()) }
                        snapshot.audioCodec?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.player_audio) to it.uppercase()) }
                        formatNerdBitrate(snapshot.audioBitrateBitsPerSecond)?.let { add(stringResource(R.string.player_nerd_audio_bitrate) to it) }
                    },
                    audioSession = controller.audioSession,
                    hasVideoExtras = videoTracks.size > 1,
                    videoExtras = {
                        if (videoTracks.size > 1) com.ambient.tv.ui.common.RightPanelSectionTitle(stringResource(R.string.settings_advanced_title))
                        if (videoTracks.size > 1) PlayerDropUpSelector(
                            stringResource(R.string.player_quality),
                            videoTracks.firstOrNull { it.selected }?.label ?: stringResource(R.string.player_automatic),
                            listOf("auto" to stringResource(R.string.player_automatic)) + videoTracks.map { it.id.toString() to it.label },
                            videoTracks.firstOrNull { it.selected }?.id?.toString() ?: "auto",
                        ) { controller.selectVideo(it.toIntOrNull()) }
                    },
                ),
                panelNavigation = panelNavigation,
            )
        }
    }
}

/** The YouTube OSD (Media3, VLC and MPV alike), drawn by PersistentPlayerOverlays above the engine. */
internal data class YouTubeOsdModel(
    val playback: YouTubePlayback,
    val backend: String,
    val position: Long,
    val duration: Long,
    val playing: Boolean,
    val loading: Boolean,
    val seekJumpMs: Long,
    val onToggle: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onBack: () -> Unit,
    val info: List<String>,
    /** "Passthrough", "→ PCM", "E-AC3 5.1 → PCM 5.1"; null for plain AAC/Opus decode. */
    val audioBadge: String? = null,
    val channel: YouTubeChannelOsd? = null,
    val itemControls: PlaybackItemControls? = null,
) : com.ambient.tv.ui.player.PlayerOsdSpec {
    @Composable
    override fun Content(visible: Boolean, panel: UnifiedPlayerPanel) {
        val seekFocusRequester = com.ambient.tv.ui.player.rememberPlayerSeekFocus()
        val osd = com.ambient.tv.ui.player.rememberPlayerOsdController()
        val playFocusRequester = com.ambient.tv.ui.player.rememberPlayerPlayFocus()
        val navigation = LocalPlayerPanelNavigation.current
        // Shared visibility hides controls while panels own the screen and Back.
        PlayerOverlayVisibility(visible = visible) {
            ReferencePlayerOverlayBackground()
            ReferencePlayerClock(
                liveLabel = if (playback.isLive) stringResource(R.string.youtube_live_badge) else null,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = deviceDp(48.dp, 16.dp)),
            )
            ReferencePlayerBottomContent(onTogglePlayback = onToggle) {
                ReferencePlayerHeader(
                    title = playback.title,
                    artworkUrl = youtubePanelThumbnailUrls(playback.videoId).firstOrNull(),
                    artworkFallbackUrls = (listOfNotNull(playback.backgroundUrl, playback.imageUrl) + youtubePanelThumbnailUrls(playback.videoId).drop(1)).distinct(),
                    contextLine = listOfNotNull(
                        com.ambient.tv.ui.common.systemName(com.ambient.tv.ui.common.MediaSystem.YOUTUBE),
                        backend,
                        stringResource(R.string.youtube_live_badge).takeIf { playback.isLive },
                    ).joinToString("  •  "),
                    subtitle = playback.author,
                )
                if (!playback.isLive && duration > 0L) {
                    TvPlayerSeekBar(
                        positionMs = position,
                        durationMs = duration,
                        onSeekBy = onSeek,
                        onInteraction = osd::interact,
                        onTogglePlayback = onToggle,
                        onPlay = { if (!playing) onToggle() },
                        modifier = Modifier.focusRequester(seekFocusRequester),
                    )
                }
                PlayerTechBadgeRow {
                    MetaBadge(com.ambient.tv.ui.common.systemName(com.ambient.tv.ui.common.MediaSystem.YOUTUBE), emphasized = true)
                    MetaBadge(backend)
                    com.ambient.tv.ui.player.PlayerStreamBadges(info)
                    audioBadge?.let { MetaBadge(it) }
                }
                PlayerControlBar(
                    player = com.ambient.tv.ui.player.OsdPlayer.YOUTUBE,
                    leftContent = {
                        itemControls?.let { actions ->
                            PlayerIconButton(Icons.Filled.SkipPrevious,
                                stringResource(R.string.player_previous_item),
                                { actions.previous?.invoke() }, enabled = actions.hasPreviousItem && actions.previous != null,
                                osd = com.ambient.tv.ui.player.OsdButton.NEXT)
                        }
                        val seekJumpSeconds = (BackgroundPlaybackSession.seekJumpMs(seekJumpMs) / 1000L).toInt().coerceAtLeast(1)
                        if (!playback.isLive) PlayerIconButton(Icons.Filled.FastRewind, stringResource(R.string.player_seek_back, seekJumpSeconds), { onSeek(-BackgroundPlaybackSession.seekJumpMs(seekJumpMs)) }, osd = com.ambient.tv.ui.player.OsdButton.SEEK)
                        PlayerIconButton(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            stringResource(if (playing) R.string.player_pause else R.string.player_play),
                            onToggle,
                            modifier = Modifier.focusRequester(playFocusRequester),
                            primary = true,
                            loading = loading,
                        )
                        PlayerIconButton(Icons.Filled.Stop, stringResource(R.string.player_stop), BackgroundPlaybackSession::stop, osd = com.ambient.tv.ui.player.OsdButton.STOP)
                        if (!playback.isLive) PlayerIconButton(Icons.Filled.FastForward, stringResource(R.string.player_seek_forward, seekJumpSeconds), { onSeek(BackgroundPlaybackSession.seekJumpMs(seekJumpMs)) }, osd = com.ambient.tv.ui.player.OsdButton.SEEK)
                        itemControls?.let { actions ->
                            PlayerIconButton(Icons.Filled.SkipNext, stringResource(R.string.player_next_item),
                                { actions.next?.invoke() }, enabled = actions.next != null,
                                osd = com.ambient.tv.ui.player.OsdButton.NEXT)
                        }
                    },
                    rightContent = {
                        PlayerExitButton(onBack)
                        channel?.let { osd ->
                            PlayerIconButton(Icons.Filled.AccountCircle, osd.channel.title ?: stringResource(R.string.youtube_channel_open), osd.onOpen, osd = com.ambient.tv.ui.player.OsdButton.CHANNEL, imageUrl = osd.channel.avatarUrl)
                            // Signed out the subscription is unknown and cannot be changed.
                            osd.channel.subscribed?.let { subscribed ->
                                PlayerIconButton(
                                    Icons.Filled.Subscriptions,
                                    stringResource(if (subscribed) R.string.youtube_subscribed else R.string.youtube_subscribe),
                                    osd.onToggleSubscription,
                                    selected = subscribed,
                                    osd = com.ambient.tv.ui.player.OsdButton.SUBSCRIBE,
                                )
                            }
                        }
                        PlayerIconButton(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.player_audio), { navigation?.open(UnifiedPlayerPanel.AUDIO) }, selected = panel.isAudio, osd = com.ambient.tv.ui.player.OsdButton.AUDIO)
                        PlayerIconButton(Icons.Filled.Subtitles, stringResource(R.string.player_subtitles), { navigation?.open(UnifiedPlayerPanel.SUBTITLES) }, selected = panel == UnifiedPlayerPanel.SUBTITLES, osd = com.ambient.tv.ui.player.OsdButton.SUBTITLES)
                        PlayerIconButton(Icons.Filled.Movie, stringResource(R.string.player_video), { navigation?.open(UnifiedPlayerPanel.VIDEO) }, selected = panel.isVideo, osd = com.ambient.tv.ui.player.OsdButton.VIDEO)
                        PlayerSleepTimerButton({ navigation?.open(UnifiedPlayerPanel.SLEEP) }, selected = panel == UnifiedPlayerPanel.SLEEP)
                        com.ambient.tv.ui.common.PlayerDiagnosticsButtons()
                        com.ambient.tv.ui.common.PlayerToolButtons()
                        PlayerIconButton(Icons.Filled.Settings, stringResource(R.string.player_playback_settings), { navigation?.open(UnifiedPlayerPanel.SETTINGS) }, selected = panel != UnifiedPlayerPanel.NONE)
                    },
                )
            }
        }
    }
}

@Composable
private fun YouTubeControls(
    controlsVisible: Boolean,
    playback: YouTubePlayback,
    backend: String,
    position: Long,
    duration: Long,
    playing: Boolean,
    loading: Boolean,
    rendererKey: String,
    seekJumpMs: Long,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onRenderer: (String, Boolean) -> Unit,
    info: List<String>,
    panelConfig: YouTubePanelConfig,
    panelNavigation: com.ambient.tv.ui.common.PlayerPanelNavigation,
) {
    val primaryFocusRequester = com.ambient.tv.ui.player.rememberPlayerPlayFocus()
    val panel by panelNavigation
    RequestPlayerOverlayFocus(
        requester = primaryFocusRequester,
        enabled = controlsVisible && panel == UnifiedPlayerPanel.NONE,
        playbackStarted = playing,
        panelNavigation = panelNavigation,
    )
    CompositionLocalProvider(LocalPlayerPanelNavigation provides panelNavigation) {
    // Drawn by PersistentPlayerOverlays above the engine, so both survive a renderer switch.
    com.ambient.tv.ui.player.PublishPlayerOsd(YouTubeOsdModel(
        playback = playback,
        backend = backend,
        position = position,
        duration = duration,
        playing = playing,
        loading = loading,
        seekJumpMs = seekJumpMs,
        onToggle = onToggle,
        onSeek = onSeek,
        onBack = onBack,
        info = info,
        audioBadge = com.ambient.tv.ui.player.audioOutputBadge(panelConfig.audioSession),
        channel = LocalYouTubeChannelOsd.current,
        itemControls = LocalPlaybackItemControls.current,
    ), visible = controlsVisible)
    com.ambient.tv.ui.player.PublishPlayerPanel(com.ambient.tv.ui.player.PlayerPanelModel(
        scrimAlpha = 0.32f,
        projectMBrowser = com.ambient.tv.ui.common.projectm.LocalProjectMBrowser.current,
        audioSession = panelConfig.audioSession,
        hasVideo = panelConfig.hasVideo,
        isLive = playback.isLive,
        scalingKey = panelConfig.scalingKey,
        aspectKey = panelConfig.aspectKey,
        zoomFactor = panelConfig.zoomFactor,
        speed = panelConfig.speed,
        onScalingChanged = panelConfig.onScalingChanged,
        onAspectChanged = panelConfig.onAspectChanged,
        onZoomChanged = panelConfig.onZoomChanged,
        onSpeedChanged = panelConfig.onSpeedChanged,
        audioOptions = panelConfig.audioOptions,
        selectedAudio = panelConfig.selectedAudio,
        onAudioSelected = panelConfig.onAudioSelected,
        subtitleOptions = panelConfig.subtitleOptions,
        selectedSubtitle = panelConfig.selectedSubtitle,
        onSubtitleSelected = panelConfig.onSubtitleSelected,
        sleepTimer = panelConfig.sleepTimer,
        onSleepChanged = panelConfig.onSleepChanged,
        qualityOptions = panelConfig.qualityOptions,
        selectedQuality = panelConfig.selectedQuality,
        onQualitySelected = panelConfig.onQualitySelected,
        rendererKey = rendererKey,
        onRendererSelected = onRenderer,
        infoLines = panelConfig.infoLines,
        // The heading comes with the quality choice itself: with one stream it stayed empty.
        hasVideoExtras = panelConfig.hasVideoExtras,
        videoExtras = panelConfig.videoExtras,
    ))
    }
}

@Composable
private fun YouTubeSeekCommit(
    generation: Int,
    coordinator: PlaybackSeekCoordinator,
    commit: (Long) -> Unit,
) {
    val commitState = rememberUpdatedState(commit)
    LaunchedEffect(generation) {
        if (generation == 0) return@LaunchedEffect
        delay(coordinator.debounceMs)
        coordinator.consumeCommit()?.let { commitState.value(it) }
    }
}

private fun ytTrackChoices(context: android.content.Context, tracks: Tracks, type: Int): List<YtTrackChoice> = buildList {
    val displayLocale = context.resources.configuration.locales[0]
    tracks.groups.forEachIndexed { gi, group ->
        if (group.type != type) return@forEachIndexed
        repeat(group.length) { ti ->
            val f = group.getTrackFormat(ti)
            // Like SmartTube, list SDR video streams a decoder reports as too large or too fast too
            // (many TV chips under-report 4K/60 fps); they are marked and only played on request.
            val handled = group.isTrackSupported(ti)
            val exceeds = type == C.TRACK_TYPE_VIDEO && group.getTrackSupport(ti) == C.FORMAT_EXCEEDS_CAPABILITIES &&
                com.ambient.tv.youtube.youtubeHdrKind(f.codecs) == null
            if (!handled && !exceeds) return@repeat
            val label = when (type) {
                C.TRACK_TYPE_VIDEO -> {
                    val family = com.ambient.tv.youtube.youtubeVideoFamily(f.codecs ?: f.sampleMimeType)
                    if (f.height > 0 && family != null) com.ambient.tv.youtube.YouTubeVideoOption("", f.height, f.frameRate.toInt(), family,
                        com.ambient.tv.youtube.youtubeHdrKind(f.codecs), hardware = handled).label
                    else listOfNotNull(if (f.height > 0) "${f.height}p" else null, f.codecs ?: f.sampleMimeType?.substringAfter('/'), if (f.frameRate > 0) "${f.frameRate.toInt()}fps" else null).joinToString(" · ").ifBlank { context.getString(R.string.vlc_video_track_fallback, ti + 1) }
                }
                C.TRACK_TYPE_AUDIO -> listOfNotNull(f.language, f.label, f.codecs ?: f.sampleMimeType?.substringAfter('/'), if (f.channelCount > 0) "${f.channelCount}ch" else null).joinToString(" · ").let { youtubeTrackLabel(it, displayLocale) }.ifBlank { context.getString(R.string.vlc_audio_track_fallback, ti + 1) }
                else -> listOfNotNull(f.language, f.label).joinToString(" · ").let { youtubeTrackLabel(it, displayLocale) }.ifBlank { context.getString(R.string.vlc_subtitle_track_fallback, ti + 1) }
            }
            add(
                YtTrackChoice(
                    key = "$gi:$ti",
                    label = label,
                    language = f.language,
                    forced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                    group = group,
                    index = ti,
                ),
            )
        }
    }
}

/** One audio language among Media3's tracks: the DASH adaptation sets that carry it (MP4 and WebM). */
private data class YtAudioLanguage(val option: com.ambient.tv.youtube.YouTubeAudioOption, val groups: List<Tracks.Group>)

/** Media3's audio groups by language, original first; SmartTube's MPD writes one adaptation set per language and container. */
private fun ytAudioLanguages(tracks: Tracks): List<YtAudioLanguage> {
    val languages = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        .groupBy { it.getTrackFormat(0).language?.lowercase(java.util.Locale.ROOT).orEmpty() }
        .map { (raw, groups) ->
            val format = groups.first().getTrackFormat(0)
            val parsed = com.ambient.tv.youtube.youtubeParseAudioTrack(raw)
            YtAudioLanguage(
                com.ambient.tv.youtube.YouTubeAudioOption(
                    key = "m:$raw",
                    language = parsed?.first ?: raw.takeIf(String::isNotBlank),
                    original = parsed?.second == "original" || isYouTubeOriginalAudioTrack(format.language, format.label),
                    kind = parsed?.second,
                ),
                groups,
            )
        }
    val order = com.ambient.tv.youtube.youtubeSortAudioOptions(languages.map { it.option })
    return languages.sortedBy { order.indexOf(it.option) }
}

/** Plays [language], adaptive within its group; the group already playing keeps its codec. */
private fun applyYtAudioLanguage(player: ExoPlayer, language: YtAudioLanguage) {
    val group = language.groups.firstOrNull { it.isSelected } ?: language.groups.first()
    val indices = (0 until group.length).filter(group::isTrackSupported)
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, indices))
        .build()
}

/**
 * Starts in the original language: YouTube can mark an auto-dub as the manifest default. Without
 * an original marker Media3's manifest selection stays untouched.
 */
private fun applyYouTubeDefaultAudioTrack(player: ExoPlayer, tracks: Tracks) {
    val languages = ytAudioLanguages(tracks)
    if (languages.size < 2) return
    languages.firstOrNull { it.option.original }?.let { applyYtAudioLanguage(player, it) }
}

private fun currentTrackLabel(options: List<YtTrackChoice>, fallback: String): String = options.firstOrNull { it.group.isTrackSelected(it.index) }?.label ?: fallback
private fun applyTrackChoice(player: ExoPlayer, options: List<YtTrackChoice>, key: String, type: Int) {
    val builder = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
    if (key == "auto") {
        builder.clearOverridesOfType(type)
    } else {
        options.firstOrNull { it.key == key }?.let { choice -> builder.setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, listOf(choice.index))) }
    }
    player.trackSelectionParameters = builder.build()
}
private fun time(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)

/** Media3 sample MIME type of a YouTube codec family. */
private fun youtubeFamilyMimeType(family: String): String? = when (family) {
    "avc" -> androidx.media3.common.MimeTypes.VIDEO_H264
    "vp9" -> androidx.media3.common.MimeTypes.VIDEO_VP9
    "av1" -> androidx.media3.common.MimeTypes.VIDEO_AV1
    else -> null
}

/** SmartTube's player options "Network engine" and "Buffer size", as in its own player settings. */
@Composable
private fun YouTubeStreamTuningControls(
    tuning: com.ambient.tv.ui.player.Media3StreamTuning,
    onChange: (com.ambient.tv.ui.player.Media3StreamTuning) -> Unit,
) {
    val engines = com.ambient.tv.ui.player.MEDIA3_NETWORK_ENGINES.map { engine ->
        engine to stringResource(when (engine) {
            com.ambient.tv.ui.player.MEDIA3_NETWORK_CRONET -> R.string.settings_youtube_network_engine_play
            com.ambient.tv.ui.player.MEDIA3_NETWORK_DEFAULT -> R.string.settings_youtube_network_engine_default
            else -> R.string.settings_youtube_network_engine_classic
        })
    }
    val buffers = com.ambient.tv.ui.player.MEDIA3_BUFFER_KEYS.map { key ->
        key to stringResource(when (key) {
            "low" -> R.string.settings_youtube_buffer_low
            "high" -> R.string.settings_youtube_buffer_high
            else -> R.string.settings_youtube_buffer_medium
        })
    }
    com.ambient.tv.ui.common.RightPanelSectionTitle(stringResource(R.string.player_youtube_stream))
    PlayerDropUpSelector(
        stringResource(R.string.settings_youtube_network_engine),
        engines.firstOrNull { it.first == tuning.networkEngine }?.second ?: engines.first().second,
        engines,
        tuning.networkEngine,
    ) { key -> if (key != tuning.networkEngine) onChange(tuning.copy(networkEngine = key)) }
    PlayerDropUpSelector(
        stringResource(R.string.settings_youtube_buffer),
        buffers.firstOrNull { it.first == tuning.bufferKey }?.second ?: buffers[1].second,
        buffers,
        tuning.bufferKey,
    ) { key -> if (key != tuning.bufferKey) onChange(tuning.copy(bufferKey = key)) }
    Text(stringResource(R.string.player_youtube_stream_hint), style = MaterialTheme.typography.bodySmall)
}
