package com.ambient.tv.ui.moonlight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.games.metadata.GameArtworkPriority
import com.ambient.tv.ui.common.*
import com.ambient.tv.ui.games.*
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.theme.screenGutter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Same lazy layouts, focus chain and view panel as local games and RomM. */
@Composable
fun MoonlightLibraryScreen(
    initialSection: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onDetails: (String, Int) -> Unit,
    onSelect: (String, Int) -> Unit = onDetails,
    model: MoonlightLibraryViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsState()
    val games by model.games.collectAsState()
    val controls = rememberGameLibraryBrowseControls()
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var selectedSection by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val rowsState = rememberLazyListState()
    val requests = remember { mutableMapOf<String, FocusRequester>() }
    val artworkPriority = remember { GameArtworkPriority() }
    val artworkSource = remember(model) { GameArtworkSource(model::artworkPresentation) }
    val gameIndex = remember(games) { games.associateBy { it.key } }
    val currentGames by rememberUpdatedState(gameIndex)
    LaunchedEffect(initialSection) { model.section(selectedSection) }
    LaunchedEffect(searchText) { model.search(searchText) }
    MoonlightRefreshWhileVisible(model)
    ReportSidebarContentReady(state.ready)
    BackHandler {
        when {
            controls.showOptions -> controls.closeOptions()
            searchVisible -> { searchVisible = false; searchText = "" }
            else -> onBack()
        }
    }
    LaunchedEffect(artworkPriority, state.onlineHosts) {
        artworkPriority.demands.collect { demands ->
            demands.forEachIndexed { index, demand ->
                currentGames[demand.fileId]?.let { model.prioritizeArtwork(it, visible = index == 0) }
            }
        }
    }
    val hostBySection = remember(state.hosts) { state.hosts.associateBy { "host:" + it.id } }
    val sections = remember(state.hosts) { listOf("all", "favorites", "recent") + hostBySection.keys }
    val section = state.sections
    fun selectSection(value: String) {
        if (value !in sections || value == selectedSection) return
        selectedSection = value
        focusedId = null
        model.section(value)
    }
    InPlaceSectionHost(MoonlightSectionSelection, section, ::selectSection)
    LaunchedEffect(section, state.localLoaded) {
        if (state.localLoaded && selectedSection !in sections) selectedSection = section
    }
    val sectionHosts = state.hosts.filter { !section.startsWith("host:") || "host:" + it.id == section }
    val offline = sectionHosts.filter { it.id in state.pairedHosts && it.id !in state.onlineHosts && (it.id in state.offlineHosts || state.errors[it.id] == "offline") }
    val offlineStatus = offlineSourcesStatusForMoonlight(offline.map { it.name.ifBlank { it.host } })
    val library = GameLibraryUiState(section = section, query = state.query, view = state.view,
        gridColumns = state.gridColumns, listRows = state.listRows, sortKey = state.sortKey,
        sortDescending = state.sortDescending, busy = state.loadingHosts.isNotEmpty(), ready = state.ready)
    BrowseFocusContentIdentity(listOf(section, state.view, state.sortKey, state.sortDescending, state.query), restoreInitialTarget = true)
    val initial = games.firstOrNull { it.key == focusedId } ?: games.firstOrNull()
    if (initial != null) BrowseFocusInitialTarget(initial.key, row = initial.host.id.takeIf { state.view == "rows" })
    val name = systemName(MediaSystem.MOONLIGHT)
    fun open(game: MoonlightBrowseGame) { focusedId = game.key; onDetails(game.host.id, game.app.id) }
    CompositionLocalProvider(LocalPageOfflineStatus provides offlineStatus, LocalGameArtworkPriority provides artworkPriority, LocalGameArtworkSource provides artworkSource) {
        Column(
            Modifier.fillMaxSize().padding(start = screenGutter(), end = screenGutter(),
                top = pageHeaderTop(screenGutter()), bottom = screenGutter()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GameLibraryHeader(library, managing = false, searchShown = searchVisible, controls = controls,
                onManage = null, onSearch = { searchVisible = !searchVisible; if (!searchVisible) searchText = "" },
                onRefresh = { model.load(force = true, onlyHost = hostBySection[section]?.id) },
                title = hostBySection[section]?.let { it.name.ifBlank { it.host } } ?: name,
                system = MediaSystem.MOONLIGHT, itemCount = games.size, onSettings = onOpenSettings)
            GameLibrarySections(sections, section, controls,
                platform = { key -> hostBySection[key]?.let { it.name.ifBlank { it.host } to "moonlight" } },
                sectionIcon = { key -> if (key in hostBySection) Icons.Filled.Computer else null }) { value ->
                selectSection(value)
            }
            if (searchVisible) TvTextField(searchText, { searchText = it.take(150) }, stringResource(R.string.action_search),
                Modifier.fillMaxWidth().pageFocusStage(controls.focusChain, PageFocusLevel.SEARCH_LOWER, controls.searchFocus))
            GameLibrarySortBar(library, controls, model::sort, model::sortDescending)
            if (state.errors.values.any { it == "identity" }) Text(stringResource(R.string.moonlight_identity_changed),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            when {
                !state.ready -> StatePanel(title = stringResource(R.string.player_loading), modifier = Modifier.weight(1f).fillMaxWidth())
                sectionHosts.isEmpty() -> StatePanel(
                    title = stringResource(R.string.moonlight_library_unconfigured),
                    actionLabel = stringResource(R.string.moonlight_open_settings), onAction = onOpenSettings,
                    modifier = Modifier.weight(1f).fillMaxWidth())
                sectionHosts.none { it.id in state.pairedHosts } -> StatePanel(
                    title = stringResource(R.string.moonlight_not_paired),
                    actionLabel = stringResource(R.string.moonlight_open_settings), onAction = onOpenSettings,
                    modifier = Modifier.weight(1f).fillMaxWidth())
                games.isEmpty() -> StatePanel(title = stringResource(when {
                    state.loadingHosts.isNotEmpty() -> R.string.player_loading
                    offline.isNotEmpty() -> R.string.offline_browse_no_cache_body
                    state.query.isNotBlank() || section != "all" -> R.string.game_library_no_results
                    else -> R.string.moonlight_library_empty
                }), actionLabel = if (offline.isNotEmpty()) stringResource(R.string.moonlight_refresh) else null,
                    onAction = if (offline.isNotEmpty()) ({ model.load(force = true, onlyHost = hostBySection[section]?.id) }) else null,
                    modifier = Modifier.weight(1f).fillMaxWidth())
                else -> GameLibraryGames(
                    games = games, view = state.view, section = section, sortKey = state.sortKey,
                    gridColumns = state.gridColumns, listRows = state.listRows, controls = controls,
                    focusedId = focusedId, onFocused = { key ->
                        focusedId = key
                        key?.let { currentGames[it] }?.let { model.prioritizeArtwork(it) }
                    }, itemRequests = requests, listState = listState, gridState = gridState, rowsState = rowsState,
                    onSelect = { game -> focusedId = game.key; onSelect(game.host.id, game.app.id) },
                    onOpenDetails = ::open, onFavorite = model::favorite,
                    rowKey = { it.host.id }, rowTitle = { id ->
                        state.hosts.firstOrNull { it.id == id }?.let { it.name.ifBlank { it.host } } ?: id
                    }, showSystem = !section.startsWith("host:"),
                )
            }
        }
        GameLibraryViewOptionsPanel(library, controls, model::view, model::gridColumns, model::listRows,
            model::sort, model::sortDescending, onTitleStyle = null)
    }
}

@Composable
private fun offlineSourcesStatusForMoonlight(names: List<String>): String? =
    names.takeIf { it.isNotEmpty() }?.let { offlineTitle(it.joinToString(", ")) }

/** Refresh only while this navigation entry is actually resumed; cached data stays visible. */
@Composable
internal fun MoonlightRefreshWhileVisible(model: MoonlightLibraryViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, model) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.enter()
            try {
                kotlinx.coroutines.coroutineScope {
                    launch { model.observeRecovery() }
                    while (isActive) {
                        delay(30_000)
                        model.load()
                    }
                }
            } finally { model.leave() }
        }
    }
}
