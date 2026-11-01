package com.ambient.tv.ui.player

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.DetailArtworkFrame
import com.ambient.tv.ui.common.DetailCreditsSection
import com.ambient.tv.ui.common.DetailEyebrow
import com.ambient.tv.ui.common.DetailGenres
import com.ambient.tv.ui.common.DetailPrimaryMetaRow
import com.ambient.tv.ui.common.DetailSummary
import com.ambient.tv.ui.common.DetailTagline
import com.ambient.tv.ui.common.DetailTitleRow
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.OverlayAccentStrip
import com.ambient.tv.ui.common.OverlayEdgeFades
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.StreamPropertyAdaptiveRows
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.detailEyebrow
import com.ambient.tv.ui.common.detailHasCredits
import com.ambient.tv.ui.common.overlayPanelBorder
import com.ambient.tv.ui.common.overlayPanelBrush
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.domain.model.dedupeCastMembers
import kotlinx.coroutines.launch

/** What a remote key does inside the details dialog. */
internal enum class PlayerDetailKeyAction { SCROLL_UP, SCROLL_DOWN, FOCUS_CLOSE, FOCUS_TEXT, CONSUME, PASS }

/**
 * Key routing of the dialog: the text scrolls with Up/Down; at its end (or with Right) focus
 * moves to the Close button, and Up/Left from the button returns to the text. Left/Right never
 * leave the dialog. Back and media keys pass on (Back closes via the BackHandler).
 */
internal fun playerDetailKeyAction(
    key: Key,
    onCloseButton: Boolean,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): PlayerDetailKeyAction = when (key) {
    Key.DirectionDown, Key.PageDown -> when {
        onCloseButton -> PlayerDetailKeyAction.CONSUME
        canScrollForward -> PlayerDetailKeyAction.SCROLL_DOWN
        else -> PlayerDetailKeyAction.FOCUS_CLOSE
    }
    Key.DirectionUp, Key.PageUp -> when {
        onCloseButton -> PlayerDetailKeyAction.FOCUS_TEXT
        canScrollBackward -> PlayerDetailKeyAction.SCROLL_UP
        else -> PlayerDetailKeyAction.CONSUME
    }
    Key.DirectionRight -> if (onCloseButton) PlayerDetailKeyAction.CONSUME else PlayerDetailKeyAction.FOCUS_CLOSE
    Key.DirectionLeft -> if (onCloseButton) PlayerDetailKeyAction.FOCUS_TEXT else PlayerDetailKeyAction.CONSUME
    // OK on the text does nothing; on the button it activates it.
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (onCloseButton) PlayerDetailKeyAction.PASS else PlayerDetailKeyAction.CONSUME
    else -> PlayerDetailKeyAction.PASS
}

/** Opens the details dialog as if its action-bar button had been pressed, so closing focuses that button. */
internal fun openPlayerDetails(navigation: com.ambient.tv.ui.common.PlayerPanelNavigation, buttonLabel: String) {
    navigation.fromOrigin("bar:$buttonLabel", root = true) { navigation.open(UnifiedPlayerPanel.DETAILS) }
}

/** Closes the dialog; the navigation hands focus back to the Details button. */
internal fun closePlayerDetails(navigation: com.ambient.tv.ui.common.PlayerPanelNavigation) {
    navigation.back(UnifiedPlayerPanel.DETAILS)
}

/** Opt-in action-bar button; only present when the player has details to show. */
@Composable
internal fun PlayerDetailsButton() {
    val chrome = LocalPlayerChromeState.current ?: return
    if (chrome.detail.value == null) return
    val navigation = LocalPlayerPanelNavigation.current
    PlayerIconButton(
        Icons.Outlined.Info,
        stringResource(R.string.player_details),
        // PlayerIconButton records this button as the origin, so Back returns focus to it.
        { navigation?.open(UnifiedPlayerPanel.DETAILS) },
        selected = navigation?.panel == UnifiedPlayerPanel.DETAILS,
        osd = OsdButton.DETAILS,
    )
}

