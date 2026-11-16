package com.ambient.tv.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherMapError
import com.ambient.tv.data.weather.WeatherMapImage
import com.ambient.tv.data.weather.WeatherMapKind
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherMapViewport
import com.ambient.tv.ui.common.FeedbackLayout
import com.ambient.tv.ui.common.FullscreenSpinner
import com.ambient.tv.ui.common.OperationFeedbackContent
import com.ambient.tv.ui.common.OperationFeedbackScope
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.TvLazyColumn
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Display one enabled provider at a time, without eagerly loading the others. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeatherMapPanel(
    location: WeatherLocation,
    kind: WeatherMapKind,
    providerId: String,
    onOpenSettings: () -> Unit,
    networkAllowed: Boolean,
    modifier: Modifier = Modifier,
    viewModel: WeatherMapViewModel = hiltViewModel(),
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val contentVisible = LocalWeatherPresentation.current.contentVisible
    val providers = remember(providerId, kind) { WeatherMapProvider.selectedMany(providerId, kind) }
    var activeProviderId by rememberSaveable(location.id, kind.name) { mutableStateOf("") }
    val source = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()
    // The chosen place remains the centre at every zoom. Provider changes retain the zoom;
    // selecting another place starts at its own local view.
    val initial = remember(location.id, location.latitude, location.longitude) { WeatherMapViewport.at(location.latitude, location.longitude) }
    var zoom by rememberSaveable(location.id, location.latitude, location.longitude) { mutableIntStateOf(initial.zoom) }
    val viewport = remember(initial, zoom) { WeatherMapViewport.at(location.latitude, location.longitude, zoom) }
    val request = remember(location, kind, source, viewport, networkAllowed) {
        WeatherMapRequest(location, kind, source, viewport, networkAllowed)
    }
    val current by viewModel.state.collectAsState()
    // A changed provider/area must never flash the previous response while effects are applying.
    val state = current.takeIf { it.request == request } ?: WeatherMapUiState(request)
    val image = state.result?.image
    val error = state.result?.error
    val count = state.result?.series?.frames?.size ?: 0
    val canPlay = image != null && count > 1 && !state.loading && error != WeatherMapError.NO_COVERAGE

    OperationFeedbackScope(viewModel.feedback, request.scope, enabled = contentVisible)
    DisposableEffect(viewModel, request, lifecycle, contentVisible) {
        fun update() = viewModel.present(request, contentVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); viewModel.dismiss(request) }
    }

    TvLazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp), allowUpExit = true, wrapVerticalFocus = false) {
        if (source == null) {
            item(key = "choose-provider") {
                StatePanel(
                    title = stringResource(R.string.weather_maps_choose_provider),
                    body = stringResource(R.string.weather_maps_choose_hint),
                    actionLabel = stringResource(R.string.weather_settings),
                    onAction = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            if (providers.size > 1) item(key = "provider-choices") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    providers.forEach { provider ->
                        TvChip(provider.title, selected = provider.id == source.id,
                            onClick = { activeProviderId = provider.id })
                    }
                }
            }
            item(key = "provider") {
                Text(source.title, style = MaterialTheme.typography.titleMedium)
            }
            item(key = "source-description") {
                Text(stringResource(when (source) {
                    WeatherMapProvider.RAINVIEWER -> R.string.weather_maps_rainviewer_description
                    WeatherMapProvider.DWD -> R.string.weather_maps_dwd_description
                    WeatherMapProvider.NASA -> R.string.weather_maps_nasa_description
                    WeatherMapProvider.EUMETSAT -> R.string.weather_maps_eumetsat_description
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(key = "time-controls") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvActionButton(stringResource(R.string.weather_maps_previous), enabled = canPlay && state.selectedFrame > 0,
                        onClick = { viewModel.step(-1) })
                    TvActionButton(stringResource(if (state.playing) R.string.weather_maps_pause else R.string.weather_maps_play),
                        enabled = canPlay, onClick = viewModel::togglePlayback)
                    TvActionButton(stringResource(R.string.weather_maps_next), enabled = canPlay && state.selectedFrame < count - 1,
                        onClick = { viewModel.step(1) })
                    TvActionButton(stringResource(R.string.weather_maps_retry), enabled = networkAllowed,
                        loading = state.loading, onClick = viewModel::refresh)
                }
            }
            // Keep navigation before every changing image/error item. Loading a new extent
            // must not remove items above the focused, pinned TV control while HTTP is pending.
            item(key = "map-controls") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvChip(stringResource(R.string.weather_maps_zoom_out), enabled = zoom > WeatherMapViewport.MIN_ZOOM,
                        onClick = { zoom = (zoom - 1).coerceAtLeast(WeatherMapViewport.MIN_ZOOM) })
                    TvChip(stringResource(R.string.weather_maps_zoom_in), enabled = zoom < WeatherMapViewport.MAX_ZOOM,
                        onClick = { zoom = (zoom + 1).coerceAtMost(WeatherMapViewport.MAX_ZOOM) })
                }
            }
            if (error != null && image != null) item(key = "map-feedback") {
                OperationFeedbackContent(weatherMapFeedback(error, request), Modifier.fillMaxWidth(), FeedbackLayout.COMPACT)
            }
            // Reserve the map area even between requests and on failure. Replacing a frame
            // changes its contents, never the surrounding lazy-list structure or scroll anchor.
            item(key = "map") {
                WeatherMapImageContent(image, source, viewport, location, state.frameLoading,
                    error = error, request = request)
            }
            if (image != null) item(key = "image-time") {
                WeatherMapImageTime(image, source, location, error != null)
            }
            if (image?.baseMissing == true) item(key = "base-feedback") {
                OperationFeedbackContent(weatherMapLayerFeedback(request, coverage = false), Modifier.fillMaxWidth(), FeedbackLayout.FIELD)
            }
            if (image?.coverageMissing == true) item(key = "coverage-feedback") {
                OperationFeedbackContent(weatherMapLayerFeedback(request, coverage = true), Modifier.fillMaxWidth(), FeedbackLayout.FIELD)
            }
            if (image != null && kind == WeatherMapKind.RADAR) item(key = "radar-legend") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { WeatherMapLegend(source) }
            }
            item(key = "attribution") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.weather_maps_width, viewport.widthKilometres.roundToInt()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WeatherSourceLink(source.attribution, source.sourceUrl)
                }
            }
        }
    }
}

