@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.ambient.tv.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.tv.material3.LocalTextStyle
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.deviceDp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.sound.LocalUiSoundPlayer
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.player.playerPanelInteractionGuard
import com.ambient.tv.ui.theme.appControlSurface
import com.ambient.tv.ui.theme.menuFocusMotion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Handheld windows can be narrower than the television panel width, above all in portrait, where
 * a fixed 500 dp panel would push its start edge off screen. Television keeps the exact width.
 */
/**
 * Standalone browse/settings drawers share the player's slide duration and easing. The player
 * itself owns its dismissal animation and must not be animated a second time.
 *
 * Only the outer handheld overlay owns this state; the nested panel column inherits it so
 * outside-tap, Back and footer all dismiss the same session exactly once.
 */
internal class RightPanelDismissOnce {
    var closing by mutableStateOf(false)
        private set

    fun claim(): Boolean {
        if (closing) return false
        closing = true
        return true
    }
}

private class StandaloneRightPanelMotion {
    val progress = Animatable(0f)
    val dismiss = RightPanelDismissOnce()
}

private val LocalStandaloneRightPanelMotion = staticCompositionLocalOf<StandaloneRightPanelMotion?> { null }

internal fun rightPanelBackKey(key: Key): Boolean = key == Key.Back || key == Key.Escape

/** Scrollbar of the enclosing [RightSidePanel] while its body overflows; null otherwise. */
internal val LocalRightPanelScrollbarFocus = androidx.compose.runtime.staticCompositionLocalOf<FocusRequester?> { null }

/** The control that Left from the panel scrollbar returns to, see [rightPanelScrollbarReturn]. */
internal class RightPanelReturnFocus {
    var target by mutableStateOf<FocusRequester?>(null)
    var memberFocused = false
}

internal val LocalRightPanelReturnFocus = androidx.compose.runtime.staticCompositionLocalOf<RightPanelReturnFocus?> { null }

/**
 * Left from the scrollbar would otherwise re-enter the body by geometry and pick whatever lies
 * level with the bar. A control marked with this is restored exactly when it was the last focus.
 */
fun Modifier.rightPanelScrollbarReturn(): Modifier = composed {
    val holder = LocalRightPanelReturnFocus.current ?: return@composed Modifier
    val requester = remember { FocusRequester() }
    focusRequester(requester).onFocusChanged {
        if (it.isFocused) { holder.memberFocused = true; holder.target = requester }
        else if (holder.target === requester) holder.memberFocused = false
    }
}

/**
 * Window rows (top, bottom, in px) of the handheld page area: below the status bar, above the
 * bottom navigation, mini player and system navigation bar. Right panels keep clear of it.
 */
class HandheldPanelBounds {
    var top by mutableStateOf(0)
    var bottom by mutableStateOf(0)
}

val LocalHandheldPanelBounds = androidx.compose.runtime.staticCompositionLocalOf<HandheldPanelBounds?> { null }

/**
 * Stretches an overlay from the window's top to its bottom even when its parent starts lower,
 * e.g. a library page below the top tab bar, so every right panel sits at the same place.
 * With [handheldGap] a handheld panel instead spans the page area of [LocalHandheldPanelBounds]
 * minus that gap at both ends, so it never sits behind the menu or the system bars.
 */
fun Modifier.fillWindowHeight(handheldGap: Dp? = null): Modifier = composed {
    val view = androidx.compose.ui.platform.LocalView.current
    val bounds = if (handheldGap != null && com.ambient.tv.ui.mobile.LocalHandheldUi.current) LocalHandheldPanelBounds.current else null
    var parentTop by remember { mutableStateOf(0) }
    this
        .onPlaced { coordinates ->
            parentTop = coordinates.parentLayoutCoordinates?.localToWindow(androidx.compose.ui.geometry.Offset.Zero)?.y?.toInt()?.coerceAtLeast(0) ?: 0
        }
        .layout { measurable, constraints ->
            val windowHeight = view.rootView.height
            var top = 0
            var height = if (windowHeight > 0) windowHeight else constraints.maxHeight
            if (bounds != null && bounds.bottom > bounds.top) {
                val gap = handheldGap!!.roundToPx()
                top = bounds.top + gap
                height = (bounds.bottom - bounds.top - 2 * gap).coerceAtLeast(0)
            }
            val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
            layout(placeable.width, constraints.constrainHeight(height)) { placeable.place(0, top - parentTop) }
        }
}

