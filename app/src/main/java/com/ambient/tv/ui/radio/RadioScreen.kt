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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FavoriteBorder
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
import com.ambient.tv.data.radio.RadioFacet
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.CenteredMarqueeTitle
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.SystemPageTitle
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.TvLazyVerticalGrid
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.skeletonPulseAlpha
import com.ambient.tv.ui.common.overlayLazyGridScrollbar
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.theme.screenGutter
import java.util.Locale

@Composable
fun RadioScreen(
    onBack: (() -> Unit)? = null,
    viewModel: RadioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val playback by BackgroundPlaybackSession.snapshot.collectAsState()

    LaunchedEffect(viewModel) { viewModel.enter() }
    ReportSidebarContentReady(!state.loading)

    BackHandler(enabled = state.selectedFacet != null) { viewModel.clearFacet() }
    BackHandler(enabled = state.selectedFacet == null && state.submittedQuery.isNotBlank()) {
        viewModel.clearSearch()
    }
    if (onBack != null) {
        BackHandler(enabled = state.selectedFacet == null && state.submittedQuery.isBlank()) { onBack() }
    }

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()
    LaunchedEffect(state.section, state.selectedFacet?.id, state.submittedQuery) {
        if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) {
            runCatching { gridState.scrollToItem(0) }
        }
    }
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
        PageHeader(
            focusChain = focusChain,
            actions = {
                TvChip(
                    text = stringResource(R.string.action_reload),
                    icon = Icons.Filled.Refresh,
                    iconOnly = true,
                    loading = state.loading || state.loadingMore,
                    onClick = viewModel::refresh,
                )
            },
        ) {
            SystemPageTitle(com.ambient.tv.ui.common.MediaSystem.RADIO)
            Text(
                radioPageSubtitle(state),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            lazyItems(RadioSection.entries, key = { it.name }) { section ->
                TvChip(
                    text = radioSectionLabel(section),
                    selected = state.section == section &&
                        state.submittedQuery.isBlank() &&
                        state.selectedFacet == null,
                    onClick = { viewModel.selectSection(section) },
                )
            }
        }

        state.selectedFacet?.let { selectedFacet ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvChip(
                    text = stringResource(R.string.radio_browse_back, radioSectionLabel(state.section)),
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = viewModel::clearFacet,
                )
                Text(
                    radioFacetLabel(state.section, selectedFacet),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                TvActionButton(
                    text = stringResource(R.string.search_clear_recent),
                    onClick = viewModel::clearSearch,
                )
            }
        }

        val showingFacets = state.section.isFacetSection &&
            state.selectedFacet == null &&
            state.submittedQuery.isBlank()

        when {
            state.loading -> com.ambient.tv.ui.common.SkeletonRow(
                title = "",
                modifier = Modifier.fillMaxWidth(),
            )
            state.error && state.stations.isEmpty() && state.facets.isEmpty() -> StatePanel(
                title = stringResource(R.string.radio_error),
                modifier = Modifier.fillMaxWidth(),
            )
            showingFacets && state.facets.isEmpty() -> StatePanel(
                title = stringResource(R.string.radio_no_stations),
                modifier = Modifier.fillMaxWidth(),
            )
            showingFacets -> TvLazyVerticalGrid(
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
                items(state.facets, key = { it.id }) { facet ->
                    RadioFacetTile(
                        section = state.section,
                        facet = facet,
                        onClick = { viewModel.selectFacet(facet) },
                    )
                }
            }
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
                itemsIndexed(state.stations, key = { _, station -> station.id }) { _, station ->
                    RadioStationTile(
                        station = station,
                        playing = playback.metadata?.source == "radio" &&
                            playback.metadata?.mediaId == station.id &&
                            playback.wantsToPlay,
                        loading = state.startingStationId == station.id,
                        favorite = station.id in state.favoriteIds,
                        onClick = { viewModel.play(station) },
                        onFavorite = { viewModel.toggleFavorite(station) },
                        onFocused = { viewModel.onStationFocused(station.id) },
                    )
                }
                if (state.loadingMore) {
                    items(
                        count = RADIO_GRID_END_PLACEHOLDERS,
                        key = { radioGridEndPlaceholderKey(it) },
                    ) {
                        RadioEndPlaceholder(Modifier.width(deviceDp(190.dp, 160.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun radioPageSubtitle(state: RadioUiState): String = when {
    state.submittedQuery.isNotBlank() -> state.submittedQuery
    state.selectedFacet != null -> radioFacetLabel(state.section, state.selectedFacet)
    else -> radioSectionLabel(state.section)
}

@Composable
private fun radioSectionLabel(section: RadioSection): String = stringResource(
    when (section) {
        RadioSection.POPULAR -> R.string.radio_popular
        RadioSection.FAVORITES -> R.string.radio_favorites
        RadioSection.RECENT -> R.string.radio_recent
        RadioSection.GENRES -> R.string.radio_genres
        RadioSection.COUNTRIES -> R.string.radio_countries
        RadioSection.LANGUAGES -> R.string.radio_languages
    },
)

@Composable
private fun radioFacetLabel(section: RadioSection, facet: RadioFacet): String = when (section) {
    RadioSection.COUNTRIES -> runCatching {
        Locale.Builder()
            .setRegion(facet.id)
            .build()
            .getDisplayCountry(Locale.getDefault())
            .takeIf(String::isNotBlank)
    }.getOrNull() ?: facet.name
    RadioSection.LANGUAGES -> facet.name.replaceFirstChar { it.titlecase() }
    else -> facet.name
}

@Composable
private fun RadioFacetTile(
    section: RadioSection,
    facet: RadioFacet,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
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
                    if (focused) MaterialTheme.colorScheme.primary
                    else Color.White.copy(alpha = .08f),
                    shape,
                )
                .padding(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                radioFacetLabel(section, facet),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.radio_station_count, facet.stationCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal const val RADIO_GRID_END_PLACEHOLDERS = 2

internal fun radioGridEndPlaceholderKey(index: Int) = "radio-grid-more:$index"

@Composable
private fun RadioEndPlaceholder(modifier: Modifier = Modifier) {
    val pulse = if (com.ambient.tv.ui.performance.LocalPerformanceProfile.current.reducedEffects) null
        else com.ambient.tv.ui.common.rememberSharedPulse(com.ambient.tv.ui.common.SharedArtworkPulse)
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .58f), shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .skeletonPulseAlpha(pulse)
                .background(fill, RoundedCornerShape(8.dp)),
        )
        Box(
            Modifier
                .fillMaxWidth(.78f)
                .align(Alignment.CenterHorizontally)
                .padding(top = 2.dp)
                .aspectRatio(8f)
                .skeletonPulseAlpha(pulse)
                .background(fill, RoundedCornerShape(3.dp)),
        )
        Box(
            Modifier
                .fillMaxWidth(.5f)
                .align(Alignment.CenterHorizontally)
                .aspectRatio(10f)
                .skeletonPulseAlpha(pulse)
                .background(fill, RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun RadioStationTile(
    station: RadioStation,
    playing: Boolean,
    loading: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.width(deviceDp(190.dp, 160.dp)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            onClick = onClick,
            colors = transparentSurfaceColors(),
            scale = noSurfaceScale(),
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            modifier = Modifier
                .fillMaxWidth()
                .mediaFocusMotion(focused)
                .activateOnTvKey(onClick = onClick)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                },
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
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.DarkGray),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.DarkGray, RoundedCornerShape(8.dp)),
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
                    radioStationSubtitle(station),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        TvChip(
            text = if (favorite) stringResource(R.string.radio_remove_favorite)
            else stringResource(R.string.radio_add_favorite),
            icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            iconOnly = true,
            selected = favorite,
            onClick = onFavorite,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
