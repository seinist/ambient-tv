package com.ambient.tv.ui.common

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** The same controls adapt to a full settings page or a narrow playback panel. */
enum class SettingsPresentation { Page, Panel }

val LocalSettingsPresentation = staticCompositionLocalOf { SettingsPresentation.Panel }

/** A destination is a focusable row; actions such as Test or Reset use TvActionButton. */
@Composable
fun SettingsNavigationRow(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null,
    onClick: () -> Unit,
) {
    RightPanelChoice(
        text = title,
        subtitle = summary,
        selected = false,
        enabled = enabled,
        icon = icon,
        trailingIcon = trailingIcon ?: Icons.AutoMirrored.Filled.KeyboardArrowRight,
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    )
}

/** Supporting text belongs to its setting and must not add a D-pad stop of its own. */
@Composable
fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 14.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Intrinsic-width actions wrap on narrow panels, phones and enlarged TV layouts. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsActionRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val page = LocalSettingsPresentation.current == SettingsPresentation.Page
    FlowRow(
        modifier = modifier.fillMaxWidth().then(if (page) Modifier.focusGroup() else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/**
 * A lone vertical settings action should take part in focus search across the whole row,
 * just like surrounding switches and selectors. The button itself keeps its compact
 * intrinsic width and its existing pressed/focused appearance.
 */
@Composable
fun SettingsSingleAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    selected: Boolean = false,
    icon: ImageVector? = null,
) {
    Box(Modifier.fillMaxWidth().focusGroup()) {
        TvActionButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            loading = loading,
            selected = selected,
            icon = icon,
        )
    }
}
