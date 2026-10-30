package com.ambient.tv.ui.common

import androidx.compose.foundation.Canvas
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.ambient.tv.R
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.appControlSurface

enum class ViewPreview { POSTER, LANDSCAPE, LIST, INFO_LIST, WALL, CINEMATIC, GRID, ROWS, POSTER_LIST, POSTER_INFO_LIST, POSTER_WALL, POSTER_CINEMATIC }
data class PanelOption(val key: String, val title: String)
data class ViewOption(val key: String, val title: String, val preview: ViewPreview)
data class PanelOptionGroup(val id: String, val title: String, val options: List<PanelOption>, val selectedKey: String)

/** Landscape artwork is twice as wide as poster artwork, so the shared density preference maps
 * to half as many visible columns. Keep that conversion in one place and step by one *visible*
 * landscape column instead of exposing duplicate 4/5, 6/7, ... settings. */
internal fun landscapeGridColumns(gridColumns: Int): Int = (gridColumns / 2).coerceIn(2, 6)

internal fun gridDensityStep(preview: ViewPreview?): Int =
    if (preview == ViewPreview.LANDSCAPE || preview == ViewPreview.ROWS) 2 else 1

internal fun displayedGridColumns(gridColumns: Int, preview: ViewPreview?): Int =
    if (gridDensityStep(preview) == 2) landscapeGridColumns(gridColumns) else gridColumns

internal fun changedGridColumns(gridColumns: Int, preview: ViewPreview?, visibleDelta: Int): Int {
    val step = gridDensityStep(preview)
    return if (step == 2) {
        ((displayedGridColumns(gridColumns, preview) + visibleDelta) * step).coerceIn(4, 12)
    } else {
        (gridColumns + visibleDelta).coerceIn(4, 12)
    }
}