@Composable
private fun panelWidth(width: Dp): Dp {
    if (!com.ambient.tv.ui.mobile.LocalHandheldUi.current) return width
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    // Landscape: 38 % of the screen, as on television.
    return if (configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        screenWidth * 0.38f
    else minOf(width, screenWidth)
}

/**
 * Shared shell for every modal panel that slides in from the right edge.
 *
 * Chrome matches [AppDialog]: accent strip, gradient fill, primary border, hairlines around the
 * scrolling body, footer Back/Close, and a scrollbar when the list overflows.
 */
@Composable
fun RightSidePanel(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    width: Dp = 500.dp,
    closeInsteadOfBack: Boolean = false,
    requestHeaderFocus: Boolean = true,
    scrollable: Boolean = true,
    header: (@Composable () -> Unit)? = null,
    onOutsideTap: (() -> Unit)? = onBack,
    retainFocus: Boolean = true,
    focusRescue: PanelFocusRescue? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val scrollbarContentFocus = remember { FocusRequester() }
    val scrollbarFocus = remember { FocusRequester() }
    val returnFocus = remember { RightPanelReturnFocus() }
    // Paging with the scrollbar moves away from the remembered control; Left then enters the
    // body by position again, so the scrollbar keeps its purpose.
    LaunchedEffect(scrollState) {
        androidx.compose.runtime.snapshotFlow { scrollState.value }.collect {
            if (!returnFocus.memberFocused) returnFocus.target = null
        }
    }
    val overflow = scrollable && scrollState.maxValue > 0
    RightSidePanelChrome(
        title = title,
        onBack = onBack,
        modifier = modifier,
        subtitle = subtitle,
        width = width,
        closeInsteadOfBack = closeInsteadOfBack,
        requestHeaderFocus = requestHeaderFocus,
        scrollToTop = if (scrollable) ({ scrollState.scrollTo(0) }) else null,
        scrollbar = if (overflow) {
            { OverlayScrollbar(scrollState, Modifier.fillMaxHeight().focusRequester(scrollbarFocus), contentFocus = returnFocus.target ?: scrollbarContentFocus) }
        } else null,
        canScrollBackward = scrollable && scrollState.canScrollBackward,
        canScrollForward = scrollable && scrollState.canScrollForward,
        header = header,
        onOutsideTap = onOutsideTap,
        retainFocus = retainFocus,
        focusRescue = focusRescue,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .focusRequester(scrollbarContentFocus).focusGroup()
                // Any unmarked control taking focus ends the marked one's claim on the return.
                .onFocusEvent { if (it.hasFocus && !returnFocus.memberFocused) returnFocus.target = null }
                .padding(end = if (overflow) 16.dp else 0.dp)
                .padding(vertical = 12.dp)
                .then(if (scrollable) Modifier.wrapVerticalScrollFocus(scrollState, focusActionsOnRight = true).verticalScroll(scrollState) else Modifier)
                .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.itemGap),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalRightPanelScrollbarFocus provides if (overflow) scrollbarFocus else null,
                LocalRightPanelReturnFocus provides returnFocus,
            ) { content() }
        }
    }
}

