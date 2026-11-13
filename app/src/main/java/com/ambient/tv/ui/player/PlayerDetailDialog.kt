package com.ambient.tv.ui.player

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.ambient.tv.ui.common.dispatchBackOnRelease
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.OverlayAccentStrip
import com.ambient.tv.ui.common.OverlayEdgeFades
import com.ambient.tv.ui.common.OverlayScrollbar
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.ScrollbarKeyScroller
import com.ambient.tv.ui.common.StreamPropertyAdaptiveRows
import com.ambient.tv.ui.common.SystemBadge
import com.ambient.tv.ui.common.TitleLogo
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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/** What a remote key does inside the details dialog. */
internal enum class PlayerDetailKeyAction { SCROLL_UP, SCROLL_DOWN, FOCUS_CLOSE, FOCUS_TEXT, FOCUS_SCROLLBAR, CONSUME, PASS }

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
    hasScrollbar: Boolean = false,
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
    Key.DirectionRight -> if (onCloseButton) PlayerDetailKeyAction.CONSUME
        else if (hasScrollbar) PlayerDetailKeyAction.FOCUS_SCROLLBAR else PlayerDetailKeyAction.FOCUS_CLOSE
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
    val hidden = osdHiddenButtons()
    val enabled = info != null && osdButtonVisible(OsdButton.DETAILS, info.osdPlayer, hidden)
    androidx.compose.runtime.SideEffect {
        chrome.detail.value = info
        chrome.detailEnabled.value = enabled
    }
    androidx.compose.runtime.DisposableEffect(chrome) {
        onDispose {
            chrome.detail.value = null
            chrome.detailEnabled.value = false
        }
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
    val scrollbarFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    var closeFocused by remember { mutableStateOf(false) }
    var linkFocused by remember { mutableStateOf(false) }
    var scrollbarFocused by remember { mutableStateOf(false) }
    var pressedKey by remember { mutableStateOf<Key?>(null) }
    var pressedAction by remember { mutableStateOf<PlayerDetailKeyAction?>(null) }
    var scrollbarPressedKey by remember { mutableStateOf<Key?>(null) }
    var previousItemHeldKey by remember { mutableStateOf<Key?>(null) }
    val scrolling = remember(scope, scroll) {
        ScrollbarKeyScroller(scope, scroll, { scroll.viewportSize.toFloat() }, { false })
    }
    fun cancelTextGesture() {
        scrolling.stop()
        pressedKey = null
        pressedAction = null
    }
    DisposableEffect(scrolling) {
        onDispose { scrolling.stop() }
    }
    // Only a different playing item resets reading. Enriched titles and replacement artwork
    // belong to the same item and must not jump a reader back to the first paragraph.
    val contentKey = detail.contentKey ?: (detail.osdPlayer to detail.title)
    LaunchedEffect(contentKey) {
        val textScrollKey = pressedKey?.takeIf {
            it == Key.DirectionUp || it == Key.DirectionDown || it == Key.PageUp || it == Key.PageDown
        }
        previousItemHeldKey = textScrollKey ?: scrollbarPressedKey ?: previousItemHeldKey
        cancelTextGesture()
        // A held scrollbar/touch gesture must not prevent a genuinely new item starting at
        // the top. Its old scroll mutation is cancelled along with the text gesture above.
        scroll.scroll(MutatePriority.PreventUserInput) { scrollBy(-scroll.value.toFloat()) }
    }
    // If enrichment makes a long description short while the scrollbar had focus, it
    // disappears. Recover the owned target, without stealing focus from the Close action.
    LaunchedEffect(scroll.maxValue, interactive) {
        if (scroll.maxValue <= 0 && scrollbarFocused) {
            if (interactive && !closeFocused) runCatching { textFocus.requestFocus() }
            scrollbarFocused = false
        }
    }

    LaunchedEffect(interactive) {
        if (!interactive) {
            cancelTextGesture()
            scrollbarPressedKey = null
            previousItemHeldKey = null
            return@LaunchedEffect
        }
        // The level appears with the OSD's hide; claim focus once it is laid out.
        repeat(5) {
            withFrameNanos { }
            if (runCatching { textFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    val keys = Modifier.onPreviewKeyEvent { event ->
        if (!interactive) return@onPreviewKeyEvent true
        if (event.type == KeyEventType.KeyUp && scrollbarPressedKey == event.key) scrollbarPressedKey = null
        // Cancelling the old scroll mutation is not enough: another native repeat could
        // restart it on the new item. That held direction belongs to the previous item until
        // released; Back, media keys and other gestures keep their normal route.
        if (linkFocused && event.key !in setOf(Key.Back, Key.Escape)) {
            return@onPreviewKeyEvent false
        }
        if (previousItemHeldKey == event.key) {
            if (event.type == KeyEventType.KeyUp) {
                previousItemHeldKey = null
                // Let an attached scrollbar finish its own press/release bookkeeping.
                return@onPreviewKeyEvent !(scrollbarFocused && scroll.maxValue > 0)
            }
            return@onPreviewKeyEvent true
        }
        // The interactive scrollbar owns its own Left/Right and Page/Arrow events.
        if (scrollbarFocused && scroll.maxValue > 0) {
            if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                    event.key == Key.PageUp || event.key == Key.PageDown)) scrollbarPressedKey = event.key
            return@onPreviewKeyEvent false
        }
        if (event.type == KeyEventType.KeyUp && pressedKey == event.key) {
            val action = pressedAction
            pressedKey = null
            pressedAction = null
            when (action) {
                PlayerDetailKeyAction.SCROLL_DOWN, PlayerDetailKeyAction.SCROLL_UP -> scrolling.onKeyEvent(event)
                PlayerDetailKeyAction.FOCUS_CLOSE -> runCatching { closeFocus.requestFocus() }
                PlayerDetailKeyAction.FOCUS_TEXT -> runCatching { textFocus.requestFocus() }
                PlayerDetailKeyAction.FOCUS_SCROLLBAR -> runCatching { scrollbarFocus.requestFocus() }
                else -> Unit
            }
            return@onPreviewKeyEvent true
        }
        val action = playerDetailKeyAction(event.key, closeFocused,
            scroll.canScrollBackward, scroll.canScrollForward,
            hasScrollbar = scroll.maxValue > 0 && !handheld)
        if (action == PlayerDetailKeyAction.PASS) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) {
            // A held press stays in its initial region, even after reaching the end. A new
            // deliberate press then reaches Close, rather than a native repeat doing so.
            if (pressedKey == event.key) return@onPreviewKeyEvent true
            cancelTextGesture()
            pressedKey = event.key
            pressedAction = action
            if (action == PlayerDetailKeyAction.SCROLL_DOWN || action == PlayerDetailKeyAction.SCROLL_UP) {
                scrolling.onKeyEvent(event)
            }
        }
        true
    }
    val onTextFocusChanged: (Boolean) -> Unit = { focused -> if (!focused) cancelTextGesture() }
    val onScrollbarFocusChanged: (Boolean) -> Unit = { focused ->
        if (!focused) scrollbarPressedKey = null
        // Disposal reports lost focus too. Retain ownership until the recovery effect above
        // can move it to the text; an ordinary move while the bar exists releases ownership.
        if (focused || scroll.maxValue > 0) scrollbarFocused = focused
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            // A tap beside the dialog closes it, like the side panels on a phone.
            .pointerInput(Unit) { detectTapGestures { if (interactive) onClose() } },
    ) {
        val maxCardHeight = maxHeight * if (handheld) 0.86f else 0.84f
        // Capture the BoxWithConstraints scope before entering ColumnScope (which shadows
        // maxWidth). This also reflects the actual card width, not the entire TV viewport.
        val dialogWidth = if (handheld) maxWidth else minOf(maxWidth * 0.74f, 1180.dp)
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
                .then(keys)
                // Back would otherwise move focus to the player's root and leave the dialog open.
                .dispatchBackOnRelease(),
        ) {
            OverlayAccentStrip()
            Spacer(Modifier.height(tokens.itemGap))
            // Only the description/metadata column scrolls. Artwork stays pinned at the
            // left on TV and landscape; on a narrow phone it stays above the text viewport.
            // The footer remains outside this viewport, so Close is always accessible.
            val sideBySide = playerDetailSideBySide(dialogWidth, handheld)
            Box(Modifier.weight(1f, fill = false)) {
                if (sideBySide) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.Top) {
                        PlayerDetailArtwork(detail, compact = handheld)
                        Box(Modifier.weight(1f)) {
                            PlayerDetailScrollableText(detail, scroll, textFocus, scrollbarFocus,
                                closeFocus, onTextFocusChanged, onScrollbarFocusChanged,
                                onLinkFocusChanged = { linkFocused = it }, onClose = onClose)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        PlayerDetailArtwork(detail, compact = true)
                        Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                            PlayerDetailScrollableText(detail, scroll, textFocus, scrollbarFocus,
                                closeFocus, onTextFocusChanged, onScrollbarFocusChanged,
                                onLinkFocusChanged = { linkFocused = it }, onClose = onClose)
                        }
                    }
                }
            }
            Spacer(Modifier.height(tokens.itemGap))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TvActionButton(
                    text = stringResource(R.string.action_close),
                    icon = Icons.Filled.Close,
                    onClick = { if (interactive) onClose() },
                    modifier = Modifier.focusRequester(closeFocus).onFocusChanged { closeFocused = it.isFocused }
                        .focusProperties {
                            // Directional gestures are routed above, including accelerated
                            // repeats; they must not jump into the player behind the dialog.
                            up = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        },
                )
            }
        }
    }
}

