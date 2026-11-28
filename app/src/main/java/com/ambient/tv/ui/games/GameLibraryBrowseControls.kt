package com.ambient.tv.ui.games

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.games.GameSystems
import com.ambient.tv.ui.common.PageChipRow
import com.ambient.tv.ui.common.FilterBar
import com.ambient.tv.ui.common.FilterFocusState
import com.ambient.tv.ui.common.LocalFilterBarSetting
import com.ambient.tv.ui.common.LocalFilterFocusState
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.PageFocusChain
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.PanelOption
import com.ambient.tv.ui.common.RightPanelOverlay
import com.ambient.tv.ui.common.SystemPageTitle
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.ViewOption
import com.ambient.tv.ui.common.ViewOptionsChip
import com.ambient.tv.ui.common.ViewOptionsPanel
import com.ambient.tv.ui.common.ViewPreview
import com.ambient.tv.ui.common.rememberPageFocusChain
import com.ambient.tv.ui.common.rememberFilterFocusState
import com.ambient.tv.ui.common.retryFocusFor
import com.ambient.tv.ui.common.sortDirectionOptions
import com.ambient.tv.ui.mobile.LocalHandheldUi

/** Keep the common header targets alive while switching view, density, or sort in its panel. */
@Stable
internal class GameLibraryBrowseControls(val focusChain: PageFocusChain, val filterFocus: FilterFocusState) {
    val optionsFocus = FocusRequester()
    val refreshFocus = FocusRequester()
    val searchFocus = FocusRequester()
    val scanFocus = FocusRequester()
    val sortFocus = FocusRequester()
    var showOptions by mutableStateOf(false)
        private set
    internal var restoreOptionsFocus by mutableStateOf(false)
        private set

    fun openOptions() {
        filterFocus.keepControls()
        restoreOptionsFocus = false
        showOptions = true
    }

    fun closeOptions() {
        if (!showOptions) return
        showOptions = false
        restoreOptionsFocus = true
    }

    internal fun focusRestored() { restoreOptionsFocus = false }
}

@Composable
internal fun rememberGameLibraryBrowseControls(): GameLibraryBrowseControls {
    val chain = rememberPageFocusChain()
    val filterFocus = rememberFilterFocusState()
    val controls = remember(chain, filterFocus) { GameLibraryBrowseControls(chain, filterFocus) }
    val handheld = LocalHandheldUi.current
    LaunchedEffect(controls, controls.showOptions, controls.restoreOptionsFocus, handheld) {
        if (!controls.showOptions && controls.restoreOptionsFocus) {
            if (!handheld) {
                withFrameNanos { }
                retryFocusFor { runCatching { controls.optionsFocus.requestFocus() }.getOrDefault(false) }
            }
            controls.focusRestored()
        }
    }
    return controls
}

