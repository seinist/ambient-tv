package com.ambient.tv.ui.common

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import com.ambient.tv.ui.mobile.LocalHandheldUi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.ui.theme.LocalUiTokens

@Composable
internal fun overlayPanelBrush(): Brush = Brush.verticalGradient(
    listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f),
        MaterialTheme.colorScheme.surface.copy(alpha = 0.995f),
    ),
)

@Composable
internal fun overlayPanelBorder(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)

@Composable
internal fun overlayEdgeFade(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.995f)

@Composable
internal fun OverlayAccentStrip(modifier: Modifier = Modifier) {
    val tokens = LocalUiTokens.current
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(tokens.pillRadius))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

@Composable
internal fun OverlayHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.12f)),
    )
}

private val OverlayScrollbarWidth = 5.dp

private fun DrawScope.drawOverlayScrollbar(scroll: Int, max: Int, topLeft: Offset, barSize: Size, focused: Boolean = false, accent: Color = Color.White) {
    val radius = CornerRadius(barSize.width / 2)
    drawRoundRect(if (focused) accent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.10f), topLeft = topLeft, size = barSize, cornerRadius = radius)
    if (max <= 0) return
    val viewport = barSize.height
    val content = max + viewport
    val minThumb = 28.dp.toPx().coerceAtMost(viewport)
    val thumbHeight = (viewport * viewport / content).coerceIn(minThumb, viewport)
    val travel = (viewport - thumbHeight).coerceAtLeast(0f)
    val thumbY = travel * (scroll.toFloat() / max.toFloat())
    drawRoundRect(
        color = if (focused) accent else Color.White.copy(alpha = 0.42f),
        topLeft = topLeft + Offset(0f, thumbY),
        size = Size(barSize.width, thumbHeight),
        cornerRadius = radius,
    )
}

/** Horizontal entry only: Left returns to content, Right reaches adjacent or registered actions. */
@Composable
private fun InteractiveScrollbar(
    scroll: Int,
    max: Int,
    modifier: Modifier,
    contentFocus: FocusRequester? = null,
    actionsFocus: FocusRequester? = null,
    page: suspend (Int) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var horizontalPress by remember { mutableStateOf<Key?>(null) }
    val scope = rememberCoroutineScope()
    val scrollbarDescription = androidx.compose.ui.res.stringResource(com.ambient.tv.R.string.common_scrollbar)
    var paging by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    val handheld = LocalHandheldUi.current
    val focusManager = LocalFocusManager.current
    val localActions = LocalScrollListActionFocus.current
    val actions = LocalScrollListActions.current
    val view = LocalView.current
    Canvas(modifier.width(OverlayScrollbarWidth)
        .focusProperties {
            onEnter = {
                if (requestedFocusDirection == FocusDirection.Down || requestedFocusDirection == FocusDirection.Up) {
                    if (contentFocus == null || !runCatching {
                            contentFocus.requestFocus(requestedFocusDirection)
                        }.getOrDefault(false)) cancelFocusChange()
                }
            }
        }
        .focusGroup()
        .onFocusChanged {
            focused = it.isFocused
            if (!focused) horizontalPress = null
        }
        .onPreviewKeyEvent { event ->
            // Select rows dismiss on Left-up. Complete this gesture here before returning
            // focus, so its release cannot accidentally close the newly focused option.
            if (event.key == Key.DirectionLeft) {
                if (event.type == KeyEventType.KeyDown) horizontalPress = event.key
                if (event.type == KeyEventType.KeyUp && horizontalPress == event.key) {
                    horizontalPress = null
                    paging?.cancel()
                    if (contentFocus == null || !runCatching {
                            contentFocus.requestFocus(FocusDirection.Left)
                        }.getOrDefault(false)) focusManager.moveFocus(FocusDirection.Left)
                }
                return@onPreviewKeyEvent true
            }
            if (event.key == Key.DirectionRight) {
                // The Right-down that entered the bar belongs to the list. Its release
                // must not immediately skip the bar and continue to the footer.
                if (event.type == KeyEventType.KeyDown) horizontalPress = event.key
                if (event.type == KeyEventType.KeyUp && horizontalPress == event.key) {
                    horizontalPress = null
                    paging?.cancel()
                    // Prefer adjacent controls, then the owning modal footer/page header.
                    // An explicit modal target must never fall through to the page behind it.
                    if (!focusManager.moveFocus(FocusDirection.Right)) {
                        val target = actionsFocus ?: localActions
                        if (target != null) runCatching { target.requestFocus() }
                        else actions?.requestFocus(view)
                    }
                }
                return@onPreviewKeyEvent true
            }
            val direction = when (event.key) {
                Key.DirectionUp, Key.PageUp -> -1
                Key.DirectionDown, Key.PageDown -> 1
                else -> return@onPreviewKeyEvent false
            }
            if (event.type == KeyEventType.KeyDown && paging?.isActive != true) {
                paging = scope.launch { page(direction) }
            }
            true
        }
        .semantics { contentDescription = scrollbarDescription }
        .focusable(enabled = max > 0 && !handheld)
    ) { drawOverlayScrollbar(scroll, max, Offset.Zero, size, focused, accent) }
}

@Composable
internal fun OverlayScrollbar(state: ScrollState, modifier: Modifier = Modifier, contentFocus: FocusRequester? = null, actionsFocus: FocusRequester? = null) {
    InteractiveScrollbar(state.value, state.maxValue, modifier, contentFocus, actionsFocus) { direction ->
        state.animateScrollBy(direction * state.viewportSize.toFloat())
    }
}

