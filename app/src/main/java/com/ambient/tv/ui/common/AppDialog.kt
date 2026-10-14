@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.ambient.tv.ui.common

import com.ambient.tv.ui.sound.navigationSoundInput

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.mobile.isHandheldLandscape
import com.ambient.tv.ui.mobile.isTabletUi
import com.ambient.tv.ui.theme.LocalAccentModeKey
import com.ambient.tv.ui.theme.LocalUiTokens

/** Tallest a dialog may be. The leftover fraction is split as top and bottom inset. */
private const val AppDialogMaxHeightFraction = 0.72f

internal fun appDialogDefaultWidthFraction(
    handheld: Boolean,
    wideHandheld: Boolean,
    tablet: Boolean = false,
): Float = when {
    // Tablet landscape uses more of the window than a phone card. Portrait matches the phone
    // fraction but is not capped like a landscape phone.
    tablet && wideHandheld -> 0.82f
    tablet -> 0.94f
    wideHandheld -> 0.68f
    handheld -> 0.94f
    else -> 0.46f
}

/** Dialog width cap in dp. Landscape phones stay at a 560dp card; tablets do not. */
internal fun appDialogMaxWidthDp(handheld: Boolean, wideHandheld: Boolean, tablet: Boolean = false): Int = when {
    tablet && wideHandheld -> 1200
    tablet -> 960
    wideHandheld -> 560
    handheld -> 720
    else -> 640
}

internal fun appDialogHeightFraction(requested: Float, handheld: Boolean, wideHandheld: Boolean): Float =
    if (handheld && wideHandheld) maxOf(requested, 0.90f).coerceAtMost(0.94f) else requested

