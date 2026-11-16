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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import com.ambient.tv.data.weather.WeatherMapFrame
import com.ambient.tv.data.weather.WeatherMapKind
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherMapSeries
import com.ambient.tv.data.weather.WeatherMapViewport
import com.ambient.tv.ui.common.StatePanel
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

private data class WeatherMapPreview(val image: com.ambient.tv.data.weather.WeatherMapImage, val locationId: String)

/** Fullscreen weather-only player. No shared video settings, tracks, service, history or OSD panels. */
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
                    onChangeProvider = { providerId = it },
                    onZoom = { newZoom, epoch -> frameEpoch = epoch; zoom = newZoom },
                    onRetry = { refreshSerial++ },
                    startPlaying = playIntent,
                    onPlayIntentChanged = { playIntent = it },
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

@OptIn(ExperimentalLayoutApi::class, UnstableApi::class)
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
    val selected = remember(request, series) { weatherPlayerFrameIndex(frames, initialFrameEpoch) }
    val playlist = remember(request, series) {
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
    var loop by rememberSaveable { mutableStateOf(true) }
    var infoVisible by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var chromeSerial by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubIndex by remember { mutableIntStateOf(selected) }
    val surfaceRequester = remember { FocusRequester() }
    val playRequester = remember { FocusRequester() }
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
            playlist.close() // Cancel any on-demand HTTP work before releasing its image source.
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(loop, player) { player.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF }
    LaunchedEffect(controlsVisible, infoVisible, chromeSerial, failed, buffering) {
        if (controlsVisible && !infoVisible && !failed && !buffering) {
            delay(8_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible) {
        withFrameNanos { }
        runCatching {
            if (controlsVisible) playRequester.requestFocus() else surfaceRequester.requestFocus()
        }
    }
    BackHandler(infoVisible) { infoVisible = false; controlsVisible = true }
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

    Box(Modifier.fillMaxSize()
        .onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (!controlsVisible) {
                controlsVisible = true
                chromeSerial++
                true
            } else { chromeSerial++; false }
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
                .clickable { controlsVisible = !controlsVisible; chromeSerial++ }) {
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
        if (controlsVisible) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xff101c28).copy(alpha = .96f))
                .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(if (provider.kind == WeatherMapKind.RADAR)
                            R.string.weather_radar else R.string.weather_satellite) + " · " + provider.title,
                            color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(location.name + " · " + frameLabel, color = Color.White,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TvChip(stringResource(R.string.weather_player_close), onClick = onBack)
                }
                if (providers.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    providers.forEach { choice ->
                        TvChip(choice.title, selected = choice == provider,
                            onClick = { onChangeProvider(choice.id) })
                    }
                }
                if (infoVisible) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 210.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(stringResource(R.string.weather_player_product,
                            when (provider) {
                                WeatherMapProvider.RAINVIEWER, WeatherMapProvider.DWD ->
                                    stringResource(R.string.weather_player_radar_product)
                                WeatherMapProvider.NASA -> stringResource(R.string.weather_player_nasa_product)
                                WeatherMapProvider.EUMETSAT -> stringResource(R.string.weather_player_eumetsat_product)
                            }), color = Color.White)
                        Text(stringResource(R.string.weather_player_frame_counter,
                            currentIndex + 1, frames.size), color = Color.White)
                        Text(stringResource(R.string.weather_player_zoom, viewport.zoom), color = Color.White)
                        Text(stringResource(R.string.weather_maps_width, viewport.widthKilometres.roundToInt()), color = Color.White)
                        Text(stringResource(R.string.weather_player_manifest,
                            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm z", locale).withZone(zone)
                                .format(Instant.ofEpochMilli(series.fetchedAtMillis))), color = Color.White)
                        if ((initialCached && currentIndex == selected) || stale || sourceError != null) {
                            Text(stringResource(R.string.weather_maps_stored), color = Color.White)
                        }
                        if (frameBaseMissing) Text(stringResource(R.string.weather_maps_base_missing), color = Color.White)
                        if (frameCoverageMissing) Text(stringResource(R.string.weather_maps_coverage_missing), color = Color.White)
                        if (provider.kind == WeatherMapKind.RADAR) {
                            Box(Modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                                WeatherMapLegend(provider)
                            }
                        }
                        Box(Modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                            WeatherSourceLink(provider.attribution, provider.sourceUrl)
                        }
                    }
                }
                if (frames.size > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(firstLabel, modifier = Modifier.weight(1f), color = Color.White,
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(currentShortLabel, modifier = Modifier.weight(1f), color = Color.White,
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(lastLabel, modifier = Modifier.weight(1f), color = Color.White,
                            textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Slider(
                        value = if (scrubbing) scrubIndex.toFloat() else currentIndex.toFloat(),
                        onValueChange = { scrubbing = true; scrubIndex = it.roundToInt().coerceIn(0, frames.lastIndex) },
                        onValueChangeFinished = {
                            player.seekTo(scrubIndex, 0L)
                            scrubbing = false
                        },
                        valueRange = 0f..frames.lastIndex.toFloat(),
                        steps = (frames.size - 2).coerceAtLeast(0),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvChip(stringResource(R.string.weather_maps_previous), enabled = frames.size > 1,
                        onClick = { player.seekTo((currentIndex - 1).coerceAtLeast(0), 0L) })
                    TvChip(stringResource(if (wantsPlayback && !ended && !failed) R.string.weather_maps_pause
                        else R.string.weather_maps_play),
                        modifier = Modifier.focusRequester(playRequester),
                        onClick = {
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
                        })
                    TvChip(stringResource(R.string.weather_maps_next), enabled = frames.size > 1,
                        onClick = { player.seekTo((currentIndex + 1).coerceAtMost(frames.lastIndex), 0L) })
                    TvChip(stringResource(R.string.weather_maps_zoom_out),
                        enabled = viewport.zoom > WeatherMapViewport.MIN_ZOOM,
                        onClick = { onZoom((viewport.zoom - 1).coerceAtLeast(WeatherMapViewport.MIN_ZOOM), currentFrame.epochMillis) })
                    TvChip(stringResource(R.string.weather_maps_zoom_in),
                        enabled = viewport.zoom < WeatherMapViewport.MAX_ZOOM,
                        onClick = { onZoom((viewport.zoom + 1).coerceAtMost(WeatherMapViewport.MAX_ZOOM), currentFrame.epochMillis) })
                    TvChip(stringResource(R.string.weather_player_repeat), selected = loop,
                        onClick = { loop = !loop })
                    TvChip(stringResource(R.string.weather_player_info), selected = infoVisible,
                        onClick = { infoVisible = !infoVisible })
                    TvChip(stringResource(R.string.weather_player_hide), onClick = { controlsVisible = false })
                }
                if (buffering || failed || sourceError != null) {
                    Text(stringResource(when {
                        failed -> R.string.weather_maps_unavailable
                        sourceError != null -> weatherPlayerErrorMessage(sourceError)
                        else -> R.string.weather_maps_loading
                    }), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    if (failed || sourceError != null) TvChip(stringResource(R.string.weather_maps_retry),
                        onClick = {
                            if (failed) {
                                failed = false
                                ended = false
                                player.prepare()
                                player.playWhenReady = wantsPlayback
                                buffering = player.playbackState == Player.STATE_BUFFERING
                            }
                            onRetry()
                        })
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
