package com.ambient.tv.ui.common

import androidx.tv.material3.LocalTextStyle
import com.ambient.tv.ui.sound.navigationSoundInput

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.appControlSurface
import com.ambient.tv.ui.theme.interactionColors
import com.ambient.tv.ui.theme.menuFocusMotion
import kotlin.math.roundToInt

data class PlayerDropUpSection(
    val title: String,
    val options: List<Pair<String, String>>,
)

internal fun wrapSelectorIndex(index: Int, count: Int, delta: Int): Int {
    if (count <= 0) return 0
    return (index + delta).mod(count)
}

internal fun nextEnabledSelectorIndex(index: Int, keys: List<String>, disabled: Set<String>, delta: Int): Int? =
    (1..keys.size).asSequence().map { wrapSelectorIndex(index, keys.size, delta * it) }
        .firstOrNull { keys[it] !in disabled }

/** Television and handheld both use the custom anchored popup so the option list
 * matches the closed control width (Material DropdownMenu was display-wide on phones). */
internal fun selectorMenuMatchesTrigger(@Suppress("UNUSED_PARAMETER") handheld: Boolean): Boolean = true

/** Tallest television option list: whichever side of the control has more room. */
internal fun selectMenuMaxHeight(anchorTop: Int, anchorBottom: Int, windowHeight: Int, gap: Int, margin: Int): Int =
    maxOf(windowHeight - margin - anchorBottom - gap, anchorTop - gap - margin).coerceAtLeast(0)

/** Top edge of the television option list: below the control like a desktop select, above it
 * when the list only fits there. */
internal fun selectMenuTop(anchorTop: Int, anchorBottom: Int, windowHeight: Int, popupHeight: Int, gap: Int, margin: Int): Int {
    val below = anchorBottom + gap
    if (below + popupHeight <= windowHeight - margin) return below
    val above = anchorTop - gap - popupHeight
    if (above >= margin) return above
    return (windowHeight - margin - popupHeight).coerceAtLeast(margin)
}

private val SelectMenuGap = 4.dp
private val SelectMenuScreenMargin = 16.dp
private val SelectMenuScrollbarClearance = 12.dp

/** Compact selector. The closed control and option list have the same width on television and
 * handheld: a custom anchored popup below the control, or above it when there is no room.
 * [dropUp] is kept for call-site compatibility; placement follows available space. */
@Composable
fun PlayerDropUpSelector(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    modifier: Modifier = Modifier,
    dropUp: Boolean = true,
    disabledKeys: Set<String> = emptySet(),
    selectedKeys: Set<String> = setOf(selectedKey),
    optionSwatches: Map<String, List<Color>> = emptyMap(),
    closeOnSelection: Boolean? = null,
    /** Leading icon, so a selector sits in a panel list beside [PlayerPanelEntry] rows. */
    icon: ImageVector? = null,
    onSelected: (String) -> Unit,
) {
    PlayerDropUpSelectorImpl(label, value, options, emptyList(), selectedKey, modifier, dropUp, onSelected,
        disabledKeys, selectedKeys, optionSwatches, closeOnSelection, icon)
}

