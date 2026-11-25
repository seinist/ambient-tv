package com.ambient.tv.ui.romm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.PageFocusLevel
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.pageFocusStage
import com.ambient.tv.ui.common.pageHeaderTop
import com.ambient.tv.ui.common.systemName
import com.ambient.tv.ui.games.GameLibraryGames
import com.ambient.tv.ui.games.GameLibraryHeader
import com.ambient.tv.ui.games.GameLibrarySections
import com.ambient.tv.ui.games.GameLibrarySortBar
import com.ambient.tv.ui.games.GameLibraryViewOptionsPanel
import com.ambient.tv.ui.games.rememberGameLibraryBrowseControls
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.theme.screenGutter

/**
 * RomM's library with the game library's own building blocks: the same header, section chips,
 * sort bar, view options and grid/list/rows, so both feel like one system. The sections are
 * All, Favorites, Recent and RomM's platforms with the names given there.
 */
@Composable
fun RommLibraryScreen(
    initialSection: String,
    onSelect: (LibraryGame) -> Unit,
    onOpenDetails: (LibraryGame) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    model: RommLibraryViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsState()
    val library = state.library
    val controls = rememberGameLibraryBrowseControls()
    var showSearch by remember { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val rowsState = rememberLazyListState()
    val itemRequests = remember { mutableMapOf<String, FocusRequester>() }
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }

    var selectedSection by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    LaunchedEffect(initialSection) { model.section(selectedSection) }
    LaunchedEffect(searchText) { model.query(searchText) }
    ReportSidebarContentReady(library.ready)
    BackHandler { if (controls.showOptions) controls.closeOptions() else onBack() }

    val games = library.games
    com.ambient.tv.ui.common.BrowseFocusContentIdentity(
        listOf(library.section, library.view, library.sortKey, library.sortDescending), restoreInitialTarget = true,
    )
    val initialGame = games.firstOrNull { it.fileId == focusedId } ?: games.firstOrNull()
    if (initialGame != null) com.ambient.tv.ui.common.BrowseFocusInitialTarget(
        initialGame.fileId, row = rommRowKey(initialGame).takeIf { library.view == "rows" },
    )
    val sections = remember(state.platforms) { listOf("all", "favorites", "recent") + state.platforms.map { it.section } }
    val platformsBySection = remember(state.platforms) { state.platforms.associateBy { it.section } }
    val rommName = systemName(MediaSystem.ROMM)
    // Offline shows beside the header actions, the way every server page shows it.
    val offlineStatus = if (state.offline || state.serverOffline) com.ambient.tv.ui.home.offlineSourcesStatus(listOf("romm")) else null

    androidx.compose.runtime.CompositionLocalProvider(com.ambient.tv.ui.common.LocalPageOfflineStatus provides offlineStatus) {
    Column(
        Modifier.fillMaxSize().padding(start = screenGutter(), end = screenGutter(),
            top = pageHeaderTop(screenGutter()), bottom = screenGutter()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GameLibraryHeader(library, managing = false, searchShown = showSearch, controls = controls,
            onManage = null, onSearch = { showSearch = !showSearch }, onRefresh = model::refresh,
            title = platformsBySection[library.section]?.title ?: rommName, system = MediaSystem.ROMM)
        GameLibrarySections(sections, library.section, controls,
            platform = { key -> platformsBySection[key]?.let { it.title to it.systemId } }) {
            selectedSection = it
            model.section(it)
            focusedId = null
        }
        // Offline is in the header; a refused sign-in or another server error says what happened.
        state.error?.takeIf { !state.offline }?.let { error ->
            Text(error,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.signInRejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().pageFocusStage(controls.focusChain, PageFocusLevel.CONTEXT, controls.scanFocus))
        }
        if (showSearch) TvTextField(searchText, { searchText = it },
            stringResource(R.string.action_search), Modifier.fillMaxWidth()
                .pageFocusStage(controls.focusChain, PageFocusLevel.SEARCH_LOWER, controls.searchFocus))
        GameLibrarySortBar(library, controls, onSort = model::sort, onSortDescending = model::sortDescending)
        when {
            !state.signedIn -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.romm_library_signed_out), style = MaterialTheme.typography.bodyMedium)
                TvActionButton(stringResource(R.string.romm_open_settings), onOpenSettings, icon = Icons.Filled.Settings)
            }
            games.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(when {
                    library.busy || !library.ready -> R.string.player_loading
                    !state.hasCatalog -> R.string.romm_library_not_loaded
                    library.query.isNotBlank() || library.section != "all" -> R.string.game_library_no_results
                    else -> R.string.romm_library_empty
                }), style = MaterialTheme.typography.bodyMedium)
            }
            else -> GameLibraryGames(
                games = games, view = library.view, section = library.section, sortKey = library.sortKey,
                gridColumns = library.gridColumns, listRows = library.listRows, controls = controls,
                focusedId = focusedId, onFocused = { focusedId = it }, itemRequests = itemRequests,
                listState = listState, gridState = gridState, rowsState = rowsState,
                onSelect = { focusedId = it.fileId; onSelect(it) },
                onOpenDetails = { focusedId = it.fileId; onOpenDetails(it) }, onFavorite = model::favorite,
                rowKey = ::rommRowKey, rowTitle = { it },
                showSystem = library.section !in platformsBySection,
            )
        }
    }
    }
    GameLibraryViewOptionsPanel(library, controls,
        onView = model::view, onGridColumns = model::gridColumns, onListRows = model::listRows,
        onSort = model::sort, onSortDescending = model::sortDescending, onTitleStyle = model::titleStyle)
}

/** Rows follow RomM's platforms (their names), not the consoles they map to. */
internal fun rommRowKey(game: LibraryGame): String = game.metadataPlatform ?: game.systemId
