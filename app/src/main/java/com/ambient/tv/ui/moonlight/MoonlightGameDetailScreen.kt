package com.ambient.tv.ui.moonlight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.common.pageHeaderTop

/**
 * GameStream item details, distinct from Libretro game's detail page. A disconnected host
 * remains browseable but cannot be launched. Disconnect and remote quit are separate actions.
 */
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
    MoonlightRefreshWhileVisible(model)
    LaunchedEffect(hostId, appId, game?.key, state.onlineHosts) {
        game?.let { model.prioritizeArtwork(it) }
    }
    BackHandler { if (showQuitConfirmation) showQuitConfirmation = false else onBack() }
    val online = hostId in state.onlineHosts && hostId in state.pairedHosts
    Column(
        Modifier.fillMaxSize().padding(start = screenGutter(), end = screenGutter(),
            top = pageHeaderTop(screenGutter()), bottom = screenGutter()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (game == null) {
            Text(stringResource(R.string.moonlight_library_unconfigured),
                style = MaterialTheme.typography.bodyLarge)
            TvActionButton(stringResource(R.string.back), onBack)
            return@Column
        }
        Text(game.app.title, style = MaterialTheme.typography.headlineLarge)
        Text(game.host.name.ifBlank { game.host.host } +
            if (game.app.hdrSupported) " · HDR" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val artwork by produceState<java.io.File?>(null, game.key, state.artworkVersion) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { model.artwork(game) }
        }
        if (artwork != null) {
            AsyncImage(model = artwork, contentDescription = game.app.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(0.7f).aspectRatio(16f / 9f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)))
        } else {
            Box(Modifier.fillMaxWidth(0.7f).aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.moonlight_no_artwork))
            }
        }
        if (!online) {
            Text(stringResource(R.string.moonlight_offline_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (game.host.macAddress.isNotBlank()) {
                TvActionButton(stringResource(R.string.moonlight_wake_pc),
                    { model.wakeHost(hostId) }, loading = hostId in state.wakingHosts)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvActionButton(stringResource(R.string.moonlight_stream_play), onPlay, enabled = online)
            TvActionButton(stringResource(if (state.favorites.contains("$hostId:$appId"))
                R.string.moonlight_remove_favorite else R.string.moonlight_add_favorite),
                { model.favorite(game) })
            TvActionButton(stringResource(R.string.moonlight_refresh), { model.load(force = true) })
            TvActionButton(stringResource(R.string.moonlight_open_settings), onOpenSettings)
        }
        if (state.errors[hostId] == "quit_failed") {
            Text(stringResource(R.string.moonlight_remote_quit_failed), color = MaterialTheme.colorScheme.error)
        }
        // Quitting the game on the host is irreversible; explicit confirmation prevents
        // accidentally killing a running PC process when users merely wanted to disconnect.
        if (online) {
            TvActionButton(stringResource(R.string.moonlight_quit_remote), {
                showQuitConfirmation = true
            }, loading = hostId in state.quittingHosts)
        }
    }
    if (showQuitConfirmation) {
        AppDialog(
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
}
