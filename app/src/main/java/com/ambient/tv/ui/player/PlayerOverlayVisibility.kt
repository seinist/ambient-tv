package com.ambient.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.playerPanelInput
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal const val PLAYER_PANEL_ENTER_MS = 250
internal const val PLAYER_PANEL_EXIT_MS = 210
internal const val PLAYER_PANEL_SCRIM_ENTER_MS = 190
internal const val PLAYER_PANEL_SCRIM_EXIT_MS = 180
internal const val PLAYER_PANEL_REDUCED_MS = 120
internal const val PLAYER_PANEL_CONTENT_MS = 160
internal const val PLAYER_PANEL_CONTENT_FADE_MS = 120
internal const val PLAYER_PANEL_CONTENT_OFFSET_DP = 24
internal const val PLAYER_PANEL_DRAWER_MIN_ALPHA = 0.86f

internal enum class PlayerPanelContentMotion { FORWARD, BACKWARD, REPLACE }

internal fun playerPanelContentMotion(fromDepth: Int, toDepth: Int): PlayerPanelContentMotion = when {
    toDepth > fromDepth -> PlayerPanelContentMotion.FORWARD
    toDepth < fromDepth -> PlayerPanelContentMotion.BACKWARD
    else -> PlayerPanelContentMotion.REPLACE
}

internal fun playerPanelDrawerTranslationFraction(progress: Float, reducedEffects: Boolean): Float =
    if (reducedEffects) 0f else 1f - progress.coerceIn(0f, 1f)

internal fun playerPanelDrawerAlpha(progress: Float, reducedEffects: Boolean): Float {
    val value = progress.coerceIn(0f, 1f)
    return if (reducedEffects) value else PLAYER_PANEL_DRAWER_MIN_ALPHA +
        (1f - PLAYER_PANEL_DRAWER_MIN_ALPHA) * value
}

private val StaticPlayerPanelProgress = mutableFloatStateOf(1f)

internal data class PlayerPanelMotionState(
    val active: Boolean = false,
    val panelProgress: State<Float> = StaticPlayerPanelProgress,
    val scrimProgress: State<Float> = StaticPlayerPanelProgress,
    val visualDepth: Int = 1,
    val reducedEffects: Boolean = false,
)

internal val LocalPlayerPanelMotion = staticCompositionLocalOf { PlayerPanelMotionState() }
/** False only while the already-dismissed panel finishes its visual exit. */
internal val LocalPlayerPanelInteractive = staticCompositionLocalOf { true }
internal val LocalPlayerPanelStateHolder = staticCompositionLocalOf<SaveableStateHolder?> { null }

internal fun Modifier.playerPanelInteractionGuard(interactive: Boolean): Modifier =
    if (interactive) this else this
        .focusProperties { onEnter = { cancelFocusChange() } }
        .onPreviewKeyEvent { true }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                }
            }
        }
        .clearAndSetSemantics { }

/**
 * Navigation is dismissed immediately, but the last visual level stays composed until its exit
 * motion finishes. That lets the OSD start returning underneath without leaving a blank frame,
 * while Back, focus and pointer input already belong to the player again.
 */
