package com.ambient.tv.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R

/**
 * Filters and sort, always visible between the header and the content. Each group opens its own
 * anchored list; a group with exactly two options (off, on) is a toggle chip instead.
 * The first option of every group is its neutral value.
 */
@Composable
fun FilterBar(
    groups: List<PanelOptionGroup>,
    onSelect: (groupId: String, key: String) -> Unit,
    modifier: Modifier = Modifier,
    sorts: List<PanelOption> = emptyList(), selectedSort: String = "", onSort: (String) -> Unit = {},
    sortOrders: List<PanelOption> = emptyList(), selectedSortOrder: String = "", onSortOrder: (String) -> Unit = {},
    onReset: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    up: FocusRequester? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val focus = LocalFilterFocusState.current
    val firstControl = groups.firstOrNull()?.let { "filter:${it.id}" }
        ?: "sort".takeIf { sorts.isNotEmpty() }
        ?: "sort-order".takeIf { sortOrders.size >= 2 }
    fun Modifier.firstFocus(control: String): Modifier =
        if (control == firstControl && focusRequester != null) focusRequester(focusRequester) else this
    Row(
        modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
            .then(if (up == null) Modifier else Modifier.onPreviewKeyEvent { event ->
                if (event.key != Key.DirectionUp) false else {
                    if (event.type == KeyEventType.KeyDown) runCatching { up.requestFocus() }
                    true
                }
            }),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        groups.forEach { group -> key(group.id) {
            val control = "filter:${group.id}"
            val neutral = group.options.firstOrNull()?.key
            if (group.options.size == 2) {
                val on = group.selectedKey != neutral
                TvChip(group.options[1].title, selected = on,
                    modifier = Modifier.filterControlFocus(control, FilterFocusSurface.BAR).firstFocus(control),
                    onClick = {
                        focus?.edited(control)
                        onSelect(group.id, if (on) group.options[0].key else group.options[1].key)
                    })
            } else {
                PlayerDropUpSelector(
                    label = group.title,
                    value = group.options.firstOrNull { it.key == group.selectedKey }?.title ?: group.selectedKey,
                    options = group.options.map { it.key to it.title },
                    selectedKey = group.selectedKey,
                    modifier = Modifier.width(FilterSelectorWidth).filterControlFocus(control, FilterFocusSurface.BAR).firstFocus(control),
                    dropUp = false,
                    onSelected = { focus?.edited(control); onSelect(group.id, it) },
                )
            }
        } }
        if (sorts.isNotEmpty()) {
            PlayerDropUpSelector(
                label = stringResource(R.string.library_sort),
                value = sorts.firstOrNull { it.key == selectedSort }?.title ?: selectedSort,
                options = sorts.map { it.key to it.title },
                selectedKey = selectedSort,
                modifier = Modifier.width(FilterSelectorWidth).filterControlFocus("sort", FilterFocusSurface.BAR).firstFocus("sort"),
                dropUp = false,
                onSelected = { focus?.edited("sort"); onSort(it) },
            )
        }
        if (sortOrders.size >= 2) {
            val current = sortOrders.firstOrNull { it.key == selectedSortOrder } ?: sortOrders.first()
            val next = sortOrders.first { it.key != current.key }
            TvChip(current.title, modifier = Modifier.filterControlFocus("sort-order", FilterFocusSurface.BAR).firstFocus("sort-order"),
                icon = if (current.key == "DESCENDING") Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                onClick = { focus?.edited("sort-order"); onSortOrder(next.key) })
        }
        if (onReset != null && groups.any { it.selectedKey != it.options.firstOrNull()?.key }) {
            TvChip(stringResource(R.string.library_filter_reset), icon = Icons.Filled.FilterAltOff, onClick = {
                // The chip disappears; return to the field that changed, not the first result.
                if (focus != null) focus.restoreFilter(FilterFocusSurface.BAR)
                else focusRequester?.let { runCatching { it.requestFocus() } }
                onReset()
            })
        }
        trailing()
    }
}

private val FilterSelectorWidth = 190.dp

/** Whether screens show [FilterBar]; without it, sort and filters stay in the view options panel. */
class FilterBarSetting(val shown: Boolean, val set: (Boolean) -> Unit)

val LocalFilterBarSetting = androidx.compose.runtime.compositionLocalOf { FilterBarSetting(true) {} }

/** Everything a screen filters and sorts by, handed to both [FilterBar] and the view options panel. */
class FilterBarModel(
    val groups: List<PanelOptionGroup> = emptyList(),
    val onSelect: (groupId: String, key: String) -> Unit = { _, _ -> },
    val sorts: List<PanelOption> = emptyList(), val selectedSort: String = "", val onSort: (String) -> Unit = {},
    val sortOrders: List<PanelOption> = emptyList(), val selectedSortOrder: String = "", val onSortOrder: (String) -> Unit = {},
    val onReset: (() -> Unit)? = null,
) {
    val activeCount: Int get() = groups.count { it.selectedKey != it.options.firstOrNull()?.key }
}

@Composable
fun FilterBar(
    model: FilterBarModel,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    up: FocusRequester? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) = FilterBar(model.groups, model.onSelect, modifier, model.sorts, model.selectedSort, model.onSort,
    model.sortOrders, model.selectedSortOrder, model.onSortOrder, model.onReset, focusRequester, up, trailing)

/** The panel holding only the filter bar switch, or sort and filters while the bar is hidden. */
@Composable
fun FilterOptionsPanel(
    title: String,
    model: FilterBarModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = stringResource(R.string.browse_filter_options_hint),
) {
    ViewOptionsPanel(
        views = emptyList(), selectedView = "", onView = {},
        gridColumns = 0, listRows = 0, onGridColumns = {}, onListRows = {},
        onClose = onClose, modifier = modifier, showDensity = false, title = title, hint = hint,
        sorts = model.sorts, selectedSort = model.selectedSort, onSort = model.onSort,
        sortOrders = model.sortOrders, selectedSortOrder = model.selectedSortOrder, onSortOrder = model.onSortOrder,
        filterGroups = model.groups, onFilterGroup = model.onSelect,
        filtersActive = model.activeCount > 0, onResetFilters = model.onReset,
    )
}
