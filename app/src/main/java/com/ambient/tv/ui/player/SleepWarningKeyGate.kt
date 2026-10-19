package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Whether OK on the remote extends the sleep pre-warning: only on a player screen whose controls
 * are hidden and with no side panel open. Otherwise OK goes to the focused element as usual
 * (dialogs are separate windows and never see the activity's key dispatch).
 */
object SleepWarningKeyGate {
    private val chrome = mutableStateMapOf<Any, Boolean>()
    private var panels by mutableIntStateOf(0)
    /** Set by MainActivity from the current navigation route. */
    var playerRoute by mutableStateOf(false)

    /** Player screens report whether their OSD/controls are visible. */
    @Composable
    fun ReportPlayerChrome(visible: Boolean) {
        val token = remember { Any() }
        SideEffect { chrome[token] = visible }
        DisposableEffect(token) { onDispose { chrome.remove(token) } }
    }

    /** Side panels (settings, audio, sleep, ...) hold OK for their own focus while shown. */
    @Composable
    fun BlockWhileShown() {
        DisposableEffect(Unit) {
            panels++
            onDispose { panels-- }
        }
    }

    fun takesOk(warningVisible: Boolean): Boolean = sleepWarningTakesOk(warningVisible, playerRoute, chrome.values, panels)
}

internal fun sleepWarningTakesOk(warningVisible: Boolean, onPlayerRoute: Boolean, chromeVisible: Collection<Boolean>, openPanels: Int): Boolean =
    warningVisible && onPlayerRoute && chromeVisible.isNotEmpty() && chromeVisible.none { it } && openPanels == 0