@Composable
internal fun GameLibraryHeader(
    state: GameLibraryUiState,
    managing: Boolean,
    searchShown: Boolean,
    controls: GameLibraryBrowseControls,
    onManage: (() -> Unit)?,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    /** Heading in place of the library/console name (a RomM platform). */
    title: String? = null,
    /** Provider identity for a server library, including its platform sections. */
    system: MediaSystem? = null,
    itemCount: Int = state.games.size,
    onSettings: (() -> Unit)? = null,
) {
    PageHeader(focusChain = controls.focusChain, actions = {
        // RomM has no folders to manage here: its library is the server's.
        if (onManage != null) TvChip(
            text = stringResource(R.string.game_library_manage_action),
            icon = Icons.Filled.Folder, iconOnly = true, selected = managing, onClick = onManage,
        )
        if (!managing) {
            TvChip(text = stringResource(R.string.action_search), icon = Icons.Filled.Search,
                iconOnly = true, selected = searchShown, onClick = onSearch)
            TvChip(text = stringResource(R.string.action_reload), icon = Icons.Filled.Refresh,
                modifier = Modifier.focusRequester(controls.refreshFocus),
                iconOnly = true, loading = state.busy, loadingOnlyAfterClick = true, onClick = onRefresh)
            ViewOptionsChip(0, modifier = Modifier.focusRequester(controls.optionsFocus),
                onClick = controls::openOptions)
            if (onSettings != null) TvChip(text = stringResource(R.string.moonlight_open_settings),
                icon = Icons.Filled.Settings, iconOnly = true, onClick = onSettings)
        }
    }) {
        val heading = if (managing) stringResource(R.string.game_library_manage)
            else if (title != null) title
            else if (state.section in setOf("all", "favorites", "recent")) stringResource(R.string.game_library_title)
            else GameSystems.byId(state.section)?.name ?: stringResource(R.string.game_library_title)
        if (system != null) SystemPageTitle(system, title = heading)
        else Text(heading,
            style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.game_library_count, itemCount),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun GameLibrarySections(
    sections: List<String>,
    selected: String,
    controls: GameLibraryBrowseControls,
    /** Name and console of a section that is not a console of its own (a RomM platform). */
    platform: (String) -> Pair<String, String>? = { null },
    sectionIcon: @Composable (String) -> ImageVector? = { null },
    onSelect: (String) -> Unit,
) {
    PageChipRow(controls.focusChain, Modifier.fillMaxWidth(), selectedIndex = sections.indexOf(selected)) {
        items(sections, key = { it }) { key ->
            val custom = platform(key)
            val label = when (key) {
                "all" -> stringResource(R.string.game_library_all)
                "favorites" -> stringResource(R.string.game_library_favorites)
                "recent" -> stringResource(R.string.game_library_recent)
                else -> custom?.first ?: GameSystems.byId(key)?.shortName ?: key
            }
            TvChip(text = label, selected = selected == key,
                modifier = if (selected == key) Modifier.focusRequester(controls.focusChain.selectedChip) else Modifier,
                icon = when (key) {
                    "all" -> Icons.Filled.Apps
                    "favorites" -> Icons.Filled.Favorite
                    "recent" -> Icons.Filled.History
                    else -> sectionIcon(key) ?: gameSystemVector(custom?.second ?: key)
                }, onClick = { onSelect(key) })
        }
    }
}

@Composable
private fun gameLibrarySortChoices() = listOf(
    PanelOption("name", stringResource(R.string.game_library_sort_name)),
    PanelOption("system", stringResource(R.string.game_library_sort_system)),
    PanelOption("recent", stringResource(R.string.game_library_sort_recent)),
)

/** Direction labels follow the sort, like the other libraries ("Newest" for recently played). */
@Composable
private fun gameLibrarySortOrders(sortKey: String) = if (sortKey == "recent") {
    sortDirectionOptions(stringResource(R.string.library_sort_newest), stringResource(R.string.library_sort_oldest))
} else {
    sortDirectionOptions(stringResource(R.string.library_sort_az), stringResource(R.string.library_sort_za))
}

/** The shared options panel moves sorting here when the app-wide filter bar is shown. */
@Composable
internal fun GameLibrarySortBar(
    state: GameLibraryUiState,
    controls: GameLibraryBrowseControls,
    onSort: (String) -> Unit,
    onSortDescending: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!LocalFilterBarSetting.current.shown) return
    CompositionLocalProvider(LocalFilterFocusState provides controls.filterFocus) {
        FilterBar(
            groups = emptyList(), onSelect = { _, _ -> },
            modifier = modifier,
            sorts = gameLibrarySortChoices(), selectedSort = state.sortKey, onSort = onSort,
            sortOrders = gameLibrarySortOrders(state.sortKey),
            selectedSortOrder = if (state.sortDescending) "DESCENDING" else "ASCENDING",
            onSortOrder = { onSortDescending(it == "DESCENDING") },
            focusRequester = controls.sortFocus, focusChain = controls.focusChain,
        )
    }
}

@Composable
internal fun GameLibraryViewOptionsPanel(
    state: GameLibraryUiState,
    controls: GameLibraryBrowseControls,
    onView: (String) -> Unit,
    onGridColumns: (Int) -> Unit,
    onListRows: (Int) -> Unit,
    onSort: (String) -> Unit,
    onSortDescending: (Boolean) -> Unit,
    onTitleStyle: ((String) -> Unit)?,
) {
    if (!controls.showOptions) return
    CompositionLocalProvider(LocalFilterFocusState provides controls.filterFocus) {
    RightPanelOverlay(onOutsideTap = controls::closeOptions) {
        Box(Modifier.align(Alignment.TopEnd)) {
            ViewOptionsPanel(
                views = listOf(
                    ViewOption("grid", stringResource(R.string.library_view_grid), ViewPreview.GRID),
                    ViewOption("list", stringResource(R.string.library_view_list), ViewPreview.LIST),
                    ViewOption("rows", stringResource(R.string.library_view_rows), ViewPreview.ROWS),
                ),
                selectedView = state.view, onView = onView,
                gridColumns = state.gridColumns, listRows = state.listRows,
                onGridColumns = onGridColumns, onListRows = onListRows,
                densityPreview = if (state.view == "rows") ViewPreview.ROWS else null,
                sorts = gameLibrarySortChoices(),
                selectedSort = state.sortKey, onSort = onSort,
                sortOrders = gameLibrarySortOrders(state.sortKey),
                selectedSortOrder = if (state.sortDescending) "DESCENDING" else "ASCENDING",
                onSortOrder = { onSortDescending(it == "DESCENDING") },
                title = stringResource(R.string.game_library_title), onClose = controls::closeOptions,
                extraContent = {
                    // The same section and choice rows as the other libraries' extra options.
                    if (onTitleStyle != null) {
                    com.ambient.tv.ui.common.RightPanelDivider()
                    com.ambient.tv.ui.common.RightPanelSectionTitle(stringResource(R.string.game_library_titles))
                    listOf("auto" to R.string.game_library_title_auto, "internal" to R.string.game_library_title_internal,
                        "filename" to R.string.game_library_title_filename).forEach { (key, label) ->
                        com.ambient.tv.ui.common.RightPanelChoice(
                            text = stringResource(label),
                            selected = state.titleStyle == key,
                            onClick = { onTitleStyle(key) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    }
                },
            )
        }
    }
    }
}
