package com.ambient.tv.ui.games

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.ambient.tv.games.library.coverAspectRatio
import com.ambient.tv.games.library.GameLibraryFolder
import com.ambient.tv.ui.common.AlphabetBar
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.LocalBrowseRightIndexFocus
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.common.TvLazyVerticalGrid
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.browseFocusTarget
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.bringFocusedContextIntoView
import com.ambient.tv.ui.common.PageFocusLevel
import com.ambient.tv.ui.common.pageFocusStage
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.pageHeaderTop
import com.ambient.tv.ui.common.RegisterBrowseFocusGrid
import com.ambient.tv.ui.common.RegisterBrowseFocusList
import com.ambient.tv.ui.common.RegisterBrowseFocusRows
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.ContentSectionHeader
import com.ambient.tv.ui.common.RegisterRowColumnFocus
import com.ambient.tv.ui.common.RowColumnFocus
import com.ambient.tv.ui.common.rememberRowColumnFocus
import com.ambient.tv.ui.common.rowColumnFocusNavigation
import com.ambient.tv.ui.common.rowColumnFocusTarget
import com.ambient.tv.ui.common.rowColumnFocusWrapRight
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.noSurfaceBorder
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.theme.animatedFocusBorder
import com.ambient.tv.ui.theme.animatedFocusSurface
import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.sound.LocalUiSoundPlayer
import com.ambient.tv.ui.sound.rememberUiSoundAction
import com.ambient.tv.ui.mobile.deviceDensityGridCells
import kotlinx.coroutines.launch
import java.util.Locale