@Composable
fun PlayerPanelVisibility(
    panel: UnifiedPlayerPanel,
    content: @Composable BoxScope.(UnifiedPlayerPanel) -> Unit,
) {
    PlaybackFrameDropOverlay(panel.isVideo || panel == UnifiedPlayerPanel.RENDERER)
    val navigation = LocalPlayerPanelNavigation.current
    val reducedEffects = LocalPerformanceProfile.current.reducedEffects
    val currentDepth = navigation?.depth ?: if (panel == UnifiedPlayerPanel.NONE) 0 else 1
    var visualPanel by remember { mutableStateOf(panel) }
    var visualDepth by remember { mutableIntStateOf(currentDepth) }
    var stateGeneration by remember { mutableIntStateOf(0) }
    val panelProgress = remember { Animatable(if (panel == UnifiedPlayerPanel.NONE) 0f else 1f) }
    val scrimProgress = remember { Animatable(if (panel == UnifiedPlayerPanel.NONE) 0f else 1f) }
    val panelProgressState = remember(panelProgress) { derivedStateOf { panelProgress.value } }
    val scrimProgressState = remember(scrimProgress) { derivedStateOf { scrimProgress.value } }
    val interactive = panel != UnifiedPlayerPanel.NONE

    LaunchedEffect(panel, currentDepth, reducedEffects) {
        if (panel != UnifiedPlayerPanel.NONE) {
            visualPanel = panel
            visualDepth = currentDepth
            coroutineScope {
                launch {
                    panelProgress.animateTo(
                        1f,
                        tween(if (reducedEffects) PLAYER_PANEL_REDUCED_MS else PLAYER_PANEL_ENTER_MS,
                            easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    scrimProgress.animateTo(
                        1f,
                        tween(if (reducedEffects) PLAYER_PANEL_REDUCED_MS else PLAYER_PANEL_SCRIM_ENTER_MS,
                            easing = FastOutSlowInEasing),
                    )
                }
            }
        } else if (visualPanel != UnifiedPlayerPanel.NONE) {
            coroutineScope {
                launch {
                    panelProgress.animateTo(
                        0f,
                        tween(if (reducedEffects) PLAYER_PANEL_REDUCED_MS else PLAYER_PANEL_EXIT_MS,
                            easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    scrimProgress.animateTo(
                        0f,
                        tween(if (reducedEffects) PLAYER_PANEL_REDUCED_MS else PLAYER_PANEL_SCRIM_EXIT_MS,
                            easing = FastOutSlowInEasing),
                    )
                }
            }
            // A reopen cancels this effect before the visual level is discarded.
            if (panel == UnifiedPlayerPanel.NONE) {
                visualPanel = UnifiedPlayerPanel.NONE
                visualDepth = 0
                // A complete dismissal starts a fresh panel session next time, matching the
                // old immediate-unmount behavior while still allowing this exit to finish.
                stateGeneration++
            }
        }
    }

    if (visualPanel == UnifiedPlayerPanel.NONE) return

    val input = if (interactive) {
        Modifier.playerPanelInput(navigation)
    } else {
        Modifier.playerPanelInteractionGuard(false)
    }

    key(stateGeneration) {
        val levels = rememberSaveableStateHolder()
        CompositionLocalProvider(
            LocalPlayerPanelInteractive provides interactive,
            LocalPlayerPanelStateHolder provides levels,
            LocalPlayerPanelMotion provides PlayerPanelMotionState(
                active = true,
                panelProgress = panelProgressState,
                scrimProgress = scrimProgressState,
                visualDepth = visualDepth,
                reducedEffects = reducedEffects,
            ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(PLAYER_SETTINGS_PANEL_Z_INDEX)
                    .then(input),
            ) {
                content(visualPanel)
            }
        }
    }
}

/** Delay the final fade until the outgoing action row has slid away. Panels never stow the OSD. */
internal fun playerOsdExitFadeDelayMs(
    stowFirst: Boolean,
    closingFromExpanded: Boolean,
    stowFraction: Float,
    handheld: Boolean,
): Int = if (stowFirst && (closingFromExpanded || stowFraction < 0.999f)) {
    if (handheld) 300 else 700
} else 0

/** A re-mounted overlay starts visible only when the OSD was on screen and still should be. */
internal fun playerOsdResumesOnScreen(wasOnScreen: Boolean, show: Boolean): Boolean = wasOnScreen && show

/** Handheld openings slide the action row in; a resumed OSD keeps its current layout. */
internal fun playerOsdStowStartsCollapsed(
    resumed: Boolean,
    handheld: Boolean,
    hasController: Boolean,
    expanded: Boolean?,
): Boolean = if (resumed) expanded != true else (handheld && hasController) || expanded == false

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
    // A visible OSD that is only re-mounted (new channel/station model, engine switch) keeps
    // showing; only a real opening fades in.
    val resumed = remember(hidden) { playerOsdResumesOnScreen(osd?.onScreen == true, show) }
    val visibility = remember(hidden) { MutableTransitionState(resumed) }
    val controlFocus = LocalPlayerControlFocus.current
    androidx.compose.runtime.LaunchedEffect(show, panelOpen, controlFocus) {
        // A panel temporarily hides the OSD but retains its originating control.
        // A real dismissal enters compact controls next time, retaining the last used button.
        if (!show && !panelOpen) controlFocus?.resetForNextOpen()
    }
    visibility.targetState = show
    // Handheld reopening slides the action row in from below, even if the OSD expands immediately.
    val stow = remember(hidden) {
        PlayerOsdStow(initiallyCollapsed = playerOsdStowStartsCollapsed(resumed, handheld, osd != null, osd?.expanded))
    }
    val preview = remember(hidden) { SeekPreviewOverlayState() }
    var previewOrigin by remember { mutableStateOf(Offset.Zero) }
    var previewViewport by remember { mutableStateOf(IntSize.Zero) }
    // A real dismissal stows the bar first; a panel takes over the screen right away.
    val stowFirst = !show && !panelOpen
    androidx.compose.runtime.SideEffect {
        if (osd != null) osd.onScreen = show && (visibility.currentState || visibility.targetState)
    }
    androidx.compose.runtime.SideEffect { stow.closing = stowFirst }
    // Capture once per dismissal; a moving bar must not change the fade delay midway through it.
    val fadeDelay = remember(show, panelOpen, osd?.transitionVersion) {
        playerOsdExitFadeDelayMs(stowFirst, osd?.closingFromExpanded == true, stow.fraction, handheld)
    }
    val closeVersion = osd?.transitionVersion
    androidx.compose.runtime.LaunchedEffect(visibility.isIdle, visibility.currentState, visibility.targetState, closeVersion, panelOpen) {
        if (!panelOpen && osd?.closing == true && visibility.isIdle && !visibility.currentState && !visibility.targetState) {
            osd.finishClose(closeVersion)
        }
    }
    // A second Back completes dismissal immediately; a later opening starts compact again.
    if (hidden && osd.closing != true) return
    AnimatedVisibility(
        visibleState = visibility,
        // Above the nerd stats sibling: zIndex only orders siblings, so the panel's own
        // zIndex inside this box could not lift it over the stats.
        modifier = modifier.fillMaxSize().zIndex(PLAYER_SETTINGS_PANEL_Z_INDEX),
        enter = fadeIn(tween(180, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(200, delayMillis = fadeDelay, easing = FastOutSlowInEasing)),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPlayerOsdStow provides stow,
            LocalSeekPreviewOverlayState provides preview,
        ) { Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    previewOrigin = coordinates.positionInRoot()
                    previewViewport = coordinates.size
                }
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
                            if (!currentPanelOpen) {
                                if (handheld) osd?.expandForHandheldTap() else osd?.reveal()
                            }
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            swallowingGesture = false
                        }
                    }
                }),
        ) {
            content()
            SeekPreviewOverlay(preview, previewOrigin, previewViewport)
        } }
    }
}