/** Use a horizontal, fixed-artwork layout wherever the dialog has enough room. */
internal fun playerDetailSideBySide(availableWidth: Dp, handheld: Boolean): Boolean =
    !handheld || availableWidth >= 680.dp

/** Scrollable text only; its scrollbar and fades use the same shared panel chrome. */
@Composable
private fun PlayerDetailScrollableText(
    detail: PlayerDetailInfo,
    scroll: ScrollState,
    textFocus: FocusRequester,
    scrollbarFocus: FocusRequester,
    closeFocus: FocusRequester,
    onTextFocusChanged: (Boolean) -> Unit,
    onScrollbarFocusChanged: (Boolean) -> Unit,
    onLinkFocusChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .testTag("player-detail-text")
                .focusRequester(textFocus)
                .onFocusChanged { onTextFocusChanged(it.isFocused) }
                .focusProperties {
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                }
                .focusable()
                .verticalScroll(scroll)
                // A stable gutter prevents line reflow when a scrollbar appears on enrichment.
                .padding(end = 16.dp),
        ) {
            PlayerDetailText(detail, onLinkFocusChanged, onClose)
        }
        OverlayEdgeFades(scroll.canScrollBackward, scroll.canScrollForward)
        if (scroll.maxValue > 0) {
            OverlayScrollbar(
                state = scroll,
                modifier = Modifier.align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 10.dp, horizontal = 4.dp)
                    .focusRequester(scrollbarFocus)
                    .focusProperties { left = textFocus; right = closeFocus }
                    // This observer wraps the scrollbar's internal focus group.
                    .onFocusChanged { onScrollbarFocusChanged(it.hasFocus) },
                contentFocus = textFocus,
                actionsFocus = closeFocus,
            )
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
private fun PlayerDetailText(
    detail: PlayerDetailInfo,
    onLinkFocusChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val item = detail.item
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        DetailEyebrow(detail.eyebrow ?: item?.let { detailEyebrow(it) }.orEmpty())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            detail.system?.let { SystemBadge(it) }
            // OSD details have room for a readable second title line. Keep the normal detail
            // page's shared title component unchanged.
            TitleLogo(
                title = detail.title,
                logoUrl = detail.logoUrl,
                textStyle = MaterialTheme.typography.headlineLarge,
                maxLines = 2,
                maxLogoWidth = deviceDp(440.dp, 240.dp),
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        DetailPrimaryMetaRow(
            primaryMeta = detail.meta,
            item = item,
            leading = if (detail.live) ({ LiveBadge() }) else null,
        )
        DetailTagline(detail.tagline)
        DetailSummary(detail.overview, maxLines = Int.MAX_VALUE)
        DescriptionLinkButtons(detail.youtubeLinks, playerDetailTelegramLinks(detail.overview), onLinkFocusChanged, onClose)
        detail.lines.forEach { line ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(line.label), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(line.value, style = MaterialTheme.typography.bodyMedium)
            }
        }
        DetailGenres(detail.genres)
        // Like SmartTube, lead with the playing content. File, codec and picture properties
        // support that description instead of pushing it below a block of technical badges.
        if (item != null) {
            StreamPropertyAdaptiveRows(item, maxItems = 5, itemsPerRow = 3)
        } else if (detail.chips.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { detail.chips.forEach { Chip(it) } }
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


@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PlayerDetailYouTubeEntryPoint {
    fun engine(): com.ambient.tv.youtube.SmartTubeEngine
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PlayerDetailTelegramEntryPoint {
    fun telegram(): com.ambient.tv.data.telegram.TelegramRepository
}

/**
 * Focusable buttons for the YouTube and Telegram links of a description: each opens its video,
 * channel or post inside the app. Shared by the player's details dialog, the SmartTube detail
 * page and Monogram's post page. [onClose] runs before navigating (closes a dialog).
 */
@Composable
internal fun DescriptionLinkButtons(
    links: List<PlayerDetailYouTubeLink>,
    telegramLinks: List<PlayerDetailTelegramLink> = emptyList(),
    onLinkFocusChanged: (Boolean) -> Unit = {},
    onClose: () -> Unit = {},
    /** The dialog keeps Left/Right on the button; a page lets them move on. */
    lockHorizontal: Boolean = true,
) {
    if (links.isEmpty() && telegramLinks.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PlayerDetailYouTubeEntryPoint::class.java,
        ).engine()
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        links.take(16).forEach { link ->
            TvActionButton(
                text = link.label,
                onClick = {
                    when (link.kind) {
                        PlayerDetailYouTubeLinkKind.VIDEO -> {
                            onClose()
                            com.ambient.tv.launcher.LauncherIntents.post(
                                com.ambient.tv.ui.navigation.Destinations.youtubePlayer(link.value))
                        }
                        PlayerDetailYouTubeLinkKind.CHANNEL_ID -> {
                            onClose()
                            com.ambient.tv.launcher.LauncherIntents.post(
                                com.ambient.tv.ui.navigation.Destinations.youtubeChannel(link.value))
                        }
                        PlayerDetailYouTubeLinkKind.CHANNEL_QUERY -> scope.launch {
                            val channel = runCatching {
                                engine.search(link.value).asSequence().flatMap { it.items.asSequence() }
                                    .firstOrNull { item ->
                                        item.isChannel && (
                                            item.title.equals(link.value, true) ||
                                            item.title.equals("@" + link.value, true) ||
                                            item.subtitle?.contains(link.value, true) == true
                                        )
                                    }
                                    ?: engine.search("@" + link.value).asSequence().flatMap { it.items.asSequence() }
                                        .firstOrNull { it.isChannel }
                            }.getOrNull()
                            val channelId = channel?.let { com.ambient.tv.youtube.youtubeChannelIdOf(it.videoId) }
                                ?: channel?.channelId
                            if (!channelId.isNullOrBlank()) {
                                onClose()
                                com.ambient.tv.launcher.LauncherIntents.post(
                                    com.ambient.tv.ui.navigation.Destinations.youtubeChannel(
                                        channelId, channel?.title, channel?.imageUrl))
                            }
                        }
                    }
                },
                modifier = Modifier
                    .onFocusChanged { onLinkFocusChanged(it.isFocused) }
                    .focusProperties {
                        if (lockHorizontal) {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }
                    },
            )
        }
        // Public names need a signed-in Monogram to resolve; without it they stay text.
        val telegram = remember(context) {
            if (!com.ambient.tv.BuildConfig.MONOGRAM_NATIVE) null else EntryPointAccessors.fromApplication(
                context.applicationContext, PlayerDetailTelegramEntryPoint::class.java).telegram()
        }
        val notFound = stringResource(com.ambient.tv.R.string.monogram_link_not_found)
        telegramLinks.filter { it.chatId != null || telegram != null }.take(8).forEach { link ->
            TvActionButton(
                text = link.label,
                onClick = {
                    fun open(chatId: Long) {
                        onClose()
                        com.ambient.tv.launcher.LauncherIntents.post(
                            if (link.messageId != null) com.ambient.tv.ui.navigation.Destinations.telegramDetail(chatId, link.messageId)
                            else com.ambient.tv.ui.navigation.Destinations.telegramSection("channel:$chatId"))
                    }
                    val known = link.chatId
                    if (known != null) open(known)
                    else if (telegram != null && link.username != null) scope.launch {
                        val channel = telegram.resolveChannel(link.username)
                        if (channel != null) open(channel.chatId)
                        else android.widget.Toast.makeText(context, notFound, android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .onFocusChanged { onLinkFocusChanged(it.isFocused) }
                    .focusProperties {
                        if (lockHorizontal) {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }
                    },
            )
        }
    }
}
