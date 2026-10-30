package com.ambient.tv.ui.common

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.ambient.tv.ui.mobile.LocalHandheldUi

private val AlphabetBarEntryHeight = 22.dp

@Composable
fun AlphabetBar(
    letters: List<Char>,
    selected: Char?,
    onSelect: (Char?) -> Unit,
    gridFocus: FocusRequester,
    modifier: Modifier = Modifier,
    onReturnToContent: (() -> Unit)? = null,
    firstEntryFocusRequester: FocusRequester? = null,
    actionsFocus: FocusRequester? = null,
) {
    // Entries keep a natural height and the bar is centered; it only compresses when the full
    // alphabet does not fit, instead of stretching a few letters across the whole column.
    // Phone keeps a slightly wider gutter so letter taps stay reachable beside the grid.
    val barWidth = if (LocalHandheldUi.current) 36.dp else 28.dp
    val naturalEntry = if (LocalHandheldUi.current) 28.dp else AlphabetBarEntryHeight
    BoxWithConstraints(modifier.width(barWidth).padding(end = 4.dp, top = 8.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        val entryHeight = minOf(naturalEntry, maxHeight / letters.size.coerceAtLeast(1))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionLeft -> if (event.type == KeyEventType.KeyDown) {
                            if (onReturnToContent != null) onReturnToContent() else runCatching { gridFocus.requestFocus() }
                            true
                        } else false
                        Key.DirectionRight -> if (actionsFocus != null && event.type == KeyEventType.KeyDown) {
                            runCatching { actionsFocus.requestFocus() }.getOrDefault(false)
                        } else false
                        else -> false
                    }
                }
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEachIndexed { index, letter ->
                val entryModifier = Modifier.height(entryHeight).then(
                    if (index == 0 && firstEntryFocusRequester != null) Modifier.focusRequester(firstEntryFocusRequester) else Modifier)
                AlphabetBarEntry(letter.toString(), selected == letter, gridFocus, entryModifier) { onSelect(letter) }
            }
        }
    }
}

@Composable
private fun AlphabetBarEntry(
    text: String,
    selected: Boolean,
    gridFocus: FocusRequester,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val entryModifier = modifier.fillMaxWidth().focusProperties { left = gridFocus }
        .activateOnTvKey(onClick = onClick).onFocusChanged { focused = it.isFocused }
    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = if (selected || focused) 12.sp else 10.sp),
                color = when {
                    selected -> MaterialTheme.colorScheme.primary
                    focused -> Color.White
                    else -> Color.White.copy(alpha = 0.38f)
                },
            )
        }
    }
    if (LocalHandheldUi.current) {
        androidx.compose.material3.Surface(onClick = onClick, modifier = entryModifier, color = Color.Transparent) { content() }
    } else {
        Surface(
            onClick = onClick,
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent, pressedContainerColor = Color.Transparent),
            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f),
            modifier = entryModifier,
        ) { content() }
    }
}
