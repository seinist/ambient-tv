package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.flow.first

/** How long focus keeps trying to reach the remembered row after a player dialog closed. */
internal const val DIALOG_FOCUS_RETURN_MS = 1_500L

/**
 * The row to focus after a player dialog closed: the one that held focus when it opened, as long
 * as the same panel level is still shown. A dialog button that opened another level (e.g.
 * "Anpassen") wins; with no panel open there is nothing to restore.
 */
internal fun dialogFocusReturnTarget(capturedPanel: UnifiedPlayerPanel, capturedKey: String?,
    panelNow: UnifiedPlayerPanel): String? =
    capturedKey?.takeIf { capturedPanel != UnifiedPlayerPanel.NONE && capturedPanel == panelNow }

/** Remembers the focused panel row while a player dialog is open and puts focus back afterwards. */
@Stable
class DialogFocusReturn internal constructor() {
    internal var captured: Pair<UnifiedPlayerPanel, String?>? = null
    internal var pending by mutableStateOf<Pair<UnifiedPlayerPanel, String?>?>(null)
    internal var generation by mutableIntStateOf(0)

    internal fun capture(navigation: PlayerPanelNavigation?) {
        if (captured != null || navigation == null) return
        captured = navigation.panel to navigation.focusedKey
    }

    internal fun restore() {
        val target = captured ?: return
        captured = null
        pending = target
        generation++
    }

    /** The dialog ended playback (Stop): nothing to go back to. */
    fun forget() {
        captured = null
        pending = null
    }
}

/**
 * While [showing], remembers the panel row that had focus; once the dialog is gone (Warten, Back,
 * Weiter, or the player recovered on its own) and the player window has the focus again, focus
 * goes back to that row. The panel's own rescue does not run here: the panel never lost its
 * focus, the dialog's window only borrowed the window focus.
 */
@Composable
fun rememberDialogFocusReturn(navigation: PlayerPanelNavigation?, showing: Boolean): DialogFocusReturn {
    val state = remember(navigation) { DialogFocusReturn() }
    if (showing) SideEffect { state.capture(navigation) }
    LaunchedEffect(state, showing) { if (!showing) state.restore() }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(state, state.generation) {
        val (panel, key) = state.pending ?: return@LaunchedEffect
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        retryFocusFor(DIALOG_FOCUS_RETURN_MS) {
            if (state.pending == null) return@retryFocusFor true
            val target = dialogFocusReturnTarget(panel, key, navigation?.panel ?: UnifiedPlayerPanel.NONE)
                ?: return@retryFocusFor true
            navigation?.requestFocus(target) == true
        }
        state.pending = null
    }
    return state
}