/** Same selector with non-interactive section headings for long technical option lists. */
@Composable
fun GroupedPlayerDropUpSelector(
    label: String,
    value: String,
    sections: List<PlayerDropUpSection>,
    selectedKey: String,
    modifier: Modifier = Modifier,
    dropUp: Boolean = true,
    icon: ImageVector? = null,
    onSelected: (String) -> Unit,
) {
    PlayerDropUpSelectorImpl(
        label = label,
        value = value,
        options = sections.flatMap { it.options },
        sections = sections,
        selectedKey = selectedKey,
        modifier = modifier,
        dropUp = dropUp,
        onSelected = onSelected,
        icon = icon,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlayerDropUpSelectorImpl(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    sections: List<PlayerDropUpSection>,
    selectedKey: String,
    modifier: Modifier,
    @Suppress("UNUSED_PARAMETER") dropUp: Boolean,
    onSelected: (String) -> Unit,
    disabledKeys: Set<String> = emptySet(),
    selectedKeys: Set<String> = setOf(selectedKey),
    optionSwatches: Map<String, List<Color>> = emptyMap(),
    closeOnSelection: Boolean? = null,
    icon: ImageVector? = null,
) {
    val uiSounds = com.ambient.tv.ui.sound.LocalUiSoundPlayer.current
    val panelNavigation = LocalPlayerPanelNavigation.current
    val videoState = LocalVideoPanelState.current
    val selectorId = "${videoState?.section}:$label"
    var localExpanded by remember { mutableStateOf(false) }
    val expanded = videoState?.let { it.expandedSelector == selectorId } ?: localExpanded
    fun setExpanded(value: Boolean) {
        if (videoState == null) localExpanded = value
        else if (value) videoState.expandedSelector = selectorId
        else if (videoState.expandedSelector == selectorId) videoState.expandedSelector = null
    }
    // Single selections close in every panel. VideoPanelState retains the trigger's identity
    // across renderer rebuilds; only explicitly configured multi-select menus stay open.
    val shouldCloseOnSelection = closeOnSelection ?: true
    var focused by remember { mutableStateOf(false) }
    var restoreTriggerFocus by remember { mutableStateOf(false) }
    var triggerWidth by remember { mutableStateOf(0.dp) }
    var menuMaxHeight by remember { mutableStateOf(Dp.Unspecified) }
    val anchor = remember { Ref<LayoutCoordinates>() }
    val density = LocalDensity.current
    val rootView = LocalView.current.rootView
    val matchTrigger = selectorMenuMatchesTrigger(LocalHandheldUi.current)
    // Keyed in the panel navigation, so a rebuilt level can put focus back on this selector.
    val panelFocusKey = "selector:$selectorId"
    val localTriggerFocus = remember { FocusRequester() }
    val triggerFocus = panelNavigation?.focusRequester(panelFocusKey) ?: localTriggerFocus
    val optionKeys = remember(options) { options.map { it.first } }
    val optionFocus = remember(optionKeys) { optionKeys.map { FocusRequester() } }
    val startIndex = options.indexOfFirst { it.first == selectedKey && it.first !in disabledKeys }
        .takeIf { it >= 0 } ?: options.indexOfFirst { it.first !in disabledKeys }.coerceAtLeast(0)
    val enabled = options.any { it.first !in disabledKeys }
    val shape = RoundedCornerShape(8.dp)
    fun close() {
        restoreTriggerFocus = !focused
        setExpanded(false)
    }
    fun open() {
        if (!enabled) return
        if (matchTrigger) menuMaxHeight = selectMenuMaxHeight(anchor.value, rootView, density)
        restoreTriggerFocus = false
        setExpanded(true)
    }
    val openAction = com.ambient.tv.ui.sound.rememberUiSoundAction(enabled = enabled, onClick = ::open)
    val dismissAction = com.ambient.tv.ui.sound.rememberUiSoundAction(kind = com.ambient.tv.ui.sound.UiSound.BACK, onClick = ::close)
    BackHandler(enabled = expanded, onBack = dismissAction)
    LaunchedEffect(videoState, selectorId) {
        if (videoState?.focusedSelector == selectorId && !expanded) {
            retryFocusFor {
                videoState.focusedSelector != selectorId || runCatching { triggerFocus.requestFocus() }.getOrDefault(false)
            }
        }
    }
    LaunchedEffect(expanded) {
        if (!expanded && restoreTriggerFocus) {
            // A popup may already retain/restore the trigger's focus. Once it has done so,
            // a delayed request must not steal focus from the user's next D-pad movement.
            val generation = panelNavigation?.restoreGeneration
            withFrameNanos { }
            if (restoreTriggerFocus && panelNavigation?.restoreGeneration == generation) {
                runCatching { triggerFocus.requestFocus() }
                restoreTriggerFocus = false
            }
        }
    }
    LaunchedEffect(expanded, optionKeys) {
        if (!expanded || matchTrigger || optionFocus.isEmpty()) return@LaunchedEffect
        repeat(12) {
            withFrameNanos { }
            if (runCatching { optionFocus[startIndex].requestFocus() }.getOrDefault(false)) {
                return@LaunchedEffect
            }
        }
    }
    Box(
        modifier
            .onSizeChanged { size -> triggerWidth = with(density) { size.width.toDp() } }
            .onGloballyPositioned {
                anchor.value = it
                if (expanded && matchTrigger) menuMaxHeight = selectMenuMaxHeight(it, rootView, density)
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(triggerFocus)
                .onFocusChanged {
                    focused = it.hasFocus
                    if (it.hasFocus) videoState?.focusedSelector = selectorId
                    if (it.hasFocus) panelNavigation?.noteFocused(panelFocusKey)
                    if (it.hasFocus && !expanded) restoreTriggerFocus = false
                    if (it.isFocused) uiSounds?.playFocus()
                }
                .menuFocusMotion(focused, focusedScale = 1.006f)
                .appControlSurface(focused = focused, enabled = enabled, shape = shape)
                .onPreviewKeyEvent { event ->
                    if (!expanded) return@onPreviewKeyEvent false
                    selectorMenuKey(event.key) {
                        val dismissal = event.key == Key.Back || event.key == Key.Escape || event.key == Key.DirectionLeft
                        if (event.type != if (dismissal) KeyEventType.KeyUp else KeyEventType.KeyDown) return@selectorMenuKey
                        when (event.key) {
                            Key.DirectionDown, Key.DirectionUp ->
                                optionFocus.getOrNull(startIndex)?.let { runCatching { it.requestFocus() } }
                            else -> dismissAction()
                        }
                    }
                }
                .activateOnTvKey(enabled, openAction)
                .clickable(enabled = enabled, role = Role.Button, onClick = openAction)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon?.let {
                Icon(
                    it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (label.isNotBlank()) {
                    MarqueeTitle(
                        label,
                        focused,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.55f),
                        style = LocalTextStyle.current.copy(fontSize = 13.sp),
                    )
                }
                MarqueeTitle(
                    value,
                    focused,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.78f else 0.38f),
                    style = LocalTextStyle.current.copy(fontSize = if (label.isBlank()) 13.sp else 11.sp),
                )
            }
            SelectorColorSwatch(optionSwatches[selectedKey].orEmpty(), enabled)
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.8f else 0.38f),
            )
        }
        if (matchTrigger && expanded && triggerWidth > 0.dp && menuMaxHeight != Dp.Unspecified) {
            SelectorListPopup(triggerWidth, menuMaxHeight, dismissAction, options, sections,
                startIndex, selectedKeys, disabledKeys, optionFocus, optionSwatches,
                shouldCloseOnSelection, ::close, onSelected)
        }
    }
}

