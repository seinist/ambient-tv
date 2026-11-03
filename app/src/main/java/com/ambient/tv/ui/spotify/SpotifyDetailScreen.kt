package com.ambient.tv.ui.spotify

import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.companion.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.spotify.SpotifyAlbum
import com.ambient.tv.spotify.SpotifyTrack
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.CenteredMarqueeTitle
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.PageUtilityBar
import com.ambient.tv.ui.common.ScreensaverArtworkRegistry
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.overlayLazyScrollbar
import com.ambient.tv.ui.common.transparentSurfaceColors

/**
 * Spotify album, playlist and artist pages in the shared detail look: an atmospheric backdrop
 * (a wide artist image when one exists, else the blurred cover), the hero with cover, title,
 * metadata and Play, then the tracks as list rows and an artist's albums as cover cards.
 */
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
    com.ambient.tv.ui.common.RefreshOnEnterAndReconnect(setOf("spotify"), viewModel::load)
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val current = playbackIndicator(CompanionMedia("spotify:${viewModel.type}", viewModel.id))
    val openCurrent = LocalOpenActivePlayback.current
    val playLabel = stringResource(if (current != null) R.string.playback_open else R.string.spotify_play)
    val playContext = { if (current != null) openCurrent(current) else { viewModel.playContext(onNowPlaying) } }

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
        if (handheld || initialFocusRequested || state.loading || (state.offline && state.error == null) || restoreFocusPending) return@LaunchedEffect
        withFrameNanos { }
        initialFocusRequested = runCatching { primaryFocus.requestFocus() }.getOrDefault(false)
    }
    LaunchedEffect(state.artworkUrl) { state.artworkUrl?.let { ScreensaverArtworkRegistry.offer(it) } }

    Box(Modifier.fillMaxSize()) {
        SpotifyDetailBackdrop(state)
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(
            allowUpExit = true,
            wrapVerticalFocus = false,
            state = listState,
            modifier = Modifier.fillMaxSize().overlayLazyScrollbar(listState),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("hero") {
                SpotifyDetailHero(
                    state = state,
                    type = viewModel.type,
                    current = current,
                    playLabel = playLabel,
                    onPlay = playContext,
                    onRetry = viewModel::load,
                    onChooseDevice = { viewModel.playContext(onNowPlaying) },
                    primaryFocus = primaryFocus,
                    onActionFocused = { rememberFocused(null) },
                )
            }
            if (state.loading) {
                items(8, key = { "skeleton:$it" }) { SpotifyTrackRowSkeleton() }
            } else {
                if (state.tracks.isNotEmpty()) item("tracks-heading") {
                    SpotifySectionHeading(stringResource(if (viewModel.type == "artist") R.string.spotify_top_tracks else R.string.search_tracks))
                }
                itemsIndexed(state.tracks, key = { index, track -> "track:$index:${track.id}" }) { index, track ->
                    val playingTrack = playbackIndicator(CompanionMedia("spotify:track", track.id))
                    val focusKey = "track:${track.id}"
                    val requester = remember(track.id) { FocusRequester() }
                    RestoreSpotifyFocus(restoreFocusKey == focusKey, requester)
                    SpotifyTrackRow(
                        index = index,
                        track = track,
                        playing = playingTrack,
                        showAlbum = viewModel.type != "album",
                        enabled = !state.offline,
                        onClick = { if (playingTrack != null) openCurrent(playingTrack) else viewModel.playTrack(track, onNowPlaying) },
                        modifier = Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) rememberFocused(focusKey) },
                    )
                }
                if (state.albums.isNotEmpty()) {
                    item("albums-heading") { SpotifySectionHeading(stringResource(R.string.nav_spotify_albums)) }
                    item("albums") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = screenGutter()),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(state.albums, key = { "album:${it.id}" }) { album ->
                                val focusKey = "album:${album.id}"
                                val requester = remember(album.id) { FocusRequester() }
                                RestoreSpotifyFocus(restoreFocusKey == focusKey, requester)
                                SpotifyAlbumCard(
                                    album = album,
                                    onClick = { onOpenAlbum(album.id) },
                                    modifier = Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) rememberFocused(focusKey) },
                                )
                            }
                        }
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
            onDown = if (!handheld && !state.loading && (!state.offline || state.error != null)) ({
                rememberFocused(null)
                runCatching { primaryFocus.requestFocus() }
            }) else null,
            requestInitialFocus = !handheld && (state.loading || (state.offline && state.error == null)),
            leftNavigatesBack = false,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/** Same layering as the shared detail page: image, vertical and horizontal fades, accent wash. */
