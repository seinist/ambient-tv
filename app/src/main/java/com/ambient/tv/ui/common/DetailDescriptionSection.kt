package com.ambient.tv.ui.common

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.animatedFocusBorder

/**
 * Full detail text with its own bounded viewport. Artwork and actions outside this section do
 * not move while reading, and short descriptions keep their natural height. A single scroll
 * gesture handles a held remote key, just like the shared scrollbar, without queued animations.
 * Replacing/enriching [text] preserves focus and does not run another initial-focus request.
 */
@Composable
fun DetailDescriptionSection(
    text: String?,
    modifier: Modifier = Modifier,
    actionFocus: FocusRequester? = null,
    maxHeight: Dp = 220.dp,
) {
    val description = text?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val handheld = LocalHandheldUi.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val textFocus = remember { FocusRequester() }
    val scrollbarFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var scrollbarOwnedFocus by remember { mutableStateOf(false) }
    var pressedKey by remember { mutableStateOf<Key?>(null) }
    var scrollingKey by remember { mutableStateOf(false) }
    val scrolling = remember(scope, scroll) {
        ScrollbarKeyScroller(scope, scroll, { scroll.viewportSize.toFloat() }, { false })
    }
    DisposableEffect(scrolling) {
        onDispose { scrolling.stop() }
    }
    LaunchedEffect(scroll.maxValue, scrollbarOwnedFocus) {
        // Only reclaim an owned target that vanished. Normal metadata enrichment never
        // requests focus, and a user who moved to another control keeps that control.
        if (scroll.maxValue == 0 && scrollbarOwnedFocus) {
            scrollbarOwnedFocus = false
            runCatching { textFocus.requestFocus() }
        }
    }

    Box(modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(textFocus)
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) {
                        scrolling.stop()
                        pressedKey = null
                        scrollingKey = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (handheld) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) {
                        if (pressedKey != event.key) return@onPreviewKeyEvent false
                        val wasScrolling = scrollingKey
                        pressedKey = null
                        scrollingKey = false
                        if (wasScrolling) scrolling.onKeyEvent(event)
                        else when (event.key) {
                            Key.DirectionUp, Key.PageUp -> runCatching { actionFocus?.requestFocus() }
                            Key.DirectionRight -> runCatching { scrollbarFocus.requestFocus() }
                            else -> Unit
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // Keep a held press in its initial region even when it reaches the end.
                    // The next deliberate press can leave for the actions or following content.
                    if (pressedKey == event.key) return@onPreviewKeyEvent true
                    scrolling.stop()
                    pressedKey = null
                    scrollingKey = false
                    val canScroll = when (event.key) {
                        Key.DirectionUp, Key.PageUp -> scroll.canScrollBackward
                        Key.DirectionDown, Key.PageDown -> scroll.canScrollForward
                        else -> false
                    }
                    val handled = canScroll || when (event.key) {
                        Key.DirectionUp, Key.PageUp -> actionFocus != null
                        Key.DirectionRight -> scroll.maxValue > 0
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> true
                        else -> false
                    }
                    if (!handled) return@onPreviewKeyEvent false
                    pressedKey = event.key
                    scrollingKey = canScroll
                    if (canScroll) scrolling.onKeyEvent(event)
                    true
                }
                .focusProperties {
                    // Page-level D-pad acceleration uses moveFocus() for held repeats.
                    // Keep those moves inside the reading viewport while it owns a scroll.
                    if (!handheld) {
                        up = if (scroll.canScrollBackward || (scrollingKey &&
                                (pressedKey == Key.DirectionUp || pressedKey == Key.PageUp))) FocusRequester.Cancel
                            else actionFocus ?: FocusRequester.Default
                        down = if (scroll.canScrollForward || (scrollingKey &&
                                (pressedKey == Key.DirectionDown || pressedKey == Key.PageDown))) FocusRequester.Cancel
                            else FocusRequester.Default
                        right = if (scroll.maxValue > 0) scrollbarFocus else FocusRequester.Default
                    }
                }
                .focusable(enabled = !handheld)
                .animatedFocusBorder(
                    focused = focused,
                    shape = RoundedCornerShape(8.dp),
                    focusedColor = MaterialTheme.colorScheme.primary,
                    width = 1.dp,
                )
                .verticalScroll(scroll)
                // Reserve the bar's gutter from the first layout so arriving metadata does
                // not change line wraps when the description becomes scrollable.
                .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 20.dp),
        )
        Box(Modifier.matchParentSize()) {
            OverlayEdgeFades(scroll.canScrollBackward, scroll.canScrollForward)
            if (scroll.maxValue > 0) {
                OverlayScrollbar(
                    state = scroll,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                        .focusRequester(scrollbarFocus)
                        .onFocusChanged {
                            if (it.hasFocus) scrollbarOwnedFocus = true
                            else if (scroll.maxValue > 0) scrollbarOwnedFocus = false
                        },
                    contentFocus = textFocus,
                    actionsFocus = actionFocus,
                )
            }
        }
    }
}
