package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.ambient.tv.ui.common.PLAYER_CONTROLS_TIMEOUT_MS
import kotlinx.coroutines.delay

/** Every player hides the OSD while a settings panel owns the screen. */
internal fun playerOsdVisible(controlsVisible: Boolean, panelOpen: Boolean): Boolean =
    controlsVisible && !panelOpen

internal fun playerControlsCanAutoHide(
    visible: Boolean,
    playing: Boolean,
    panelOpen: Boolean,
    changingItem: Boolean,
    closing: Boolean = false,
): Boolean = visible && playing && !panelOpen && !changingItem && !closing

/**
 * One timeout policy for every content source and decoder. Opening a panel cancels the
 * deadline and retains the return state; closing the last panel starts a full, fresh timeout.
 * User input, pause and item changes also cancel/restart the deadline.
 */
@Composable
internal fun PlayerControlsAutoHide(
    visible: Boolean,
    playing: Boolean,
    panelOpen: Boolean,
    changingItem: Boolean = false,
    interactionVersion: Any? = null,
    onVisibilityChanged: (Boolean) -> Unit,
) {
    val setVisible by rememberUpdatedState(onVisibilityChanged)
    val navigationInput = LocalPlaybackNavigation.current?.interactionVersion
    val userInput = BackgroundPlaybackSession.userInputVersion
    val osd = LocalPlayerChromeState.current?.osd
    val timeoutMs = PLAYER_CONTROLS_TIMEOUT_MS
    LaunchedEffect(visible, playing, panelOpen, changingItem, interactionVersion,
        osd?.interactionVersion, osd?.closing, navigationInput, userInput, timeoutMs) {
        if (panelOpen) {
            // The overlay hides visually through playerOsdVisible. Keep its logical return
            // state so panel Back restores the OSD and the originating action together.
            if (!visible) setVisible(true)
            return@LaunchedEffect
        }
        if (playerControlsCanAutoHide(visible, playing, panelOpen, changingItem, osd?.closing == true)) {
            delay(timeoutMs)
            setVisible(false)
        }
    }
}