@Composable
private fun SpotifyDetailBackdrop(state: SpotifyDetailUiState) {
    val wide = state.backdropUrl
    val model = wide ?: state.artworkUrl ?: return
    ArtworkImage(
        model = model,
        title = state.title,
        contentScale = ContentScale.Crop,
        placeholder = false,
        fallbackModels = listOfNotNull(state.artworkUrl).filter { it != model },
        // A square cover stretched to the screen only works as a soft colour field.
        modifier = Modifier.fillMaxSize().then(if (wide == null) Modifier.blur(48.dp) else Modifier),
    )
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Black.copy(alpha = if (wide == null) 0.30f else 0.12f),
        0.42f to background.copy(alpha = 0.66f),
        1f to background,
    )))
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
        background.copy(alpha = 0.96f), background.copy(alpha = 0.62f), Color.Transparent,
    ))))
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
        0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.095f), 0.38f to Color.Transparent,
    )))
}

@Composable
private fun SpotifyDetailHero(
    state: SpotifyDetailUiState,
    type: String,
    current: PlaybackIndicator?,
    playLabel: String,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onChooseDevice: () -> Unit,
    primaryFocus: FocusRequester,
    onActionFocused: () -> Unit,
) {
    com.ambient.tv.ui.mobile.DeviceActionRow(
        stack = com.ambient.tv.ui.mobile.LocalHandheldUi.current,
        modifier = Modifier.fillMaxWidth().padding(
            start = screenGutter(), end = screenGutter(),
            top = com.ambient.tv.ui.common.pageHeaderTop(18.dp), bottom = 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val coverWidth = deviceDp(210.dp, 144.dp)
        if (state.loading && state.artworkUrl == null) {
            Box(Modifier.width(coverWidth).aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .58f), RoundedCornerShape(14.dp)))
        } else state.artworkUrl?.let { cover ->
            com.ambient.tv.ui.common.DetailArtworkFrame(model = cover, title = state.title, aspectRatio = 1f, width = coverWidth)
        }
        Column(Modifier.weight(1f, fill = false).widthIn(max = 860.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            com.ambient.tv.ui.common.DetailEyebrow(spotifyEyebrow(type, state))
            com.ambient.tv.ui.common.DetailTitleRow(title = state.title, logoUrl = null, system = MediaSystem.SPOTIFY)
            com.ambient.tv.ui.common.DetailPrimaryMetaRow(spotifyPrimaryMeta(state), item = null)
            PlaybackStatusSection(current)
            if (state.offline) Text(stringResource(R.string.browse_offline), color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val focus = Modifier.focusRequester(primaryFocus).onFocusChanged { if (it.isFocused) onActionFocused() }
                when {
                    state.error != null -> TvActionButton(stringResource(R.string.home_retry), onRetry, modifier = focus)
                    !state.offline -> TvActionButton(playLabel, onPlay, modifier = focus, enabled = !state.loading,
                        icon = Icons.Default.PlayArrow)
                }
            }
            if (current != null && !state.offline) CompanionChooseDeviceAction(onChooseDevice)
        }
    }
}

