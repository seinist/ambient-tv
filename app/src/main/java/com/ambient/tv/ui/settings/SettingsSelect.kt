package com.ambient.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.SettingsHint

internal data class SettingsSelectOption(
    val text: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val swatchColors: List<Color> = emptyList(),
)

/** Settings share the player's TV select, including Back and focus restoration. */
@Composable
internal fun SettingsSelect(
    options: List<SettingsSelectOption>,
    multiple: Boolean = false,
    modifier: Modifier = Modifier,
    label: String = "",
    description: String? = null,
    icon: ImageVector? = null,
) {
    val selected = options.withIndex().filter { it.value.selected }
    val value = when {
        selected.isEmpty() -> stringResource(R.string.settings_select_placeholder)
        selected.size == 1 -> selected.single().value.text
        else -> stringResource(R.string.settings_select_count, selected.size)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PlayerDropUpSelector(
            label = label,
            icon = icon,
            value = value,
            options = options.mapIndexed { index, option -> index.toString() to option.text },
            selectedKey = selected.firstOrNull()?.index?.toString().orEmpty(),
            selectedKeys = selected.map { it.index.toString() }.toSet(),
            disabledKeys = options.withIndex().filter { !it.value.enabled }.map { it.index.toString() }.toSet(),
            optionSwatches = options.mapIndexedNotNull { index, option ->
                option.swatchColors.takeIf { it.isNotEmpty() }?.let { index.toString() to it }
            }.toMap(),
            closeOnSelection = !multiple,
            dropUp = false,
            onSelected = { key -> options.getOrNull(key.toInt())?.takeIf { it.enabled }?.onClick?.invoke() },
        )
        description?.takeIf(String::isNotBlank)?.let { SettingsHint(it) }
    }
}
