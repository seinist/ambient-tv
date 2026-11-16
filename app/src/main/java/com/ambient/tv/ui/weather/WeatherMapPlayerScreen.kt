package com.ambient.tv.ui.weather

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherMapError
import com.ambient.tv.data.weather.WeatherMapImage
import com.ambient.tv.data.weather.WeatherMapFrame
import com.ambient.tv.data.weather.WeatherMapKind
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherMapSeries
import com.ambient.tv.data.weather.WeatherMapViewport
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerExitButton
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.RightPanelChoice
import com.ambient.tv.ui.common.RightPanelOverlay
import com.ambient.tv.ui.common.RightSidePanel
import com.ambient.tv.ui.player.LocalPlayerChromeState
import com.ambient.tv.ui.player.LocalPlayerControlFocus
import com.ambient.tv.ui.player.PlayerChromeState
import com.ambient.tv.ui.player.PlayerControlsAutoHide
import com.ambient.tv.ui.player.PlayerOverlayVisibility
import com.ambient.tv.ui.player.PlayerTechBadgeRow
import com.ambient.tv.ui.player.PlayerTimelineSlot
import com.ambient.tv.ui.player.ReferencePlayerBottomContent
import com.ambient.tv.ui.player.ReferencePlayerClock
import com.ambient.tv.ui.player.ReferencePlayerHeader
import com.ambient.tv.ui.player.ReferencePlayerOverlayBackground
import com.ambient.tv.ui.player.TvPlayerSeekBar
import com.ambient.tv.ui.player.rememberPlayerOsdController
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlinx.coroutines.delay
import androidx.compose.runtime.withFrameNanos
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private data class WeatherMapPreview(val image: WeatherMapImage, val locationId: String)

