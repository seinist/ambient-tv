package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.PlayerPanelNavigation
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlinx.coroutines.delay

/**
 * Default D-pad target for the player OSD: play/pause, so OK right after opening the OSD
 * toggles playback instead of landing on the seek bar.
 *
 * Overlay children attach a frame late, and the native video surface can steal window focus
 * the moment playback actually starts. Retry on first show, then once more when
 * [playbackStarted] latches true.
 */
@Composable
fun RequestPlayerOverlayFocus(
    requester: FocusRequester,
    enabled: Boolean,
    playbackStarted: Boolean = false,
    playbackIdentity: Any? = null,
    panelNavigation: PlayerPanelNavigation? = LocalPlayerPanelNavigation.current,
) {
    val handheld = LocalHandheldUi.current
    val controlFocus = LocalPlayerControlFocus.current
    val changingItem = LocalPlayerChangingItem.current
    val mediaRoute = LocalPlaybackMetadata.current?.route
    // A closing settings panel hands focus back to the action-bar button that opened it.
    val currentEnabled by rememberUpdatedState(enabled)
    val panelHandingBack = panelNavigation?.restoreKey != null
    var seenPlaybackStart by remember { mutableStateOf(false) }
    if (playbackStarted) seenPlaybackStart = true
    val focusReturnVersion = LocalPlaybackNavigation.current?.focusReturnVersion
    LaunchedEffect(enabled, seenPlaybackStart, playbackIdentity, requester, focusReturnVersion, panelNavigation?.restoreFailed, mediaRoute, changingItem) {
        if (!enabled && panelNavigation?.panel == com.ambient.tv.ui.common.UnifiedPlayerPanel.NONE) {
            panelNavigation.cancelFocusReturn()
        }
        if (handheld || !enabled || panelHandingBack || changingItem) return@LaunchedEffect
        val generation = panelNavigation?.restoreGeneration
        repeat(12) { attempt ->
            if (attempt == 0) {
                withFrameNanos { }
                delay(40)
            } else {
                delay(50)
            }
            if (!currentEnabled || panelNavigation?.panel?.let { it != com.ambient.tv.ui.common.UnifiedPlayerPanel.NONE } == true ||
                panelNavigation?.restoreGeneration != generation || panelNavigation?.restoreKey != null) return@LaunchedEffect
            // A live channel can remount the entire OSD. Restore its last control, and never
            // steal focus again when the new stream reports its first playing frame.
            if (controlFocus?.hasFocus == true) return@LaunchedEffect
            val target = controlFocus?.target(requester) ?: requester
            if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
}
