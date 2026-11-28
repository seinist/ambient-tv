package com.ambient.tv.ui.games

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.games.GameCoreExperiment
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.games.library.coverAspectRatio
import com.ambient.tv.games.metadata.GameArtworkKind
import com.ambient.tv.games.metadata.GameMetadata
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.DetailEyebrow
import com.ambient.tv.ui.common.DetailGenres
import com.ambient.tv.ui.common.DetailPrimaryMetaRow
import com.ambient.tv.ui.common.DetailSummary
import com.ambient.tv.ui.common.DetailTitleRow
import com.ambient.tv.ui.common.PageBackground
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.common.browseFocusTarget
import com.ambient.tv.ui.common.overlayLazyScrollbar
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.screenGutter
import java.io.File
import com.ambient.tv.ui.common.PageUtilityBar
import androidx.compose.material.icons.filled.RestartAlt

@Composable
fun GameDetailScreen(
    onBack: () -> Unit,
    onPlay: (LibraryGame) -> Unit,
    onPlayFresh: (LibraryGame) -> Unit = onPlay,
    onPlayExperiment: (LibraryGame, GameCoreExperiment) -> Unit,
    model: GameDetailViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var choosingSystem by remember { mutableStateOf(false) }
    var confirmFresh by remember { mutableStateOf(false) }
    var restoreEditFocus by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    val primaryFocus = remember { FocusRequester() }
    val editFocus = remember { FocusRequester() }
    val handheld = LocalHandheldUi.current
    val listState = rememberLazyListState()
    BackHandler(enabled = !editing && !settingsOpen && !confirmFresh && !choosingSystem, onBack = onBack)
    LaunchedEffect(state.game?.fileId, handheld) {
        if (handheld || state.game == null) return@LaunchedEffect
        repeat(4) {
            withFrameNanos { }
            if (runCatching { primaryFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    val closeEditor = {
        editing = false
        model.clearSearch()
        restoreEditFocus = !handheld
        Unit
    }
    LaunchedEffect(editing, restoreEditFocus) {
        if (!editing && restoreEditFocus) {
            repeat(4) {
                withFrameNanos { }
                if (runCatching { editFocus.requestFocus() }.getOrDefault(false)) {
                    restoreEditFocus = false
                    return@LaunchedEffect
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        PageBackground(Modifier.fillMaxSize())
        state.game?.backdropPath?.let { path ->
            ArtworkImage(
                model = File(path), title = state.game?.title.orEmpty(),
                modifier = Modifier.fillMaxWidth().height(440.dp),
                contentScale = ContentScale.Crop, focusCrop = true, backdrop = true,
                placeholder = false,
            )
            Box(Modifier.fillMaxWidth().height(460.dp).background(Brush.verticalGradient(listOf(
                MaterialTheme.colorScheme.background.copy(alpha = .35f),
                MaterialTheme.colorScheme.background,
            ))))
        }
        when {
            state.loading -> StatePanel(
                title = stringResource(R.string.game_detail_title),
                body = stringResource(R.string.player_loading), modifier = Modifier.fillMaxSize())
            state.game == null -> StatePanel(
                title = stringResource(R.string.game_detail_title),
                body = state.error ?: stringResource(R.string.game_detail_missing),
                actionLabel = stringResource(R.string.back), onAction = onBack,
                modifier = Modifier.fillMaxSize())
            else -> {
                val game = state.game!!
                TvLazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().overlayLazyScrollbar(listState),
                    // Room for the shared Back/Settings bar every other detail page uses.
                    contentPadding = PaddingValues(start = screenGutter(), end = screenGutter(), top = 94.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    wrapVerticalFocus = false,
                ) {
                    item("hero") {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val compact = maxWidth < 600.dp
                            val coverSize = gameCoverFrameSize(
                                if (compact) minOf(maxWidth, 340.dp) else minOf(maxWidth * .42f, 440.dp),
                                if (compact) 220.dp else 315.dp,
                                game.coverAspectRatio,
                            )
                            val cover: @Composable () -> Unit = {
                                GameCoverArtwork(
                                    cover = game.coverPath?.let(::File), title = game.title,
                                    systemId = game.systemId,
                                    modifier = Modifier.size(coverSize),
                                )
                            }
                            val information: @Composable () -> Unit = {
                                GameDetailInformation(game, state.metadata,
                                    busy = state.busy,
                                    onPlay = { onPlay(game) }, onPlayFresh = { confirmFresh = true },
                                    onFavorite = model::favorite,
                                    onEdit = { editing = true }, onRefresh = model::refresh,
                                     onChangeSystem = if (state.systemChoices.isNotEmpty()) ({ choosingSystem = true }) else null,
                                    primaryFocus = primaryFocus, editFocus = editFocus)
                            }
                            if (compact) Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { cover() }
                                information()
                            } else Row(horizontalArrangement = Arrangement.spacedBy(28.dp),
                                verticalAlignment = Alignment.Top) {
                                cover()
                                Column(Modifier.weight(1f)) { information() }
                            }
                        }
                    }
                    state.error?.let { error -> item("error") {
                        Text(error, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    } }
                    if (state.busy) item("working") {
                        Text(stringResource(R.string.player_loading), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.refreshEmpty) item("refresh-empty") {
                        Text(stringResource(R.string.game_detail_refresh_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item("core-tests") { GameCoreExperimentOptions(game, onPlayExperiment) }
                    item("description") {
                        DetailSummary(state.metadata.summary.ifBlank {
                            stringResource(R.string.game_detail_no_summary)
                        }, maxLines = Int.MAX_VALUE)
                    }
                    item("more") { GameDetailCredits(game, state.metadata) }
                }
            }
        }
        PageUtilityBar(
            onBack = onBack,
            // The game's gear opens the library's metadata settings.
            onSettings = { settingsOpen = true },
            onDown = if (state.game != null) ({ runCatching { primaryFocus.requestFocus() } }) else null,
            leftNavigatesBack = false,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
    if (editing && state.game != null) GameMetadataEditor(
        game = state.game!!, state = state, model = model, onDismiss = closeEditor)
    if (choosingSystem) com.ambient.tv.ui.common.AppDialog(
        onDismiss = { choosingSystem = false },
        title = stringResource(R.string.game_library_change_system),
        fillHeight = true, scrollable = false,
    ) {
        TvLazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                com.ambient.tv.ui.common.SettingsNavigationRow(
                    title = stringResource(R.string.game_library_auto), onClick = {
                        choosingSystem = false
                        model.changeSystem(null)
                    })
            }
            items(state.systemChoices, key = { it.id }) { system ->
                com.ambient.tv.ui.common.SettingsNavigationRow(title = system.name,
                    onClick = { choosingSystem = false; model.changeSystem(system.id) })
            }
        }
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
    if (settingsOpen) GameMetadataSettingsPanel(onDismiss = {
        settingsOpen = false
        model.providersChanged()
    })
}

@Composable
private fun GameDetailInformation(
    game: LibraryGame,
    metadata: GameMetadata,
    busy: Boolean,
    onPlay: () -> Unit,
    onPlayFresh: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onChangeSystem: (() -> Unit)?,
    primaryFocus: FocusRequester,
    editFocus: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        DetailEyebrow(GameSystems.byId(game.systemId)?.name ?: game.systemId)
        DetailTitleRow(game.title, logoUrl = null, system = null)
        DetailPrimaryMetaRow(buildList {
            (metadata.year ?: game.metadataYear)?.let { add(it.toString()) }
            metadata.players.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.game_detail_players, it)) }
            metadata.region.takeIf(String::isNotBlank)?.let(::add)
        }, item = null)
        DetailGenres(metadata.genres)
        DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TvActionButton(stringResource(R.string.game_detail_play), onClick = onPlay,
                enabled = !busy, icon = Icons.Filled.PlayArrow,
                modifier = Modifier.focusRequester(primaryFocus).browseFocusTarget("game-play"))
            // Play continues where the game was left, like Resume on other details; this starts over.
            if (game.lastPlayed > 0L) TvActionButton(stringResource(R.string.game_reset), onClick = onPlayFresh,
                enabled = !busy, icon = Icons.Filled.RestartAlt)
            TvActionButton(stringResource(if (game.favorite) R.string.radio_remove_favorite else R.string.radio_add_favorite),
                onClick = onFavorite, enabled = !busy, selected = game.favorite,
                icon = if (game.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder)
            TvActionButton(stringResource(R.string.game_detail_edit), onClick = onEdit,
                enabled = !busy, icon = Icons.Filled.Edit, modifier = Modifier.focusRequester(editFocus))
        }
        DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TvActionButton(stringResource(R.string.game_detail_refresh), onClick = onRefresh,
                enabled = !busy, icon = Icons.Filled.Refresh)
            onChangeSystem?.let { change ->
                TvActionButton(stringResource(R.string.game_library_change_system), onClick = change,
                    enabled = !busy, icon = Icons.Filled.Edit)
            }
        }
    }
}

@Composable
private fun GameDetailCredits(game: LibraryGame, metadata: GameMetadata) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(stringResource(R.string.detail_more_info), style = MaterialTheme.typography.titleMedium)
        if (metadata.developer.isNotBlank()) Text(stringResource(R.string.game_detail_developer, metadata.developer))
        if (metadata.publisher.isNotBlank()) Text(stringResource(R.string.game_detail_publisher, metadata.publisher))
        if (metadata.region.isNotBlank()) Text(stringResource(R.string.game_detail_region, metadata.region))
        Text(stringResource(R.string.game_detail_file, game.filename), style = MaterialTheme.typography.bodySmall)
        if (metadata.provider.isNotBlank()) {
            Text(stringResource(R.string.game_detail_source, gameMetadataProviderName(metadata.provider)),
                style = MaterialTheme.typography.bodySmall)
        }
        metadata.sourceUrl?.takeIf { it.startsWith("https://") }?.let { source ->
            TvActionButton(stringResource(R.string.game_detail_source_open),
                onClick = { runCatching { uriHandler.openUri(source) } })
        }
        listOf(GameArtworkKind.COVER to game.coverPath, GameArtworkKind.BACKDROP to game.backdropPath)
            .forEach { (kind, path) ->
                if (path != null || metadata.artwork(kind) != null) {
                    val provider = if (metadata.artwork(kind) == null)
                        stringResource(R.string.game_detail_local_source)
                    else gameMetadataProviderName(metadata.artworkProviders[kind.key] ?: metadata.provider)
                    Text(stringResource(if (kind == GameArtworkKind.COVER) R.string.game_detail_cover_source
                        else R.string.game_detail_backdrop_source, provider),
                        style = MaterialTheme.typography.bodySmall)
                    metadata.artworkSources[kind.key]
                        ?.takeIf { it.startsWith("https://") && it != metadata.sourceUrl }
                        ?.let { source ->
                            TvActionButton(stringResource(if (kind == GameArtworkKind.COVER)
                                R.string.game_detail_cover_source_open else R.string.game_detail_backdrop_source_open),
                                onClick = { runCatching { uriHandler.openUri(source) } })
                        }
                }
            }
    }
}

internal fun gameMetadataProviderName(id: String): String = when (id) {
    "libretro" -> "Libretro"
    "screenscraper" -> "ScreenScraper"
    "thegamesdb" -> "TheGamesDB"
    else -> id
}
