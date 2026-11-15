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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherMapError
import com.ambient.tv.data.weather.WeatherMapKind
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherMapRepository
import com.ambient.tv.data.weather.WeatherMapResult
import com.ambient.tv.data.weather.WeatherMapViewport
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WeatherMapEntryPoint { fun weatherMapRepository(): WeatherMapRepository }

/** Manual source selection; a source error never changes the user's saved provider. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeatherMapPanel(
    location: WeatherLocation,
    kind: WeatherMapKind,
    providerId: String,
    onProviderSelected: (String) -> Unit,
    networkAllowed: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, WeatherMapEntryPoint::class.java).weatherMapRepository()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val contentVisible = LocalWeatherPresentation.current.contentVisible
    val source = WeatherMapProvider.selected(providerId, kind)
    // Source changes preserve the geographic view. A newly selected location recentres it.
    val initial = remember(location.id, location.latitude, location.longitude) { WeatherMapViewport.at(location.latitude, location.longitude) }
    var zoom by rememberSaveable(location.id, location.latitude, location.longitude) { mutableIntStateOf(initial.zoom) }
    var x by rememberSaveable(location.id, location.latitude, location.longitude) { mutableIntStateOf(initial.x) }
    var y by rememberSaveable(location.id, location.latitude, location.longitude) { mutableIntStateOf(initial.y) }
    val viewport = WeatherMapViewport(zoom, x, y)
    fun move(next: WeatherMapViewport) { zoom = next.zoom; x = next.x; y = next.y }
    val identity = "${location.id}:${source?.id}:${viewport.key}"
    var result by remember(identity) { mutableStateOf<WeatherMapResult?>(null) }
    var metadataLoading by remember(identity) { mutableStateOf(false) }
    var frameLoading by remember(identity) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedFrame by remember(identity) { mutableIntStateOf(-1) }
    var playing by remember(identity, networkAllowed, contentVisible) { mutableStateOf(false) }

    LaunchedEffect(identity, networkAllowed, refresh, lifecycle, contentVisible) {
        if (source == null || !contentVisible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            metadataLoading = true
            val cached = repository.cached(source, viewport)
            if (cached.image != null) result = cached
            try {
                result = repository.load(source, viewport, networkAllowed, force = refresh > 0)
                selectedFrame = result?.series?.frames?.lastIndex ?: -1
                if (result?.error != null) playing = false
            } finally { metadataLoading = false }
            awaitCancellation()
        }
    }
    LaunchedEffect(identity, selectedFrame, result?.series?.fetchedAtMillis, networkAllowed, lifecycle, contentVisible) {
        if (!contentVisible) return@LaunchedEffect
        val series = result?.series ?: return@LaunchedEffect
        val provider = source ?: return@LaunchedEffect
        if (result?.error == WeatherMapError.NO_COVERAGE) return@LaunchedEffect
        val target = series.frames.getOrNull(selectedFrame) ?: return@LaunchedEffect
        if (result?.image?.frame?.epochMillis == target.epochMillis) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            frameLoading = true
            try {
                val next = repository.frame(provider, viewport, series, selectedFrame, networkAllowed)
                // Browsing a cached frame does not establish that a failed source recovered.
                // A successful explicit refresh clears the previous outage.
                result = if (next.image != null) next.copy(error = next.error ?: result?.error)
                    else result?.copy(error = next.error)
                if (next.error != null) playing = false
            } finally { frameLoading = false }
            awaitCancellation()
        }
    }
    LaunchedEffect(identity, playing, lifecycle, contentVisible) {
        if (!playing || !contentVisible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                delay(1100)
                val count = result?.series?.frames?.size ?: 0
                if (count > 1 && !metadataLoading && !frameLoading) selectedFrame = (selectedFrame + 1) % count
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.weather_maps_choose_provider), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WeatherMapProvider.forKind(kind).forEach { provider ->
                TvChip(provider.title, selected = source == provider, onClick = { playing = false; onProviderSelected(provider.id) })
            }
        }
        if (source == null) {
            Text(stringResource(R.string.weather_maps_choose_hint), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(stringResource(when (source) {
            WeatherMapProvider.RAINVIEWER -> R.string.weather_maps_rainviewer_description
            WeatherMapProvider.DWD -> R.string.weather_maps_dwd_description
            WeatherMapProvider.NASA -> R.string.weather_maps_nasa_description
            WeatherMapProvider.EUMETSAT -> R.string.weather_maps_eumetsat_description
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val image = result?.image
        val error = result?.error
        if (error != null) {
            Text(stringResource(when (error) {
                WeatherMapError.NETWORK_DISABLED -> R.string.weather_maps_network_disabled
                WeatherMapError.OFFLINE -> R.string.weather_maps_offline
                WeatherMapError.UNAVAILABLE -> R.string.weather_maps_unavailable
                WeatherMapError.RATE_LIMITED -> R.string.weather_maps_rate_limited
                WeatherMapError.ACCESS_DENIED -> R.string.weather_maps_access_denied
                WeatherMapError.NO_COVERAGE -> R.string.weather_maps_no_coverage
            }), style = MaterialTheme.typography.bodyMedium)
        }
        if (image != null || metadataLoading) {
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
                    } else Text(stringResource(R.string.weather_maps_loading), Modifier.align(Alignment.Center).padding(20.dp))
                }
            }
        }
        if (image != null) {
            val zone = remember(location.timezone) { runCatching { ZoneId.of(location.timezone) }.getOrDefault(ZoneOffset.UTC) }
            val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
            val time = remember(image.frame.time, source.daily, zone, locale) {
                if (source.daily) image.frame.time else DateTimeFormatter.ofPattern("dd.MM. HH:mm z", locale).withZone(zone)
                    .format(Instant.ofEpochMilli(image.frame.epochMillis))
            }
            val old = System.currentTimeMillis() - image.frame.epochMillis > source.freshnessMillis
            Text(stringResource(if (source.daily) R.string.weather_maps_daily_time else R.string.weather_maps_frame_time, time) +
                if (old || error != null) " · " + stringResource(R.string.weather_maps_stored) else "",
                style = MaterialTheme.typography.bodyMedium)
            if (image.baseMissing) Text(stringResource(R.string.weather_maps_base_missing), style = MaterialTheme.typography.bodySmall)
            if (image.coverageMissing) Text(stringResource(R.string.weather_maps_coverage_missing), style = MaterialTheme.typography.bodySmall)
            WeatherMapLegend(source)
        }
        val count = result?.series?.frames?.size ?: 0
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TvActionButton(stringResource(R.string.weather_maps_previous), enabled = count > 1,
                onClick = { playing = false; selectedFrame = (selectedFrame - 1).coerceAtLeast(0) })
            TvActionButton(stringResource(if (playing) R.string.weather_maps_pause else R.string.weather_maps_play), enabled = count > 1,
                onClick = { playing = !playing })
            TvActionButton(stringResource(R.string.weather_maps_next), enabled = count > 1,
                onClick = { playing = false; selectedFrame = (selectedFrame + 1).coerceAtMost(count - 1) })
            TvActionButton(stringResource(R.string.weather_maps_retry), enabled = networkAllowed,
                onClick = { playing = false; refresh++ })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TvChip(stringResource(R.string.weather_maps_zoom_out), enabled = zoom > WeatherMapViewport.MIN_ZOOM, onClick = { move(viewport.zoomed(-1)) })
            TvChip(stringResource(R.string.weather_maps_zoom_in), enabled = zoom < WeatherMapViewport.MAX_ZOOM, onClick = { move(viewport.zoomed(1)) })
            TvChip(stringResource(R.string.weather_maps_west), onClick = { move(viewport.pan(-1, 0)) })
            TvChip(stringResource(R.string.weather_maps_east), onClick = { move(viewport.pan(1, 0)) })
            TvChip(stringResource(R.string.weather_maps_north), enabled = y > 0, onClick = { move(viewport.pan(0, -1)) })
            TvChip(stringResource(R.string.weather_maps_south), enabled = y < viewport.tiles - 1, onClick = { move(viewport.pan(0, 1)) })
            TvChip(stringResource(R.string.weather_maps_centre), onClick = { move(WeatherMapViewport.at(location.latitude, location.longitude, zoom)) })
        }
        Text(stringResource(R.string.weather_maps_width, viewport.widthKilometres.roundToInt()),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WeatherSourceLink(source.attribution, source.sourceUrl)
    }
}
