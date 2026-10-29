package com.ambient.tv.ui.spotify

import com.ambient.tv.ui.theme.screenGutter
import androidx.activity.compose.BackHandler
import com.ambient.tv.ui.mobile.deviceDp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import com.ambient.tv.ui.common.TvLazyVerticalGrid as LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.ambient.tv.ui.common.CenteredMarqueeTitle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.spotify.SpotifyBrowseItem
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.ScreensaverArtworkRegistry
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.pageContentUp
import androidx.compose.foundation.focusGroup
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.overlayLazyGridScrollbar
import com.ambient.tv.ui.common.resolveContentFocusTarget
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import kotlinx.coroutines.delay

@Composable
fun SpotifyHomeScreen(
    onOpen: (SpotifyBrowseItem) -> Unit,
    onNowPlaying: () -> Unit,
    initialSectionKey: String? = null,
    onBack: (() -> Unit)? = null,
    viewModel: SpotifyHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    com.ambient.tv.ui.common.RefreshOnReconnect(setOf("spotify"), viewModel::refresh)
    val expectedSectionKey = initialSectionKey ?: "home"
    val focusStateKey = remember(expectedSectionKey) { "spotify:$expectedSectionKey" }
    val focusItemId = remember(focusStateKey, state.items) {
        resolveContentFocusTarget(
            savedItemId = viewModel.focusedItemId(focusStateKey),
            savedRowId = null,
            rows = listOf(null to state.items.map(::spotifyFocusId)),
        ).itemId
    }
    ReportSidebarContentReady(!state.loading)
    if (onBack != null) {
        BackHandler { onBack() }
    }
    val signInFocusRequester = remember { FocusRequester() }
    val nowPlayingFocusRequester = remember { FocusRequester() }
    val firstItemFocusRequester = remember { FocusRequester() }
    var initialContentFocusPending by remember(expectedSectionKey) { mutableStateOf(true) }
    LaunchedEffect(initialSectionKey) { initialSectionKey?.let(viewModel::loadSection) }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
        initialFirstVisibleItemIndex = state.items.indexOfFirst { spotifyFocusId(it) == focusItemId }.coerceAtLeast(0),
    )
    LaunchedEffect(state.loading, state.items, state.sectionKey, state.webAuthorized, initialContentFocusPending, focusItemId) {
        if (!initialContentFocusPending || state.sectionKey != expectedSectionKey || state.loading) return@LaunchedEffect
        val failed = state.error != null || state.errorRes != null
        delay(140)
        // The grid state was created while loading (no items yet): bring the saved tile into view first.
        val savedIndex = state.items.indexOfFirst { spotifyFocusId(it) == focusItemId }
        if (!failed && savedIndex > 0) runCatching { gridState.scrollToItem(savedIndex) }
        val requester = when {
            // With an error the header still offers sign-in or Now playing.
            failed -> if (!state.webAuthorized) signInFocusRequester else nowPlayingFocusRequester
            state.items.isNotEmpty() -> firstItemFocusRequester
            !state.webAuthorized -> signInFocusRequester
            else -> nowPlayingFocusRequester
        }
        repeat(4) {
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) {
                initialContentFocusPending = false
                return@LaunchedEffect
            }
            delay(32)
        }
    }
    val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()
    Column(Modifier.fillMaxSize().padding(start = screenGutter(), end = screenGutter(), bottom = screenGutter(), top = com.ambient.tv.ui.common.pageHeaderTop(screenGutter())), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageHeader(focusChain = focusChain, actions = {
            TvChip(
                text = when {
                    state.authBusy -> stringResource(R.string.action_signing_in)
                    state.webAuthorized -> stringResource(R.string.action_sign_out)
                    else -> stringResource(R.string.action_sign_in)
                },
                modifier = Modifier.focusRequester(signInFocusRequester),
                icon = if (state.webAuthorized) Icons.AutoMirrored.Filled.Logout else Icons.AutoMirrored.Filled.Login,
                loading = state.authBusy,
                iconOnly = true,
                onClick = if (state.webAuthorized) viewModel::logout else viewModel::login,
            )
            TvChip(stringResource(R.string.action_now_playing), modifier = Modifier.focusRequester(nowPlayingFocusRequester), icon = Icons.Default.PlayArrow, iconOnly = true, onClick = onNowPlaying)
        }) {
            com.ambient.tv.ui.common.SystemPageTitle(com.ambient.tv.ui.common.MediaSystem.SPOTIFY)
            val webStatus = if (state.webAuthorized) stringResource(R.string.spotify_web_authorized, state.accountName ?: stringResource(R.string.spotify_title)) else stringResource(R.string.spotify_web_unauthorized)
            val connectStatus = if (state.connected) stringResource(R.string.spotify_connect_connected) else stringResource(R.string.spotify_connect_hint)
            Text("$webStatus  •  $connectStatus", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!state.nativeAvailable) {
            Text(stringResource(R.string.spotify_native_missing), color = MaterialTheme.colorScheme.error)
        }
        state.errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when {
            state.loading -> SpotifyHomeSkeleton()
            state.items.isEmpty() && state.error == null && state.errorRes == null -> {
                com.ambient.tv.ui.common.StatePanel(
                    title = com.ambient.tv.ui.common.systemString(com.ambient.tv.ui.common.MediaSystem.SPOTIFY, R.string.spotify_empty),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> LazyVerticalGrid(columns = GridCells.Adaptive(deviceDp(190.dp, 144.dp)), modifier = Modifier.fillMaxSize().overlayLazyGridScrollbar(gridState).pageContentUp(focusChain).focusGroup(), state = gridState, contentPadding = PaddingValues(bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(state.items, key = { "${it.type}:${it.id}" }) { item ->
                    SpotifyTile(
                        item,
                        { onOpen(item) },
                        { viewModel.play(item, onNowPlaying) },
                        onFocused = { viewModel.onItemFocused(focusStateKey, item) },
                        modifier = if (spotifyFocusId(item) == focusItemId) Modifier.focusRequester(firstItemFocusRequester) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotifyTile(
    item: SpotifyBrowseItem,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(item.artworkUrl) { item.artworkUrl?.let { ScreensaverArtworkRegistry.offer(it) } }
    var focused by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(10.dp)
    val activate = if (item.type.name == "TRACK") onPlay else onOpen
    // One focusable Surface only — no nested Play button.
    Surface(
        onClick = activate,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape = cardShape),
        modifier = modifier.width(deviceDp(190.dp, 160.dp)).mediaFocusMotion(focused).activateOnTvKey(onClick = activate).onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        },
    ) {
        Column(
            Modifier
                .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), cardShape)
                .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f), cardShape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                if (item.artworkUrl != null) {
                    ArtworkImage(item.artworkUrl, item.title, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color.DarkGray), contentScale = ContentScale.Crop)
                } else Box(Modifier.fillMaxSize().background(Color.DarkGray, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { androidx.compose.material3.Icon(Icons.Default.MusicNote, null) }
                com.ambient.tv.companion.PlaybackBadge(com.ambient.tv.companion.playbackIndicator(com.ambient.tv.companion.CompanionMedia("spotify:${item.type.name.lowercase()}", item.id)), Modifier.align(Alignment.TopStart).padding(5.dp))
                if (focused) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Text(stringResource(R.string.spotify_play), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            CenteredMarqueeTitle(item.title, focused = focused)
            item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

private fun spotifyFocusId(item: SpotifyBrowseItem): String = "${item.type}:${item.id}"


@Composable
private fun SpotifyHomeSkeleton() {
    LazyVerticalGrid(columns = GridCells.Adaptive(deviceDp(190.dp, 144.dp)), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(12) {
            Column(Modifier.width(deviceDp(190.dp, 160.dp)).padding(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.62f)))
                Box(Modifier.fillMaxWidth(.76f).height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f), RoundedCornerShape(999.dp)))
                Box(Modifier.fillMaxWidth(.52f).height(11.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.42f), RoundedCornerShape(999.dp)))
                Box(Modifier.fillMaxWidth().height(44.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.48f), RoundedCornerShape(10.dp)))
            }
        }
    }
}