@Composable
fun RightSideLazyPanel(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    width: Dp = 500.dp,
    closeInsteadOfBack: Boolean = false,
    requestHeaderFocus: Boolean = true,
    onOutsideTap: (() -> Unit)? = onBack,
    content: LazyListScope.() -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrollbarContentFocus = remember { FocusRequester() }
    val overflow = listState.canScrollForward || listState.canScrollBackward
    RightSidePanelChrome(
        title = title,
        onBack = onBack,
        modifier = modifier,
        subtitle = subtitle,
        width = width,
        closeInsteadOfBack = closeInsteadOfBack,
        requestHeaderFocus = requestHeaderFocus,
        scrollToTop = { listState.scrollToItem(0) },
        scrollToEnd = { listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) },
        scrollbar = if (overflow) {
            { OverlayLazyScrollbar(listState, Modifier.fillMaxHeight(), contentFocus = scrollbarContentFocus) }
        } else null,
        canScrollBackward = listState.canScrollBackward,
        canScrollForward = listState.canScrollForward,
        header = null,
        onOutsideTap = onOutsideTap,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .focusRequester(scrollbarContentFocus).focusGroup()
                .padding(end = if (overflow) 16.dp else 0.dp)
                .padding(vertical = 12.dp),
            contentPadding = PaddingValues(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun RightSidePanelChrome(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    subtitle: String?,
    width: Dp,
    closeInsteadOfBack: Boolean,
    requestHeaderFocus: Boolean,
    scrollToTop: (suspend () -> Unit)?,
    scrollToEnd: (suspend () -> Unit)? = null,
    scrollbar: (@Composable () -> Unit)?,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    header: (@Composable () -> Unit)?,
    onOutsideTap: (() -> Unit)?,
    retainFocus: Boolean = false,
    focusRescue: PanelFocusRescue? = null,
    body: @Composable () -> Unit,
) {
    com.ambient.tv.ui.player.SleepWarningKeyGate.BlockWhileShown()
    val playerPanelInteractive = com.ambient.tv.ui.player.LocalPlayerPanelInteractive.current
    val playerPanelMotion = com.ambient.tv.ui.player.LocalPlayerPanelMotion.current
    val reducedEffects = LocalPerformanceProfile.current.reducedEffects
    val inheritedMotion = LocalStandaloneRightPanelMotion.current
    val ownMotion = remember { StandaloneRightPanelMotion() }
    val standaloneMotion = inheritedMotion ?: ownMotion
    val standalone = !playerPanelMotion.active
    val panelInteractive = playerPanelInteractive && (!standalone || !standaloneMotion.dismiss.closing)
    val dismissScope = rememberCoroutineScope()
    val uiSounds = LocalUiSoundPlayer.current

    if (standalone && inheritedMotion == null) {
        LaunchedEffect(standaloneMotion, reducedEffects) {
            standaloneMotion.progress.animateTo(
                1f,
                tween(if (reducedEffects) com.ambient.tv.ui.player.PLAYER_PANEL_REDUCED_MS
                    else com.ambient.tv.ui.player.PLAYER_PANEL_ENTER_MS, easing = FastOutSlowInEasing),
            )
        }
    }

    fun dismiss(action: () -> Unit) {
        if (!playerPanelInteractive) return
        if (!standalone) {
            // PlayerPanelVisibility retains the outgoing level and animates its exit itself.
            action()
            return
        }
        if (!standaloneMotion.dismiss.claim()) return
        dismissScope.launch {
            standaloneMotion.progress.animateTo(
                0f,
                tween(if (reducedEffects) com.ambient.tv.ui.player.PLAYER_PANEL_REDUCED_MS
                    else com.ambient.tv.ui.player.PLAYER_PANEL_EXIT_MS, easing = FastOutSlowInEasing),
            )
            action()
        }
    }
    fun dismissBack() {
        if (!panelInteractive) return
        if (uiSounds != null) uiSounds.perform(com.ambient.tv.ui.sound.UiSound.BACK) { dismiss(onBack) }
        else dismiss(onBack)
    }

    if (onOutsideTap != null && com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
        // The outside area and inner column must share the same animation/dismiss gate.
        androidx.compose.runtime.CompositionLocalProvider(LocalStandaloneRightPanelMotion provides standaloneMotion) {
            Row(modifier.fillWindowHeight().fillMaxSize()) {
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .then(if (panelInteractive) Modifier.pointerInput(onOutsideTap) {
                            detectTapGestures { dismiss(onOutsideTap) }
                        } else Modifier),
                )
                RightSidePanelChrome(title, onBack, Modifier, subtitle, width, closeInsteadOfBack, requestHeaderFocus,
                    scrollToTop, scrollToEnd, scrollbar, canScrollBackward, canScrollForward, header, onOutsideTap = null,
                    retainFocus = retainFocus, focusRescue = focusRescue, body = body)
            }
        }
        return
    }
    // Android system Back and TV remote Back must always dismiss the modal rather than merely
    // handing focus to the underlying view. A second Back during exit is consumed, not forwarded.
    androidx.activity.compose.BackHandler(enabled = playerPanelInteractive) { dismissBack() }
    val footerFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    var panelHasFocus by remember { mutableStateOf(false) }
    // A rebuilt level that already held focus rescues it like a panel that just lost it.
    var hadPanelFocus by remember { mutableStateOf(focusRescue?.levelFresh == false) }
    val panelFocus = remember { FocusRequester() }
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val isHandheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val rescueScope = androidx.compose.runtime.rememberCoroutineScope()
    val rescue = remember { RescueRun() }
    val footerAllowed = panelFocusFallback(focusRescue?.levelFresh != false) == PanelFocusFallback.FOOTER
    fun focusFirstRow(): Boolean = runCatching { bodyFocus.requestFocus() }.getOrDefault(false)
    // Parking on the first row while the real row is still missing must not make that row the
    // one remembered for later (e.g. after "Warten" in a player dialog).
    fun parkOnFirstRow(): Boolean = focusRescue?.park?.invoke(::focusFirstRow) ?: focusFirstRow()
    val dialogOwnsFocus = focusRescue?.dialogOwnsFocus?.invoke() == true
    LaunchedEffect(retainFocus, panelHasFocus, windowInfo.isWindowFocused, dialogOwnsFocus, panelInteractive) {
        if (!panelInteractive) return@LaunchedEffect
        // A player dialog's focus return owns the hand-back: a paused target of ours is obsolete.
        if (dialogOwnsFocus) rescue.pausedTarget = null
        if (!panelRescueShouldStart(retainFocus, isHandheld, hadPanelFocus, panelHasFocus,
                windowInfo.isWindowFocused, rescue.pausedTarget, dialogOwnsFocus)) return@LaunchedEffect
        if (rescue.job?.isActive == true) return@LaunchedEffect
        val target = rescue.pausedTarget ?: focusRescue?.target?.invoke()
        rescue.pausedTarget = null
        rescue.userMoved = false
        // A removed capability row can leave Compose without a focused child. The remembered
        // row may come back a few hundred milliseconds later (renderer rebuild): keep trying
        // it for a while, holding focus on the first row meanwhile so the D-pad stays here.
        rescue.job = rescueScope.launch {
            try {
                var parked = false
                val placed = retryFocusFor {
                    when (panelRescueStep(windowInfo.isWindowFocused, rescue.userMoved,
                        focusRescue?.dialogOwnsFocus?.invoke() == true)) {
                        PanelRescueStep.STOP -> return@retryFocusFor true
                        // A dialog took the window: keep the target and go on once it is closed.
                        PanelRescueStep.PAUSE -> { rescue.pausedTarget = target; return@retryFocusFor true }
                        PanelRescueStep.CONTINUE -> Unit
                    }
                    if (target != null && focusRescue?.request?.invoke(target) == true) return@retryFocusFor true
                    if (target == null) {
                        if (!panelHasFocus) runCatching { panelFocus.requestFocus() }
                        return@retryFocusFor true
                    }
                    if (!panelHasFocus && !parked) parked = parkOnFirstRow()
                    false
                }
                if (!placed && !panelHasFocus && windowInfo.isWindowFocused) {
                    if (!parkOnFirstRow()) runCatching { panelFocus.requestFocus() }
                }
            } finally {
                // Let the stand-in's focus event (possibly delivered a frame later) pass first.
                if (isActive) runCatching { androidx.compose.runtime.withFrameNanos { } }
                focusRescue?.parkingDone?.invoke()
            }
        }
    }
    if (requestHeaderFocus && panelInteractive) {
        LaunchedEffect(Unit) {
            delay(400)
            if (panelHasFocus || !panelInteractive) return@LaunchedEffect
            // Back only for a level that was just opened; elsewhere the next OK would leave it.
            if (footerAllowed || !focusFirstRow()) runCatching { footerFocus.requestFocus() }
        }
    }
    val tokens = LocalUiTokens.current
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    // Handheld: the panel floats clear of menu and system bars, so all its corners are rounded.
    val panelShape = if (handheld) RoundedCornerShape(topStart = tokens.panelRadius, bottomStart = tokens.panelRadius,
        topEnd = tokens.panelRadius, bottomEnd = tokens.panelRadius)
    else RoundedCornerShape(topStart = tokens.panelRadius, bottomStart = tokens.panelRadius)
    Column(
        modifier = modifier
            .fillWindowHeight(handheldGap = 12.dp)
            .then(if (handheld) Modifier.padding(end = 8.dp) else Modifier)
            .fillMaxHeight()
            .width(panelWidth(width))
            .then(if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) Modifier.pointerInput(Unit) {
                // Consume taps on empty panel space instead of reaching the page/player below.
                detectTapGestures { }
            } else Modifier)
            .graphicsLayer {
                val progress = if (playerPanelMotion.active) playerPanelMotion.panelProgress.value
                    else standaloneMotion.progress.value
                val reduced = if (playerPanelMotion.active) playerPanelMotion.reducedEffects else reducedEffects
                translationX = size.width * com.ambient.tv.ui.player.playerPanelDrawerTranslationFraction(progress, reduced)
                alpha = com.ambient.tv.ui.player.playerPanelDrawerAlpha(progress, reduced)
            }
            .background(overlayPanelBrush(), panelShape)
            .border(1.dp, overlayPanelBorder(), panelShape)
            .focusProperties {
                canFocus = panelInteractive
                onEnter = { if (!panelInteractive) cancelFocusChange() }
                onExit = { cancelFocusChange() }
            }
            .focusRequester(panelFocus)
            .onFocusChanged {
                panelHasFocus = it.hasFocus
                if (it.hasFocus) {
                    hadPanelFocus = true
                    focusRescue?.onFocused?.invoke()
                }
            }
            // Back on a focused TV panel is a modal action, not a page/sidebar focus move.
            // The system BackHandler above handles Android predictive/system navigation as well.
            .onPreviewKeyEvent { event ->
                if (standalone && rightPanelBackKey(event.key) && !TvTextFieldFocus.anyFocused) {
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) dismissBack()
                    true
                } else {
                    if (panelInteractive) {
                        rescue.userMoved = true
                        focusRescue?.parkingDone?.invoke()
                    }
                    false
                }
            }
            .then(if (!panelInteractive) Modifier.playerPanelInteractionGuard(false) else Modifier)
            .then(if (retainFocus) Modifier.focusRestorer() else Modifier)
            .focusGroup()
            .tvModalFocus(initialFocus = requestHeaderFocus, scrollToTop = scrollToTop,
                footerFocus = footerFocus, scrollToEnd = scrollToEnd)
            // Handheld edge-to-edge: keep header and footer clear of the status and navigation bars.
            .then(if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.systemBars.only(androidx.compose.foundation.layout.WindowInsetsSides.Vertical)) else Modifier)
            .padding(start = deviceDp(28.dp, 16.dp), end = 24.dp, top = deviceDp(26.dp, 16.dp), bottom = deviceDp(16.dp, 12.dp)),
    ) {
        OverlayAccentStrip()
        Spacer(Modifier.height(tokens.itemGap))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(tokens.compactGap))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(tokens.itemGap))
        // Fixed content above the scrolling body, e.g. playback artwork that must stay visible.
        header?.let {
            it()
            Spacer(Modifier.height(tokens.itemGap))
        }
        OverlayHairline()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .focusRequester(bodyFocus)
                .focusProperties {
                    onExit = { bodyFocus.saveFocusedChild() }
                }
                .focusGroup(),
        ) {
            // Nested lazy lists route their right-edge shortcut to this panel's own footer.
            androidx.compose.runtime.CompositionLocalProvider(
                LocalScrollListActionFocus provides footerFocus,
                LocalSettingsPresentation provides SettingsPresentation.Panel,
            ) { body() }
            OverlayEdgeFades(canScrollBackward, canScrollForward)
            if (scrollbar != null) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(vertical = 12.dp),
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalScrollListActionFocus provides footerFocus) { scrollbar() }
                }
            }
        }
        OverlayHairline()
        DeviceActionRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemGap),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvActionButton(
                text = stringResource(if (closeInsteadOfBack) R.string.action_close else R.string.back),
                onClick = { dismiss(onBack) },
                modifier = Modifier.focusRequester(footerFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) {
                            // Return to the item that led to Back, independent of its geometry.
                            if (!bodyFocus.restoreFocusedChild()) bodyFocus.requestFocus()
                        }
                        true
                    },
            )
        }
    }
}

