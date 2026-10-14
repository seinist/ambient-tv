package com.ambient.tv.ui.common

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/** Separate instances keep content and footer actions in their own horizontal rows. */
internal class DialogButtonFocusScope(val containSingleButton: Boolean = false) {
    val buttons = mutableListOf<DialogButtonFocusTarget>()
}

internal class DialogButtonFocusTarget(val enabled: () -> Boolean) {
    val requester = FocusRequester()
    var bounds = Rect.Zero
}

internal val LocalDialogButtonFocus = staticCompositionLocalOf<DialogButtonFocusScope?> { null }

/** Register common buttons automatically, including buttons in nested dialog content. */
internal fun Modifier.dialogButtonFocus(enabled: Boolean): Modifier = composed {
    val scope = LocalDialogButtonFocus.current ?: return@composed Modifier
    val actionsFocus = LocalScrollListActionFocus.current
    val currentEnabled = rememberUpdatedState(enabled)
    val target = remember(scope) { DialogButtonFocusTarget { currentEnabled.value } }
    DisposableEffect(scope, target) {
        scope.buttons.add(target)
        onDispose { scope.buttons.remove(target) }
    }
    Modifier
        .focusRequester(target.requester)
        .onGloballyPositioned { target.bounds = it.boundsInWindow() }
        .onKeyEvent { event ->
            val right = when (event.key) {
                Key.DirectionRight -> true
                Key.DirectionLeft -> false
                else -> return@onKeyEvent false
            }
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val origin = target.bounds
            if (origin.width <= 0f || origin.height <= 0f) return@onKeyEvent false
            // Require substantial vertical overlap, so neighbouring rows never join the cycle.
            val row = scope.buttons.filter { candidate ->
                val bounds = candidate.bounds
                (candidate === target || candidate.enabled()) && bounds.width > 0f &&
                    minOf(origin.bottom, bounds.bottom) - maxOf(origin.top, bounds.top) >
                    minOf(origin.height, bounds.height) / 2f
            }.sortedBy { it.bounds.center.x }
            if (row.size < 2) {
                // Default geometric search can jump diagonally into the previous row.
                // A single content action instead enters the dialog footer on Right.
                return@onKeyEvent if (scope.containSingleButton) true
                    else if (right && actionsFocus != null && scope.buttons.any {
                        it !== target && it.enabled() && it.bounds.center.x > origin.right
                    }) runCatching { actionsFocus.requestFocus() }.getOrDefault(false)
                    // Full-width list rows leave Right to their scrollbar/list boundary.
                    else false
            }
            val index = row.indexOf(target)
            if (index < 0) return@onKeyEvent false
            val next = row[(index + (if (right) 1 else row.size - 1)) % row.size]
            next.requester.requestFocus()
        }
}