/** Hands the playing title's details to the player chrome; survives renderer switches. */
@Composable
internal fun PublishPlayerDetail(info: PlayerDetailInfo?) {
    val chrome = LocalPlayerChromeState.current ?: return
    androidx.compose.runtime.SideEffect { chrome.detail.value = info }
    androidx.compose.runtime.DisposableEffect(chrome) {
        onDispose { chrome.detail.value = null }
    }
}

/** Whether the details dialog is the open player level (hides the stats overlays). */
@Composable
internal fun playerDetailsOpen(): Boolean =
    LocalPlayerChromeState.current?.panels?.panel == UnifiedPlayerPanel.DETAILS

@Composable
internal fun PlayerDetailDialog(
    info: PlayerDetailInfo?,
    onClose: () -> Unit,
) {
    // A title change keeps the dialog: show the last details until the new ones arrive.
    var shown by remember { mutableStateOf(info) }
    if (info != null) shown = info
    val detail = shown ?: run {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    SleepWarningKeyGate.BlockWhileShown()
    val interactive = LocalPlayerPanelInteractive.current
    androidx.activity.compose.BackHandler(enabled = interactive) { onClose() }
    val motion = LocalPlayerPanelMotion.current
    val handheld = LocalHandheldUi.current
    val tokens = LocalUiTokens.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val textFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    var closeFocused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val step = with(density) { 160.dp.toPx() }

    LaunchedEffect(interactive) {
        if (!interactive) return@LaunchedEffect
        // The level appears with the OSD's hide; claim focus once it is laid out.
        repeat(5) {
            withFrameNanos { }
            if (runCatching { textFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    val keys = Modifier.onPreviewKeyEvent { event ->
        val action = playerDetailKeyAction(event.key, closeFocused, scroll.canScrollBackward, scroll.canScrollForward)
        if (action == PlayerDetailKeyAction.PASS) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) when (action) {
            PlayerDetailKeyAction.SCROLL_DOWN -> scope.launch { scroll.animateScrollBy(step) }
            PlayerDetailKeyAction.SCROLL_UP -> scope.launch { scroll.animateScrollBy(-step) }
            PlayerDetailKeyAction.FOCUS_CLOSE -> runCatching { closeFocus.requestFocus() }
            PlayerDetailKeyAction.FOCUS_TEXT -> runCatching { textFocus.requestFocus() }
            else -> Unit
        }
        true
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            // A tap beside the dialog closes it, like the side panels on a phone.
            .pointerInput(Unit) { detectTapGestures { if (interactive) onClose() } },
    ) {
        val maxCardHeight = maxHeight * if (handheld) 0.86f else 0.84f
        val shape = if (handheld) RoundedCornerShape(topStart = tokens.panelRadius, topEnd = tokens.panelRadius)
            else RoundedCornerShape(tokens.panelRadius)
        Column(
            Modifier
                .align(if (handheld) Alignment.BottomCenter else Alignment.Center)
                .then(if (handheld) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.74f).widthIn(max = 1180.dp))
                .heightIn(max = maxCardHeight)
                .graphicsLayer {
                    val progress = motion.panelProgress.value
                    alpha = progress
                    if (handheld) {
                        translationY = size.height * (1f - progress) * 0.35f
                    } else {
                        val scale = 0.96f + 0.04f * progress
                        scaleX = scale
                        scaleY = scale
                    }
                }
                // Taps on the dialog itself must not close it.
                .pointerInput(Unit) { detectTapGestures { } }
                .background(overlayPanelBrush(), shape)
                .border(1.dp, overlayPanelBorder(), shape)
                .then(if (handheld) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
                .padding(horizontal = deviceDp(32.dp, 16.dp), vertical = deviceDp(26.dp, 14.dp))
                .then(keys),
        ) {
            OverlayAccentStrip()
            Spacer(Modifier.height(tokens.itemGap))
            Box(Modifier.weight(1f, fill = false)) {
                Box(
                    Modifier
                        .focusRequester(textFocus)
                        .focusable()
                        .verticalScroll(scroll),
                ) {
                    if (handheld) {
                        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                            PlayerDetailArtwork(detail, compact = true)
                            PlayerDetailText(detail)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
                            PlayerDetailArtwork(detail, compact = false)
                            Column(Modifier.weight(1f)) { PlayerDetailText(detail) }
                        }
                    }
                }
                OverlayEdgeFades(scroll.canScrollBackward, scroll.canScrollForward)
            }
            Spacer(Modifier.height(tokens.itemGap))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TvActionButton(
                    text = stringResource(R.string.action_close),
                    icon = Icons.Filled.Close,
                    onClick = { if (interactive) onClose() },
                    modifier = Modifier.focusRequester(closeFocus).onFocusChanged { closeFocused = it.isFocused },
                )
            }
        }
    }
}

/** "Name (Role), Name, ..." of up to [max] cast members, or null without cast. */
internal fun playerDetailCastText(item: com.ambient.tv.domain.model.MediaItem?, max: Int = 16): String? =
    item?.cast?.dedupeCastMembers().orEmpty().take(max)
        .map { member -> member.role?.takeIf { it.isNotBlank() }?.let { "${member.name} ($it)" } ?: member.name }
        .joinToString(", ").takeIf { it.isNotBlank() }

/** Width of the artwork column for each shape, like the detail page's poster. */
internal fun playerDetailArtworkWidth(shape: PlayerDetailArtShape, compact: Boolean): Dp = when (shape) {
    PlayerDetailArtShape.POSTER -> if (compact) 96.dp else 190.dp
    PlayerDetailArtShape.SQUARE, PlayerDetailArtShape.LOGO -> if (compact) 104.dp else 210.dp
    PlayerDetailArtShape.WIDE -> if (compact) 184.dp else 300.dp
}

internal fun playerDetailArtworkAspect(shape: PlayerDetailArtShape): Float = when (shape) {
    PlayerDetailArtShape.POSTER -> 2f / 3f
    PlayerDetailArtShape.SQUARE, PlayerDetailArtShape.LOGO -> 1f
    PlayerDetailArtShape.WIDE -> 16f / 9f
}

@Composable
private fun PlayerDetailArtwork(detail: PlayerDetailInfo, compact: Boolean) {
    val url = detail.artworkUrl?.takeIf { it.isNotBlank() } ?: return
    DetailArtworkFrame(
        model = url,
        title = detail.title,
        aspectRatio = playerDetailArtworkAspect(detail.artShape),
        width = playerDetailArtworkWidth(detail.artShape, compact),
        // A channel logo is fitted on a dark plate instead of being cropped.
        contentScale = if (detail.artShape == PlayerDetailArtShape.LOGO) ContentScale.Fit else ContentScale.Crop,
        fallbackModels = detail.artworkFallbackUrls,
        headers = detail.artworkHeaders,
        modifier = if (detail.artShape == PlayerDetailArtShape.LOGO)
            Modifier.background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(14.dp)) else Modifier,
    )
}

@Composable
private fun PlayerDetailText(detail: PlayerDetailInfo) {
    val item = detail.item
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        DetailEyebrow(detail.eyebrow ?: item?.let { detailEyebrow(it) }.orEmpty())
        DetailTitleRow(detail.title, detail.logoUrl, detail.system, maxLogoWidth = deviceDp(440.dp, 240.dp))
        DetailPrimaryMetaRow(
            primaryMeta = detail.meta,
            item = item,
            leading = if (detail.live) ({ LiveBadge() }) else null,
        )
        if (item != null) {
            StreamPropertyAdaptiveRows(item, maxItems = 5, itemsPerRow = 3)
        } else if (detail.chips.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { detail.chips.forEach { Chip(it) } }
        }
        DetailTagline(detail.tagline)
        DetailSummary(detail.overview, maxLines = Int.MAX_VALUE)
        DetailGenres(detail.genres)
        detail.lines.forEach { line ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(line.label), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(line.value, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // Cast as plain text: the dialog has no rows or carousels of the detail page.
        playerDetailCastText(item)?.let { cast ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                Text(stringResource(R.string.detail_cast), style = MaterialTheme.typography.titleMedium)
                Text(cast, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (item != null && detailHasCredits(item)) {
            DetailCreditsSection(item, Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun LiveBadge() {
    Text(
        stringResource(R.string.youtube_live_badge),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier.background(Color(0xFFD32F2F), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