@Composable
fun RightPanelSectionTitle(title: String, description: String? = null) {
    SettingsSectionHeader(title = title, description = description)
}

@Composable
fun RightPanelChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    /** Decorative status icon; its meaning belongs in the row text. */
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val uiSounds = LocalUiSoundPlayer.current
    val activate = com.ambient.tv.ui.sound.rememberUiSoundAction(enabled = enabled, onClick = onClick)
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LocalUiTokens.current.controlRadius)
    Row(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) uiSounds?.playFocus() }
            .menuFocusMotion(focused, focusedScale = 1.006f)
            .appControlSurface(focused, selected, enabled = enabled, shape = shape)
            .semantics { this.selected = selected }
            .activateOnTvKey(enabled = enabled, onClick = activate)
            .clickable(enabled = enabled, role = Role.Button, onClick = activate)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            MarqueeTitle(text, focused, color = MaterialTheme.colorScheme.onSurface, style = LocalTextStyle.current.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium))
            subtitle?.takeIf { it.isNotBlank() }?.let {
                MarqueeTitle(it, focused, color = MaterialTheme.colorScheme.onSurfaceVariant, style = LocalTextStyle.current.copy(fontSize = 13.sp))
            }
        }
        val endIcon = trailingIcon ?: Icons.Filled.Check.takeIf { selected }
        if (endIcon != null) {
            Icon(endIcon, contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
fun RightPanelChoiceRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Shared numeric control for settings and view options. */
@Composable
fun RightPanelValueSlider(
    title: String,
    value: Int,
    valueRange: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    valueLabel: String = value.toString(),
    subtitle: String? = null,
) {
    val uiSounds = LocalUiSoundPlayer.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PlayerValueSlider(
            label = title,
            value = value,
            valueRange = valueRange,
            step = step,
            valueLabel = valueLabel,
            modifier = Modifier.onFocusChanged { if (it.isFocused) uiSounds?.playFocus() },
            onValueChange = { next ->
                if (next != value) {
                    if (uiSounds != null) uiSounds.perform(com.ambient.tv.ui.sound.UiSound.VALUE) { onValueChange(next) }
                    else onValueChange(next)
                }
            },
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun RightPanelDivider() {
    OverlayHairline()
}

/**
 * Read-only line in an informational panel. It takes focus anyway: a panel of plain text gives the
 * D-pad nothing to land on, so focus would skip to the footer and the list could never scroll.
 */
@Composable
fun RightPanelInfoRow(text: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LocalUiTokens.current.controlRadius)
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (focused) 1f else 0.78f),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .appControlSurface(focused, false, shape = shape)
            .focusable()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private class RescueRun {
    var job: kotlinx.coroutines.Job? = null
    var userMoved = false
    /** Row a rescue still has to reach after a dialog window gave the focus back. */
    var pausedTarget: String? = null
}
