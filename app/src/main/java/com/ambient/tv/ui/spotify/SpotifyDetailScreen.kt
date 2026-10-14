package com.ambient.tv.ui.spotify

import com.ambient.tv.ui.common.leftKeyAction
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.ambient.tv.ui.common.overlayLazyScrollbar

@Composable
fun SpotifyDetailScreen(
    onBack: () -> Unit,
    onNowPlaying: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    viewModel: SpotifyDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    com.ambient.tv.ui.common.RetryOfflineBrowse(viewModel::retryOffline)
    val current = playbackIndicator(CompanionMedia("spotify:${viewModel.type}", viewModel.id))
    val openCurrent = LocalOpenActivePlayback.current
    val playLabel = stringResource(if (current != null) R.string.playback_open else R.string.spotify_play)
    val playContext = { if (current != null) openCurrent(current) else { viewModel.playContext(onNowPlaying) } }
    if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
        LazyColumn(allowUpExit = true, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("back") { com.ambient.tv.ui.common.TvChip(stringResource(R.string.back), icon = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = onBack) }
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
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!state.offline) TvActionButton(playLabel, playContext, enabled = !state.loading && state.error == null, fillWidth = true)
                }
            }
            itemsIndexed(state.tracks, key = { index, track -> "track:$index:${track.id}" }) { index, track ->
                val playingTrack = playbackIndicator(CompanionMedia("spotify:track", track.id))
                PlaybackBadge(playingTrack)
                    if (state.offline) Text("${index + 1}. ${track.name}") else TvActionButton("${index + 1}. ${track.name} · ${track.artists.joinToString(", ") { it.name }}", { if (playingTrack != null) openCurrent(playingTrack) else { viewModel.playTrack(track, onNowPlaying) } }, fillWidth = true)
            }
            items(state.albums, key = { "album:${it.id}" }) { album -> TvActionButton(album.name, { onOpenAlbum(album.id) }, fillWidth = true) }
        }
        return
    }
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { backFocus.requestFocus() }
    }
    LaunchedEffect(state.artworkUrl) { state.artworkUrl?.let { ScreensaverArtworkRegistry.offer(it) } }
    Row(Modifier.fillMaxSize().padding(screenGutter()), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
        Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            com.ambient.tv.ui.common.TvChip(stringResource(R.string.back), modifier = Modifier.focusRequester(backFocus).leftKeyAction(onBack), icon = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = onBack)
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
                if (!state.offline) TvActionButton(playLabel, onClick = playContext, enabled = state.error == null, fillWidth = true)
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
                state.error?.let { error -> item("error") { Text(error, color = MaterialTheme.colorScheme.error) } }
                itemsIndexed(state.tracks, key = { index, track -> "track:$index:${track.id}" }) { index, track ->
                    val playingTrack = playbackIndicator(CompanionMedia("spotify:track", track.id))
                    PlaybackBadge(playingTrack)
                        if (state.offline) Text("${index + 1}. ${track.name}") else TvActionButton(text = "${index + 1}. ${track.name}  •  ${track.artists.joinToString(", ") { it.name }}", onClick = { if (playingTrack != null) openCurrent(playingTrack) else { viewModel.playTrack(track, onNowPlaying) } }, modifier = Modifier.fillMaxWidth(), fillWidth = true)
                }
                items(state.albums, key = { "album:${it.id}" }) { album ->
                    TvActionButton(album.name, { onOpenAlbum(album.id) }, Modifier.fillMaxWidth(), fillWidth = true)
                }
            }
        }
    }
}
