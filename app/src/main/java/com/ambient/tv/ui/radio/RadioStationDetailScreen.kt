package com.ambient.tv.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.radio.RadioBrowserRepository
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.common.*
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.theme.screenGutter
import kotlinx.coroutines.CancellationException

@Composable
fun RadioStationDetailScreen(
    stationId: String,
    repository: RadioBrowserRepository,
    preferences: AppPreferences,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (RadioStation) -> Unit,
    onOpenStation: (RadioStation) -> Unit,
) {
    var station by remember(stationId) { mutableStateOf<RadioStation?>(null) }
    var related by remember(stationId) { mutableStateOf<List<RadioStation>>(emptyList()) }
    var loading by remember(stationId) { mutableStateOf(true) }
    var error by remember(stationId) { mutableStateOf<String?>(null) }
    var favoriteRevision by remember(stationId) { mutableIntStateOf(0) }
    val primaryFocus = remember(stationId) { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(stationId) {
        loading = true
        error = null
        try {
            val loaded = repository.station(stationId)
            station = loaded
            if (loaded == null) {
                error = "Station unavailable"
            } else {
                val tag = loaded.tags.firstOrNull()
                related = if (tag != null) repository.search(tag = tag, limit = 16).items
                    .filterNot { it.id == loaded.id }.take(12) else emptyList()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        } finally {
            loading = false
        }
    }

    // Read the composition local outside the coroutine effect.
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    LaunchedEffect(station?.id, handheld) {
        if (station == null || handheld) return@LaunchedEffect
        repeat(4) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { primaryFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageBackground(Modifier.fillMaxSize())
        when {
            loading -> Column(
                Modifier.fillMaxSize().padding(start = screenGutter(), end = screenGutter(), top = 94.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SkeletonRow(title = "", style = MediaTileStyle.LANDSCAPE, itemCount = 1)
                SkeletonRow(title = "", style = MediaTileStyle.POSTER, itemCount = 5)
            }
            station == null -> StatePanel(
                title = stringResource(R.string.radio_station_detail),
                body = error,
                actionLabel = stringResource(R.string.back),
                onAction = onBack,
                modifier = Modifier.fillMaxSize(),
            )
            else -> {
                val item = station!!
                val favorite = remember(item.id, favoriteRevision) { preferences.isRadioFavorite(item.id) }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(start = screenGutter(), end = screenGutter(), top = 94.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(deviceDp(36.dp, 18.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.width(deviceDp(280.dp, 160.dp)).aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = .7f)),
                        ) {
                            ArtworkImage(
                                model = item.artworkUrl,
                                title = item.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SystemBadge(MediaSystem.RADIO)
                            Text(item.name, style = MaterialTheme.typography.headlineLarge)
                            listOfNotNull(
                                item.country,
                                item.state,
                                item.languages.take(2).joinToString(", ").takeIf(String::isNotBlank),
                            ).takeIf { it.isNotEmpty() }?.let {
                                Text(it.joinToString("  ·  "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOfNotNull(
                                    item.codec,
                                    item.bitrateKbps?.let { "${it} kbps" },
                                    "HLS".takeIf { item.isHls },
                                ).forEach { MetaBadge(it) }
                                item.tags.take(5).forEach { MetaBadge(it) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TvActionButton(
                                    stringResource(R.string.common_play),
                                    onClick = { onPlay(item) },
                                    modifier = Modifier.focusRequester(primaryFocus),
                                )
                                TvActionButton(
                                    stringResource(R.string.radio_favorites),
                                    onClick = {
                                        preferences.setRadioFavorite(item, !favorite)
                                        favoriteRevision++
                                    },
                                    selected = favorite,
                                )
                            }
                        }
                    }
                    if (related.isNotEmpty()) {
                        RadioDetailRow(
                            title = stringResource(R.string.radio_similar_stations),
                            stations = related,
                            preferences = preferences,
                            onOpenStation = onOpenStation,
                        )
                    }
                }
            }
        }
        PageUtilityBar(
            onBack = onBack,
            onSettings = onOpenSettings,
            onDown = if (!loading && station != null) ({ runCatching { primaryFocus.requestFocus() } }) else null,
            leftNavigatesBack = false,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun RadioDetailRow(
    title: String,
    stations: List<RadioStation>,
    preferences: AppPreferences,
    onOpenStation: (RadioStation) -> Unit,
) {
    val focus = rememberRowColumnFocus()
    val state = rememberLazyListState()
    val key = "radio-detail:" + title
    RegisterRowColumnFocus(focus, key, state, stations.map { it.id })
    Column(
        Modifier.fillMaxWidth().rowColumnFocusWrapRight(focus),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        LazyRow(
            state = state,
            modifier = Modifier.containRowFocusExit(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(stations, key = { it.id }) { item ->
                RadioStationTile(
                    station = item,
                    modifier = Modifier.width(deviceDp(190.dp, 160.dp))
                        .rowColumnFocusTarget(focus, key, item.id),
                    playing = false,
                    loading = false,
                    favorite = preferences.isRadioFavorite(item.id),
                    onClick = { onOpenStation(item) },
                    onFocused = {},
                )
            }
        }
    }
}
