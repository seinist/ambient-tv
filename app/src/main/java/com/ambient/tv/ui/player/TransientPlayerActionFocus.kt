package com.ambient.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlinx.coroutines.delay

/**
 * A temporary player action (skip intro/credits, Next Up) owns TV remote focus while visible.
 * Attach [requester] to its primary button. Do not re-focus after a user has moved to a
 * secondary action, and retry until the button is laid out and the player window is focused.
 *
 * Back always dismisses the action, even if an underlying player control currently has focus.
 * Touch devices retain pointer navigation rather than being forced into D-pad focus.
 */
@Composable
internal fun TransientPlayerActionFocus(
    visible: Boolean,
    requester: FocusRequester,
    onDismiss: () -> Unit,
    focusIdentity: Any? = null,
    hasActionFocus: Boolean = false,
) {
    val handheld = LocalHandheldUi.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val currentActionFocused by rememberUpdatedState(hasActionFocus)
    LaunchedEffect(visible, requester, focusIdentity, handheld, windowFocused) {
        if (!visible || handheld || !windowFocused) return@LaunchedEffect
        // A button composed in the same pass cannot reliably be focused before layout.
        delay(40)
        repeat(12) {
            withFrameNanos { }
            if (currentActionFocused) return@LaunchedEffect
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }
    BackHandler(enabled = visible, onBack = onDismiss)
}
