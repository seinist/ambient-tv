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
import androidx.compose.material.icons.filled.Radio
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.ReportSidebarContentReady
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.SystemPageTitle
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvLazyVerticalGrid
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.overlayLazyGridScrollbar
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.theme.screenGutter

@Composable
fun RadioHomeScreen(
    onStationClick: (RadioStation) -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: RadioHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.enter() }
    if (onBack != null) BackHandler { onBack() }
    ReportSidebarContentReady(!state.loading)

    val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()
    val queryFocus = remember { FocusRequester() }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

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
            SystemPageTitle(MediaSystem.RADIO)
            Text(
                stringResource(R.string.radio_provider_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                label = stringResource(R.string.radio_search_placeholder),
                modifier = Modifier.weight(1f).focusRequester(queryFocus),
            )
            TvActionButton(
                text = stringResource(R.string.action_search),
                onClick = viewModel::submitSearch,
                enabled = state.query.trim().length >= 2,
                loading = state.loading && state.hasSearched,
            )
        }

        val heading = if (state.hasSearched) state.query.trim() else stringResource(R.string.radio_popular)
        Text(heading, style = MaterialTheme.typography.titleMedium)

        Box(
            Modifier
                .fillMaxSize()
                .pageContentUp(focusChain) { queryFocus }
                .focusGroup(),
        ) {
            when {
                state.loading -> RadioSkeleton()
                state.error != null && state.items.isEmpty() -> StatePanel(
                    title = stringResource(R.string.radio_error),
                    actionLabel = stringResource(R.string.home_retry),
                    onAction = viewModel::refresh,
                    modifier = Modifier.align(Alignment.Center),
                )
                state.items.isEmpty() -> StatePanel(
                    title = stringResource(R.string.radio_no_stations),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> TvLazyVerticalGrid(
                    columns = GridCells.Adaptive(deviceDp(190.dp, 144.dp)),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().overlayLazyGridScrollbar(gridState),
                    contentPadding = PaddingValues(bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(state.items, key = RadioStation::id) { station ->
                        RadioStationTile(station = station, onClick = { onStationClick(station) })
                    }
                    if (state.hasMore) {
                        item(key = "radio:more") {
                            TvActionButton(
                                text = stringResource(R.string.radio_load_more),
                                onClick = viewModel::loadMore,
                                loading = state.loadingMore,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioStationTile(
    station: RadioStation,
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
                    if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .14f)
                    else MaterialTheme.colorScheme.surface.copy(alpha = .72f),
                    shape,
                )
                .border(
                    2.dp,
                    if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .08f),
                    shape,
                )
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center,
            ) {
                if (station.artworkUrl != null) {
                    ArtworkImage(
                        station.artworkUrl,
                        station.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    androidx.compose.material3.Icon(
                        Icons.Default.Radio,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            CenteredMarqueeTitle(station.name, focused = focused)
            Text(
                listOfNotNull(
                    station.countryCode,
                    station.codec,
                    station.bitrateKbps?.let { "$it kbps" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RadioSkeleton() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.radio_popular),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