private fun selectMenuMaxHeight(anchor: LayoutCoordinates?, rootView: View, density: Density): Dp {
    if (anchor == null || !anchor.isAttached) return Dp.Unspecified
    val top = anchor.positionInWindow().y.roundToInt()
    return with(density) {
        selectMenuMaxHeight(
            top, top + anchor.size.height, rootView.height,
            SelectMenuGap.roundToPx(), SelectMenuScreenMargin.roundToPx(),
        ).toDp()
    }
}

private class SelectMenuPositionProvider(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        selectMenuTop(anchorBounds.top, anchorBounds.bottom, windowSize.height, popupContentSize.height, gap, margin),
    )
}

/** Anchored option list: a flat bordered box as wide as the control, without dialog chrome. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SelectorListPopup(
    width: Dp, maxHeight: Dp, onDismiss: () -> Unit,
    options: List<Pair<String, String>>, sections: List<PlayerDropUpSection>,
    startIndex: Int, selectedKeys: Set<String>, disabledKeys: Set<String>,
    optionFocus: List<FocusRequester>, optionSwatches: Map<String, List<Color>>,
    closeOnSelection: Boolean, close: () -> Unit, onSelected: (String) -> Unit,
) {
    val density = LocalDensity.current
    val gap = with(density) { SelectMenuGap.roundToPx() }
    val margin = with(density) { SelectMenuScreenMargin.roundToPx() }
    // Headings participate in layout but never in option navigation.
    val entries = remember(options, sections) {
        if (sections.isEmpty()) options.map { it.first to it.second }
        else sections.flatMap { section -> listOf(null to section.title) + section.options }
    }
    val optionKeys = options.map { it.first }
    val rowIndices = optionKeys.map { key -> entries.indexOfFirst { it.first == key } }
    // A new popup starts at the stored option before its very first layout.
    val initialIndex = remember(optionKeys) { startIndex }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = rowIndices.getOrElse(initialIndex) { 0 }.coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val barFocus = remember { FocusRequester() }
    val scrollbarContentFocus = remember { FocusRequester() }
    val shape = RoundedCornerShape(8.dp)
    Popup(
        popupPositionProvider = remember(gap, margin) { SelectMenuPositionProvider(gap, margin) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        com.ambient.tv.ui.sound.SilenceSystemMenuSounds()
        com.ambient.tv.ui.display.SuspendUiHdrForPopup()
        CompositionLocalProvider(LocalDensity provides density) {
            LaunchedEffect(optionKeys) {
                scroll.scrollToItem(rowIndices.getOrElse(initialIndex) { 0 }.coerceAtLeast(0))
                repeat(12) {
                    withFrameNanos { }
                    if (runCatching { optionFocus[initialIndex].requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                }
            }
            Box(
                Modifier.navigationSoundInput()
                    .requiredWidth(width)
                    .heightIn(max = maxHeight)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f), shape)
                    .padding(1.dp)
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup(),
            ) {
                LazyColumn(
                    state = scroll,
                    modifier = Modifier.focusRequester(scrollbarContentFocus).focusGroup().fillMaxWidth().padding(end = SelectMenuScrollbarClearance),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    items(entries.size) { rowIndex ->
                        val (key, text) = entries[rowIndex]
                        if (key == null) {
                            Text(text, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 14.dp, top = 9.dp, end = 14.dp, bottom = 4.dp))
                        } else {
                            val index = optionKeys.indexOf(key)
                            PlayerDropUpOptionRow(index, key, text, selectedKeys, disabledKeys,
                                optionKeys, optionFocus, true, sections.isNotEmpty(),
                                optionSwatches[key].orEmpty(), closeOnSelection, close, onSelected,
                                moveFocus = { target ->
                                    scope.launch {
                                        val targetRow = rowIndices[target]
                                        val info = scroll.layoutInfo
                                        val visible = info.visibleItemsInfo.firstOrNull { it.index == targetRow }
                                        if (visible == null) {
                                            // Reveal forward targets at the bottom, rather than
                                            // jumping a whole viewport for a single Down press.
                                            val rowHeight = info.visibleItemsInfo.firstOrNull { it.index == rowIndex }?.size ?: 0
                                            val offset = if (target > index) {
                                                -(info.viewportEndOffset - info.afterContentPadding - rowHeight).coerceAtLeast(0)
                                            } else 0
                                            scroll.scrollToItem(targetRow, offset)
                                        }
                                        repeat(12) {
                                            withFrameNanos { }
                                            if (runCatching { optionFocus[target].requestFocus() }.getOrDefault(false)) return@launch
                                        }
                                    }
                                },
                                focusScrollbar = {
                                    if (scroll.canScrollForward || scroll.canScrollBackward) runCatching { barFocus.requestFocus() }
                                },
                            )
                        }
                    }
                }
                if (scroll.canScrollForward || scroll.canScrollBackward) {
                    Box(Modifier.matchParentSize().padding(top = 6.dp, end = 3.dp, bottom = 6.dp),
                        contentAlignment = Alignment.CenterEnd) {
                        OverlayLazyScrollbar(scroll, Modifier.fillMaxHeight().focusRequester(barFocus), contentFocus = scrollbarContentFocus)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerDropUpOptionRow(
    index: Int,
    key: String,
    text: String,
    selectedKeys: Set<String>,
    disabledKeys: Set<String>,
    optionKeys: List<String>,
    optionFocus: List<FocusRequester>,
    matchTrigger: Boolean,
    indent: Boolean,
    swatchColors: List<Color>,
    closeOnSelection: Boolean,
    close: () -> Unit,
    onSelected: (String) -> Unit,
    moveFocus: ((Int) -> Unit)? = null,
    focusScrollbar: (() -> Unit)? = null,
) {
    val uiSounds = com.ambient.tv.ui.sound.LocalUiSoundPlayer.current
    var optionFocused by remember(key) { mutableStateOf(false) }
    val singleSelection = closeOnSelection || LocalVideoPanelState.current != null
    val selected = key in selectedKeys
    val enabled = key !in disabledKeys
    val shape = if (matchTrigger) RectangleShape else RoundedCornerShape(5.dp)
    // Resolve the semantic colors without a second animation; the menu row itself owns the
    // transparent idle state and animates both focus-in and focus-out.
    val colors = interactionColors(
        optionFocused,
        selected = matchTrigger && selected,
        enabled = enabled,
        animate = false,
    )
    fun select() {
        if (!enabled) return
        if (closeOnSelection) close()
        onSelected(key)
    }
    val selectAction = com.ambient.tv.ui.sound.rememberUiSoundAction(enabled = enabled, onClick = ::select)
    val dismissAction = com.ambient.tv.ui.sound.rememberUiSoundAction(kind = com.ambient.tv.ui.sound.UiSound.BACK, onClick = close)
    val fillTarget = when {
        optionFocused || matchTrigger && selected -> colors.container
        else -> Color.Transparent
    }
    val outlineTarget = if (optionFocused) colors.outline else Color.Transparent
    val animationSpec = com.ambient.tv.ui.theme.focusColorSpec()
    val fill by animateColorAsState(fillTarget, animationSpec, label = "selectorOptionFill")
    val outline by animateColorAsState(outlineTarget, animationSpec, label = "selectorOptionOutline")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .menuFocusMotion(optionFocused, focusedScale = 1.006f)
            .semantics {
                if (singleSelection) this.selected = selected
                else toggleableState = ToggleableState(selected)
            }
            .focusRequester(optionFocus[index])
            .onFocusChanged { optionFocused = it.isFocused || it.hasFocus; if (it.isFocused) uiSounds?.playFocus() }
            .background(fill, shape)
            .border(LocalUiTokens.current.focusBorder, outline, shape)
            .onPreviewKeyEvent { event ->
                selectorMenuKey(event.key) {
                    val dismissal = event.key == Key.Back || event.key == Key.Escape || event.key == Key.DirectionLeft
                    if (event.type != if (dismissal) KeyEventType.KeyUp else KeyEventType.KeyDown) return@selectorMenuKey
                    when (event.key) {
                        Key.DirectionDown ->
                            nextEnabledSelectorIndex(index, optionKeys, disabledKeys, 1)?.let { target ->
                                if (moveFocus != null) moveFocus(target) else runCatching { optionFocus[target].requestFocus() }
                            }
                        Key.DirectionUp ->
                            nextEnabledSelectorIndex(index, optionKeys, disabledKeys, -1)?.let { target ->
                                if (moveFocus != null) moveFocus(target) else runCatching { optionFocus[target].requestFocus() }
                            }
                        Key.DirectionRight -> focusScrollbar?.invoke()
                        else -> dismissAction()
                    }
                }
            }
            .activateOnTvKey(enabled, selectAction)
            .clickable(enabled = enabled, role = if (singleSelection) Role.RadioButton else Role.Checkbox, onClick = selectAction)
            .padding(start = if (indent) 26.dp else 14.dp, end = 14.dp)
            .padding(vertical = if (matchTrigger) 10.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val checkTint = MaterialTheme.colorScheme.primary
        if (selected && !matchTrigger) Icon(Icons.Filled.Check, null, tint = checkTint)
        SelectorColorSwatch(swatchColors, enabled)
        MarqueeTitle(
            text,
            optionFocused,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            style = LocalTextStyle.current.copy(fontSize = 13.sp),
            modifier = Modifier.weight(1f, fill = matchTrigger),
        )
        // Trailing on television, so option text lines up with the control's own label.
        if (selected && matchTrigger) Icon(Icons.Filled.Check, null, tint = checkTint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SelectorColorSwatch(colors: List<Color>, enabled: Boolean) {
    if (colors.isEmpty()) return
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .size(18.dp)
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.28f else 0.12f), shape),
    ) {
        colors.forEach { color ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(color.copy(alpha = if (enabled) 1f else 0.38f)),
            )
        }
    }
}

private inline fun selectorMenuKey(key: Key, handle: () -> Unit): Boolean {
    val menuKey = key == Key.DirectionDown ||
        key == Key.DirectionUp ||
        key == Key.DirectionLeft ||
        key == Key.DirectionRight ||
        key == Key.Back ||
        key == Key.Escape
    if (!menuKey) return false
    handle()
    return true
}
