package com.ambient.tv.ui.player

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.ambient.tv.ui.common.TvTextFieldFocus
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import kotlinx.coroutines.delay

/** Settings need reading time independent of the much shorter control-bar display setting. */
internal const val PLAYER_PANEL_TIMEOUT_MS = 30_000L

/** One deadline per fullscreen chrome, across nested panels and renderer replacements. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerPanelAutoHide(chrome: PlayerChromeState, changingItem: Boolean) {
    val navigation = chrome.panels
    val activity = navigation.activity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val window = LocalWindowInfo.current
    val windowFocused = window.isWindowFocused || activity.popupWindowFocused
    val blocked = activity.blocked || navigation.dialogFocusOwner != null ||
        WindowInsets.isImeVisible || TvTextFieldFocus.anyFocused
    // Activity-wide input also covers remote/media keys intercepted before panel dispatch.
    val userInput = BackgroundPlaybackSession.userInputVersion
    LaunchedEffect(navigation.panel, activity.version, blocked, windowFocused, lifecycleState, changingItem, userInput) {
        if (navigation.panel == UnifiedPlayerPanel.NONE || blocked || !windowFocused ||
            lifecycleState != Lifecycle.State.RESUMED || changingItem) return@LaunchedEffect
        val version = activity.version
        delay(PLAYER_PANEL_TIMEOUT_MS)
        // An input can precede the recomposition that cancels this coroutine.
        if (BackgroundPlaybackSession.userInputVersion != userInput ||
            lifecycle.currentState != Lifecycle.State.RESUMED ||
            (!window.isWindowFocused && !activity.popupWindowFocused)) return@LaunchedEffect
        chrome.closePanelsForInactivity(version)
    }
}
