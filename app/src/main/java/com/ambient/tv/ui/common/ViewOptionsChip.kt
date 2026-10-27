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
