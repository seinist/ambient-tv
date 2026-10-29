package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.PlayerPanelNavigation
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A fresh OSD enters its compact controls: the seek bar, or channel info without a timeline.
 * Expanded controls retain the last button, including a settings panel's explicit hand-back.
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
    val osd = LocalPlayerChromeState.current?.osd
    // Duration/timeshift may arrive after opening, or disappear on a channel change.
    val compactTarget = controlFocus?.compactTarget()
    val changingItem = LocalPlayerChangingItem.current
    val mediaRoute = LocalPlaybackMetadata.current?.route
    // A closing settings panel hands focus back to the action-bar button that opened it.
    val currentEnabled by rememberUpdatedState(enabled)
    val panelHandingBack = panelNavigation?.restoreKey != null
    var seenPlaybackStart by remember { mutableStateOf(false) }
    if (playbackStarted) seenPlaybackStart = true
    val focusReturnVersion = LocalPlaybackNavigation.current?.focusReturnVersion
    LaunchedEffect(enabled, seenPlaybackStart, playbackIdentity, requester, focusReturnVersion, panelNavigation?.restoreFailed, mediaRoute, changingItem, osd?.mode, osd?.closing, compactTarget) {
        if (!enabled && panelNavigation?.panel == com.ambient.tv.ui.common.UnifiedPlayerPanel.NONE) {
            panelNavigation.cancelFocusReturn()
        }
        if (handheld || !enabled || panelHandingBack || changingItem || osd?.closing == true) return@LaunchedEffect
        val generation = panelNavigation?.restoreGeneration
        repeat(12) { attempt ->
            if (attempt == 0) {
                withFrameNanos { }
                delay(40)
            } else {
                delay(50)
            }
            if (!currentEnabled || osd?.closing == true || panelNavigation?.panel?.let { it != com.ambient.tv.ui.common.UnifiedPlayerPanel.NONE } == true ||
                panelNavigation?.restoreGeneration != generation || panelNavigation?.restoreKey != null) return@LaunchedEffect
            // A live channel can remount the entire OSD. Restore its last control, and never
            // steal focus again when the new stream reports its first playing frame.
            val compact = osd != null && !osd.expanded
            if (controlFocus?.hasFocus == true && (osd == null ||
                    if (compact) controlFocus.isFocused(compactTarget) else !controlFocus.compactFocused)) return@LaunchedEffect
            val target = when {
                compact -> controlFocus?.compactTarget()
                osd == null -> controlFocus?.target(requester) ?: requester
                else -> controlFocus?.buttonTarget(requester) ?: requester
            }
            if (target == null) return@repeat
            if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
}

/** Mode changes precede focus requests, so an off-screen action never acquires focus first. */
@Composable
internal fun rememberExpandPlayerControls(): () -> Unit {
    val osd = LocalPlayerChromeState.current?.osd
    val controls = LocalPlayerControlFocus.current
    val fallback = rememberPlayerPlayFocus()
    val navigation = LocalPlayerPanelNavigation.current
    val scope = rememberCoroutineScope()
    return remember(osd, controls, fallback, navigation, scope) {
        {
            osd?.expand()
            val generation = osd?.transitionVersion
            scope.launch {
                repeat(12) {
                    withFrameNanos { }
                    if (osd?.let { it.transitionVersion != generation || !it.expanded || it.closing } == true ||
                        navigation?.panel?.let { it != com.ambient.tv.ui.common.UnifiedPlayerPanel.NONE } == true) return@launch
                    val target = controls?.buttonTarget(fallback) ?: fallback
                    if (runCatching { target.requestFocus() }.getOrDefault(false)) return@launch
                }
            }
        }
    }
}

/** Fold the buttons while returning to the same compact controls used on a fresh opening. */
@Composable
internal fun rememberCollapsePlayerControls(): () -> Unit {
    val osd = LocalPlayerChromeState.current?.osd
    val controls = LocalPlayerControlFocus.current
    val navigation = LocalPlayerPanelNavigation.current
    val scope = rememberCoroutineScope()
    return remember(osd, controls, navigation, scope) {
        {
            osd?.collapse()
            val generation = osd?.transitionVersion
            scope.launch {
                repeat(12) {
                    withFrameNanos { }
                    if (osd?.let { it.transitionVersion != generation || it.expanded || it.closing } == true ||
                        navigation?.panel?.let { it != com.ambient.tv.ui.common.UnifiedPlayerPanel.NONE } == true) return@launch
                    val target = controls?.compactTarget() ?: return@repeat
                    if (runCatching { target.requestFocus() }.getOrDefault(false)) return@launch
                }
            }
        }
    }
}
