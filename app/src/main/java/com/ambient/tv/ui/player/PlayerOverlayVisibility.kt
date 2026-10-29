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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.focusProperties
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

/** Stow expanded controls, hold the compact information briefly, then fade the shared OSD. */
@Composable
fun PlayerOverlayVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val panelOpen = com.ambient.tv.ui.common.LocalPlayerPanelNavigation.current?.panel
        ?.let { it != UnifiedPlayerPanel.NONE } == true
    val osd = LocalPlayerChromeState.current?.osd
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val hidden = osd?.mode == PlayerOsdMode.HIDDEN
    val show = playerOsdVisible(visible && osd?.closing != true, panelOpen)
    val currentShow by rememberUpdatedState(show)
    val currentPanelOpen by rememberUpdatedState(panelOpen)
    var swallowingGesture by remember { mutableStateOf(false) }
    val visibility = remember(hidden) { MutableTransitionState(false) }
    val controlFocus = LocalPlayerControlFocus.current
    androidx.compose.runtime.LaunchedEffect(show, panelOpen, controlFocus) {
        // A panel temporarily hides the OSD but retains its originating control.
        // A real dismissal enters compact controls next time, retaining the last used button.
        if (!show && !panelOpen) controlFocus?.resetForNextOpen()
    }
    visibility.targetState = show
    val stow = remember(hidden) { PlayerOsdStow(initiallyCollapsed = !handheld && osd?.expanded == false) }
    // A real dismissal stows the bar first; a panel takes over the screen right away.
    val stowFirst = !show && !panelOpen
    androidx.compose.runtime.SideEffect { stow.closing = stowFirst }
    // Capture once per dismissal; a moving bar must not change the fade delay midway through it.
    val fadeDelay = remember(show, panelOpen, osd?.transitionVersion) {
        if (stowFirst && !handheld && (osd?.closingFromExpanded == true || stow.fraction < 0.999f)) 700 else 0
    }
    val closeVersion = osd?.transitionVersion
    androidx.compose.runtime.LaunchedEffect(visibility.isIdle, visibility.currentState, visibility.targetState, closeVersion, panelOpen) {
        if (!panelOpen && osd?.closing == true && visibility.isIdle && !visibility.currentState && !visibility.targetState) {
            osd.finishClose(closeVersion)
        }
    }
    // A second Back completes dismissal immediately; a later opening starts compact again.
    if (hidden && osd?.closing != true) return
    AnimatedVisibility(
        visibleState = visibility,
        // Above the nerd stats sibling: zIndex only orders siblings, so the panel's own
        // zIndex inside this box could not lift it over the stats.
        modifier = modifier.fillMaxSize().zIndex(PLAYER_SETTINGS_PANEL_Z_INDEX),
        enter = fadeIn(tween(180, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(200, delayMillis = fadeDelay, easing = FastOutSlowInEasing)),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalPlayerOsdStow provides stow) { Box(
            Modifier.fillMaxSize()
                // Exiting controls remain drawn; focus cannot re-enter their stale actions.
                .focusProperties { onEnter = { if (!show) cancelFocusChange() } }
                .focusGroup()
                // Intercept before outgoing child clickables consume the Main pass. Keep
                // the guard until every finger lifts even though reveal recomposes the OSD;
                // a second finger must not activate the now-visible actions mid-gesture.
                // With no intercepted gesture, omit the full-screen node while open so
                // free-picture taps still reach the video touch layer below this sibling.
                .then(if (show && !swallowingGesture) Modifier else Modifier.pointerInput(osd) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (currentShow) return@awaitEachGesture
                        swallowingGesture = true
                        try {
                            currentEvent.changes.forEach { it.consume() }
                            if (!currentPanelOpen) osd?.reveal()
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            swallowingGesture = false
                        }
                    }
                }),
            content = content,
        ) }
    }
}