/** Scroll offset and scroll range in pixels, estimated from the visible items; null while the list fits. */
private fun LazyListState.estimatedScroll(): Pair<Int, Int>? {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    val total = info.totalItemsCount
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    if (visible.isEmpty() || total <= 0 || (!canScrollForward && !canScrollBackward)) return null
    val first = visible.first()
    val last = visible.last()
    val covered = (last.offset + last.size - first.offset).coerceAtLeast(1)
    val average = covered.toFloat() / visible.size
    val content = average * total
    val max = (content - viewport).coerceAtLeast(1f)
    val scroll = (first.index * average - first.offset).coerceIn(0f, max)
    return scroll.toInt() to max.toInt()
}

@Composable
internal fun OverlayLazyScrollbar(state: LazyListState, modifier: Modifier = Modifier, contentFocus: FocusRequester? = null, actionsFocus: FocusRequester? = null) {
    val (scroll, max) = state.estimatedScroll() ?: return
    InteractiveScrollbar(scroll, max, modifier, contentFocus, actionsFocus) { direction ->
        val info = state.layoutInfo
        state.animateScrollBy(direction * (info.viewportEndOffset - info.viewportStartOffset).toFloat() * if (info.reverseLayout) -1 else 1)
    }
}

/** Scroll offset and scroll range in pixels, estimated from the visible lines; null while the grid fits. */
private fun LazyGridState.estimatedScroll(): Pair<Int, Int>? {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    val total = info.totalItemsCount
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    if (visible.isEmpty() || total <= 0 || (!canScrollForward && !canScrollBackward)) return null
    val first = visible.first()
    val last = visible.last()
    val columns = visible.count { it.row == first.row }.coerceAtLeast(1)
    val lines = (last.row - first.row + 1).coerceAtLeast(1)
    val bottom = visible.maxOf { it.offset.y + it.size.height }
    val average = (bottom - first.offset.y).coerceAtLeast(1).toFloat() / lines
    // Lines after the last visible one, assuming full lines from there on.
    val totalLines = last.row + 1 + (total - 1 - last.index + columns - 1) / columns
    val max = (average * totalLines - viewport).coerceAtLeast(1f)
    val scroll = (first.row * average - first.offset.y).coerceIn(0f, max)
    return scroll.toInt() to max.toInt()
}

/** Consumed by the TV list shells so the bar is a real sibling focus target. */
internal data class LazyScrollbarSpec(val gutter: Dp?, val enabled: Boolean) : Modifier.Element

internal fun Modifier.lazyScrollbarSpec(): LazyScrollbarSpec? =
    foldIn<LazyScrollbarSpec?>(null) { found, element -> (element as? LazyScrollbarSpec) ?: found }

internal fun Modifier.overlayLazyScrollbar(state: LazyListState, gutter: Dp? = null, enabled: Boolean = true): Modifier =
    then(LazyScrollbarSpec(gutter, enabled))

internal fun Modifier.overlayLazyGridScrollbar(state: LazyGridState, gutter: Dp? = null, enabled: Boolean = true): Modifier =
    then(LazyScrollbarSpec(gutter, enabled))

@Composable
internal fun OverlayLazyGridScrollbar(state: LazyGridState, modifier: Modifier = Modifier, contentFocus: FocusRequester? = null, actionsFocus: FocusRequester? = null) {
    val (scroll, max) = state.estimatedScroll() ?: return
    InteractiveScrollbar(scroll, max, modifier, contentFocus, actionsFocus) { direction ->
        val info = state.layoutInfo
        state.animateScrollBy(direction * (info.viewportEndOffset - info.viewportStartOffset).toFloat() * if (info.reverseLayout) -1 else 1)
    }
}

/** Browse content may exit Right only into its adjacent scrollbar, never a distant header. */
internal val LocalLazyScrollbarRightTarget = staticCompositionLocalOf<FocusRequester?> { null }

/** Match the existing scrollbar gutter without contributing to the list's measured size. */
@Composable
internal fun LazyScrollbarFrame(
    modifier: Modifier,
    overflow: Boolean,
    scrollbar: @Composable (Modifier, FocusRequester) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val spec = modifier.lazyScrollbarSpec()
    if (spec == null) {
        content(modifier)
        return
    }
    val contentFocus = remember { FocusRequester() }
    val scrollbarFocus = remember { FocusRequester() }
    val visible = spec.enabled && overflow
    Box(modifier, propagateMinConstraints = true) {
        CompositionLocalProvider(LocalLazyScrollbarRightTarget provides scrollbarFocus.takeIf { visible }) {
            content(Modifier.focusRequester(contentFocus).focusGroup().padding(end = if (visible && spec.gutter == null) 16.dp else 0.dp))
        }
        if (visible) {
            Box(Modifier.matchParentSize().padding(
                end = spec.gutter?.let { ((it - OverlayScrollbarWidth) / 2).coerceAtLeast(0.dp) } ?: 0.dp,
                top = if (spec.gutter != null) 12.dp else 0.dp,
                bottom = if (spec.gutter != null) 12.dp else 0.dp,
            ), contentAlignment = Alignment.CenterEnd) {
                scrollbar(Modifier.fillMaxHeight().focusRequester(scrollbarFocus), contentFocus)
            }
        }
    }
}

@Composable
internal fun BoxScope.OverlayEdgeFades(canScrollBackward: Boolean, canScrollForward: Boolean) {
    val edgeFade = overlayEdgeFade()
    if (canScrollBackward) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(22.dp)
                .background(Brush.verticalGradient(listOf(edgeFade, Color.Transparent))),
        )
    }
    if (canScrollForward) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(22.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, edgeFade))),
        )
    }
}
