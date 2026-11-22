package com.ambient.tv.ui.games

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.ui.files.NetworkSourceDialog
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.games.library.GameLibraryFolder
import com.ambient.tv.ui.common.AlphabetBar
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.PanelOption
import com.ambient.tv.ui.common.sortDirectionOptions
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.common.TvLazyVerticalGrid
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.ViewOption
import com.ambient.tv.ui.common.ViewOptionsChip
import com.ambient.tv.ui.common.ViewOptionsPanel
import com.ambient.tv.ui.common.ViewPreview
import com.ambient.tv.ui.common.browseFocusTarget
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.theme.animatedFocusBorder
import com.ambient.tv.ui.mobile.deviceDensityGridCells
import kotlinx.coroutines.launch
import java.util.Locale

/** The existing app's Files source and game player remain responsible for transport and playback. */
@Composable
fun GameLibraryScreen(
    initialSection: String,
    onOpenDetails: (LibraryGame) -> Unit,
    startManaging: Boolean = false,
    onBack: () -> Unit,
    model: GameLibraryViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsState()
    var managing by rememberSaveable(startManaging) { mutableStateOf(startManaging) }
    var choosingSystem by rememberSaveable { mutableStateOf(false) }
    var networkEditor by remember { mutableStateOf<FileSource?>(null) }
    var removing by remember { mutableStateOf<GameLibraryFolder?>(null) }
    var metadataSettingsOpen by remember { mutableStateOf(false) }
    var choosingDatSystem by remember { mutableStateOf(false) }
    var datSystemId by rememberSaveable { mutableStateOf("snes") }
    var showOptions by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val itemRequests = remember { mutableMapOf<String, FocusRequester>() }
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(initialSection) { model.section(initialSection) }
    LaunchedEffect(searchText) { model.query(searchText) }
    ReportSidebarContentReady(state.ready)

    BackHandler {
        when {
            removing != null -> removing = null
            metadataSettingsOpen -> metadataSettingsOpen = false
            showOptions -> showOptions = false
            choosingDatSystem -> choosingDatSystem = false
            choosingSystem -> choosingSystem = false
            state.selectedSource != null -> if (!model.folderUp()) model.closePicker()
            managing -> if (startManaging) onBack() else managing = false
            else -> onBack()
        }
    }
    val sourcesById = remember(state.sources) { state.sources.associateBy { it.id } }
    // The repository returns just this section/search in final order, off the UI thread.
    val games = state.games
    val alphabet = remember(games, state.section, state.view, state.sortKey) {
        if (state.section == "recent" || state.view == "rows" || state.sortKey != "name") emptyList()
        else games.mapNotNull { game ->
            game.title.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }
        }.distinct()
    }

    val datPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importDat(datSystemId, uri)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.addLocalSource(uri)
    }
    LaunchedEffect(state.networkSaveRevision) {
        if (state.networkSaveRevision > 0) networkEditor = null
    }
    val openDetails: (LibraryGame) -> Unit = onOpenDetails
    Column(
        Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(actions = {
            if (!managing) {
                TvChip(text = stringResource(R.string.action_search), icon = Icons.Filled.Search,
                    iconOnly = true, selected = showSearch, onClick = { showSearch = !showSearch })
                TvChip(text = stringResource(R.string.action_reload), icon = Icons.Filled.Refresh,
                    iconOnly = true, loading = state.busy, loadingOnlyAfterClick = true,
                    onClick = model::scanAll)
                if (state.scanning) TvChip(
                    text = stringResource(R.string.game_library_scan_cancel),
                    icon = Icons.Filled.Close, iconOnly = true, onClick = model::cancelScan)
                ViewOptionsChip(0, onClick = { showOptions = true })
                TvChip(text = stringResource(R.string.game_detail_title),
                    icon = Icons.Filled.Info, iconOnly = true, enabled = focusedId != null,
                    onClick = {
                        val game = state.games.firstOrNull { it.fileId == focusedId }
                        if (game != null) onOpenDetails(game)
                    })
            }
            TvChip(text = stringResource(R.string.game_detail_settings),
                icon = Icons.Filled.Settings, iconOnly = true,
                onClick = { metadataSettingsOpen = true })
            TvChip(text = stringResource(R.string.game_library_manage), icon = Icons.Filled.Folder,
                iconOnly = true, selected = managing, onClick = {
                    managing = !managing
                    choosingSystem = false
                    if (!managing) model.closePicker()
                })
        }) {
            Text(if (managing) stringResource(R.string.game_library_manage)
                else if (state.section in setOf("all", "favorites", "recent")) stringResource(R.string.game_library_title)
                else GameSystems.byId(state.section)?.name ?: stringResource(R.string.game_library_title),
                style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.game_library_count, games.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!managing) {
            if (state.scanning) {
                Text(
                    if (state.scanDiscovered > 0)
                        stringResource(if (state.enriching) R.string.game_folder_metadata_progress else R.string.game_library_scan_progress,
                            state.scanProcessed, state.scanDiscovered)
                    else stringResource(if (state.enriching) R.string.game_folder_enrich else R.string.game_library_scanning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showSearch) TvTextField(searchText, { searchText = it },
                stringResource(R.string.action_search), Modifier.fillMaxWidth())
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val sections = listOf("all", "favorites", "recent") +
                    GameSystems.sortIdsByName(state.folders.map { it.systemId }.filter { it != "auto" } +
                        state.systems)
                items(sections, key = { it }) { key ->
                    val label = when (key) {
                        "all" -> stringResource(R.string.game_library_all)
                        "favorites" -> stringResource(R.string.radio_favorites)
                        "recent" -> stringResource(R.string.radio_recent)
                        else -> GameSystems.byId(key)?.shortName ?: key
                    }
                    TvChip(text = label, selected = state.section == key,
                        icon = when (key) {
                            "all" -> Icons.Filled.Apps
                            "favorites" -> Icons.Filled.Favorite
                            "recent" -> Icons.Filled.History
                            else -> gameSystemVector(key)
                        },
                        onClick = { model.section(key); focusedId = null })
                }
            }
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.folders.isEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.game_library_empty),
                        style = MaterialTheme.typography.bodyMedium)
                    TvActionButton(stringResource(R.string.game_library_add_folder),
                        { managing = true }, icon = Icons.Filled.CreateNewFolder)
                }
            } else if (games.isEmpty()) {
                Text(if (state.busy) stringResource(R.string.player_loading)
                    else stringResource(R.string.game_library_no_results),
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                val firstFocus = remember(state.section, state.view) { FocusRequester() }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.view == "list") {
                        TvLazyColumn(modifier = Modifier.weight(1f).fillMaxHeight(),
                            state = listState, focusActionsOnRight = false,
                            verticalArrangement = Arrangement.spacedBy((9f * 7 / state.listRows).coerceIn(6f, 16f).dp)) {
                            items(games, key = { it.fileId }) { game ->
                                GameListEntry(game, source = sourcesById[game.sourceId]?.name.orEmpty(),
                                    onPlay = { openDetails(game) }, onFavorite = { model.favorite(game) },
                                    onFocus = { focusedId = game.fileId },
                                    onRequester = { itemRequests[game.fileId] = it },
                                    onDisposed = { itemRequests.remove(game.fileId) },
                                    modifier = if (games.firstOrNull()?.fileId == game.fileId)
                                        Modifier.focusRequester(firstFocus) else Modifier,
                                )
                            }
                        }
                    } else if (state.view == "rows") {
                        // As on Home, each console has a horizontal row with its own lazy
                        // viewport. Entirely cached data means the NAS never wakes for layout.
                        val grouped = remember(games) {
                            games.groupBy { it.systemId }.toList().sortedBy {
                                (GameSystems.byId(it.first)?.name ?: it.first).lowercase(Locale.ROOT)
                            }
                        }
                        TvLazyColumn(
                            modifier = Modifier.weight(1f).fillMaxHeight().focusGroup(),
                            verticalArrangement = Arrangement.spacedBy(15.dp),
                        ) {
                            items(grouped, key = { it.first }) { (system, entries) ->
                                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                        androidx.compose.material3.Icon(
                                            painterResource(GameSystemIcons.drawable(system)),
                                            contentDescription = null,
                                            modifier = Modifier.size(26.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(GameSystems.byId(system)?.name ?: system,
                                            style = MaterialTheme.typography.titleMedium)
                                    }
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth().focusGroup(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
                                    ) {
                                        items(entries, key = { it.fileId }) { game ->
                                            GameTile(game,
                                                onPlay = { openDetails(game) },
                                                onFavorite = { model.favorite(game) },
                                                onFocus = { focusedId = game.fileId },
                                                onRequester = { itemRequests[game.fileId] = it },
                                                onDisposed = { itemRequests.remove(game.fileId) },
                                                modifier = (if (games.firstOrNull()?.fileId == game.fileId)
                                                    Modifier.focusRequester(firstFocus) else Modifier)
                                                    .width((224f * 8f / state.gridColumns).coerceIn(130f, 275f).dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        TvLazyVerticalGrid(
                            columns = deviceDensityGridCells(state.gridColumns, 150.dp, 8),
                            modifier = Modifier.weight(1f).fillMaxHeight().focusGroup(),
                            state = gridState,
                            contentPadding = PaddingValues(bottom = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(games, key = { it.fileId }) { game ->
                                GameTile(game,
                                    onPlay = { openDetails(game) },
                                    onFavorite = { model.favorite(game) },
                                    onFocus = { focusedId = game.fileId },
                                    onRequester = { itemRequests[game.fileId] = it },
                                    onDisposed = { itemRequests.remove(game.fileId) },
                                    modifier = if (games.firstOrNull()?.fileId == game.fileId)
                                        Modifier.focusRequester(firstFocus) else Modifier,
                                )
                            }
                        }
                    }
                    if (alphabet.isNotEmpty()) {
                        val focusedLetter = games.firstOrNull { it.fileId == focusedId }
                            ?.title?.firstOrNull()?.uppercaseChar()
                        AlphabetBar(
                            letters = alphabet, selected = focusedLetter,
                            gridFocus = firstFocus,
                            onReturnToContent = {
                                focusedId?.let { itemRequests[it] }?.let { runCatching { it.requestFocus() } }
                                    ?: runCatching { firstFocus.requestFocus() }
                            },
                            onSelect = { letter ->
                                if (letter == null) return@AlphabetBar
                                val index = games.indexOfFirst { it.title.firstOrNull()?.uppercaseChar() == letter }
                                if (index >= 0) scope.launch {
                                    if (state.view == "list") listState.scrollToItem(index)
                                    else gridState.scrollToItem(index)
                                    withFrameNanos { }
                                    itemRequests[games[index].fileId]?.let { runCatching { it.requestFocus() } }
                                }
                            },
                        )
                    }
                }
            }
        } else if (choosingDatSystem) {
            Text(stringResource(R.string.game_library_dat_system), style = MaterialTheme.typography.titleMedium)
            TvLazyColumn(modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(GameSystems.alphabetical, key = { it.id }) { system ->
                    TvActionButton(system.name, onClick = {
                        datSystemId = system.id
                        choosingDatSystem = false
                        datPicker.launch(arrayOf("application/xml", "text/xml", "*/*"))
                    }, icon = gameSystemVector(system.id), fillWidth = true)
                }
            }
        } else {
            GameFolderManager(
                state = state, choosingSystem = choosingSystem,
                onChooseSystem = { choosingSystem = it },
                onSelectSource = model::selectSource,
                onEnter = model::enterFolder,
                onAdd = { system ->
                    if (model.assign(system)) { choosingSystem = false; managing = false }
                },
                onRemove = { removing = it },
                onScan = model::scanFolder,
                onEnrich = model::enrichFolder,
                onCancelScan = model::cancelScan,
                onFolderFileVisibility = model::setAlsoShowInFiles,
                onDeleteSource = model::deleteUnusedGameSource,
                onAddNetwork = {
                    networkEditor = FileSource(name = "Retro NAS", kind = "smb", showInFiles = false)
                },
                onImportDat = { choosingDatSystem = true },
                onEditSource = { source -> networkEditor = source },
                onWake = model::wakeCurrentSource,
                onAddLocal = {
                    try { folderPicker.launch(null) }
                    catch (_: ActivityNotFoundException) { }
                },
                onCloseSource = model::closePicker,
            )
        }
    }
    removing?.let { folder ->
        AppDialog(
            onDismiss = { removing = null },
            title = stringResource(R.string.game_folder_remove_title),
            hint = stringResource(R.string.game_folder_remove_hint),
            footer = {
                TvActionButton(stringResource(R.string.files_cancel), onClick = { removing = null })
                TvActionButton(stringResource(R.string.game_library_remove), onClick = {
                    model.remove(folder)
                    removing = null
                })
            },
        ) {
            Text(folder.path.ifBlank {
                sourcesById[folder.sourceId]?.name ?: stringResource(R.string.game_folder_fallback)
            })
        }
    }
    if (metadataSettingsOpen) GameMetadataSettingsPanel(onDismiss = { metadataSettingsOpen = false })
    networkEditor?.let { source ->
        NetworkSourceDialog(
            source = source,
            saving = state.savingNetworkSource,
            testing = state.testingNetworkSource,
            feedback = null,
            wireGuardAvailable = model.wireGuardConfigured(),
            onDismiss = { networkEditor = null },
            onTest = model::testNewNetworkSource,
            onSave = model::saveNewNetworkSource,
        )
    }
    if (showOptions) {
        com.ambient.tv.ui.common.RightPanelOverlay(onOutsideTap = { showOptions = false }) {
            Box(Modifier.align(Alignment.TopEnd)) {
                ViewOptionsPanel(
                    views = listOf(
                        ViewOption("grid", stringResource(R.string.library_view_grid), ViewPreview.GRID),
                        ViewOption("list", stringResource(R.string.library_view_list), ViewPreview.LIST),
                        ViewOption("rows", stringResource(R.string.library_view_rows), ViewPreview.ROWS),
                    ),
                    selectedView = state.view, onView = model::view,
                    gridColumns = state.gridColumns, listRows = state.listRows,
                    onGridColumns = model::gridColumns, onListRows = model::listRows,
                    densityPreview = if (state.view == "rows") ViewPreview.ROWS else null,
                    sorts = listOf(
                        PanelOption("name", stringResource(R.string.game_library_sort_name)),
                        PanelOption("system", stringResource(R.string.game_library_sort_system)),
                        PanelOption("recent", stringResource(R.string.game_library_sort_recent)),
                    ),
                    selectedSort = state.sortKey, onSort = model::sort,
                    sortOrders = sortDirectionOptions("A–Z", "Z–A"),
                    selectedSortOrder = if (state.sortDescending) "DESCENDING" else "ASCENDING",
                    onSortOrder = { model.sortDescending(it == "DESCENDING") },
                    title = stringResource(R.string.game_library_title),
                    onClose = { showOptions = false },
                    extraContent = {
                        Text(stringResource(R.string.game_library_titles),
                            style = MaterialTheme.typography.titleSmall)
                        listOf(
                            "auto" to R.string.game_library_title_auto,
                            "internal" to R.string.game_library_title_internal,
                            "filename" to R.string.game_library_title_filename,
                        ).forEach { (key, label) ->
                            TvActionButton(stringResource(label),
                                onClick = { model.titleStyle(key) },
                                selected = state.titleStyle == key, fillWidth = true)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GameFolderManager(
    state: GameLibraryUiState,
    choosingSystem: Boolean,
    onChooseSystem: (Boolean) -> Unit,
    onSelectSource: (FileSource) -> Unit,
    onEnter: (com.ambient.tv.data.files.FileEntry) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (com.ambient.tv.games.library.GameLibraryFolder) -> Unit,
    onScan: (GameLibraryFolder) -> Unit,
    onEnrich: (GameLibraryFolder) -> Unit,
    onCancelScan: () -> Unit,
    onFolderFileVisibility: (com.ambient.tv.games.library.GameLibraryFolder, Boolean) -> Unit,
    onDeleteSource: (FileSource) -> Unit,
    onAddNetwork: () -> Unit,
    onImportDat: () -> Unit,
    onEditSource: (FileSource) -> Unit,
    onWake: () -> Unit,
    onAddLocal: () -> Unit,
    onCloseSource: () -> Unit,
) {
    if (state.error != null) Text(state.error,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall)
    if (choosingSystem) {
        Text(stringResource(R.string.game_library_select_system),
            style = MaterialTheme.typography.titleMedium)
        TvLazyColumn(modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TvActionButton(stringResource(R.string.game_library_auto),
                onClick = { onAdd("auto") }, fillWidth = true) }
            items(GameSystems.alphabetical, key = { it.id }) { system ->
                TvActionButton(system.name, onClick = { onAdd(system.id) },
                    icon = gameSystemVector(system.id), fillWidth = true)
            }
        }
        return
    }
    val source = state.selectedSource
    if (source == null) {
        TvLazyColumn(modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                TvActionButton(stringResource(R.string.game_library_add_local),
                    onClick = onAddLocal, icon = Icons.Filled.CreateNewFolder, fillWidth = true)
            }
            item {
                TvActionButton(stringResource(R.string.game_library_add_network),
                    onClick = onAddNetwork, icon = Icons.Filled.Storage, fillWidth = true)
            }
            item {
                TvActionButton(stringResource(R.string.game_library_import_dat),
                    onClick = onImportDat, icon = Icons.Filled.LibraryBooks, fillWidth = true)
            }
            state.message?.let { feedback -> item {
                Text(feedback, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            item { Text(stringResource(R.string.game_library_existing_sources),
                style = MaterialTheme.typography.titleMedium) }
            items(state.sources, key = { it.id }) { entry ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvActionButton(entry.name + " · " + entry.kind.uppercase() +
                        (if (entry.showInFiles) " · " + stringResource(R.string.game_library_source_files_games)
                        else " · " + stringResource(R.string.game_library_source_games_only)),
                        onClick = { onSelectSource(entry) }, icon = Icons.Filled.Folder,
                        modifier = Modifier.weight(1f))
                    if (!entry.showInFiles && entry.network) {
                        TvActionButton(stringResource(R.string.files_edit),
                            onClick = { onEditSource(entry) }, icon = Icons.Filled.Edit)
                    }
                    if (!entry.showInFiles && state.folders.none { it.sourceId == entry.id }) {
                        TvActionButton(stringResource(R.string.game_library_remove),
                            onClick = { onDeleteSource(entry) }, icon = Icons.Filled.Delete)
                    }
                }
            }
            item { Text(stringResource(R.string.game_library_assigned),
                style = MaterialTheme.typography.titleMedium) }
            items(state.folders, key = { it.id }) { folder ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text((state.sources.firstOrNull { it.id == folder.sourceId }?.name
                        ?: stringResource(R.string.game_folder_fallback)) +
                        " / " + folder.path + " · " +
                        (GameSystems.byId(folder.systemId)?.shortName ?: stringResource(R.string.game_library_auto)),
                        maxLines = 2)
                    if (state.activeScanFolderId == folder.id) {
                        Text(stringResource(if (state.enriching) R.string.game_folder_metadata_progress
                            else R.string.game_folder_scan_progress, state.scanProcessed, state.scanDiscovered),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    com.ambient.tv.ui.mobile.DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.activeScanFolderId == folder.id) {
                            TvActionButton(stringResource(R.string.game_library_scan_cancel),
                                onClick = onCancelScan, icon = Icons.Filled.Close)
                        } else {
                            TvActionButton(stringResource(R.string.game_folder_scan),
                                onClick = { onScan(folder) }, enabled = !state.scanning && folder.id !in state.removingFolders,
                                icon = Icons.Filled.Refresh)
                        }
                        TvActionButton(stringResource(R.string.game_folder_enrich),
                            onClick = { onEnrich(folder) }, enabled = !state.scanning && folder.id !in state.removingFolders,
                            icon = Icons.Filled.Image)
                    }
                    com.ambient.tv.ui.mobile.DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvActionButton(stringResource(R.string.game_library_show_in_files),
                            onClick = { onFolderFileVisibility(folder, !folder.alsoShowInFiles) },
                            enabled = folder.id !in state.removingFolders,
                            selected = folder.alsoShowInFiles,
                            icon = if (folder.alsoShowInFiles) Icons.Filled.Visibility
                                else Icons.Filled.VisibilityOff)
                        TvActionButton(stringResource(R.string.game_library_remove),
                            onClick = { onRemove(folder) }, enabled = folder.id !in state.removingFolders,
                            icon = Icons.Filled.Delete)
                    }
                    if (folder.id in state.removingFolders) Text(stringResource(R.string.game_folder_removing),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Text(stringResource(R.string.game_library_add_network_hint),
                style = MaterialTheme.typography.bodySmall) }
        }
        return
    }
    Text(source.name + " / " + state.browsedPaths.joinToString("/") { it.second },
        style = MaterialTheme.typography.titleSmall, maxLines = 2)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvActionButton(stringResource(R.string.game_library_use_folder),
            onClick = { onChooseSystem(true) }, icon = Icons.Filled.Check)
        TvActionButton(stringResource(R.string.game_library_back),
            onClick = onCloseSource, icon = Icons.Filled.ArrowBack)
        if (source.wolMac.isNotBlank()) {
            TvActionButton(stringResource(R.string.game_library_wake),
                onClick = onWake, icon = Icons.Filled.PowerSettingsNew)
        }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.browsing) Text(stringResource(R.string.player_loading),
        style = MaterialTheme.typography.bodySmall)
    TvLazyColumn(modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.directoryEntries, key = { it.id }) { folder ->
            TvActionButton(folder.name, onClick = { onEnter(folder) },
                icon = Icons.Filled.Folder, fillWidth = true)
        }
    }
}

@Composable
private fun GameTile(
    game: LibraryGame,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onFocus: () -> Unit,
    onRequester: (FocusRequester) -> Unit,
    onDisposed: () -> Unit,
) {
    var focused by remember(game.fileId) { mutableStateOf(false) }
    val requester = remember(game.fileId) { FocusRequester() }
    DisposableEffect(requester) { onRequester(requester); onDispose(onDisposed) }
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxWidth()) {
    Surface(onClick = onPlay,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape),
        modifier = modifier.fillMaxWidth().focusRequester(requester)
            .browseFocusTarget(game.fileId)
            .mediaFocusMotion(focused)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
    ) {
        Column(
            Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .72f), shape)
                .animatedFocusBorder(focused, shape,
                    focusedColor = MaterialTheme.colorScheme.primary,
                    unfocusedColor = Color.White.copy(alpha = .06f))
                .padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f), shape),
                contentAlignment = Alignment.Center) {
                com.ambient.tv.ui.common.ArtworkImage(
                    model = game.coverPath?.let { java.io.File(it) }, title = game.title,
                    modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    staggerUncached = true, prioritizeUncached = focused,
                    fallbackContent = { GameSystemArt(game.systemId, Modifier.fillMaxSize(.48f)) },
                )
                // The favorite action lives outside the playing Surface as a TV focus target.
            }
            Text(game.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(GameSystems.byId(game.systemId)?.shortName ?: game.systemId,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
    TvActionButton("", onClick = onFavorite, selected = game.favorite,
        icon = if (game.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

@Composable
private fun GameListEntry(
    game: LibraryGame,
    source: String,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onFocus: () -> Unit,
    onRequester: (FocusRequester) -> Unit,
    onDisposed: () -> Unit,
) {
    val requester = remember(game.fileId) { FocusRequester() }
    DisposableEffect(requester) { onRequester(requester); onDispose(onDisposed) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.ambient.tv.ui.common.ArtworkImage(
            model = game.coverPath?.let { java.io.File(it) }, title = game.title,
            modifier = Modifier.width(48.dp).height(64.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit, staggerUncached = true,
            fallbackContent = { GameSystemArt(game.systemId, Modifier.size(32.dp)) },
        )
        TvActionButton(game.title + " · " +
            (GameSystems.byId(game.systemId)?.shortName ?: game.systemId) + " · " + source,
            onClick = onPlay, fillWidth = false,
            modifier = modifier.weight(1f).focusRequester(requester).onFocusChanged {
                if (it.isFocused) onFocus()
            })
        TvActionButton("", onClick = onFavorite, selected = game.favorite,
            icon = if (game.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder)
    }
}

@Composable
private fun GameSystemArt(id: String, modifier: Modifier = Modifier) {
    val icon = GameSystemIcons.drawable(id)
    androidx.compose.material3.Icon(painterResource(icon), contentDescription = null, modifier = modifier,
        tint = MaterialTheme.colorScheme.onSurfaceVariant)
}