/** One layout selector for providers. Selecting a tile is explicit; focus alone changes nothing. */
@Composable
fun ViewOptionsPanel(
    views: List<ViewOption>, selectedView: String, onView: (String) -> Unit,
    gridColumns: Int, listRows: Int, onGridColumns: (Int) -> Unit, onListRows: (Int) -> Unit,
    onClose: () -> Unit, modifier: Modifier = Modifier,
    showDensity: Boolean = true,
    /** Allow a fixed-layout page to show only density controls, without a redundant view selector. */
    densityPreview: ViewPreview? = null,
    hint: String = stringResource(R.string.library_options_hint),
    title: String = stringResource(R.string.library_options),
    sorts: List<PanelOption> = emptyList(), selectedSort: String = "", onSort: (String) -> Unit = {},
    sortOrders: List<PanelOption> = emptyList(), selectedSortOrder: String = "", onSortOrder: (String) -> Unit = {},
    filters: List<PanelOption> = emptyList(), selectedFilter: String = "", onFilter: (String) -> Unit = {},
    filterGroups: List<PanelOptionGroup> = emptyList(), onFilterGroup: (String, String) -> Unit = { _, _ -> },
    filtersActive: Boolean = false, onResetFilters: (() -> Unit)? = null,
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    val focus = LocalFilterFocusState.current
    // Returning to a filter replaces the panel's initial focus for this opening. Clearing the
    // return request must not start the panel's normal initial-focus effect afterwards.
    val requestInitialPanelFocus = remember(focus) { focus?.pendingSurface != FilterFocusSurface.PANEL }
    val resetFilters: (() -> Unit)? = onResetFilters?.let { reset -> {
        focus?.restoreFilter(FilterFocusSurface.PANEL)
        reset()
    } }
    DisposableEffect(focus) {
        focus?.keepControls()
        onDispose { }
    }
    RightSidePanel(title, onClose, modifier,
        subtitle = hint, closeInsteadOfBack = true,
        requestHeaderFocus = requestInitialPanelFocus) {
        if (views.isNotEmpty()) RightPanelSectionTitle(stringResource(R.string.library_view))
        // The panel focuses its first tile on open; hand that first focus to the selected layout.
        val selectedFocus = remember { FocusRequester() }
        val selectedIntoView = remember { BringIntoViewRequester() }
        var selectedSize by remember { mutableStateOf(IntSize.Zero) }
        val visibilityMargin = with(LocalDensity.current) { 32.dp.toPx() }
        var initialFocusPending by remember(focus) { mutableStateOf(requestInitialPanelFocus) }
        var redirectInitialFocus by remember { mutableStateOf(false) }
        LaunchedEffect(redirectInitialFocus) {
            if (redirectInitialFocus) {
                initialFocusPending = false
                runCatching { selectedFocus.requestFocus() }
                // Focus alone does not scroll while the panel is still sliding in; scroll explicitly,
                // with a margin so the tile clears the panel's edge fades, and again once it has settled.
                suspend fun reveal() = runCatching {
                    selectedIntoView.bringIntoView(Rect(0f, -visibilityMargin, selectedSize.width.toFloat(), selectedSize.height + visibilityMargin))
                }
                withFrameNanos { }
                reveal()
                delay(260)
                reveal()
            }
        }
        // Inset so the focus-scaled tiles stay inside the panel body's clip bounds.
        if (views.isNotEmpty()) BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                .onFocusChanged { if (it.hasFocus && initialFocusPending) redirectInitialFocus = true },
        ) {
            val columns = if (maxWidth >= 320.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Right from the rightmost tile goes to the scrollbar, not to whatever control lies
                // diagonally below (the sort order button); without a scrollbar, to the footer.
                val scrollbarFocus = LocalRightPanelScrollbarFocus.current
                views.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { option -> key(option.key) {
                            val selected = selectedView == option.key
                            val rightmost = option == row.last()
                            ViewPreviewChoice(option, selected, { focus?.keepControls(); onView(option.key) },
                                Modifier.weight(1f).then(
                                    if (rightmost) Modifier.focusProperties { right = scrollbarFocus ?: FocusRequester.Cancel }.rightPanelScrollbarReturn() else Modifier,
                                ).then(
                                    if (selected) Modifier.focusRequester(selectedFocus).bringIntoViewRequester(selectedIntoView).onSizeChanged { selectedSize = it } else Modifier,
                                ))
                        } }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        val preview = views.firstOrNull { it.key == selectedView }?.preview ?: densityPreview
        if (showDensity && preview != null) {
            RightPanelSectionTitle(stringResource(R.string.library_layout_density))
            if (preview in setOf(ViewPreview.LIST, ViewPreview.INFO_LIST, ViewPreview.POSTER_LIST, ViewPreview.POSTER_INFO_LIST)) {
                RightPanelValueSlider(
                    title = stringResource(R.string.library_list_density_title),
                    value = listRows,
                    valueRange = 4..10,
                    valueLabel = stringResource(R.string.library_list_rows, listRows),
                    onValueChange = { focus?.keepControls(); onListRows(it) },
                )
            } else {
                val displayedColumns = displayedGridColumns(gridColumns, preview)
                val minColumns = if (gridDensityStep(preview) == 2) 2 else 4
                val maxColumns = if (gridDensityStep(preview) == 2) 6 else 12
                RightPanelValueSlider(
                    title = stringResource(R.string.library_grid_density_title),
                    value = displayedColumns,
                    valueRange = minColumns..maxColumns,
                    valueLabel = stringResource(R.string.library_grid_columns, displayedColumns),
                    onValueChange = { focus?.keepControls(); onGridColumns(changedGridColumns(gridColumns, preview, it - displayedColumns)) },
                )
            }
        }
        extraContent()
        val filterBar = LocalFilterBarSetting.current
        if (sorts.isNotEmpty() || filters.isNotEmpty() || filterGroups.isNotEmpty()) {
            RightPanelDivider()
            RightPanelChoice(stringResource(R.string.library_filter_bar), filterBar.shown, { focus?.keepControls(); filterBar.set(!filterBar.shown) },
                Modifier.fillMaxWidth(), subtitle = stringResource(R.string.library_filter_bar_hint))
        }
        // The visible bar owns sort and filters; the panel takes them back only when it is hidden.
        if (filterBar.shown) return@RightSidePanel
        if (sorts.isNotEmpty()) {
            RightPanelDivider()
            RightPanelSectionTitle(stringResource(R.string.library_sort))
            PanelChoices(sorts, selectedSort) { focus?.edited("sort:$it"); onSort(it) }
            if (sortOrders.isNotEmpty()) {
                RightPanelSectionTitle(stringResource(R.string.library_sort_order))
                SortOrderToggle(sortOrders, selectedSortOrder, { focus?.edited("sort-order"); onSortOrder(it) },
                    modifier = Modifier.filterControlFocus("sort-order", FilterFocusSurface.PANEL))
            }
        }
        if (filters.isNotEmpty()) {
            if (sorts.isEmpty()) RightPanelDivider()
            if (resetFilters != null && filtersActive) {
                RightPanelChoice(stringResource(R.string.library_filter_reset), selected = false, onClick = resetFilters)
            }
            PanelSelect(stringResource(R.string.library_filter), filters, selectedFilter,
                Modifier.filterControlFocus("filter:filter", FilterFocusSurface.PANEL)) {
                focus?.edited("filter:filter"); onFilter(it)
            }
        }
        if (filterGroups.isNotEmpty()) {
            RightPanelDivider()
            if (filters.isEmpty() && resetFilters != null && filtersActive) {
                RightPanelChoice(stringResource(R.string.library_filter_reset), selected = false, onClick = resetFilters)
            }
            filterGroups.forEach { group -> key(group.id) {
                val control = "filter:${group.id}"
                PanelSelect(group.title, group.options, group.selectedKey,
                    Modifier.filterControlFocus(control, FilterFocusSurface.PANEL)) {
                    focus?.edited(control); onFilterGroup(group.id, it)
                }
            } }
        }
    }
}

@Composable
private fun PanelChoices(options: List<PanelOption>, selected: String, onSelect: (String) -> Unit) {
    options.forEach { option -> key(option.key) {
        RightPanelChoice(option.title, selected == option.key, { onSelect(option.key) },
            Modifier.fillMaxWidth().filterControlFocus("sort:${option.key}", FilterFocusSurface.PANEL))
    } }
}

@Composable
private fun PanelSelect(label: String, options: List<PanelOption>, selectedKey: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    PlayerDropUpSelector(
        label = label,
        value = options.firstOrNull { it.key == selectedKey }?.title ?: selectedKey,
        options = options.map { it.key to it.title },
        selectedKey = selectedKey,
        modifier = modifier,
        dropUp = false,
        onSelected = onSelect,
    )
}

@Composable
fun ViewPreviewChoice(option: ViewOption, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LocalUiTokens.current.cardRadius)
    Surface(onClick = onClick, colors = transparentSurfaceColors(), scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape),
        modifier = modifier.mediaFocusMotion(focused).onFocusChanged { focused = it.isFocused }
            .activateOnTvKey(onClick = onClick).semantics { this.selected = selected; role = Role.RadioButton }) {
        Column(Modifier.fillMaxWidth().appControlSurface(focused, selected, shape = shape).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ViewLayoutPreview(option.preview, Modifier.fillMaxWidth().height(64.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(option.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Schematic vectors: no image requests, decoder instances or looping preview animations. */
@Composable
fun ViewLayoutPreview(mode: ViewPreview, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        fun block(x: Float, y: Float, w: Float, h: Float, highlight: Boolean = false) {
            drawRoundRect(if (highlight) accent.copy(alpha = .65f) else ink.copy(alpha = .22f),
                Offset(x * size.width, y * size.height), Size(w * size.width, h * size.height), CornerRadius(3.dp.toPx()))
        }
        when (mode) {
            ViewPreview.LIST, ViewPreview.INFO_LIST -> repeat(3) { i ->
                val y = .05f + i * .32f
                block(.02f, y, if (mode == ViewPreview.INFO_LIST) .28f else .18f, .24f, i == 0)
                block(.36f, y + .02f, .60f, .05f)
                block(.36f, y + .13f, .40f, .04f)
            }
            ViewPreview.POSTER_LIST, ViewPreview.POSTER_INFO_LIST -> repeat(3) { i ->
                val y = .05f + i * .32f
                block(.02f, y, if (mode == ViewPreview.POSTER_INFO_LIST) .10f else .08f, .26f, i == 0)
                block(.18f, y + .02f, .60f, .05f)
                block(.18f, y + .13f, .40f, .04f)
            }
            ViewPreview.CINEMATIC -> {
                block(.02f, .02f, .96f, .59f, true)
                repeat(4) { block(.02f + it * .25f, .72f, .21f, .25f) }
            }
            ViewPreview.POSTER_CINEMATIC -> {
                block(.02f, .02f, .96f, .44f, true)
                repeat(7) { block(.02f + it * .14f, .52f, .12f, .46f) }
            }
            ViewPreview.POSTER_WALL -> repeat(2) { row -> repeat(8) { col ->
                block(.02f + col * .1225f, .02f + row * .50f, .11f, .46f, row == 0 && col == 0)
            } }
            ViewPreview.POSTER -> repeat(4) { block(.02f + it * .25f, .06f, .21f, .88f, it == 0) }
            else -> {
                val count = if (mode == ViewPreview.WALL) 4 else 3
                repeat(2) { row -> repeat(count) { col ->
                    block(.02f + col * (.98f / count), .04f + row * .50f, .90f / count, .40f, row == 0 && col == 0)
                } }
            }
        }
    }
}
