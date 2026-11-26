package com.ambient.tv.ui.moonlight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.moonlight.MoonlightArtwork
import com.ambient.tv.ui.common.*
import com.ambient.tv.ui.games.GameCoverArtwork
import com.ambient.tv.ui.games.gameCoverFrameSize
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.screenGutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Same hero layout and navigation as RomM/local games; never implicitly quit a remote app. */
@Composable
fun MoonlightGameDetailScreen(
    hostId: String,
    appId: Int,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onOpenSettings: () -> Unit,
    model: MoonlightLibraryViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsState()
    val game = model.item(hostId, appId)
    var showQuitConfirmation by remember { mutableStateOf(false) }
    val primaryFocus = remember { FocusRequester() }
    val handheld = LocalHandheldUi.current
    val listState = rememberLazyListState()
    MoonlightRefreshWhileVisible(model)
    LaunchedEffect(game?.key, state.onlineHosts) { game?.let { model.prioritizeArtwork(it) } }
    BackHandler { if (showQuitConfirmation) showQuitConfirmation = false else onBack() }
    val online = hostId in state.onlineHosts && hostId in state.pairedHosts
    val artwork by produceState<MoonlightArtwork?>(null, game?.key,
        state.hostIdentities[hostId], state.artworkRevisions["$hostId:$appId"] ?: 0L) {
        value = withContext(Dispatchers.IO) { game?.let(model::artworkInfo) }
    }
    LaunchedEffect(game?.key, handheld) {
        if (game != null && !handheld) repeat(4) {
            withFrameNanos { }
            if (runCatching { primaryFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Box(Modifier.fillMaxSize()) {
        PageBackground(Modifier.fillMaxSize())
        when {
            !state.ready && game == null -> StatePanel(
                title = stringResource(R.string.moonlight_title),
                body = stringResource(R.string.player_loading), modifier = Modifier.fillMaxSize())
            game == null -> StatePanel(
                title = stringResource(R.string.moonlight_title),
                body = stringResource(R.string.game_detail_missing),
                actionLabel = stringResource(R.string.back), onAction = onBack, modifier = Modifier.fillMaxSize())
            else -> TvLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().overlayLazyScrollbar(listState),
                contentPadding = PaddingValues(start = screenGutter(), end = screenGutter(), top = 94.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                wrapVerticalFocus = false,
            ) {
                item("hero") {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val compact = maxWidth < 600.dp
                        val size = gameCoverFrameSize(
                            if (compact) minOf(maxWidth, 340.dp) else minOf(maxWidth * .42f, 440.dp),
                            if (compact) 220.dp else 315.dp,
                            artwork?.aspectRatio ?: game.coverAspectRatio,
                        )
                        val cover: @Composable () -> Unit = {
                            GameCoverArtwork(cover = artwork?.file, title = game.title, systemId = game.systemId,
                                modifier = Modifier.size(size), systemIcon = game.artworkIcon)
                        }
                        val information: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                                DetailEyebrow(systemName(MediaSystem.MOONLIGHT) + " · " + game.systemLabel)
                                DetailTitleRow(game.title, logoUrl = null, system = null)
                                if (game.app.hdrSupported) DetailPrimaryMetaRow(listOf("HDR"), item = null)
                                if (!online) Text(stringResource(R.string.moonlight_offline_detail),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val canWake = !online && game.host.macAddress.isNotBlank() && hostId in state.pairedHosts
                                DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    TvActionButton(stringResource(R.string.moonlight_stream_play), onPlay, enabled = online,
                                        icon = Icons.Filled.PlayArrow,
                                        modifier = if (online) Modifier.focusRequester(primaryFocus).browseFocusTarget("moonlight-play") else Modifier)
                                    if (canWake) TvActionButton(stringResource(R.string.moonlight_wake_pc),
                                        { model.wakeHost(hostId) }, loading = hostId in state.wakingHosts,
                                        modifier = Modifier.focusRequester(primaryFocus).browseFocusTarget("moonlight-wake"))
                                    TvActionButton(stringResource(if (game.favorite) R.string.moonlight_remove_favorite else R.string.moonlight_add_favorite),
                                        { model.favorite(game) }, selected = game.favorite,
                                        icon = if (game.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder)
                                    TvActionButton(stringResource(R.string.moonlight_refresh),
                                        { model.load(force = true, onlyHost = hostId) },
                                        icon = Icons.Filled.Refresh, loading = hostId in state.loadingHosts,
                                        modifier = if (!online && !canWake) Modifier.focusRequester(primaryFocus).browseFocusTarget("moonlight-refresh") else Modifier)
                                }
                                if (online) TvActionButton(stringResource(R.string.moonlight_quit_remote),
                                    { showQuitConfirmation = true }, loading = hostId in state.quittingHosts)
                            }
                        }
                        if (compact) Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { cover() }
                            information()
                        } else Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
                            cover()
                            Column(Modifier.weight(1f)) { information() }
                        }
                    }
                }
                if (state.errors[hostId] == "quit_failed") item("error") {
                    Text(stringResource(R.string.moonlight_remote_quit_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        PageUtilityBar(onBack = onBack, onSettings = onOpenSettings,
            onDown = if (game != null) ({ runCatching { primaryFocus.requestFocus() } }) else null,
            leftNavigatesBack = false, modifier = Modifier.align(Alignment.TopCenter))
    }
    if (showQuitConfirmation) AppDialog(
        onDismiss = { showQuitConfirmation = false },
        title = stringResource(R.string.moonlight_quit_confirm_title),
        hint = stringResource(R.string.moonlight_quit_confirm_hint),
        footer = {
            TvActionButton(stringResource(R.string.files_cancel), { showQuitConfirmation = false })
            TvActionButton(stringResource(R.string.moonlight_quit_remote), {
                showQuitConfirmation = false
                model.quitRemote(hostId, appId)
            })
        },
        content = null,
    )
}