/** "Album · Artist", "Playlist · Owner" or "Artist". */
@Composable
private fun spotifyEyebrow(type: String, state: SpotifyDetailUiState): String {
    val kind = when (type) {
        "album" -> stringResource(R.string.spotify_kind_album)
        "playlist" -> stringResource(R.string.spotify_kind_playlist)
        "artist" -> stringResource(R.string.spotify_kind_artist)
        else -> ""
    }
    return listOfNotNull(kind.takeIf(String::isNotBlank), state.subtitle?.takeIf(String::isNotBlank)).joinToString(" · ")
}

/** Year, track count and total running time. */
@Composable
private fun spotifyPrimaryMeta(state: SpotifyDetailUiState): List<String> = buildList {
    state.releaseDate?.take(4)?.takeIf { it.length == 4 }?.let(::add)
    if (state.tracks.isNotEmpty() && state.albums.isEmpty()) {
        add(androidx.compose.ui.res.pluralStringResource(R.plurals.spotify_track_count, state.tracks.size, state.tracks.size))
        com.ambient.tv.ui.common.formatDuration(state.tracks.sumOf { it.durationMs }.takeIf { it > 0 })?.let(::add)
    }
}

@Composable
private fun SpotifySectionHeading(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = screenGutter(), end = screenGutter(), top = 12.dp, bottom = 4.dp),
    )
}

/** One track: number (or the now-playing mark), title and artists, explicit tag, duration. */
@Composable
private fun SpotifyTrackRow(
    index: Int,
    track: SpotifyTrack,
    playing: PlaybackIndicator?,
    showAlbum: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        enabled = enabled,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        modifier = modifier
            .padding(horizontal = screenGutter())
            .fillMaxWidth()
            .widthIn(max = 1100.dp)
            .activateOnTvKey(onClick = onClick)
            .onFocusChanged { focused = it.isFocused },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (focused) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.42f), shape)
                .border(2.dp, if (focused) accent else Color.Transparent, shape)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                if (playing != null) PlaybackBadge(playing)
                else Text("${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            if (showAlbum) track.album?.images?.lastOrNull()?.url?.let { thumb ->
                ArtworkImage(thumb, track.name, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    track.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (playing != null) accent else MaterialTheme.colorScheme.onSurface,
                )
                val artists = track.artists.joinToString(", ") { it.name }
                val secondary = listOfNotNull(artists.takeIf(String::isNotBlank), track.album?.name?.takeIf { showAlbum })
                if (secondary.isNotEmpty()) Text(
                    secondary.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (track.explicit) Text(
                "E", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
            com.ambient.tv.ui.common.formatDuration(track.durationMs.takeIf { it > 0 })?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SpotifyTrackRowSkeleton() {
    Row(
        Modifier.padding(horizontal = screenGutter()).fillMaxWidth().widthIn(max = 1100.dp).height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(30.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f), RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(.64f).height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .58f), RoundedCornerShape(999.dp)))
            Box(Modifier.fillMaxWidth(.36f).height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .40f), RoundedCornerShape(999.dp)))
        }
    }
}

/** An artist's album as a cover card, styled like the Spotify browse tiles. */
@Composable
private fun SpotifyAlbumCard(album: SpotifyAlbum, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        modifier = modifier.width(deviceDp(170.dp, 140.dp)).mediaFocusMotion(focused)
            .activateOnTvKey(onClick = onClick).onFocusChanged { focused = it.isFocused },
    ) {
        Column(
            Modifier
                .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), shape)
                .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f), shape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val cover = album.images.firstOrNull()?.url
            if (cover != null) ArtworkImage(cover, album.name, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.DarkGray, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(Icons.Default.MusicNote, null)
            }
            CenteredMarqueeTitle(album.name, focused = focused)
            album.releaseDate?.take(4)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private class SpotifyFocusMemory(var key: String? = null)

private val SpotifyFocusMemorySaver = androidx.compose.runtime.saveable.Saver<SpotifyFocusMemory, String>(
    save = { it.key.orEmpty() },
    restore = { SpotifyFocusMemory(it.takeIf(String::isNotBlank)) },
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
