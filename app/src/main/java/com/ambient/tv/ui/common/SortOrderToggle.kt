package com.ambient.tv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.runtime.Composable
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

/** The two directions of one sort field. Keys stay ASCENDING and DESCENDING for every screen. */
fun sortDirectionOptions(ascendingTitle: String, descendingTitle: String): List<PanelOption> = listOf(
    PanelOption("ASCENDING", ascendingTitle),
    PanelOption("DESCENDING", descendingTitle),
)

/**
 * One full-width order control. The arrow and the label show the active direction
 * (Neueste, Höchste, Z–A); the line under it names the direction a tap switches to.
 */
@Composable
fun SortOrderToggle(
    orders: List<PanelOption>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = false,
    up: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
) {
    if (orders.size < 2) return
    val current = orders.firstOrNull { it.key == selectedKey } ?: orders.first()
    val next = orders.firstOrNull { it.key != current.key } ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showTitle) RightPanelSectionTitle(stringResource(R.string.library_sort_order))
        RightPanelChoice(
            text = current.title,
            selected = false,
            onClick = { onSelect(next.key) },
            modifier = Modifier.fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .then(if (up == null) Modifier else Modifier.onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionUp) false else {
                        if (event.type == KeyEventType.KeyDown) runCatching { up.requestFocus() }
                        true
                    }
                }),
            icon = if (current.key == "DESCENDING") Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
            subtitle = stringResource(R.string.library_sort_order_toggle, next.title),
        )
    }
}