/** Fullscreen weather playback with the shared player OSD, but no video/audio settings or playback service. */
@Composable
fun WeatherMapPlayerScreen(
    kind: WeatherMapKind,
    initialProviderId: String,
    initialZoom: Int,
    initialFrameEpochMillis: Long,
    onBack: () -> Unit,
    model: WeatherMapPlayerViewModel = hiltViewModel(),
) {
    val settings by model.settings.collectAsState()
    val state by model.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val contentVisible = LocalWeatherPresentation.current.contentVisible
    val providers = settings.selectedMapProviders(kind)
    val location = settings.selectedLocation
    var providerId by rememberSaveable(kind.name) { mutableStateOf(initialProviderId) }
    var zoom by rememberSaveable(kind.name) {
        mutableIntStateOf(initialZoom.coerceIn(WeatherMapViewport.MIN_ZOOM, WeatherMapViewport.MAX_ZOOM))
    }
    var frameEpoch by rememberSaveable(kind.name) { mutableLongStateOf(initialFrameEpochMillis) }
    var refreshSerial by remember { mutableIntStateOf(0) }
    // Keep the last good map visible only while replacing its zoom/source, rather than
    // flashing the full-screen loading background before the next image arrives.
    var mapPreview by remember { mutableStateOf<WeatherMapPreview?>(null) }
    var playIntent by rememberSaveable(kind.name) { mutableStateOf(true) }
    // The Media3 playlist changes on zoom/source changes; the shared chrome belongs to the
    // fullscreen route and must survive those temporary loading states.
    val repeatState = rememberSaveable(kind.name, "repeat") { mutableStateOf(true) }
    var playerPanel by rememberSaveable(kind.name, "weather-panel") { mutableStateOf<String?>(null) }
    var focusTarget by rememberSaveable(kind.name, "focus") { mutableStateOf("play") }
    val chrome = remember(kind) { PlayerChromeState() }
    LaunchedEffect(chrome) { chrome.osd.expand() }
    val provider = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull()
    val viewport = remember(location, zoom) {
        location?.let { WeatherMapViewport.at(it.latitude, it.longitude, zoom) }
    }
    val request = remember(location, provider, viewport, settings.enabled) {
        if (settings.enabled && location != null && provider != null && viewport != null)
            WeatherPlayerRequest(location, provider, viewport, networkAllowed = true) else null
    }
    val result = state.result.takeIf { state.request == request }
    val loading = request != null && (state.request != request || state.loading)
    if (result?.image != null && location != null) SideEffect {
        mapPreview = WeatherMapPreview(result.image, location.id)
    }

    BackHandler { onBack() }
    DisposableEffect(model, request, lifecycle, contentVisible, refreshSerial) {
        fun update() {
            if (contentVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && request != null) {
                model.present(request, refreshSerial)
            } else model.pauseRequests()
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); model.pauseRequests() }
    }
    // Map manifests can change during a long fullscreen session. Refresh the currently
    // visible source periodically, but never start polling when hidden or paused.
    LaunchedEffect(model, request, contentVisible, lifecycle, refreshSerial) {
        if (contentVisible && request != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(weatherPlayerRefreshIntervalMillis(request.provider))
                model.present(request, refreshSerial)
            }
        }
    }
    val surface = Color(0xff09131c)
    CompositionLocalProvider(
        LocalPlayerChromeState provides chrome,
        LocalPlayerControlFocus provides chrome.focus,
    ) {
    Box(Modifier.fillMaxSize().background(surface)) {
        when {
            !settings.enabled -> StatePanel(stringResource(R.string.weather_disabled),
                body = stringResource(R.string.weather_disabled_hint), actionLabel = stringResource(R.string.back),
                onAction = onBack, modifier = Modifier.align(Alignment.Center))
            location == null -> StatePanel(stringResource(R.string.weather_choose_location),
                actionLabel = stringResource(R.string.back), onAction = onBack,
                modifier = Modifier.align(Alignment.Center))
            provider == null -> StatePanel(stringResource(R.string.weather_maps_choose_provider),
                body = stringResource(R.string.weather_maps_choose_hint), actionLabel = stringResource(R.string.back),
                onAction = onBack, modifier = Modifier.align(Alignment.Center))
            request != null && result?.series?.frames?.isNotEmpty() == true && result.image != null && contentVisible ->
                WeatherMedia3MapContent(
                    request = request,
                    series = result.series,
                    providers = providers,
                    initialFrameEpoch = frameEpoch,
                    onFrameChanged = { frameEpoch = it },
                    onChangeProvider = { providerId = it; playerPanel = null },
                    onZoom = { newZoom, epoch -> frameEpoch = epoch; zoom = newZoom },
                    onRetry = { refreshSerial++ },
                    startPlaying = playIntent,
                    onPlayIntentChanged = { playIntent = it },
                    repeatState = repeatState,
                    panel = playerPanel,
                    onPanelChanged = { playerPanel = it },
                    focusTarget = focusTarget,
                    onFocusTargetChanged = { focusTarget = it },
                    onBack = onBack,
                    repository = model.mapRepository,
                    initialCached = result.image.fromCache,
                    sourceError = result.error,
                    baseMissing = result.image.baseMissing,
                    coverageMissing = result.image.coverageMissing,
                )
            else -> {
                if (loading && contentVisible && location != null && mapPreview?.locationId == location.id) {
                    mapPreview?.image?.let { previous ->
                        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(minOf(maxWidth, maxHeight))) {
                                Image(previous.bitmap.asImageBitmap(), contentDescription = null,
                                    modifier = Modifier.fillMaxSize())
                                Text("© OpenStreetMap contributors",
                                    color = Color.White, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.align(Alignment.BottomEnd)
                                        .background(Color.Black.copy(alpha = .65f)).padding(4.dp))
                            }
                        }
                    }
                }
                Column(Modifier.align(Alignment.Center)
                    .background(surface.copy(alpha = .90f)).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(provider.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(stringResource(if (loading) R.string.weather_maps_loading
                        else weatherPlayerErrorMessage(result?.error)),
                        color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvChip(stringResource(R.string.back), onClick = onBack)
                        if (!loading) TvChip(stringResource(R.string.weather_maps_retry),
                            onClick = { refreshSerial++ })
                    }
                }
            }
        }
    }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun WeatherMedia3MapContent(
    request: WeatherPlayerRequest,
    series: WeatherMapSeries,
    providers: List<WeatherMapProvider>,
    initialFrameEpoch: Long,
    onFrameChanged: (Long) -> Unit,
    onChangeProvider: (String) -> Unit,
    onZoom: (Int, Long) -> Unit,
    onRetry: () -> Unit,
    startPlaying: Boolean,
    onPlayIntentChanged: (Boolean) -> Unit,
    repeatState: MutableState<Boolean>,
    panel: String?,
    onPanelChanged: (String?) -> Unit,
    focusTarget: String,
    onFocusTargetChanged: (String) -> Unit,
    onBack: () -> Unit,
    repository: com.ambient.tv.data.weather.WeatherMapRepository,
    initialCached: Boolean,
    sourceError: WeatherMapError?,
    baseMissing: Boolean,
    coverageMissing: Boolean,
) {
    val context = LocalContext.current
    val handheld = LocalHandheldUi.current
    val provider = request.provider
    val viewport = request.viewport
    val frames = series.frames
    val selected = remember(request, frames) { weatherPlayerFrameIndex(frames, initialFrameEpoch) }
    // A manifest poll may update fetchedAt without changing any frames or coverage. Keep the
    // existing Media3 player in that case instead of visibly restarting the same animation.
    val playlist = remember(request, frames, series.bounds) {
        WeatherMapPlaylist(context, repository, provider, viewport, series, request.networkAllowed)
    }
    val player = remember(playlist) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playlist.dataSourceFactory))
            .build()
    }
    val frameStatuses by playlist.frameStatuses.collectAsState()
    var currentIndex by remember(playlist) { mutableIntStateOf(selected) }
    var wantsPlayback by remember(playlist) { mutableStateOf(startPlaying) }
    var buffering by remember(playlist) { mutableStateOf(true) }
    var ended by remember(playlist) { mutableStateOf(false) }
    var failed by remember(playlist) { mutableStateOf(false) }
    var loop by repeatState
    val osd = rememberPlayerOsdController()
    val panelOpen = panel != null
    val surfaceRequester = remember { FocusRequester() }
    val playRequester = remember { FocusRequester() }
    val zoomOutRequester = remember { FocusRequester() }
    val zoomInRequester = remember { FocusRequester() }
    val sourceRequester = remember { FocusRequester() }
    val infoRequester = remember { FocusRequester() }
    val latestOnFrameChanged by rememberUpdatedState(onFrameChanged)
    val latestOnPlayIntentChanged by rememberUpdatedState(onPlayIntentChanged)

    DisposableEffect(player, playlist) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = player.currentMediaItemIndex.coerceIn(0, frames.lastIndex)
                currentIndex = index
                latestOnFrameChanged(frames[index].epochMillis)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!ended) wantsPlayback = playWhenReady
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                ended = playbackState == Player.STATE_ENDED
                if (ended) {
                    wantsPlayback = false
                    latestOnPlayIntentChanged(false)
                }
                if (playbackState == Player.STATE_READY) failed = false
            }
            override fun onPlayerError(error: PlaybackException) {
                failed = true
                buffering = false
            }
        }
        player.addListener(listener)
        player.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player.setMediaItems(playlist.mediaItems(), selected, 0L)
        player.prepare()
        player.playWhenReady = startPlaying
        onDispose {
            playlist.close()
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(loop, player) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    val currentFrame = frames[currentIndex.coerceIn(0, frames.lastIndex)]
    val location = request.location
    val zone = remember(location.timezone) {
        runCatching { ZoneId.of(location.timezone) }.getOrDefault(ZoneOffset.UTC)
    }
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val frameLabel = weatherPlayerFrameTime(currentFrame, provider, zone, locale)
    val firstLabel = weatherPlayerShortTime(frames.first(), provider, zone, locale)
    val currentShortLabel = weatherPlayerShortTime(currentFrame, provider, zone, locale)
    val lastLabel = weatherPlayerShortTime(frames.last(), provider, zone, locale)
    val stale = System.currentTimeMillis() - currentFrame.epochMillis > provider.freshnessMillis
    val currentStatus = frameStatuses[currentIndex]
    val frameBaseMissing = currentStatus?.baseMissing ?: (currentIndex == selected && baseMissing)
    val frameCoverageMissing = currentStatus?.coverageMissing ?: (currentIndex == selected && coverageMissing)
    val mapType = stringResource(if (provider.kind == WeatherMapKind.RADAR)
        R.string.weather_radar else R.string.weather_satellite)
    val timelineStart = frames.first().epochMillis
    val timelineDuration = (frames.last().epochMillis - timelineStart).coerceAtLeast(0L)
    val paused = !wantsPlayback || ended || failed

    val togglePlayback: () -> Unit = {
        osd.interact()
        if (wantsPlayback && !ended && !failed) {
            wantsPlayback = false
            onPlayIntentChanged(false)
            player.pause()
        } else {
            if (ended) {
                player.seekTo(0, 0L)
                player.prepare()
            } else if (failed) {
                player.prepare()
            }
            ended = false
            failed = false
            wantsPlayback = true
            onPlayIntentChanged(true)
            player.play()
        }
    }
    val retryPlayback: () -> Unit = {
        osd.interact()
        if (failed) {
            failed = false
            ended = false
            player.prepare()
            player.playWhenReady = wantsPlayback
            buffering = player.playbackState == Player.STATE_BUFFERING
        }
        onRetry()
    }
    // Same OSD timeout and compact/expanded navigation as the video players. Weather has
    // no audio, subtitle, renderer, playback-settings or persistent mini-player panels.
    PlayerControlsAutoHide(
        visible = osd.visible.value,
        playing = wantsPlayback && !failed && !ended,
        panelOpen = panelOpen,
        onVisibilityChanged = { osd.visible.value = it },
    )
    LaunchedEffect(playlist, panel, osd.mode) {
        if (panel == null) {
            withFrameNanos { }
            runCatching {
                if (!osd.expanded) surfaceRequester.requestFocus()
                else when (focusTarget) {
                    "zoom-out" -> if (viewport.zoom > WeatherMapViewport.MIN_ZOOM)
                        zoomOutRequester.requestFocus() else playRequester.requestFocus()
                    "zoom-in" -> if (viewport.zoom < WeatherMapViewport.MAX_ZOOM)
                        zoomInRequester.requestFocus() else playRequester.requestFocus()
                    "provider" -> if (providers.size > 1)
                        sourceRequester.requestFocus() else playRequester.requestFocus()
                    "info" -> infoRequester.requestFocus()
                    else -> playRequester.requestFocus()
                }
            }
        }
    }
    BackHandler(enabled = !panelOpen) {
        if (osd.visible.value) osd.close() else onBack()
    }

    Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || panelOpen ||
            event.key == Key.Back || event.key == Key.Escape) return@onPreviewKeyEvent false
        if (!osd.show) {
            osd.expand()
            true
        } else {
            osd.interact()
            false
        }
    }) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val mapSide = minOf(maxWidth, maxHeight)
            val pinchZoom = if (handheld) Modifier.pointerInput(viewport.zoom, currentFrame.epochMillis) {
                var cumulative = 1f
                detectTransformGestures { _, _, change, _ ->
                    cumulative *= change
                    if (cumulative > 1.20f && viewport.zoom < WeatherMapViewport.MAX_ZOOM) {
                        onZoom(viewport.zoom + 1, currentFrame.epochMillis)
                        cumulative = 1f
                    } else if (cumulative < .80f && viewport.zoom > WeatherMapViewport.MIN_ZOOM) {
                        onZoom(viewport.zoom - 1, currentFrame.epochMillis)
                        cumulative = 1f
                    }
                }
            } else Modifier
            Box(Modifier.size(mapSide)
                .then(pinchZoom)
                .focusRequester(surfaceRequester).focusable()
                .clickable {
                    if (osd.show) osd.close() else osd.expand()
                }) {
                AndroidView(factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        setImageDisplayMode(PlayerView.IMAGE_DISPLAY_MODE_FIT)
                        isFocusable = false
                        this.player = player
                    }
                }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
                viewport.marker(location.latitude, location.longitude)?.let { marker ->
                    Canvas(Modifier.fillMaxSize()) {
                        val point = Offset(marker.first * size.width, marker.second * size.height)
                        drawCircle(Color.Black.copy(alpha = .85f), 8.dp.toPx(), point)
                        drawCircle(Color.White, 6.dp.toPx(), point, style = Stroke(2.dp.toPx()))
                        drawCircle(Color(0xffE54848), 3.dp.toPx(), point)
                    }
                }
                Text("© OpenStreetMap contributors",
                    color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .background(Color.Black.copy(alpha = .65f)).padding(4.dp))
            }
        }

        PlayerOverlayVisibility(visible = osd.visible.value && !panelOpen) {
            ReferencePlayerOverlayBackground()
            ReferencePlayerClock(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 48.dp),
            )
            ReferencePlayerBottomContent(onTogglePlayback = togglePlayback) {
                ReferencePlayerHeader(
                    title = mapType + " · " + provider.title,
                    artworkUrl = null,
                    contextLine = location.name,
                    subtitle = frameLabel,
                    showPaused = paused,
                )
                PlayerTimelineSlot {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(firstLabel, modifier = Modifier.weight(1f), color = Color.White,
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(currentShortLabel, modifier = Modifier.weight(1f), color = Color.White,
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Text(lastLabel, modifier = Modifier.weight(1f), color = Color.White,
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                        TvPlayerSeekBar(
                            positionMs = (currentFrame.epochMillis - timelineStart).coerceIn(0L, timelineDuration),
                            durationMs = timelineDuration,
                            interactive = frames.size > 1 && timelineDuration > 0L,
                            onSeekBy = { delta ->
                                player.seekTo(weatherPlayerSeekTargetIndex(frames, currentIndex, delta), 0L)
                            },
                            onInteraction = osd::interact,
                            onTogglePlayback = togglePlayback,
                            onPlay = { if (paused) togglePlayback() },
                            paused = paused,
                            showEndTime = false,
                            timelineContext = { relativeMs ->
                                val frame = frames[weatherPlayerFrameIndex(frames, timelineStart + relativeMs)]
                                Text(weatherPlayerFrameTime(frame, provider, zone, locale),
                                    color = Color.White.copy(alpha = .82f),
                                    style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
                PlayerTechBadgeRow {
                    MetaBadge("Media3", emphasized = true)
                    MetaBadge(provider.title)
                    MetaBadge(stringResource(R.string.weather_player_frame_counter, currentIndex + 1, frames.size))
                    MetaBadge(stringResource(R.string.weather_player_zoom, viewport.zoom))
                }
                if (failed || sourceError != null) {
                    Text(stringResource(if (failed) R.string.weather_player_frame_error
                        else weatherPlayerErrorMessage(sourceError)),
                        color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                PlayerControlBar(
                    leftContent = {
                        PlayerIconButton(Icons.Filled.SkipPrevious,
                            stringResource(R.string.weather_maps_previous), {
                                osd.interact()
                                player.seekTo(if (currentIndex > 0) currentIndex - 1
                                    else if (loop) frames.lastIndex else 0, 0L)
                            }, enabled = frames.size > 1)
                        PlayerIconButton(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            stringResource(if (paused) R.string.weather_maps_play else R.string.weather_maps_pause),
                            togglePlayback,
                            modifier = Modifier.focusRequester(playRequester),
                            primary = true,
                            loading = buffering && wantsPlayback,
                        )
                        PlayerIconButton(Icons.Filled.SkipNext,
                            stringResource(R.string.weather_maps_next), {
                                osd.interact()
                                player.seekTo(if (currentIndex < frames.lastIndex) currentIndex + 1
                                    else if (loop) 0 else frames.lastIndex, 0L)
                            }, enabled = frames.size > 1)
                    },
                    rightContent = {
                        PlayerIconButton(Icons.Filled.ZoomOut,
                            stringResource(R.string.weather_maps_zoom_out), {
                                osd.interact()
                                onFocusTargetChanged("zoom-out")
                                onZoom((viewport.zoom - 1).coerceAtLeast(WeatherMapViewport.MIN_ZOOM), currentFrame.epochMillis)
                            }, modifier = Modifier.focusRequester(zoomOutRequester),
                            enabled = viewport.zoom > WeatherMapViewport.MIN_ZOOM)
                        PlayerIconButton(Icons.Filled.ZoomIn,
                            stringResource(R.string.weather_maps_zoom_in), {
                                osd.interact()
                                onFocusTargetChanged("zoom-in")
                                onZoom((viewport.zoom + 1).coerceAtMost(WeatherMapViewport.MAX_ZOOM), currentFrame.epochMillis)
                            }, modifier = Modifier.focusRequester(zoomInRequester),
                            enabled = viewport.zoom < WeatherMapViewport.MAX_ZOOM)
                        if (providers.size > 1) PlayerIconButton(Icons.Filled.Layers,
                            stringResource(R.string.weather_maps_choose_provider), {
                                osd.interact()
                                onFocusTargetChanged("provider")
                                onPanelChanged("provider")
                            }, modifier = Modifier.focusRequester(sourceRequester))
                        PlayerIconButton(Icons.Filled.Repeat,
                            stringResource(R.string.weather_player_repeat), {
                                osd.interact()
                                loop = !loop
                            }, selected = loop)
                        PlayerIconButton(Icons.Filled.Info,
                            stringResource(R.string.weather_player_info), {
                                osd.interact()
                                onFocusTargetChanged("info")
                                onPanelChanged("info")
                            }, modifier = Modifier.focusRequester(infoRequester))
                        if (failed || sourceError != null) PlayerIconButton(Icons.Filled.Refresh,
                            stringResource(R.string.weather_maps_retry), retryPlayback)
                        PlayerExitButton(onBack)
                    },
                )
            }
        }

        if (panelOpen) RightPanelOverlay(onOutsideTap = { onPanelChanged(null) }) {
            RightSidePanel(
                title = if (panel == "provider") stringResource(R.string.weather_maps_choose_provider)
                    else stringResource(R.string.weather_player_info),
                onBack = { onPanelChanged(null) },
                closeInsteadOfBack = true,
            ) {
                if (panel == "provider") {
                    providers.forEach { choice ->
                        RightPanelChoice(
                            text = choice.title,
                            selected = choice == provider,
                            onClick = {
                                onFocusTargetChanged("provider")
                                onChangeProvider(choice.id)
                                onPanelChanged(null)
                            },
                        )
                    }
                } else {
                    Text(location.name + " · " + frameLabel, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.weather_player_product,
                        when (provider) {
                            WeatherMapProvider.RAINVIEWER, WeatherMapProvider.DWD ->
                                stringResource(R.string.weather_player_radar_product)
                            WeatherMapProvider.NASA -> stringResource(R.string.weather_player_nasa_product)
                            WeatherMapProvider.EUMETSAT -> stringResource(R.string.weather_player_eumetsat_product)
                        }), color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.weather_player_frame_counter,
                        currentIndex + 1, frames.size), color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.weather_player_zoom, viewport.zoom), color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.weather_maps_width,
                        viewport.widthKilometres.roundToInt()), color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.weather_player_manifest,
                        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm z", locale).withZone(zone)
                            .format(Instant.ofEpochMilli(series.fetchedAtMillis))), color = MaterialTheme.colorScheme.onSurface)
                    if ((initialCached && currentIndex == selected) || stale || sourceError != null) {
                        Text(stringResource(R.string.weather_maps_stored), color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (frameBaseMissing) Text(stringResource(R.string.weather_maps_base_missing), color = MaterialTheme.colorScheme.onSurface)
                    if (frameCoverageMissing) Text(stringResource(R.string.weather_maps_coverage_missing), color = MaterialTheme.colorScheme.onSurface)
                    if (provider.kind == WeatherMapKind.RADAR) {
                        Box(Modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                            WeatherMapLegend(provider)
                        }
                    }
                    WeatherSourceLink(provider.attribution, provider.sourceUrl)
                }
            }
        }
    }
}

private fun weatherPlayerFrameTime(frame: WeatherMapFrame, provider: WeatherMapProvider,
    zone: ZoneId, locale: Locale): String =
    if (provider.daily) frame.time
    else DateTimeFormatter.ofPattern("dd.MM. HH:mm z", locale).withZone(zone)
        .format(Instant.ofEpochMilli(frame.epochMillis))

private fun weatherPlayerShortTime(frame: WeatherMapFrame, provider: WeatherMapProvider,
    zone: ZoneId, locale: Locale): String =
    if (provider.daily) frame.time
    else DateTimeFormatter.ofPattern("HH:mm", locale).withZone(zone)
        .format(Instant.ofEpochMilli(frame.epochMillis))

private fun weatherPlayerErrorMessage(error: WeatherMapError?): Int = when (error) {
    WeatherMapError.NETWORK_DISABLED -> R.string.weather_maps_network_disabled
    WeatherMapError.OFFLINE -> R.string.weather_maps_offline
    WeatherMapError.UNAVAILABLE -> R.string.weather_maps_unavailable
    WeatherMapError.RATE_LIMITED -> R.string.weather_maps_rate_limited
    WeatherMapError.ACCESS_DENIED -> R.string.weather_maps_access_denied
    WeatherMapError.NO_COVERAGE -> R.string.weather_maps_no_coverage
    null -> R.string.weather_maps_loading
}
