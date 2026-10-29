package com.ambient.tv.ui.radio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.CenteredMarqueeTitle
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.SystemPageTitle
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvLazyVerticalGrid
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.overlayLazyGridScrollbar
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.PlaybackEngineFactory
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.theme.screenGutter

@Composable
fun RadioScreen(
    onBack: (() -> Unit)? = null,
    viewModel: RadioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val playbackRequest by viewModel.playbackRequest.collectAsState()
    val playback by BackgroundPlaybackSession.snapshot.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(viewModel) { viewModel.enter() }
    ReportSidebarContentReady(!state.loading)
    if (onBack != null) BackHandler { onBack() }

    LaunchedEffect(playbackRequest?.requestId) {
        val request = playbackRequest ?: return@LaunchedEffect
        val station = request.station
        val subtitle = stationMeta(station)
        runCatching {
            PlaybackEngineFactory.create(
                context = context,
                streamUrl = request.streamUrl,
                startPositionMs = 0L,
                metadata = PlaybackMetadata(
                    route = Destinations.RADIO,
                    title = station.name,
                    subtitle = subtitle,
                    source = "radio",
                    mediaId = station.id,
                    hasVideo = false,
                    artworkUrl = station.artworkUrl,
                    artworkAspectRatio = 1f,
                    isLive = true,
                ),
                audioOnly = true,
                liveStream = true,
            )
        }.onSuccess {
            viewModel.playbackStarted(request.requestId)
        }.onFailure {
            viewModel.playbackFailed(request.requestId)
        }
    }

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()

    Column(
        Modifier
            .fillMaxSize()
            .padding(
                start = screenGutter(),
                end = screenGutter(),
                bottom = screenGutter(),
                top = com.ambient.tv.ui.common.pageHeaderTop(screenGutter()),
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader(focusChain = focusChain) {
            SystemPageTitle(com.ambient.tv.ui.common.MediaSystem.RADIO)
            Text(
                stringResource(if (state.submittedQuery.isBlank()) R.string.radio_popular else R.string.radio_title),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                label = stringResource(R.string.radio_search_placeholder),
                modifier = Modifier.weight(1f),
            )
            TvActionButton(
                text = stringResource(R.string.action_search),
                onClick = viewModel::submitSearch,
                enabled = state.query.trim().isEmpty() || state.query.trim().length >= 2,
            )
            if (state.submittedQuery.isNotBlank()) {
                TvActionButton(text = stringResource(R.string.search_clear_recent), onClick = viewModel::clearSearch)
            }
        }

        when {
            state.loading -> com.ambient.tv.ui.common.SkeletonRow(title = "", modifier = Modifier.fillMaxWidth())
            state.error && state.stations.isEmpty() -> StatePanel(
                title = stringResource(R.string.radio_error),
                modifier = Modifier.fillMaxWidth(),
            )
            state.stations.isEmpty() -> StatePanel(
                title = stringResource(R.string.radio_no_stations),
                modifier = Modifier.fillMaxWidth(),
            )
            else -> TvLazyVerticalGrid(
                columns = GridCells.Adaptive(deviceDp(190.dp, 144.dp)),
                modifier = Modifier
                    .fillMaxSize()
                    .overlayLazyGridScrollbar(gridState)
                    .focusGroup(),
                state = gridState,
                contentPadding = PaddingValues(bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.stations, key = { it.id }) { station ->
                    RadioStationTile(
                        station = station,
                        playing = playback.metadata?.source == "radio" &&
                            playback.metadata?.mediaId == station.id &&
                            playback.wantsToPlay,
                        loading = state.startingStationId == station.id,
                        onClick = { viewModel.play(station) },
                    )
                }
                if (state.hasMore) {
                    item(key = "radio:load-more") {
                        TvActionButton(
                            text = stringResource(R.string.radio_load_more),
                            onClick = viewModel::loadMore,
                            loading = state.loadingMore,
                            modifier = Modifier.width(deviceDp(190.dp, 160.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioStationTile(
    station: RadioStation,
    playing: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape),
        modifier = Modifier
            .width(deviceDp(190.dp, 160.dp))
            .mediaFocusMotion(focused)
            .activateOnTvKey(onClick = onClick)
            .onFocusChanged { focused = it.isFocused },
    ) {
        Column(
            Modifier
                .background(
                    if (focused || playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape,
                )
                .border(
                    2.dp,
                    if (focused || playing) MaterialTheme.colorScheme.primary
                    else Color.White.copy(alpha = 0.08f),
                    shape,
                )
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                if (!station.artworkUrl.isNullOrBlank()) {
                    ArtworkImage(
                        station.artworkUrl,
                        station.name,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color.DarkGray),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(Color.DarkGray, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(Icons.Default.MusicNote, null)
                    }
                }
                if (playing || loading) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f))
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            CenteredMarqueeTitle(station.name, focused = focused)
            Text(
                stationMeta(station),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun stationMeta(station: RadioStation): String = listOfNotNull(
    station.country,
    station.codec,
    station.bitrateKbps?.let { "$it kbps" },
).joinToString(" • ")
