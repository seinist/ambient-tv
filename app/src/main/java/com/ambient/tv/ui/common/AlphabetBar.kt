package com.ambient.tv.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.theme.animatedFocusSurface

private val AlphabetBarEntryHeight = 22.dp

/**
 * Finger-scrolling does not move DPAD focus. On handhelds, the A–Z marker therefore follows
 * the first visible item; TVs keep the focused item as the authoritative letter.
 */
internal fun alphabetTrackedLetter(handheld: Boolean, scrollLetter: Char?, focusedLetter: Char?): Char? =
    if (handheld) scrollLetter ?: focusedLetter else focusedLetter ?: scrollLetter

/**
 * The shared right-rail focus requester must enter at the relevant letter, not always A.
 * While inside the rail remember the last focused letter. When content focus moves to a new
 * letter outside the rail, prefer that letter on the next entry.
 */
internal class AlphabetBarFocusMemory(initialSelected: Char?) {
    private var observedSelected = initialSelected
    var entryLetter by mutableStateOf(initialSelected)
        private set

    fun observeSelection(selected: Char?, railFocused: Boolean) {
        if (selected == observedSelected) return
        observedSelected = selected
        if (!railFocused && selected != null) entryLetter = selected
    }

    fun focused(letter: Char) {
        entryLetter = letter
    }

    fun target(letters: List<Char>, selected: Char?): Char? =
        entryLetter?.takeIf { it in letters }
            ?: selected?.takeIf { it in letters }
            ?: letters.firstOrNull()
}

@Composable
fun AlphabetBar(
    letters: List<Char>,
    selected: Char?,
    onSelect: (Char?) -> Unit,
    gridFocus: FocusRequester,
    modifier: Modifier = Modifier,
    onReturnToContent: (() -> Unit)? = null,
    firstEntryFocusRequester: FocusRequester? = null,
) {
    // Entries keep a natural height and the bar is centered; it only compresses when the full
    // alphabet does not fit, instead of stretching a few letters across the whole column.
    // Phone keeps a slightly wider gutter so letter taps stay reachable beside the grid.
    val handheld = LocalHandheldUi.current
    val barWidth = if (handheld) 36.dp else 28.dp
    val naturalEntry = if (handheld) 28.dp else AlphabetBarEntryHeight
    val focusMemory = remember(letters) { AlphabetBarFocusMemory(selected) }
    var railFocused by remember(letters) { mutableStateOf(false) }
    SideEffect { focusMemory.observeSelection(selected, railFocused) }
    val entryFocusLetter = focusMemory.target(letters, selected)
    val reducedEffects = LocalPerformanceProfile.current.reducedEffects
    val focusAlpha by animateFloatAsState(
        targetValue = if (!handheld && railFocused) 0.10f else 0f,
        animationSpec = tween(if (reducedEffects) 100 else 160),
        label = "Alphabet rail focus",
    )
    val accent = MaterialTheme.colorScheme.primary

    BoxWithConstraints(
        modifier.width(barWidth).padding(end = 4.dp, top = 8.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val entryHeight = minOf(naturalEntry, maxHeight / letters.size.coerceAtLeast(1))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(accent.copy(alpha = focusAlpha), RoundedCornerShape(9.dp))
                .onFocusChanged { railFocused = it.hasFocus }
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionLeft -> {
                            // Consume both edges: a held Left must not spill into the page menu.
                            if (event.type == KeyEventType.KeyDown &&
                                event.nativeKeyEvent.repeatCount == 0) {
                                if (onReturnToContent != null) onReturnToContent()
                                else runCatching { gridFocus.requestFocus() }
                            }
                            true
                        }
                        // The rail has no neighbour on the right. Never send Right to the
                        // header action buttons or let geometric focus search escape there.
                        Key.DirectionRight -> true
                        // Keep the first/last letter in the rail, not the page header.
                        Key.DirectionUp -> railFocused && focusMemory.entryLetter == letters.firstOrNull()
                        Key.DirectionDown -> railFocused && focusMemory.entryLetter == letters.lastOrNull()
                        else -> false
                    }
                }
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEach { letter ->
                val entryModifier = Modifier.height(entryHeight).then(
                    if (letter == entryFocusLetter && firstEntryFocusRequester != null)
                        Modifier.focusRequester(firstEntryFocusRequester) else Modifier,
                )
                AlphabetBarEntry(
                    text = letter.toString(),
                    selected = selected == letter,
                    gridFocus = gridFocus,
                    modifier = entryModifier,
                    onFocused = { focusMemory.focused(letter) },
                ) { onSelect(letter) }
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
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val entryModifier = modifier.fillMaxWidth().focusProperties { left = gridFocus }
        .activateOnTvKey(onClick = onClick).onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        }
    val shape = RoundedCornerShape(5.dp)
    val accent = MaterialTheme.colorScheme.primary
    val content: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 1.dp)
                .animatedFocusSurface(
                    focused = focused,
                    shape = shape,
                    focusedBackground = accent.copy(alpha = 0.22f),
                    unfocusedBackground = if (selected) accent.copy(alpha = 0.08f) else Color.Transparent,
                    focusedBorder = accent,
                    borderWidth = 1.5.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = if (selected || focused) 12.sp else 10.sp),
                color = when {
                    focused -> Color.White
                    selected -> accent
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
            colors = transparentSurfaceColors(),
            scale = noSurfaceScale(),
            border = noSurfaceBorder(),
            modifier = entryModifier,
        ) { content() }
    }
}
