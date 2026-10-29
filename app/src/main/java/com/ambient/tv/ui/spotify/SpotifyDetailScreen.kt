package com.ambient.tv.ui.spotify

import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.companion.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.ScreensaverArtworkRegistry
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.PageUtilityBar
import com.ambient.tv.ui.common.overlayLazyScrollbar

@Composable
fun SpotifyDetailScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onNowPlaying: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    viewModel: SpotifyDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    com.ambient.tv.ui.common.RetryOfflineBrowse(viewModel::retryOffline)
    com.ambient.tv.ui.common.RefreshOnReconnect(setOf("spotify"), viewModel::load)
    val current = playbackIndicator(CompanionMedia("spotify:${viewModel.type}", viewModel.id))
    val openCurrent = LocalOpenActivePlayback.current
    val playLabel = stringResource(if (current != null) R.string.playback_open else R.string.spotify_play)
    val playContext = { if (current != null) openCurrent(current) else { viewModel.playContext(onNowPlaying) } }
    if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
        Box(Modifier.fillMaxSize()) {
        LazyColumn(allowUpExit = true, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 80.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("header") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.artworkUrl?.let { ArtworkImage(it, state.title, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) }
                    if (state.loading) androidx.compose.material3.CircularProgressIndicator()
                    com.ambient.tv.ui.common.SystemBadge(com.ambient.tv.ui.common.MediaSystem.SPOTIFY)
                    PlaybackStatusSection(current)
                    if (current != null && !state.offline) com.ambient.tv.companion.CompanionChooseDeviceAction { viewModel.playContext(onNowPlaying) }
                    Text(state.title, style = MaterialTheme.typography.headlineMedium)
                    if (state.offline) Text(stringResource(R.string.browse_offline))
                    state.subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        TvActionButton(stringResource(R.string.home_retry), viewModel::load, fillWidth = true)
                    }
                    if (!state.offline && state.error == null) TvActionButton(playLabel, playContext, enabled = !state.loading, fillWidth = true)
                }
            }
            if (state.tracks.isNotEmpty()) item("tracks-heading") { Text(stringResource(R.string.search_tracks), style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(state.tracks, key = { index, track -> "track:$index:${track.id}" }) { index, track ->
                val playingTrack = playbackIndicator(CompanionMedia("spotify:track", track.id))
                PlaybackBadge(playingTrack)
                    if (state.offline) Text("${index + 1}. ${track.name}") else TvActionButton("${index + 1}. ${track.name} · ${track.artists.joinToString(", ") { it.name }}", { if (playingTrack != null) openCurrent(playingTrack) else { viewModel.playTrack(track, onNowPlaying) } }, fillWidth = true)
            }
            if (state.albums.isNotEmpty()) item("albums-heading") { Text(stringResource(R.string.nav_spotify_albums), style = MaterialTheme.typography.titleMedium) }
            items(state.albums, key = { "album:${it.id}" }) { album -> TvActionButton(album.name, { onOpenAlbum(album.id) }, fillWidth = true) }
        }
        PageUtilityBar(onBack, onOpenSettings, leftNavigatesBack = false, modifier = Modifier.align(Alignment.TopCenter))
        }
        return
    }
    val primaryFocus = remember { FocusRequester() }
    // Focus changes are frequent. Keep the last key in a non-snapshot holder so moving through a
    // long track list does not recompose the complete screen and schedule another focus request.
    val focusMemory = androidx.compose.runtime.saveable.rememberSaveable(
        viewModel.type,
        viewModel.id,
        saver = SpotifyFocusMemorySaver,
    ) { SpotifyFocusMemory() }
    var restoreFocusPending by remember(viewModel.type, viewModel.id) { mutableStateOf(focusMemory.key != null) }
    var initialFocusRequested by remember(viewModel.type, viewModel.id) { mutableStateOf(false) }
    val restoreFocusKey = focusMemory.key.takeIf { restoreFocusPending }
    fun rememberFocused(key: String?) {
        focusMemory.key = key
        initialFocusRequested = true
        if (restoreFocusPending) restoreFocusPending = false
    }
    LaunchedEffect(state.loading, state.offline, state.error, restoreFocusPending) {
        if (initialFocusRequested || state.loading || (state.offline && state.error == null) || restoreFocusPending) return@LaunchedEffect
        withFrameNanos { }
        initialFocusRequested = runCatching { primaryFocus.requestFocus() }.getOrDefault(false)
    }
    LaunchedEffect(state.artworkUrl) { state.artworkUrl?.let { ScreensaverArtworkRegistry.offer(it) } }
    Box(Modifier.fillMaxSize()) {
    Row(
        Modifier.fillMaxSize().padding(start = screenGutter(), end = screenGutter(), top = screenGutter() + 56.dp, bottom = screenGutter()),
        horizontalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.loading) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.58f), RoundedCornerShape(16.dp)))
                Box(Modifier.fillMaxWidth(.82f).height(28.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.64f), RoundedCornerShape(999.dp)))
                Box(Modifier.fillMaxWidth(.56f).height(16.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.44f), RoundedCornerShape(999.dp)))
                Box(Modifier.fillMaxWidth().height(46.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.52f), RoundedCornerShape(12.dp)))
            } else {
                state.artworkUrl?.let { ArtworkImage(it, state.title, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) }
                com.ambient.tv.ui.common.SystemBadge(com.ambient.tv.ui.common.MediaSystem.SPOTIFY)
                PlaybackStatusSection(current)
                    Text(state.title, style = MaterialTheme.typography.headlineMedium)
                    if (state.offline) Text(stringResource(R.string.browse_offline))
                state.subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!state.offline && state.error == null) TvActionButton(
                    playLabel,
                    onClick = playContext,
                    enabled = state.error == null,
                    fillWidth = true,
                    modifier = Modifier.focusRequester(primaryFocus).onFocusChanged { if (it.isFocused) rememberFocused(null) },
                )
            }
        }
        val trackListState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(
            wrapVerticalFocus = false,
            state = trackListState,
            modifier = Modifier.weight(1f).fillMaxHeight().overlayLazyScrollbar(trackListState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (state.loading) {
                items(10) {
                    Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(36.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f), RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f).padding(top=6.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.fillMaxWidth(.64f).height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.58f), RoundedCornerShape(999.dp)))
                            Box(Modifier.fillMaxWidth(.36f).height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.40f), RoundedCornerShape(999.dp)))
                        }
                    }
                }
            } else {
                state.error?.let { error -> item("error") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TvActionButton(
                            stringResource(R.string.home_retry),
                            viewModel::load,
                            modifier = Modifier.focusRequester(primaryFocus).onFocusChanged { if (it.isFocused) rememberFocused(null) },
                        )
                    }
                } }
                if (state.tracks.isNotEmpty()) item("tracks-heading") { Text(stringResource(R.string.search_tracks), style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(state.tracks, key = { index, track -> "track:$index:${track.id}" }) { index, track ->
                    val playingTrack = playbackIndicator(CompanionMedia("spotify:track", track.id))
                    val focusKey = "track:${track.id}"
                    val requester = remember(track.id) { FocusRequester() }
                    RestoreSpotifyFocus(restoreFocusKey == focusKey, requester)
                    PlaybackBadge(playingTrack)
                        if (state.offline) Text("${index + 1}. ${track.name}") else TvActionButton(text = "${index + 1}. ${track.name}  •  ${track.artists.joinToString(", ") { it.name }}", onClick = { if (playingTrack != null) openCurrent(playingTrack) else { viewModel.playTrack(track, onNowPlaying) } }, modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { if (it.isFocused) rememberFocused(focusKey) }, fillWidth = true)
                }
                if (state.albums.isNotEmpty()) item("albums-heading") { Text(stringResource(R.string.nav_spotify_albums), style = MaterialTheme.typography.titleMedium) }
                items(state.albums, key = { "album:${it.id}" }) { album ->
                    val focusKey = "album:${album.id}"
                    val requester = remember(album.id) { FocusRequester() }
                    RestoreSpotifyFocus(restoreFocusKey == focusKey, requester)
                    TvActionButton(album.name, { onOpenAlbum(album.id) }, Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { if (it.isFocused) rememberFocused(focusKey) }, fillWidth = true)
                }
            }
        }
    }
    PageUtilityBar(
        onBack = onBack,
        onSettings = {
            rememberFocused(null)
            onOpenSettings()
        },
        onDown = if (!state.loading && (!state.offline || state.error != null)) ({
            rememberFocused(null)
            runCatching { primaryFocus.requestFocus() }
        }) else null,
        requestInitialFocus = state.loading || (state.offline && state.error == null),
        leftNavigatesBack = false,
        modifier = Modifier.align(Alignment.TopCenter),
    )
    }
}

private class SpotifyFocusMemory(var key: String? = null)

private val SpotifyFocusMemorySaver = androidx.compose.runtime.saveable.Saver<SpotifyFocusMemory, String>(
    save = { it.key.orEmpty() },
    restore = { SpotifyFocusMemory(it.takeIf(String::isNotEmpty)) },
)

@Composable
private fun RestoreSpotifyFocus(requested: Boolean, requester: FocusRequester) {
    LaunchedEffect(requested) {
        if (!requested) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
}
