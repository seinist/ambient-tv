package com.ambient.tv.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.bringFocusedContextIntoView
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.transparentSurfaceColors

/** Compact two-column alphabet rail for TV and an independently scrollable row on handheld. */
@Composable
internal fun RadioAlphabetNavigator(
    jumps: List<RadioAlphabetJump>,
    activeLetter: String?,
    handheld: Boolean,
    modifier: Modifier = Modifier,
    onJump: (RadioAlphabetJump) -> Unit,
) {
    if (jumps.isEmpty()) return
    if (handheld) {
        val barState = rememberLazyListState()
        LaunchedEffect(activeLetter) {
            val index = jumps.indexOfFirst { it.letter == activeLetter }
            if (index < 0) return@LaunchedEffect
            if (barState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                barState.scrollToItem(index)
            }
        }
        LazyRow(
            modifier = modifier.fillMaxWidth().height(42.dp),
            state = barState,
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(jumps, key = { it.letter }) { jump ->
                RadioAlphabetLetterButton(
                    jump = jump,
                    active = jump.letter == activeLetter,
                    handheld = true,
                    onClick = { onJump(jump) },
                )
            }
        }
    } else {
        Column(
            modifier = modifier.width(80.dp).fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "A–Z",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            jumps.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pair.forEach { jump ->
                        RadioAlphabetLetterButton(
                            jump = jump,
                            active = jump.letter == activeLetter,
                            handheld = false,
                            onClick = { onJump(jump) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioAlphabetLetterButton(
    jump: RadioAlphabetJump,
    active: Boolean,
    handheld: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(7.dp)
    val size = if (handheld) 38.dp else 30.dp
    val height = if (handheld) 36.dp else 25.dp
    val modifier = Modifier.size(width = size, height = height)
    val foreground = if (jump.index == null) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = .30f)
    } else if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val text = @Composable {
        Text(
            text = jump.letter,
            color = foreground,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
    if (jump.index == null) {
        Box(modifier, contentAlignment = Alignment.Center) { text() }
    } else {
        var focused by remember(jump.letter) { mutableStateOf(false) }
        Surface(
            onClick = onClick,
            colors = transparentSurfaceColors(),
            scale = noSurfaceScale(),
            shape = ClickableSurfaceDefaults.shape(shape),
            modifier = modifier
                .bringFocusedContextIntoView()
                .activateOnTvKey(onClick = onClick)
                .onFocusChanged { focused = it.isFocused },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        when {
                            focused -> MaterialTheme.colorScheme.primary.copy(alpha = .26f)
                            active -> MaterialTheme.colorScheme.primary.copy(alpha = .14f)
                            else -> Color.Transparent
                        },
                        shape,
                    )
                    .border(
                        if (focused) 2.dp else if (active) 1.dp else 0.dp,
                        if (focused || active) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape,
                    ),
                contentAlignment = Alignment.Center,
            ) { text() }
        }
    }
}