/** The existing app's Files source and game player remain responsible for transport and playback. */
@Composable
fun GameLibraryScreen(
    initialSection: String,
    onSelect: (LibraryGame) -> Unit,
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
    val controls = rememberGameLibraryBrowseControls()
    val handheld = LocalHandheldUi.current
    val metadataSettingsFocus = remember { FocusRequester() }
    var restoreMetadataFocus by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val rowsState = rememberLazyListState()
    val itemRequests = remember { mutableMapOf<String, FocusRequester>() }
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(initialSection) { model.section(initialSection) }
    LaunchedEffect(searchText) { model.query(searchText) }
    ReportSidebarContentReady(state.ready)

    BackHandler {
        when {
            removing != null -> removing = null
            metadataSettingsOpen -> { metadataSettingsOpen = false; restoreMetadataFocus = true }
            controls.showOptions -> controls.closeOptions()
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
    val gameIds = remember(games) { games.map { it.fileId } }
    // Like the other libraries: entering from the sidebar or changing section, view or sort lands
    // on the remembered or first game, not on the header chips.
    com.ambient.tv.ui.common.BrowseFocusContentIdentity(
        listOf(state.section, state.view, state.sortKey, state.sortDescending), restoreInitialTarget = true,
    )
    val initialGame = games.firstOrNull { it.fileId == focusedId } ?: games.firstOrNull()
    if (!managing && initialGame != null) com.ambient.tv.ui.common.BrowseFocusInitialTarget(
        initialGame.fileId, row = initialGame.systemId.takeIf { state.view == "rows" },
    )
    val sections = remember(state.folders, state.systems) {
        listOf("all", "favorites", "recent") +
            GameSystems.sortIdsByName(state.folders.map { it.systemId }.filter { it != "auto" } + state.systems)
    }
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
    LaunchedEffect(metadataSettingsOpen, restoreMetadataFocus, handheld) {
        if (!metadataSettingsOpen && restoreMetadataFocus) {
            if (!handheld) repeat(4) {
                withFrameNanos { }
                if (runCatching { metadataSettingsFocus.requestFocus() }.getOrDefault(false)) {
                    restoreMetadataFocus = false
                    return@LaunchedEffect
                }
            }
            restoreMetadataFocus = false
        }
    }
    Column(
        Modifier.fillMaxSize().padding(start = screenGutter(), end = screenGutter(),
            top = pageHeaderTop(screenGutter()), bottom = screenGutter()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GameLibraryHeader(state, managing, showSearch, controls,
            onManage = {
                if (managing && startManaging) onBack()
                else {
                    managing = !managing
                    choosingSystem = false
                    choosingDatSystem = false
                    if (!managing) model.closePicker()
                }
            },
            onSearch = { showSearch = !showSearch }, onRefresh = model::scanAll)
        if (!managing) {
            GameLibrarySections(sections, state.section, controls) {
                model.section(it)
                focusedId = null
            }
            if (state.scanning) {
                Row(Modifier.fillMaxWidth().pageFocusStage(controls.focusChain,
                    PageFocusLevel.CONTEXT, controls.scanFocus),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (state.scanDiscovered > 0)
                            stringResource(if (state.enriching) R.string.game_folder_metadata_progress else R.string.game_library_scan_progress,
                                state.scanProcessed, state.scanDiscovered)
                        else stringResource(if (state.enriching) R.string.game_folder_enrich else R.string.game_library_scanning),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TvChip(text = stringResource(R.string.game_library_scan_cancel),
                        icon = Icons.Filled.Close, iconOnly = true, onClick = {
                            model.cancelScan()
                            if (!handheld) runCatching { controls.refreshFocus.requestFocus() }
                        })
                }
            }
            if (showSearch) TvTextField(searchText, { searchText = it },
                stringResource(R.string.action_search), Modifier.fillMaxWidth()
                    .pageFocusStage(controls.focusChain, PageFocusLevel.SEARCH_LOWER, controls.searchFocus))
            GameLibrarySortBar(state, controls, onSort = model::sort, onSortDescending = model::sortDescending)
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
                // Keep geometry fixed for this result set while background artwork finishes.
                // Existing local dimensions are already present in the initial query.
                // The usual shape of this result set sets the slot; a rare tall or wide cover is fitted
                // into it instead of making every other cover shrink (median, not the narrowest).
                val gridSlotRatio = remember(state.section, state.view, gameIds) {
                    games.map { it.coverAspectRatio }.sorted().let { it[it.size / 2] }.coerceIn(.6f, 1.8f)
                }
                val rowArtworkHeight = (148f * 8f / state.gridColumns.coerceAtLeast(1)).coerceIn(96f, 216f).dp
                GameLibraryAlphabetLayout(
                    letters = alphabet,
                    selectedLetter = games.firstOrNull { it.fileId == focusedId }
                        ?.title?.firstOrNull()?.uppercaseChar(),
                    firstContentFocus = firstFocus,
                    onReturnToContent = {
                        focusedId?.let { itemRequests[it] }?.let { runCatching { it.requestFocus() } }
                            ?: runCatching { firstFocus.requestFocus() }
                    },
                    onSelect = { letter ->
                        val index = games.indexOfFirst { it.title.firstOrNull()?.uppercaseChar() == letter }
                        if (index >= 0) scope.launch {
                            if (state.view == "list") listState.scrollToItem(index)
                            else gridState.scrollToItem(index)
                            withFrameNanos { }
                            itemRequests[games[index].fileId]?.let { runCatching { it.requestFocus() } }
                        }
                    },
                    modifier = Modifier.fillMaxSize().pageContentUp(controls.focusChain).focusGroup(),
                ) {
                    if (state.view == "list") {
                        RegisterBrowseFocusList(listState, gameIds)
                        TvLazyColumn(modifier = Modifier.weight(1f).fillMaxHeight(),
                            state = listState, focusActionsOnRight = false, allowUpExit = true,
                            verticalArrangement = Arrangement.spacedBy((9f * 7 / state.listRows).coerceIn(6f, 16f).dp)) {
                            items(games, key = { it.fileId }) { game ->
                                GameListEntry(game,
                                    onPlay = { onSelect(game) }, onInfo = { onOpenDetails(game) },
                                    onFavorite = { model.favorite(game) },
                                    density = (7f / state.listRows.coerceIn(4, 10)).coerceIn(.72f, 1.75f),
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
                        RegisterBrowseFocusRows(rowsState, remember(grouped) {
                            grouped.map { (system, entries) -> system to entries.map { it.fileId } }
                        })
                        // Same row behavior as Home and the other libraries: Up/Down keep the column,
                        // Right on a row's last card returns to its first, shared section headings.
                        val rowFocus = rememberRowColumnFocus()
                        val rowKeys = remember(grouped) { grouped.map { it.first } }
                        TvLazyColumn(
                            modifier = Modifier.weight(1f).fillMaxHeight().focusGroup()
                                .rowColumnFocusWrapRight(rowFocus)
                                .rowColumnFocusNavigation(
                                    rowFocus,
                                    scrollToRow = { rowKey ->
                                        val index = rowKeys.indexOf(rowKey)
                                        if (index >= 0) rowsState.animateScrollToItem(index)
                                    },
                                ) { rowKeys },
                            state = rowsState, allowUpExit = true,
                            verticalArrangement = Arrangement.spacedBy(15.dp),
                        ) {
                            items(grouped, key = { it.first }) { (system, entries) ->
                                Column {
                                    ContentSectionHeader(GameSystems.byId(system)?.name ?: system, sectionKey = system)
                                    GameLibraryCoverRow(entries, rowArtworkHeight, system, rowFocus = rowFocus) { game, tileModifier, slotRatio ->
                                        GameTile(game,
                                            artworkSlotAspectRatio = slotRatio,
                                            rowKey = system,
                                            showSystem = false,
                                            onPlay = { onSelect(game) },
                                            onInfo = { onOpenDetails(game) },
                                            onFavorite = { model.favorite(game) },
                                            onFocus = { focusedId = game.fileId },
                                            onRequester = { itemRequests[game.fileId] = it },
                                            onDisposed = { itemRequests.remove(game.fileId) },
                                            modifier = (if (games.firstOrNull()?.fileId == game.fileId)
                                                Modifier.focusRequester(firstFocus) else Modifier).then(tileModifier)
                                                .rowColumnFocusTarget(rowFocus, system, game.fileId, soundsOnFocus = true),
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        RegisterBrowseFocusGrid(gridState, gameIds)
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
                                    artworkSlotAspectRatio = gridSlotRatio,
                                    showSystem = GameSystems.byId(state.section) == null,
                                    onPlay = { onSelect(game) }, onInfo = { onOpenDetails(game) },
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
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().pageContentUp(controls.focusChain).focusGroup()) {
                if (choosingDatSystem) {
                    com.ambient.tv.ui.settings.SettingsSubheader(stringResource(R.string.game_library_dat_system), firstInContent = true)
                    TvLazyColumn(modifier = Modifier.fillMaxSize(), allowUpExit = true,
                        verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(GameSystems.alphabetical, key = { it.id }) { system ->
                            com.ambient.tv.ui.common.SettingsNavigationRow(title = system.name, icon = gameSystemVector(system.id), onClick = {
                                datSystemId = system.id
                                choosingDatSystem = false
                                datPicker.launch(arrayOf("application/xml", "text/xml", "*/*"))
                            })
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
                        onMetadataSettings = { metadataSettingsOpen = true },
                        metadataSettingsModifier = Modifier.focusRequester(metadataSettingsFocus),
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
    if (metadataSettingsOpen) GameMetadataSettingsPanel(onDismiss = {
        metadataSettingsOpen = false
        restoreMetadataFocus = true
    })
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
    GameLibraryViewOptionsPanel(state, controls,
        onView = model::view, onGridColumns = model::gridColumns, onListRows = model::listRows,
        onSort = model::sort, onSortDescending = model::sortDescending, onTitleStyle = model::titleStyle)
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
    onMetadataSettings: () -> Unit,
    metadataSettingsModifier: Modifier = Modifier,
    onEditSource: (FileSource) -> Unit,
    onWake: () -> Unit,
    onAddLocal: () -> Unit,
    onCloseSource: () -> Unit,
) {
    if (state.error != null) Text(state.error,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall)
    if (choosingSystem) {
        com.ambient.tv.ui.settings.SettingsSubheader(stringResource(R.string.game_library_select_system), firstInContent = true)
        TvLazyColumn(modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { com.ambient.tv.ui.common.SettingsNavigationRow(title = stringResource(R.string.game_library_auto), onClick = { onAdd("auto") }) }
            items(GameSystems.alphabetical, key = { it.id }) { system ->
                com.ambient.tv.ui.common.SettingsNavigationRow(title = system.name, icon = gameSystemVector(system.id), onClick = { onAdd(system.id) })
            }
        }
        return
    }
    val source = state.selectedSource
    if (source == null) {
        TvLazyColumn(modifier = Modifier.fillMaxSize(), allowUpExit = true,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Same rows as Settings: destinations are navigation rows, actions stay buttons.
            item {
                com.ambient.tv.ui.common.SettingsNavigationRow(title = stringResource(R.string.game_metadata_settings_title),
                    onClick = onMetadataSettings, modifier = metadataSettingsModifier, icon = Icons.Filled.Settings)
            }
            item {
                com.ambient.tv.ui.common.SettingsNavigationRow(title = stringResource(R.string.game_library_add_local),
                    onClick = onAddLocal, icon = Icons.Filled.CreateNewFolder)
            }
            item {
                com.ambient.tv.ui.common.SettingsNavigationRow(title = stringResource(R.string.game_library_add_network),
                    onClick = onAddNetwork, icon = Icons.Filled.Storage)
            }
            item {
                com.ambient.tv.ui.common.SettingsNavigationRow(title = stringResource(R.string.game_library_import_dat),
                    onClick = onImportDat, icon = Icons.AutoMirrored.Filled.LibraryBooks)
            }
            state.message?.let { feedback -> item { com.ambient.tv.ui.common.SettingsHint(feedback) } }
            item { com.ambient.tv.ui.settings.SettingsSubheader(stringResource(R.string.game_library_existing_sources)) }
            items(state.sources, key = { it.id }) { entry ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.ambient.tv.ui.common.SettingsNavigationRow(title = entry.name,
                        summary = entry.kind.uppercase() + " · " + stringResource(if (entry.showInFiles)
                            R.string.game_library_source_files_games else R.string.game_library_source_games_only),
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
            item { com.ambient.tv.ui.settings.SettingsSubheader(stringResource(R.string.game_library_assigned)) }
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
            item { com.ambient.tv.ui.common.SettingsHint(stringResource(R.string.game_library_add_network_hint)) }
        }
        return
    }
    Text(source.name + " / " + state.browsedPaths.joinToString("/") { it.second },
        style = MaterialTheme.typography.titleSmall, maxLines = 2)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvActionButton(stringResource(R.string.game_library_use_folder),
            onClick = { onChooseSystem(true) }, icon = Icons.Filled.Check)
        TvActionButton(stringResource(R.string.game_library_back),
            onClick = onCloseSource, icon = Icons.AutoMirrored.Filled.ArrowBack)
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
            com.ambient.tv.ui.common.SettingsNavigationRow(title = folder.name, onClick = { onEnter(folder) }, icon = Icons.Filled.Folder)
        }
    }
}

/** The shared lazy shells use this target for their explicit right-edge focus exit. */
@Composable
internal fun GameLibraryAlphabetLayout(
    letters: List<Char>,
    selectedLetter: Char?,
    firstContentFocus: FocusRequester,
    onReturnToContent: () -> Unit,
    onSelect: (Char) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val alphabetFocus = remember { FocusRequester() }
    CompositionLocalProvider(LocalBrowseRightIndexFocus provides alphabetFocus.takeIf { letters.isNotEmpty() }) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
            if (letters.isNotEmpty()) AlphabetBar(
                letters = letters,
                selected = selectedLetter,
                gridFocus = firstContentFocus,
                onReturnToContent = onReturnToContent,
                onSelect = { it?.let(onSelect) },
                firstEntryFocusRequester = alphabetFocus,
            )
        }
    }
}

private val GameTileContentPadding = 9.dp
private val GameRowHorizontalPadding = 4.dp

/** Bound a whole card to the row viewport; its inner artwork still keeps its exact ratio. */
@Composable
internal fun GameLibraryCoverRow(
    games: List<LibraryGame>,
    artworkHeight: Dp,
    rowKey: String,
    modifier: Modifier = Modifier,
    rowFocus: RowColumnFocus? = null,
    itemContent: @Composable (LibraryGame, Modifier, Float) -> Unit,
) {
    val rowState = rememberLazyListState()
    // Also registers the browse focus return, like RegisterBrowseFocusList did on its own.
    RegisterRowColumnFocus(rowFocus, rowKey, rowState, remember(games) { games.map { it.fileId } })
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // A lazy row measures its children without a width bound. Reserve both its insets
        // and the tile's own padding before applying the usual artwork width limits.
        val maxArtworkWidth = (maxWidth - GameRowHorizontalPadding * 2 - GameTileContentPadding * 2)
            .coerceAtLeast(1.dp)
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = GameRowHorizontalPadding, vertical = 5.dp),
        ) {
            items(games, key = { it.fileId }) { game ->
                val stageWidth = (artworkHeight * game.coverAspectRatio)
                    .coerceIn(84.dp, 360.dp).coerceAtMost(maxArtworkWidth)
                itemContent(game, Modifier.width(stageWidth + GameTileContentPadding * 2), stageWidth / artworkHeight)
            }
        }
    }
}

/**
 * A library card with the same focus treatment as [com.ambient.tv.ui.common.MediaTile] on Home:
 * the cover frame grows and gets the accent border, the labels stay put. On a TV the favorite is a
 * badge (Info/Menu or the detail page toggles it); touch screens keep a separate heart button.
 */
@Composable
internal fun GameTile(
    game: LibraryGame,
    modifier: Modifier = Modifier,
    artworkSlotAspectRatio: Float = game.coverAspectRatio,
    rowKey: String? = null,
    showSystem: Boolean = true,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    onFavorite: () -> Unit,
    onFocus: () -> Unit,
    onRequester: (FocusRequester) -> Unit,
    onDisposed: () -> Unit,
) {
    var focused by remember(game.fileId) { mutableStateOf(false) }
    val requester = remember(game.fileId) { FocusRequester() }
    DisposableEffect(requester) { onRequester(requester); onDispose(onDisposed) }
    val handheld = LocalHandheldUi.current
    val shape = RoundedCornerShape(com.ambient.tv.ui.theme.LocalUiTokens.current.cardRadius)
    val activate = rememberUiSoundAction(onClick = onPlay)
    val showInfo = rememberUiSoundAction(onClick = onInfo)
    val uiSounds = LocalUiSoundPlayer.current
    val infoLabel = stringResource(R.string.game_detail_title)
    Box(modifier.fillMaxWidth()) {
        Surface(onClick = activate,
            colors = transparentSurfaceColors(), border = noSurfaceBorder(), scale = noSurfaceScale(),
            shape = ClickableSurfaceDefaults.shape(shape),
            modifier = Modifier.fillMaxWidth().focusRequester(requester)
                .browseFocusTarget(game.fileId, rowKey = rowKey)
                .activateOnTvKey(onClick = activate, onLongClick = showInfo)
                .bringFocusedContextIntoView()
                .semantics { customActions = listOf(CustomAccessibilityAction(infoLabel) { showInfo(); true }) }
                .testTag("game-select:${game.fileId}")
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) { uiSounds?.playFocus(); onFocus() }
                },
        ) {
            Column(
                Modifier.padding(GameTileContentPadding),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                // A shared invisible slot keeps a mixed grid's title baselines aligned. The
                // visible cover frame itself follows its native shape, including the fallback.
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(artworkSlotAspectRatio)
                    .testTag("game-cover-slot:${game.fileId}"), contentAlignment = Alignment.BottomCenter) {
                    val size = gameCoverFrameSize(maxWidth, maxHeight, game.coverAspectRatio)
                    Box(
                        Modifier.size(size.width, size.height).mediaFocusMotion(focused)
                            .animatedFocusBorder(focused, shape,
                                focusedColor = MaterialTheme.colorScheme.primary.copy(alpha = .96f),
                                unfocusedColor = Color.Transparent)
                            .testTag("game-cover:${game.fileId}"),
                    ) {
                        GameCoverArtwork(game.coverPath?.let { java.io.File(it) }, game.title, game.systemId,
                            modifier = Modifier.fillMaxSize().padding(2.dp).clip(shape), focused = focused)
                        if (game.favorite && !handheld) androidx.tv.material3.Icon(
                            Icons.Filled.Favorite,
                            contentDescription = stringResource(R.string.radio_remove_favorite),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp)
                                .background(Color.Black.copy(alpha = .55f), CircleShape).padding(3.dp)
                                .testTag("game-favorite-badge:${game.fileId}"),
                        )
                    }
                }
                Text(game.title, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("game-title:${game.fileId}"))
                if (showSystem) Text(GameSystems.byId(game.systemId)?.shortName ?: game.systemId,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        // Touch only: a heart inside the card is no D-pad target of its own on a TV.
        if (handheld) GameFavoriteButton(game, onFavorite, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

@Composable
internal fun GameListEntry(
    game: LibraryGame,
    modifier: Modifier = Modifier,
    density: Float = 1f,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    onFavorite: () -> Unit,
    onFocus: () -> Unit,
    onRequester: (FocusRequester) -> Unit,
    onDisposed: () -> Unit,
) {
    val requester = remember(game.fileId) { FocusRequester() }
    DisposableEffect(requester) { onRequester(requester); onDispose(onDisposed) }
    var focused by remember(game.fileId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val activate = rememberUiSoundAction(onClick = onPlay)
    val showInfo = rememberUiSoundAction(onClick = onInfo)
    val uiSounds = LocalUiSoundPlayer.current
    val infoLabel = stringResource(R.string.game_detail_title)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(onClick = activate, colors = transparentSurfaceColors(),
            border = noSurfaceBorder(), scale = noSurfaceScale(), shape = ClickableSurfaceDefaults.shape(shape),
            modifier = Modifier.weight(1f).focusRequester(requester).browseFocusTarget(game.fileId)
                .activateOnTvKey(onClick = activate, onLongClick = showInfo)
                .mediaFocusMotion(focused, focusedScale = 1.012f).bringFocusedContextIntoView()
                .semantics { customActions = listOf(CustomAccessibilityAction(infoLabel) { showInfo(); true }) }
                .testTag("game-select:${game.fileId}")
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) { uiSounds?.playFocus(); onFocus() } },
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp * density)
                .animatedFocusSurface(focused = focused, shape = shape,
                    focusedBackground = MaterialTheme.colorScheme.primary.copy(alpha = .09f),
                    unfocusedBackground = MaterialTheme.colorScheme.surface.copy(alpha = .58f),
                    focusedBorder = MaterialTheme.colorScheme.primary,
                    unfocusedBorder = Color.White.copy(alpha = .06f), borderWidth = 1.5.dp)
                .padding(start = 10.dp, end = 16.dp, top = 8.dp * density, bottom = 8.dp * density),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // Artwork on the left like the other libraries' lists; one height, the cover's own width.
                val coverHeight = 46.dp * density
                Box(Modifier.size(coverHeight * 1.5f, coverHeight).testTag("game-list-cover:${game.fileId}"),
                    contentAlignment = Alignment.Center) {
                    val ratio = game.coverAspectRatio.coerceIn(.55f, 1.5f)
                    GameCoverArtwork(game.coverPath?.let { java.io.File(it) }, game.title, game.systemId,
                        modifier = Modifier.size(coverHeight * ratio, coverHeight).clip(RoundedCornerShape(4.dp)),
                        focused = focused)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    com.ambient.tv.ui.common.MarqueeTitle(game.title, focused = focused,
                        style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(GameSystems.byId(game.systemId)?.shortName ?: game.systemId,
                        game.metadataYear?.toString()).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        GameFavoriteButton(game, onFavorite)
    }
}

@Composable
private fun GameFavoriteButton(game: LibraryGame, onFavorite: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(if (game.favorite) R.string.radio_remove_favorite else R.string.radio_add_favorite)
    TvActionButton("", onClick = onFavorite, selected = game.favorite,
        icon = if (game.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        modifier = modifier.semantics { contentDescription = label }.testTag("game-favorite:${game.fileId}"))
}
