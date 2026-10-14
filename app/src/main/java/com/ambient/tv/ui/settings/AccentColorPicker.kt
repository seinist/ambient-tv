package com.ambient.tv.ui.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.contentOnAccent
import java.util.Locale

private val AccentPickerHexes = buildList {
    val hues = floatArrayOf(0f, 30f, 55f, 120f, 175f, 210f, 260f, 320f)
    listOf(0.50f to 1f, 0.78f to 0.95f, 0.92f to 0.72f).forEach { (saturation, brightness) ->
        hues.forEach { hue -> add(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)).toPickerHex()) }
    }
    addAll(listOf("#FFFFFF", "#D5D8DC", "#AEB6BF", "#85929E", "#5D6D7E", "#34495E", "#17202A", "#000000"))
}

private fun Int.toPickerHex(): String = String.format(Locale.ROOT, "#%06X", this and 0xFFFFFF)

internal fun canonicalAccentHex(value: String): String = com.ambient.tv.ui.theme.normalizedCustomAccentHex(value)

@Composable
internal fun AccentColorPickerDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.settings_color_picker_title),
) {
    val initialHex = remember { canonicalAccentHex(value) }
    // A manually entered colour must still be selected and focusable when opening the picker.
    val choices = remember(initialHex) {
        if (initialHex in AccentPickerHexes) AccentPickerHexes else listOf(initialHex) + AccentPickerHexes.drop(1)
    }
    val selectedHex = canonicalAccentHex(value)
    val handheld = LocalHandheldUi.current
    val columns = if (handheld) 4 else 8
    val initialIndex = choices.indexOf(initialHex).coerceAtLeast(0)
    val choiceFocus = remember(choices) { List(choices.size) { FocusRequester() } }
    val hexFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    var lastChoiceIndex by remember { mutableStateOf(initialIndex) }
    var hexDraft by remember(selectedHex) { mutableStateOf(selectedHex) }
    val lastRowStart = (choices.size - 1) / columns * columns

    LaunchedEffect(Unit) {
        repeat(10) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { choiceFocus[initialIndex].requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    AppDialog(
        onDismiss = onDismiss,
        title = title,
        hint = stringResource(R.string.settings_color_picker_hint),
        captureInitialFocus = false,
        // Normally fits without scrolling; larger UI/font scales must not clip the last rows.
        scrollable = true,
        maxHeightFraction = if (handheld) 0.88f else 0.92f,
        footer = {
            if (handheld) {
                TvActionButton(
                    text = stringResource(R.string.settings_color_picker_done),
                    onClick = onDismiss,
                    selected = true,
                    modifier = Modifier.focusRequester(doneFocus).focusProperties {
                        up = choiceFocus[lastChoiceIndex]
                        down = choiceFocus[lastChoiceIndex]
                        left = hexFocus
                        right = hexFocus
                    },
                )
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RainbowAccentSwatch(Color(AndroidColor.parseColor(selectedHex)))
            // TvTextField focuses its navigation container first; only OK/tap opens editing.
            TvTextField(
                value = hexDraft,
                requireExplicitEditing = true,
                onValueChange = { draft ->
                    hexDraft = draft
                    val hex = draft.trim().removePrefix("#")
                    if (hex.length == 6 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                        onValueChange("#${hex.uppercase(Locale.ROOT)}")
                    }
                },
                label = stringResource(R.string.settings_color_picker_hex),
                modifier = Modifier.weight(1f).focusRequester(hexFocus)
                    .focusProperties {
                        down = choiceFocus[lastChoiceIndex]
                        up = choiceFocus[lastChoiceIndex]
                        left = choiceFocus[lastChoiceIndex]
                        right = if (handheld) doneFocus else choiceFocus[lastChoiceIndex]
                    },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.chunked(columns).forEachIndexed { rowIndex, rowChoices ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowChoices.forEachIndexed { columnIndex, hex ->
                        val index = rowIndex * columns + columnIndex
                        val rowStart = rowIndex * columns
                        val rowEnd = rowStart + rowChoices.size - 1
                        AccentColorChoice(
                            hex = hex,
                            selected = hex == selectedHex,
                            onClick = { onValueChange(hex) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(choiceFocus[index])
                                .onFocusChanged { if (it.isFocused) lastChoiceIndex = index }
                                // Wrap within the colour grid, preserving the column vertically.
                                .focusProperties {
                                    left = choiceFocus[if (index == rowStart) rowEnd else index - 1]
                                    right = if (index == rowEnd) hexFocus else choiceFocus[index + 1]
                                    up = choiceFocus[
                                        if (rowIndex == 0) (lastRowStart + columnIndex).coerceAtMost(choices.lastIndex)
                                        else index - columns
                                    ]
                                    down = choiceFocus[
                                        if (index + columns > choices.lastIndex) columnIndex
                                        else index + columns
                                    ]
                                },
                        )
                    }
                    repeat(columns - rowChoices.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AccentColorChoice(
    hex: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val color = remember(hex) { Color(AndroidColor.parseColor(hex)) }
    val shape = RoundedCornerShape(8.dp)
    val borderWidth = if (focused) 3.dp else if (selected) 2.dp else 1.dp
    val borderColor = when {
        focused -> Color.White
        selected -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
        else -> Color.White.copy(alpha = 0.18f)
    }
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(color)
            .border(borderWidth, borderColor, shape)
            .semantics {
                contentDescription = hex
                this.selected = selected
            }
            .onFocusChanged { focused = it.isFocused }
            .activateOnTvKey(onClick = onClick)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = contentOnAccent(color),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
