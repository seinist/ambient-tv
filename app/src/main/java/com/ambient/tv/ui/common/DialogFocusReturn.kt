package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.withTimeoutOrNull

/** How long focus keeps trying to reach the remembered row after a player dialog closed. */
internal const val DIALOG_FOCUS_RETURN_MS = 1_500L

/** How long the focus return waits for the player window to get its focus back. */
internal const val DIALOG_WINDOW_FOCUS_WAIT_MS = 5_000L

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

    private var owner: PlayerPanelNavigation? = null

    internal fun capture(navigation: PlayerPanelNavigation?) {
        if (captured != null || navigation == null) return
        captured = navigation.panel to navigation.focusedKey
        // Single owner of the hand-back: the panel's own rescue stays out while this one runs.
        if (navigation.panel != UnifiedPlayerPanel.NONE) {
            navigation.dialogFocusOwner = this
            owner = navigation
        }
    }

    /** Hands the panel back to its own rescue (it runs again if the panel has no focus). */
    internal fun release() {
        owner?.let { if (it.dialogFocusOwner === this) it.dialogFocusOwner = null }
        owner = null
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
        release()
    }
}

/**
 * While [showing], remembers the panel row that had focus; once the dialog is gone (Warten, Back,
 * Weiter, or the player recovered on its own) and the player window has the focus again, focus
 * goes back to that row. Exactly one owner: while the dialog is shown and until this hand-back
 * ended, the panel's own rescue stays out ([PlayerPanelNavigation.dialogFocusOwner]); afterwards
 * it takes over again, e.g. when the remembered row did not come back.
 */
@Composable
fun rememberDialogFocusReturn(navigation: PlayerPanelNavigation?, showing: Boolean): DialogFocusReturn {
    val state = remember(navigation) { DialogFocusReturn() }
    if (showing) SideEffect { state.capture(navigation) }
    LaunchedEffect(state, showing) { if (!showing) state.restore() }
    DisposableEffect(state) { onDispose { state.release() } }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(state, state.generation) {
        val taken = state.pending ?: return@LaunchedEffect
        val (panel, key) = taken
        try {
            // Bounded: a window that never gets its focus back must not hold the panel forever.
            withTimeoutOrNull(DIALOG_WINDOW_FOCUS_WAIT_MS) { snapshotFlow { windowInfo.isWindowFocused }.first { it } }
                ?: return@LaunchedEffect
            retryFocusFor(DIALOG_FOCUS_RETURN_MS) {
                if (state.pending == null) return@retryFocusFor true
                val target = dialogFocusReturnTarget(panel, key, navigation?.panel ?: UnifiedPlayerPanel.NONE)
                    ?: return@retryFocusFor true
                navigation?.requestFocus(target) == true
            }
        } finally {
            // A newer dialog may already have taken over (new capture or pending): keep its claim.
            if (state.pending === taken) state.pending = null
            if (state.pending == null && state.captured == null) state.release()
        }
    }
    return state
}
