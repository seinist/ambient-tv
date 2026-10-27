package com.ambient.tv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R

/** Focusable chips on the path between the header and the list. Each one opens the options. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveFilterChips(
    labels: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    up: FocusRequester? = null,
) {
    if (labels.isEmpty()) return
    Column(
        modifier.fillMaxWidth().then(if (up == null) Modifier else Modifier.onPreviewKeyEvent { event ->
            if (event.key != Key.DirectionUp) false else {
                if (event.type == KeyEventType.KeyDown) runCatching { up.requestFocus() }
                true
            }
        }),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.guide_active_filters),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { index, label ->
                TvChip(
                    label,
                    selected = true,
                    modifier = if (index == 0 && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
                    onClick = onClick,
                )
            }
        }
    }
}

/** View action. A filter count replaces the icon-only chip so the active set stays visible. */
@Composable
fun ViewOptionsChip(activeCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val view = stringResource(R.string.action_view)
    TvChip(
        text = if (activeCount > 0) stringResource(R.string.guide_options_filtered, view, activeCount) else view,
        selected = activeCount > 0,
        modifier = modifier,
        icon = Icons.Filled.Tune,
        iconOnly = activeCount == 0,
        onClick = onClick,
    )
}
