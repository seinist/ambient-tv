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
import com.ambient.tv.games.metadata.GameNameDatabases
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.games.library.coverModel
import com.ambient.tv.games.library.coverAspectRatio
import com.ambient.tv.games.library.GameLibraryFolder
import com.ambient.tv.ui.common.AlphabetBar
import com.ambient.tv.ui.common.alphabetTrackedLetter
import com.ambient.tv.games.library.gameIndexLetter
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
import com.ambient.tv.ui.common.browseCardSurface
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
import com.ambient.tv.ui.theme.animatedFocusSurface
import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.sound.LocalUiSoundPlayer
import com.ambient.tv.ui.sound.rememberUiSoundAction
import com.ambient.tv.ui.mobile.deviceDensityGridCells
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/** The folder manager keeps one destination visible at a time; neither ROMs nor OSD are altered. */
internal enum class GameLibraryManagerPage { HOME, ADD, SOURCES, FOLDER }

/** The existing app's Files source and game player remain responsible for transport and playback. */
@Composable
fun GameLibraryScreen(
    initialSection: String,
    onSelect: (LibraryGame) -> Unit,
    onOpenDetails: (LibraryGame) -> Unit,
    onOpenGameSettings: (String?) -> Unit = {},
    startManaging: Boolean = false,
    onBack: () -> Unit,
    model: GameLibraryViewModel = hiltViewModel(),
    embeddedManager: Boolean = false,
    modifier: Modifier = Modifier,
    focusSettingsContent: () -> Unit = {},
) {
    val state by model.state.collectAsState()
    val managing = startManaging || embeddedManager
    var choosingSystem by rememberSaveable { mutableStateOf(false) }
    var networkEditor by remember { mutableStateOf<FileSource?>(null) }
    var removing by remember { mutableStateOf<GameLibraryFolder?>(null) }
    var metadataSettingsOpen by remember { mutableStateOf(false) }
    var choosingDatSystem by remember { mutableStateOf(false) }
    var choosingNameDatabase by remember { mutableStateOf(false) }
    var datSystemId by rememberSaveable { mutableStateOf("snes") }
    var managerPage by rememberSaveable { mutableStateOf(GameLibraryManagerPage.HOME) }
    var selectedFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    val controls = rememberGameLibraryBrowseControls()
    val handheld = LocalHandheldUi.current
    val metadataSettingsFocus = remember { FocusRequester() }
    var restoreMetadataFocus by remember { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val rowsState = rememberLazyListState()
    val itemRequests = remember { mutableMapOf<String, FocusRequester>() }
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchText by rememberSaveable { mutableStateOf("") }

    // The route is an initial entry point, not the currently selected chip. Navigation
    // Compose saves this selection with the back stack entry while the player is open.
    var selectedSection by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    LaunchedEffect(initialSection, managing) {
        if (managing) model.openManager() else model.section(selectedSection)
    }
    LaunchedEffect(searchText, managing) { if (!managing) model.query(searchText) }
    if (!embeddedManager) ReportSidebarContentReady(state.ready)

    fun handleBack() {
        when {
            removing != null -> removing = null
            metadataSettingsOpen -> { metadataSettingsOpen = false; restoreMetadataFocus = true }
            controls.showOptions -> controls.closeOptions()
            choosingDatSystem -> choosingDatSystem = false
            choosingNameDatabase -> choosingNameDatabase = false
            choosingSystem -> choosingSystem = false
            state.selectedSource != null -> if (!model.folderUp()) model.closePicker()
            managerPage != GameLibraryManagerPage.HOME -> { managerPage = GameLibraryManagerPage.HOME; selectedFolderId = null }
            else -> onBack()
        }
    }
    val settingsBack = com.ambient.tv.ui.settings.LocalSettingsNestedBack.current
    if (embeddedManager) {
        SideEffect { settingsBack?.onBack = ::handleBack }
        DisposableEffect(settingsBack) { onDispose { settingsBack?.onBack = null } }
        LaunchedEffect(managerPage, choosingSystem, choosingDatSystem, choosingNameDatabase,
            state.selectedSource?.id, state.browsedPaths.lastOrNull()?.first) {
            if (!handheld) { withFrameNanos { }; focusSettingsContent() }
        }
    } else BackHandler { handleBack() }
    val sourcesById = remember(state.sources) { state.sources.associateBy { it.id } }
    // The repository returns just this section/search in final order, off the UI thread.
    val games = state.games
    // Like the other libraries: entering from the sidebar or changing section, view or sort lands
    // on the remembered or first game, not on the header chips.
    if (!managing) com.ambient.tv.ui.common.BrowseFocusContentIdentity(
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
        if (embeddedManager) modifier.fillMaxSize() else modifier.fillMaxSize().padding(
            start = screenGutter(), end = screenGutter(), top = pageHeaderTop(screenGutter()), bottom = screenGutter()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!embeddedManager) GameLibraryHeader(state, managing, showSearch, controls,
            onManage = { if (managing) onBack() else onOpenGameSettings("library") },
            onSearch = { showSearch = !showSearch }, onRefresh = model::refreshSection)
        if (embeddedManager && managerPage == GameLibraryManagerPage.HOME && !choosingSystem &&
            !choosingDatSystem && !choosingNameDatabase && state.selectedSource == null) {
            com.ambient.tv.ui.settings.SettingsSubheader(
                stringResource(R.string.games_settings_library_heading), firstInContent = true)
        }
        if (!managing) {
            GameLibrarySections(sections, state.section, controls) {
                selectedSection = it
                model.section(it)
                focusedId = null
            }
            if (state.scanning) {
                Row(Modifier.fillMaxWidth().pageFocusStage(controls.focusChain,
                    PageFocusLevel.CONTEXT, controls.scanFocus),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        gameLibraryProgressMessage(androidx.compose.ui.platform.LocalContext.current, state),
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
                        { onOpenGameSettings("library") }, icon = Icons.Filled.CreateNewFolder)
                }
            } else if (games.isEmpty()) {
                Text(if (state.busy) stringResource(R.string.player_loading)
                    else stringResource(R.string.game_library_no_results),
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                GameLibraryGames(
                    games = games, view = state.view, section = state.section, sortKey = state.sortKey,
                    gridColumns = state.gridColumns, listRows = state.listRows, controls = controls,
                    focusedId = focusedId, onFocused = { focusedId = it }, itemRequests = itemRequests,
                    listState = listState, gridState = gridState, rowsState = rowsState,
                    onSelect = { focusedId = it.fileId; onSelect(it) },
                    onOpenDetails = { focusedId = it.fileId; onOpenDetails(it) }, onFavorite = model::favorite,
                )
            }
        } else {
            val managerModifier = Modifier.weight(1f).fillMaxWidth()
            Column(if (embeddedManager) managerModifier else managerModifier.pageContentUp(controls.focusChain).focusGroup()) {
                if (state.scanning && !choosingNameDatabase &&
                    (managerPage != GameLibraryManagerPage.FOLDER || state.activeScanFolderId != selectedFolderId)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(gameLibraryProgressMessage(androidx.compose.ui.platform.LocalContext.current, state),
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TvChip(text = stringResource(R.string.game_library_scan_cancel), icon = Icons.Filled.Close,
                            iconOnly = true, onClick = { model.cancelScan() })
                    }
                }
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
                } else if (choosingNameDatabase) {
                    GameNameDatabasePage(state, onLoad = model::loadNameDatabases, onCancel = model::cancelScan)
                } else {
                    GameFolderManager(
                        state = state, choosingSystem = choosingSystem,
                        managerPage = managerPage, selectedFolderId = selectedFolderId,
                        onManagerPage = { managerPage = it },
                        onOpenFolder = { selectedFolderId = it.id; managerPage = GameLibraryManagerPage.FOLDER },
                        onChooseSystem = { choosingSystem = it },
                        onSelectSource = model::selectSource,
                        onEnter = model::enterFolder,
                        onAdd = { system ->
                            if (model.assign(system)) {
                                choosingSystem = false
                                managerPage = GameLibraryManagerPage.HOME
                                selectedFolderId = null
                            }
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
                        onLoadNameDatabases = {
                            choosingNameDatabase = true
                            model.refreshNameCatalogs()
                        },
                        onMetadataSettings = { metadataSettingsOpen = true },
                        onOpenSystemsSettings = { onOpenGameSettings("systems") },
                        onOpenAllSettings = { onOpenGameSettings("home") },
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
                    managerPage = GameLibraryManagerPage.HOME
                    selectedFolderId = null
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
    if (!managing) GameLibraryViewOptionsPanel(state, controls,
        onView = model::view, onGridColumns = model::gridColumns, onListRows = model::listRows,
        onSort = model::sort, onSortDescending = model::sortDescending, onTitleStyle = model::titleStyle)
}

/**
 * The games of a library as grid, list or rows, with the A–Z bar: shared by the game library and
 * RomM, so both look and move the same. [rowKey] groups the rows (console, or RomM platform).
 */
@Composable
internal fun GameLibraryGames(
    games: List<LibraryGame>,
    view: String,
    section: String,
    sortKey: String,
    gridColumns: Int,
    listRows: Int,
    controls: GameLibraryBrowseControls,
    focusedId: String?,
    onFocused: (String?) -> Unit,
    itemRequests: MutableMap<String, FocusRequester>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    rowsState: androidx.compose.foundation.lazy.LazyListState,
    onSelect: (LibraryGame) -> Unit,
    onOpenDetails: (LibraryGame) -> Unit,
    onFavorite: (LibraryGame) -> Unit,
    rowKey: (LibraryGame) -> String = { it.systemId },
    rowTitle: (String) -> String = { GameSystems.byId(it)?.name ?: it },
    showSystem: Boolean = GameSystems.byId(section) == null,
) {
    val scope = rememberCoroutineScope()
    val handheld = LocalHandheldUi.current
    var alphabetJumpJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(games, view, section, handheld) {
        onDispose { alphabetJumpJob?.cancel() }
    }
    val gameIds = remember(games) { games.map { it.fileId } }
    val alphabet = remember(games, section, view, sortKey) {
        if (section == "recent" || view == "rows" || sortKey != "name") emptyList()
        else games.map { gameIndexLetter(it.title) }.distinct()
    }
        val firstFocus = remember(section, view) { FocusRequester() }
        // Keep geometry fixed for this result set while background artwork finishes.
        // Existing local dimensions are already present in the initial query.
        // The usual shape of this result set sets the slot; a rare tall or wide cover is fitted
        // into it instead of making every other cover shrink (median, not the narrowest).
        val gridSlotRatio = remember(section, view, gameIds) {
            games.map { it.coverAspectRatio }.sorted().let { it[it.size / 2] }.coerceIn(.6f, 1.8f)
        }
        val rowArtworkHeight = (148f * 8f / gridColumns.coerceAtLeast(1)).coerceIn(96f, 216f).dp
        GameLibraryAlphabetLayout(
            letters = alphabet,
            selectedLetter = games.firstOrNull { it.fileId == focusedId }
                ?.let { gameIndexLetter(it.title) },
            visibleLetter = {
                val index = if (view == "list") listState.firstVisibleItemIndex else gridState.firstVisibleItemIndex
                games.getOrNull(index)?.let { gameIndexLetter(it.title) }
            },
            firstContentFocus = firstFocus,
            onReturnToContent = {
                focusedId?.let { itemRequests[it] }?.let { runCatching { it.requestFocus() } }
                    ?: runCatching { firstFocus.requestFocus() }
            },
            onSelect = { letter ->
                val index = games.indexOfFirst { gameIndexLetter(it.title) == letter }
                if (index >= 0) {
                    alphabetJumpJob?.cancel()
                    alphabetJumpJob = scope.launch {
                        if (view == "list") listState.scrollToItem(index)
                        else gridState.scrollToItem(index)
                        // Touch scrubbing owns the viewport; a delayed item focus would pull it
                        // back to an earlier letter while the finger is already moving on.
                        if (!handheld) {
                            withFrameNanos { }
                            itemRequests[games[index].fileId]?.let { runCatching { it.requestFocus() } }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize().pageContentUp(controls.focusChain).focusGroup(),
        ) {
            if (view == "list") {
                RegisterBrowseFocusList(listState, gameIds)
                TvLazyColumn(modifier = Modifier.weight(1f).fillMaxHeight(),
                    state = listState, focusActionsOnRight = false, allowUpExit = true,
                    verticalArrangement = Arrangement.spacedBy((9f * 7 / listRows).coerceIn(6f, 16f).dp)) {
                    items(games, key = { it.fileId }) { game ->
                        GameListEntry(game,
                            onPlay = { onSelect(game) }, onInfo = { onOpenDetails(game) },
                            onFavorite = { onFavorite(game) },
                            density = (7f / listRows.coerceIn(4, 10)).coerceIn(.72f, 1.75f),
                            onFocus = { onFocused(game.fileId) },
                            onRequester = { itemRequests[game.fileId] = it },
                            onDisposed = { itemRequests.remove(game.fileId) },
                            modifier = if (games.firstOrNull()?.fileId == game.fileId)
                                Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
            } else if (view == "rows") {
                // As on Home, each console has a horizontal row with its own lazy
                // viewport. Entirely cached data means the NAS never wakes for layout.
                val grouped = remember(games) {
                    games.groupBy(rowKey).toList().sortedBy { rowTitle(it.first).lowercase(Locale.ROOT) }
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
                            ContentSectionHeader(rowTitle(system), sectionKey = system)
                            GameLibraryCoverRow(entries, rowArtworkHeight, system, rowFocus = rowFocus) { game, tileModifier, slotRatio ->
                                GameTile(game,
                                    artworkSlotAspectRatio = slotRatio,
                                    rowKey = system,
                                    showSystem = false,
                                    onPlay = { onSelect(game) },
                                    onInfo = { onOpenDetails(game) },
                                    onFocus = { onFocused(game.fileId) },
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
                    columns = deviceDensityGridCells(gridColumns, 150.dp, 8),
                    modifier = Modifier.weight(1f).fillMaxHeight().focusGroup(),
                    state = gridState,
                    contentPadding = PaddingValues(bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(games, key = { it.fileId }) { game ->
                        GameTile(game,
                            artworkSlotAspectRatio = gridSlotRatio,
                            showSystem = showSystem,
                            onPlay = { onSelect(game) }, onInfo = { onOpenDetails(game) },
                            onFocus = { onFocused(game.fileId) },
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

/**
 * One page per panel, like the other sub pages: load the names of every library system at once
 * or of single systems, library systems first. Each row says how many names its catalog holds.
 */
@Composable
private fun GameNameDatabasePage(state: GameLibraryUiState, onLoad: (String?) -> Unit, onCancel: () -> Unit) {
    val available = remember { GameSystems.alphabetical.filter { GameNameDatabases.files(it.id).isNotEmpty() } }
    val (inLibrary, others) = remember(state.systems) { available.partition { it.id in state.systems } }
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val numbers = remember { java.text.NumberFormat.getIntegerInstance() }
    fun status(systemId: String): String {
        val count = state.nameCatalogs[systemId] ?: 0
        return if (count > 0) resources.getString(R.string.game_name_databases_count, numbers.format(count))
            else resources.getString(R.string.game_name_databases_missing)
    }
    com.ambient.tv.ui.settings.SettingsSubheader(stringResource(R.string.game_name_databases_load), firstInContent = true)
    TvLazyColumn(modifier = Modifier.fillMaxSize(), allowUpExit = true,
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { com.ambient.tv.ui.common.SettingsHint(stringResource(R.string.game_name_databases_page_hint)) }
        state.error?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } }
        state.message?.let { message -> item { com.ambient.tv.ui.common.SettingsHint(message) } }
        if (state.scanning) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(gameLibraryProgressMessage(androidx.compose.ui.platform.LocalContext.current, state),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TvActionButton(stringResource(R.string.game_library_scan_cancel), onClick = onCancel,
                    icon = Icons.Filled.Close)
            }
        }
        if (inLibrary.isNotEmpty()) {
            item {
                com.ambient.tv.ui.common.SettingsNavigationRow(title = stringResource(R.string.game_name_databases_library),
                    onClick = { onLoad(null) }, enabled = !state.scanning, icon = Icons.Filled.CloudDownload)
            }
            items(inLibrary, key = { it.id }) { system ->
                com.ambient.tv.ui.common.SettingsNavigationRow(title = system.name, summary = status(system.id),
                    onClick = { onLoad(system.id) }, enabled = !state.scanning, icon = gameSystemVector(system.id))
            }
        }
        if (others.isNotEmpty()) {
            item { com.ambient.tv.ui.settings.SettingsSubheader(stringResource(R.string.game_name_databases_more)) }
            items(others, key = { it.id }) { system ->
                com.ambient.tv.ui.common.SettingsNavigationRow(title = system.name, summary = status(system.id),
                    onClick = { onLoad(system.id) }, enabled = !state.scanning, icon = gameSystemVector(system.id))
            }
        }
    }
}

@Composable
private fun GameFolderManager(
    state: GameLibraryUiState,
    choosingSystem: Boolean,
    managerPage: GameLibraryManagerPage,
    selectedFolderId: String?,
    onManagerPage: (GameLibraryManagerPage) -> Unit,
    onOpenFolder: (GameLibraryFolder) -> Unit,
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
    onLoadNameDatabases: () -> Unit,
    onMetadataSettings: () -> Unit,
    onOpenSystemsSettings: () -> Unit,
    onOpenAllSettings: () -> Unit,
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
        val folder = state.folders.firstOrNull { it.id == selectedFolderId }
        when {
            managerPage == GameLibraryManagerPage.FOLDER && folder != null -> {
                TvLazyColumn(modifier = Modifier.fillMaxSize(), allowUpExit = true,
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    item {
                        com.ambient.tv.ui.settings.SettingsSubheader(
                            stringResource(R.string.game_library_folder_details), firstInContent = true)
                    }
                    item {
                        Text((GameSystems.byId(folder.systemId)?.shortName ?: stringResource(R.string.game_library_auto)) +
                            " · " + folder.path, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        Text(state.sources.firstOrNull { it.id == folder.sourceId }?.name
                            ?: stringResource(R.string.game_folder_fallback),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.activeScanFolderId == folder.id) item {
                        Text(gameLibraryProgressMessage(androidx.compose.ui.platform.LocalContext.current, state),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        com.ambient.tv.ui.common.SettingsActionRow {
                            if (state.activeScanFolderId == folder.id)
                                TvActionButton(stringResource(R.string.game_library_scan_cancel),
                                    onClick = onCancelScan, icon = Icons.Filled.Close)
                            else TvActionButton(stringResource(R.string.game_library_folder_scan_only),
                                onClick = { onScan(folder) },
                                enabled = !state.scanning && folder.id !in state.removingFolders,
                                icon = Icons.Filled.Refresh)
                        }
                    }
                    item {
                        com.ambient.tv.ui.common.SettingsActionRow {
                            TvActionButton(stringResource(R.string.game_library_folder_enrich_only),
                                onClick = { onEnrich(folder) },
                                enabled = !state.scanning && folder.id !in state.removingFolders,
                                icon = Icons.Filled.Image)
                        }
                    }
                    item {
                        com.ambient.tv.ui.common.SettingsActionRow {
                            TvActionButton(stringResource(R.string.game_library_show_in_files),
                                onClick = { onFolderFileVisibility(folder, !folder.alsoShowInFiles) },
                                enabled = folder.id !in state.removingFolders, selected = folder.alsoShowInFiles,
                                icon = if (folder.alsoShowInFiles) Icons.Filled.Visibility else Icons.Filled.VisibilityOff)
                        }
                    }
                    item {
                        com.ambient.tv.ui.common.SettingsActionRow {
                            TvActionButton(stringResource(R.string.game_library_remove),
                                onClick = { onRemove(folder) }, enabled = folder.id !in state.removingFolders,
                                icon = Icons.Filled.Delete)
                        }
                    }
                    if (folder.id in state.removingFolders) item {
                        Text(stringResource(R.string.game_folder_removing), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            managerPage == GameLibraryManagerPage.ADD -> {
                TvLazyColumn(modifier = Modifier.fillMaxSize(), allowUpExit = true,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { com.ambient.tv.ui.settings.SettingsSubheader(
                        stringResource(R.string.game_library_add_options), firstInContent = true) }
                    item { com.ambient.tv.ui.common.SettingsNavigationRow(
                        title = stringResource(R.string.game_library_add_local),
                        onClick = onAddLocal, icon = Icons.Filled.CreateNewFolder) }
                    item { com.ambient.tv.ui.common.SettingsNavigationRow(
                        title = stringResource(R.string.game_library_add_network),
                        onClick = onAddNetwork, icon = Icons.Filled.Storage) }
                    item { com.ambient.tv.ui.settings.SettingsSubheader(
                        stringResource(R.string.game_library_choose_source)) }
                    items(state.sources, key = { "source-select:" + it.id }) { entry ->
                        com.ambient.tv.ui.common.SettingsNavigationRow(
                            title = entry.name, summary = entry.kind.uppercase(),
                            onClick = { onSelectSource(entry) }, icon = Icons.Filled.Folder)
                    }
                    item { com.ambient.tv.ui.common.SettingsHint(stringResource(R.string.game_library_add_network_hint)) }
                }
            }
            managerPage == GameLibraryManagerPage.SOURCES -> {
                TvLazyColumn(modifier = Modifier.fillMaxSize(), allowUpExit = true,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { com.ambient.tv.ui.settings.SettingsSubheader(
                        stringResource(R.string.game_library_sources_manage), firstInContent = true) }
                    item { com.ambient.tv.ui.common.SettingsHint(stringResource(R.string.game_library_sources_hint)) }
                    items(state.sources, key = { "source-manage:" + it.id }) { entry ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.ambient.tv.ui.common.SettingsNavigationRow(
                                title = entry.name,
                                summary = entry.kind.uppercase() + " · " + stringResource(if (entry.showInFiles)
                                    R.string.game_library_source_files_games else R.string.game_library_source_games_only),
                                onClick = { onSelectSource(entry) }, icon = Icons.Filled.Folder,
                                modifier = Modifier.weight(1f))
                            if (!entry.showInFiles && entry.network) TvActionButton(stringResource(R.string.files_edit),
                                onClick = { onEditSource(entry) }, icon = Icons.Filled.Edit)
                            if (!entry.showInFiles && state.folders.none { it.sourceId == entry.id })
                                TvActionButton(stringResource(R.string.game_library_remove),
                                    onClick = { onDeleteSource(entry) }, icon = Icons.Filled.Delete)
                        }
                    }
                }
            }
            else -> {
                TvLazyColumn(modifier = Modifier.fillMaxSize(), allowUpExit = true,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { com.ambient.tv.ui.common.SettingsNavigationRow(
                        title = stringResource(R.string.game_library_add_options),
                        onClick = { onManagerPage(GameLibraryManagerPage.ADD) }, icon = Icons.Filled.CreateNewFolder) }
                    item { com.ambient.tv.ui.common.SettingsNavigationRow(
                        title = stringResource(R.string.games_settings_metadata_heading),
                        summary = stringResource(R.string.games_settings_metadata_summary),
                        onClick = onMetadataSettings, modifier = metadataSettingsModifier,
                        icon = Icons.Filled.Settings) }
                    item { com.ambient.tv.ui.common.SettingsNavigationRow(
                        title = stringResource(R.string.games_settings_systems_heading),
                        summary = stringResource(R.string.games_settings_systems_summary),
                        onClick = onOpenSystemsSettings, icon = Icons.Filled.SportsEsports) }
                    item { com.ambient.tv.ui.common.SettingsNavigationRow(
                        title = stringResource(R.string.games_settings_all_title),
                        summary = stringResource(R.string.games_settings_all_hint),
                        onClick = onOpenAllSettings, icon = Icons.Filled.Tune) }
                    item { com.ambient.tv.ui.common.SettingsNavigationRow(
                        title = stringResource(R.string.game_library_sources_manage),
                        onClick = { onManagerPage(GameLibraryManagerPage.SOURCES) }, icon = Icons.Filled.Storage) }
                    item { com.ambient.tv.ui.settings.SettingsSubheader(
                        stringResource(R.string.game_library_recognition_heading)) }
                    item { com.ambient.tv.ui.common.SettingsNavigationRow(
                        title = stringResource(R.string.game_name_databases_load),
                        summary = stringResource(R.string.game_name_databases_hint),
                        onClick = onLoadNameDatabases, enabled = !state.scanning, icon = Icons.Filled.CloudDownload) }
                    item { com.ambient.tv.ui.common.SettingsNavigationRow(
                        title = stringResource(R.string.game_library_import_dat),
                        onClick = onImportDat, icon = Icons.AutoMirrored.Filled.LibraryBooks) }
                    state.message?.let { feedback ->
                        item { com.ambient.tv.ui.common.SettingsHint(feedback) }
                    }
                    item { com.ambient.tv.ui.settings.SettingsSubheader(
                        stringResource(R.string.game_library_folders_heading)) }
                    items(state.folders, key = { "folder:" + it.id }) { assigned ->
                        val origin = state.sources.firstOrNull { it.id == assigned.sourceId }
                        com.ambient.tv.ui.common.SettingsNavigationRow(
                            title = (GameSystems.byId(assigned.systemId)?.shortName
                                ?: stringResource(R.string.game_library_auto)) + " · " + assigned.path,
                            summary = origin?.name ?: stringResource(R.string.game_folder_fallback),
                            onClick = { onOpenFolder(assigned) }, icon = Icons.Filled.Folder)
                    }
                }
            }
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
    visibleLetter: () -> Char? = { null },
    content: @Composable RowScope.() -> Unit,
) {
    val alphabetFocus = remember { FocusRequester() }
    CompositionLocalProvider(LocalBrowseRightIndexFocus provides alphabetFocus.takeIf { letters.isNotEmpty() }) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
            if (letters.isNotEmpty()) GameLibraryAlphabetBar(
                letters = letters,
                focusedLetter = selectedLetter,
                visibleLetter = visibleLetter,
                gridFocus = firstContentFocus,
                onReturnToContent = onReturnToContent,
                onSelect = { it?.let(onSelect) },
                firstEntryFocusRequester = alphabetFocus,
            )
        }
    }
}

/** Observe the scrolling viewport only here, keeping game cards out of rail recompositions. */
@Composable
private fun GameLibraryAlphabetBar(
    letters: List<Char>,
    focusedLetter: Char?,
    visibleLetter: () -> Char?,
    gridFocus: FocusRequester,
    onReturnToContent: () -> Unit,
    onSelect: (Char?) -> Unit,
    firstEntryFocusRequester: FocusRequester,
) {
    AlphabetBar(
        letters = letters,
        selected = alphabetTrackedLetter(LocalHandheldUi.current, visibleLetter(), focusedLetter),
        gridFocus = gridFocus,
        modifier = Modifier.fillMaxHeight(),
        onReturnToContent = onReturnToContent,
        onSelect = onSelect,
        firstEntryFocusRequester = firstEntryFocusRequester,
    )
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
 * A library card like the SmartTube/YouTube cards: one flat card holds the cover, title and
 * system, and the whole card takes focus (scale, accent fill and outline). Covers keep their
 * own shape inside it; a game without one shows the shared missing-artwork face. Like Radio
 * Browser, the card shows a favorite only as a corner badge; the list row's heart and the detail
 * page change it.
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
    onFocus: () -> Unit,
    onRequester: (FocusRequester) -> Unit,
    onDisposed: () -> Unit,
) {
    var focused by remember(game.fileId) { mutableStateOf(false) }
    val requester = remember(game.fileId) { FocusRequester() }
    DisposableEffect(requester) { onRequester(requester); onDispose(onDisposed) }
    val shape = com.ambient.tv.ui.common.BrowseCardShape
    val artworkShape = com.ambient.tv.ui.common.BrowseCardArtworkShape
    val activate = rememberUiSoundAction(onClick = onPlay)
    val showInfo = rememberUiSoundAction(onClick = onInfo)
    val uiSounds = LocalUiSoundPlayer.current
    val infoLabel = stringResource(R.string.game_detail_title)
    Box(modifier.fillMaxWidth()) {
        Surface(onClick = activate,
            colors = transparentSurfaceColors(), border = noSurfaceBorder(), scale = noSurfaceScale(),
            shape = ClickableSurfaceDefaults.shape(shape),
            modifier = Modifier.fillMaxWidth().focusRequester(requester)
                .mediaFocusMotion(focused)
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
                Modifier.browseCardSurface(focused, shape).padding(GameTileContentPadding),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                // A shared invisible slot keeps a mixed grid's title baselines aligned. The
                // visible cover frame itself follows its native shape, including the fallback.
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(artworkSlotAspectRatio)
                    .testTag("game-cover-slot:${game.fileId}"), contentAlignment = Alignment.BottomCenter) {
                    val size = gameCoverFrameSize(maxWidth, maxHeight, game.coverAspectRatio)
                    Box(
                        Modifier.size(size.width, size.height).clip(artworkShape)
                            .testTag("game-cover:${game.fileId}"),
                    ) {
                        GameCoverArtwork(game.coverModel, game.title, game.systemId,
                            modifier = Modifier.fillMaxSize(), focused = focused, fileId = game.fileId)
                        // Status only, like Radio Browser's station cards: the list's heart, the
                        // detail page and Info/long press change it, never a button on the card.
                        if (game.favorite) com.ambient.tv.ui.common.ArtworkCornerIconBadge(
                            icon = Icons.Filled.Favorite,
                            contentDescription = stringResource(R.string.radio_favorites),
                            modifier = Modifier.align(Alignment.TopEnd).padding(7.dp)
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
                    GameCoverArtwork(game.coverModel, game.title, game.systemId,
                        modifier = Modifier.size(coverHeight * ratio, coverHeight).clip(RoundedCornerShape(4.dp)),
                        focused = focused, fileId = game.fileId)
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
