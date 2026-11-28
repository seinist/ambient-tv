package com.ambient.tv.ui.romm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.games.GameCoreExperiment
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.games.library.backdropModel
import com.ambient.tv.games.library.coverAspectRatio
import com.ambient.tv.games.library.coverModel
import com.ambient.tv.romm.RommRom
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.DetailEyebrow
import com.ambient.tv.ui.common.DetailGenres
import com.ambient.tv.ui.common.DetailPrimaryMetaRow
import com.ambient.tv.ui.common.DetailSummary
import com.ambient.tv.ui.common.DetailTitleRow
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.PageBackground
import com.ambient.tv.ui.common.PageUtilityBar
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.common.browseFocusTarget
import com.ambient.tv.ui.common.overlayLazyScrollbar
import com.ambient.tv.ui.common.systemName
import com.ambient.tv.ui.games.GameCoverArtwork
import com.ambient.tv.ui.games.GameCoreExperimentOptions
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.screenGutter

/** A RomM game's page in the layout of the local game details; the data are the server's. */
@Composable
fun RommGameDetailScreen(
    onBack: () -> Unit,
    onPlay: (LibraryGame) -> Unit,
    onPlayFresh: (LibraryGame) -> Unit,
    onOpenSettings: () -> Unit,
    onPlayExperiment: (LibraryGame, GameCoreExperiment) -> Unit,
    model: RommGameDetailViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsState()
    var confirmFresh by remember { mutableStateOf(false) }
    val primaryFocus = remember { FocusRequester() }
    val handheld = LocalHandheldUi.current
    val listState = rememberLazyListState()
    BackHandler(enabled = !confirmFresh, onBack = onBack)
    LaunchedEffect(state.game?.fileId, handheld) {
        if (handheld || state.game == null) return@LaunchedEffect
        repeat(4) {
            withFrameNanos { }
            if (runCatching { primaryFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Box(Modifier.fillMaxSize()) {
        PageBackground(Modifier.fillMaxSize())
        state.game?.backdropModel?.let { backdrop ->
            ArtworkImage(
                model = backdrop, title = state.game?.title.orEmpty(),
                modifier = Modifier.fillMaxWidth().height(440.dp),
                contentScale = ContentScale.Crop, focusCrop = true, backdrop = true,
                placeholder = false,
            )
            Box(Modifier.fillMaxWidth().height(460.dp).background(Brush.verticalGradient(listOf(
                MaterialTheme.colorScheme.background.copy(alpha = .35f),
                MaterialTheme.colorScheme.background,
            ))))
        }
        val game = state.game
        when {
            state.loading -> StatePanel(
                title = stringResource(R.string.game_detail_title),
                body = stringResource(R.string.player_loading), modifier = Modifier.fillMaxSize())
            game == null -> StatePanel(
                title = stringResource(R.string.game_detail_title),
                body = state.error ?: stringResource(R.string.game_detail_missing),
                actionLabel = stringResource(R.string.back), onAction = onBack,
                modifier = Modifier.fillMaxSize())
            else -> TvLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().overlayLazyScrollbar(listState),
                contentPadding = com.ambient.tv.ui.games.gameDetailContentPadding(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                wrapVerticalFocus = false,
            ) {
                item("hero") {
                    com.ambient.tv.ui.games.GameDetailHero(
                        cover = game.coverModel, title = game.title, systemId = game.systemId,
                        aspectRatio = game.coverAspectRatio,
                    ) {
                            RommGameInformation(game, state.rom, state.platform, state.busy,
                                onPlay = { onPlay(game) }, onPlayFresh = { confirmFresh = true },
                                onFavorite = model::favorite, onRefresh = model::refresh, primaryFocus = primaryFocus)
                    }
                }
                state.error?.let { error -> item("error") {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item("core-tests") { GameCoreExperimentOptions(game, onPlayExperiment) }
                item("description") {
                    DetailSummary(state.rom?.summary?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.game_detail_no_summary), maxLines = Int.MAX_VALUE)
                }
                item("more") { RommGameCredits(game, state.rom) }
            }
        }
        PageUtilityBar(
            onBack = onBack,
            onSettings = onOpenSettings,
            onDown = if (state.game != null) ({ runCatching { primaryFocus.requestFocus() } }) else null,
            leftNavigatesBack = false,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
    if (confirmFresh) state.game?.let { game ->
        com.ambient.tv.ui.common.AppDialog(
            onDismiss = { confirmFresh = false },
            title = stringResource(R.string.game_reset_confirm_title),
            hint = stringResource(R.string.game_reset_confirm_hint),
            footer = {
                TvActionButton(stringResource(R.string.files_cancel), onClick = { confirmFresh = false })
                TvActionButton(stringResource(R.string.game_reset), onClick = {
                    confirmFresh = false
                    onPlayFresh(game)
                })
            },
            content = null,
        )
    }
}

@Composable
private fun RommGameInformation(
    game: LibraryGame,
    rom: RommRom?,
    platform: String,
    busy: Boolean,
    onPlay: () -> Unit,
    onPlayFresh: () -> Unit,
    onFavorite: () -> Unit,
    onRefresh: () -> Unit,
    primaryFocus: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        DetailEyebrow(platform.ifBlank { GameSystems.byId(game.systemId)?.name ?: game.systemId })
        DetailTitleRow(game.title, logoUrl = null, system = null)
        DetailPrimaryMetaRow(buildList {
            (rom?.releaseYear ?: game.metadataYear)?.let { add(it.toString()) }
            rom?.metadatum?.playerCount?.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.game_detail_players, it)) }
            rom?.regions?.takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
        }, item = null)
        DetailGenres(rom?.metadatum?.genres.orEmpty())
        DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TvActionButton(stringResource(R.string.game_detail_play), onClick = onPlay,
                icon = Icons.Filled.PlayArrow,
                modifier = Modifier.focusRequester(primaryFocus).browseFocusTarget("game-play"))
            if (game.lastPlayed > 0L) TvActionButton(stringResource(R.string.game_reset), onClick = onPlayFresh,
                icon = Icons.Filled.RestartAlt)
            TvActionButton(stringResource(if (game.favorite) R.string.radio_remove_favorite else R.string.radio_add_favorite),
                onClick = onFavorite, selected = game.favorite,
                icon = if (game.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder)
            TvActionButton(stringResource(R.string.game_detail_refresh), onClick = onRefresh,
                loading = busy, icon = Icons.Filled.Refresh)
        }
    }
}

@Composable
private fun RommGameCredits(game: LibraryGame, rom: RommRom?) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(stringResource(R.string.detail_more_info), style = MaterialTheme.typography.titleMedium)
        rom?.metadatum?.developers?.takeIf { it.isNotEmpty() }?.let {
            Text(stringResource(R.string.game_detail_developer, it.joinToString(", ")))
        }
        rom?.metadatum?.publishers?.takeIf { it.isNotEmpty() }?.let {
            Text(stringResource(R.string.game_detail_publisher, it.joinToString(", ")))
        }
        Text(stringResource(R.string.game_detail_file, game.filename), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.romm_detail_source, systemName(MediaSystem.ROMM)), style = MaterialTheme.typography.bodySmall)
    }
}