/**
 * Shared Settings-card dialog chrome. Dialog windows can reset density, so uiScale / UiTokens
 * from the page are re-provided. List dialogs fill [AppDialogMaxHeightFraction] of the screen so
 * the footer sits at the bottom of the panel; compact dialogs wrap and still cap at that height.
 * Neither variant reaches the screen edges.
 */
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    title: String,
    hint: String? = null,
    fillHeight: Boolean = false,
    scrollable: Boolean = true,
    captureInitialFocus: Boolean = true,
    widthFraction: Float? = null,
    minWidth: Dp = Dp.Unspecified,
    maxWidth: Dp = Dp.Unspecified,
    /** Compact dialogs whose content must not scroll on a normal TV may allow a taller panel. */
    maxHeightFraction: Float = AppDialogMaxHeightFraction,
    onDismissRequest: () -> Unit = onDismiss,
    header: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable RowScope.() -> Unit = {
        TvActionButton(text = stringResource(R.string.back), onClick = onDismiss)
    },
    /** Null for confirmation-only dialogs: skips the content area and its extra hairline. */
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val tokens = LocalUiTokens.current
    val accentMode = LocalAccentModeKey.current
    val handheld = LocalHandheldUi.current
    val wideHandheld = isHandheldLandscape()
    val tablet = isTabletUi()
    val effectiveMaxHeightFraction = appDialogHeightFraction(maxHeightFraction, handheld, wideHandheld)
    // Measured in the page's (UI-scaled) density: Configuration.screenHeightDp is in system dp
    // and would shrink every dialog by the UI scale factor, forcing content to scroll.
    val screenHeight = with(density) { LocalContext.current.resources.displayMetrics.heightPixels.toDp() }
        .takeIf { it > 0.dp } ?: LocalConfiguration.current.screenHeightDp.dp
    val maxHeight = screenHeight * effectiveMaxHeightFraction
    val verticalInset = screenHeight * ((1f - effectiveMaxHeightFraction) / 2f)
    val listScroll = rememberScrollState()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        com.ambient.tv.ui.display.MatchUiHdrDialogWindow()
        // A dialog owns its own view, so the framework's D-pad clicks have to be silenced here too.
        com.ambient.tv.ui.sound.SilenceSystemMenuSounds()
        if (handheld) DialogResizesForKeyboard()
        CompositionLocalProvider(
            LocalDensity provides density,
            LocalUiTokens provides tokens,
            LocalAccentModeKey provides accentMode,
        ) {
            val scaledTokens = LocalUiTokens.current
            val panelShape = RoundedCornerShape(scaledTokens.panelRadius)
            val fraction = widthFraction ?: appDialogDefaultWidthFraction(handheld, wideHandheld, tablet)
            val minW = if (minWidth == Dp.Unspecified) deviceDp(420.dp, 0.dp) else minWidth
            val maxW = if (maxWidth == Dp.Unspecified) {
                appDialogMaxWidthDp(handheld, wideHandheld, tablet).dp
            } else maxWidth
            val listOverflow = scrollable && listScroll.maxValue > 0
            val footerFocus = remember { FocusRequester() }
            val scrollbarContentFocus = remember { FocusRequester() }
            val contentButtons = remember { DialogButtonFocusScope() }
            val footerButtons = remember { DialogButtonFocusScope(containSingleButton = true) }
            Box(Modifier.navigationSoundInput().fillMaxSize()) {
            // The dialog window fills the screen, so Android cannot detect taps outside
            // the visible card. Keep the dismiss target behind the card and its controls.
            if (handheld) {
                Box(Modifier.fillMaxSize().pointerInput(onDismiss) {
                    detectTapGestures { onDismiss() }
                })
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
            // The phone keyboard resizes the dialog window, while the insets above come from the
            // full screen. Measure what is left so the panel shrinks and its content area scrolls.
            val keyboardShrunk = handheld && this.maxHeight < screenHeight * 0.85f
            val inset = if (keyboardShrunk) 8.dp else verticalInset
            val panelMaxHeight = if (keyboardShrunk) (this.maxHeight - inset * 2).coerceAtLeast(0.dp) else maxHeight
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = inset),
                contentAlignment = Alignment.Center,
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .widthIn(min = minW, max = maxW)
                    .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier.heightIn(max = panelMaxHeight))
                    .clip(panelShape)
                    .then(if (handheld) Modifier.pointerInput(Unit) {
                        // Blank card areas must also block the dismiss target underneath.
                        detectTapGestures { }
                    } else Modifier)
                    .background(overlayPanelBrush(), panelShape)
                    .border(1.dp, overlayPanelBorder(), panelShape)
                    .tvModalFocus(
                        initialFocus = captureInitialFocus,
                        scrollToTop = if (scrollable) ({ listScroll.scrollTo(0) }) else null,
                        footerFocus = footerFocus,
                    )
                    .padding(horizontal = deviceDp(28.dp, 16.dp))
                    .padding(top = deviceDp(28.dp, 16.dp), bottom = deviceDp(16.dp, 12.dp)),
            ) {
                OverlayAccentStrip()
                Spacer(Modifier.height(scaledTokens.itemGap))
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (!hint.isNullOrBlank()) {
                    Spacer(Modifier.height(scaledTokens.compactGap))
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                header()
                Spacer(Modifier.height(scaledTokens.itemGap))
                if (content != null) {
                    OverlayHairline()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (fillHeight) Modifier.weight(1f) else Modifier.weight(1f, fill = false))
                            .clipToBounds(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (fillHeight) Modifier.fillMaxSize() else Modifier)
                                .focusRequester(scrollbarContentFocus).focusGroup()
                                .padding(end = if (listOverflow) 16.dp else 0.dp)
                                .padding(vertical = 12.dp)
                                .then(if (scrollable) Modifier.wrapVerticalScrollFocus(listScroll, actionsFocus = footerFocus).verticalScroll(listScroll) else Modifier),
                            verticalArrangement = Arrangement.spacedBy(scaledTokens.compactGap),
                            content = {
                                CompositionLocalProvider(
                                    LocalScrollListActionFocus provides footerFocus,
                                    LocalDialogButtonFocus provides contentButtons,
                                ) { content() }
                            },
                        )
                        if (scrollable) OverlayEdgeFades(listScroll.canScrollBackward, listScroll.canScrollForward)
                        if (listOverflow) {
                            OverlayScrollbar(
                                state = listScroll,
                                actionsFocus = footerFocus,
                                contentFocus = scrollbarContentFocus,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
                OverlayHairline()
                DeviceActionRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = scaledTokens.itemGap)
                        .focusRequester(footerFocus)
                        .focusProperties { onExit = { if (requestedFocusDirection == FocusDirection.Right) cancelFocusChange() } }
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = {
                        CompositionLocalProvider(LocalDialogButtonFocus provides footerButtons) { footer() }
                    },
                )
            }
            }
            }
            com.ambient.tv.notifications.AppNotificationHost(dialog = true)
            com.ambient.tv.companion.CompanionDialogInputSurface()
            }
        }
    }
}

/**
 * Phones: shrink the dialog window above the on-screen keyboard instead of leaving the system to
 * pick pan or nothing, so a text field low in a panel stays visible. The panel layout already
 * follows the window height (fill-height panels shrink their list, compact ones re-center).
 */
@Composable
private fun DialogResizesForKeyboard() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        @Suppress("DEPRECATION")
        (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            ?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose { }
    }
}