@Composable
private fun WeatherMapImageContent(image: WeatherMapImage?, source: WeatherMapProvider, viewport: WeatherMapViewport,
    location: WeatherLocation, frameLoading: Boolean, error: WeatherMapError?, request: WeatherMapRequest) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, 360.dp)
        Box(Modifier.size(side).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (image != null) {
                val bitmap = remember(image.bitmap) { image.bitmap.asImageBitmap() }
                Image(bitmap, contentDescription = stringResource(R.string.weather_maps_image_description, source.title, location.name),
                    modifier = Modifier.fillMaxSize())
                viewport.marker(location.latitude, location.longitude)?.let { marker ->
                    Canvas(Modifier.fillMaxSize()) {
                        val point = Offset(marker.first * size.width, marker.second * size.height)
                        drawCircle(Color.Black.copy(alpha = .8f), 8.dp.toPx(), point)
                        drawCircle(Color.White, 6.dp.toPx(), point, style = Stroke(2.dp.toPx()))
                        drawCircle(Color(0xffE54848), 3.dp.toPx(), point)
                    }
                }
                WeatherSourceLink("© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright",
                    modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = .8f)), color = Color.White)
                if (frameLoading) FullscreenSpinner(Modifier.align(Alignment.TopEnd).padding(12.dp))
            } else if (error != null) {
                OperationFeedbackContent(weatherMapFeedback(error, request),
                    Modifier.align(Alignment.Center).padding(20.dp), FeedbackLayout.FIELD)
            } else Text(stringResource(R.string.weather_maps_loading), Modifier.align(Alignment.Center).padding(20.dp))
        }
    }
}

@Composable
private fun WeatherMapImageTime(image: WeatherMapImage, source: WeatherMapProvider, location: WeatherLocation, failed: Boolean) {
    val context = LocalContext.current
    val zone = remember(location.timezone) { runCatching { ZoneId.of(location.timezone) }.getOrDefault(ZoneOffset.UTC) }
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val time = remember(image.frame.time, source.daily, zone, locale) {
        if (source.daily) image.frame.time else DateTimeFormatter.ofPattern("dd.MM. HH:mm z", locale).withZone(zone)
            .format(Instant.ofEpochMilli(image.frame.epochMillis))
    }
    val old = System.currentTimeMillis() - image.frame.epochMillis > source.freshnessMillis
    Text(stringResource(if (source.daily) R.string.weather_maps_daily_time else R.string.weather_maps_frame_time, time) +
        if (old || failed) " · " + stringResource(R.string.weather_maps_stored) else "", style = MaterialTheme.typography.bodyMedium)
}
