package com.ambient.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.ambient.tv.ui.common.UnifiedPlayerPanel

/**
 * A dismissed modal leaves composition immediately: neither its BackHandler nor its focus trap
 * may outlive the navigation level. Keep each parent's scroll/selection state while visiting a
 * child, but never reuse the child's remembered focus state for its parent.
 */
@Composable
fun PlayerPanelVisibility(
    panel: UnifiedPlayerPanel,
    content: @Composable BoxScope.(UnifiedPlayerPanel) -> Unit,
) {
    PlaybackFrameDropOverlay(panel.isVideo || panel == UnifiedPlayerPanel.RENDERER)
    if (panel == UnifiedPlayerPanel.NONE) return
    val levels = rememberSaveableStateHolder()
    // Android's Back dispatcher owns this key. Forwarding it from Compose as well
    // can pop both this panel and the underlying OSD on the same key release.
    Box(Modifier.fillMaxSize().zIndex(PLAYER_SETTINGS_PANEL_Z_INDEX)) {
        levels.SaveableStateProvider(panel.name) {
            content(panel)
        }
    }
}

/** Keeps the chrome composed until its fade finishes, including when visibility reverses. On a
 * dismissal the action bar slides down first and the rest fades after it. */
@Composable
fun PlayerOverlayVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val panelOpen = com.ambient.tv.ui.common.LocalPlayerPanelNavigation.current?.panel
        ?.let { it != UnifiedPlayerPanel.NONE } == true
    val show = playerOsdVisible(visible, panelOpen)
    val visibility = remember { MutableTransitionState(false) }
    val controlFocus = LocalPlayerControlFocus.current
    androidx.compose.runtime.LaunchedEffect(show, panelOpen, controlFocus) {
        // A panel temporarily hides the OSD but retains its originating control.
        // A real OSD dismissal resets the next opening to Play/Pause.
        if (!show && !panelOpen) controlFocus?.resetForNextOpen()
    }
    visibility.targetState = show
    val stow = remember { PlayerOsdStow() }
    // A real dismissal stows the bar first; a panel takes over the screen right away.
    val stowFirst = !show && !panelOpen
    androidx.compose.runtime.SideEffect { stow.closing = stowFirst }
    AnimatedVisibility(
        visibleState = visibility,
        // Above the nerd stats sibling: zIndex only orders siblings, so the panel's own
        // zIndex inside this box could not lift it over the stats.
        modifier = modifier.fillMaxSize().zIndex(PLAYER_SETTINGS_PANEL_Z_INDEX),
        enter = fadeIn(tween(180, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(220, delayMillis = if (stowFirst) 180 else 0, easing = FastOutSlowInEasing)),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalPlayerOsdStow provides stow) { Box(
            Modifier.fillMaxSize()
                // Exiting controls remain drawn but must no longer accept input.
                .focusProperties { canFocus = show }
                // Back belongs to the new active level even during the OSD fade-out.
                .onPreviewKeyEvent { !show && it.key != Key.Back && it.key != Key.Escape }
                // Only while fading out. A pointer node over the whole screen is hit-tested even
                // when it does nothing, so keeping it while visible stopped taps on free video area
                // from reaching the touch layer below (tap to hide the controls never arrived).
                .then(if (show) Modifier else Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                }),
            content = content,
        ) }
    }
}
