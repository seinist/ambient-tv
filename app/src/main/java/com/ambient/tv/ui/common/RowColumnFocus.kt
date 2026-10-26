package com.ambient.tv.ui.common

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.sound.LocalUiSoundPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Up/Down between stacked horizontal rows lands on the adjacent row's visible tile whose center
 * is horizontally closest to the focused one. Compose's geometric search also weighs the
 * focused tile's scale, differing tile widths and rows still gliding into place, so the
 * target could otherwise drift a column or two to either side.
 */
class RowColumnFocus {
    private class Target(val requester: FocusRequester) {
        var coordinates: LayoutCoordinates? = null
        /** The tile plays its own focus sound (MediaTile, TvActionButton). */
        var soundsOnFocus = false
    }

    private val rows = HashMap<Any, LinkedHashMap<Any, Target>>()
    private var focusedRow: Any? = null
    private var focused: Target? = null
    private var lastMoveTarget: Target? = null

    /** Whether the target of the last successful move is silent itself and needs the tick. */
    internal val lastMoveNeedsSound: Boolean get() = lastMoveTarget?.soundsOnFocus != true

    fun move(rowOrder: List<Any>, down: Boolean): Boolean {
        val sourceX = focusedCenterX() ?: return false
        val index = rowOrder.indexOf(focusedRow ?: return false).takeIf { it >= 0 } ?: return false
        val next = rowOrder.getOrNull(if (down) index + 1 else index - 1) ?: return false
        return focusNearest(next, sourceX)
    }

    /** Column of the focused tile when Down leaves the last row, for [focusNearest] after the wrap. */
    fun wrapColumn(rowOrder: List<Any>, down: Boolean): Float? =
        if (down && focusedRow != null && focusedRow == rowOrder.lastOrNull()) focusedCenterX() else null

    // Unclipped center: a partly scrolled-out source still means its full tile.
    private fun focusedCenterX(): Float? = focused?.coordinates?.takeIf { it.isAttached }
        ?.let { it.localToRoot(Offset(it.size.width / 2f, it.size.height / 2f)).x }

    /** Re-focuses the last focused tile when focus re-enters the rows (e.g. back from the sidebar). */
    fun restoreLast(): Boolean {
        val target = focused?.takeIf { it.coordinates?.isAttached == true } ?: return false
        return runCatching { target.requester.requestFocus() }.getOrDefault(false)
    }

    fun focusNearest(rowKey: Any, sourceX: Float): Boolean {
        val target = rows[rowKey]?.values
            ?.mapNotNull { target ->
                val bounds = target.coordinates?.takeIf { it.isAttached }?.boundsInRoot() ?: return@mapNotNull null
                // Tiles clipped away by their row cannot be meant.
                if (bounds.width <= 0f || bounds.height <= 0f) null else target to abs(bounds.center.x - sourceX)
            }
            ?.minByOrNull { it.second }?.first
            ?: return false
        lastMoveTarget = target
        return runCatching { target.requester.requestFocus() }.getOrDefault(false)
    }

    internal fun Modifier.target(rowKey: Any, itemKey: Any, soundsOnFocus: Boolean): Modifier = composed {
        val target = remember(rowKey, itemKey) { Target(FocusRequester()) }
        target.soundsOnFocus = soundsOnFocus
        DisposableEffect(rowKey, itemKey) {
            rows.getOrPut(rowKey) { LinkedHashMap() }[itemKey] = target
            onDispose {
                rows[rowKey]?.let { row -> if (row[itemKey] === target) row.remove(itemKey) }
                if (rows[rowKey]?.isEmpty() == true) rows.remove(rowKey)
                if (focused === target) focused = null
                if (lastMoveTarget === target) lastMoveTarget = null
            }
        }
        // The coordinates stay live and are only resolved on a key press; onGloballyPositioned
        // would dispatch for every placed tile on every frame of the vertical glide.
        focusRequester(target.requester)
            .onPlaced { target.coordinates = it }
            .onFocusChanged {
                if (it.hasFocus) {
                    focusedRow = rowKey
                    focused = target
                }
            }
    }
}

/**
 * Registers a tile (or a container holding its focusable) as a [RowColumnFocus] target.
 * [soundsOnFocus]: the tile plays its own focus sound, so an Up/Down move adds none.
 */
fun Modifier.rowColumnFocusTarget(focus: RowColumnFocus?, rowKey: Any, itemKey: Any, soundsOnFocus: Boolean = false): Modifier =
    if (focus == null) this else with(focus) { target(rowKey, itemKey, soundsOnFocus) }

/**
 * Routes Up/Down inside this container through [focus]; unresolved moves fall through.
 * Down from the last row wraps to the first via [scrollToTop] and keeps the column.
 */
fun Modifier.rowColumnFocusNavigation(
    focus: RowColumnFocus,
    scrollToTop: (suspend () -> Unit)? = null,
    rowOrder: () -> List<Any>,
): Modifier = composed {
    if (LocalHandheldUi.current) return@composed Modifier
    val uiSounds = LocalUiSoundPlayer.current
    val scope = rememberCoroutineScope()
    var wrapJob by remember { mutableStateOf<Job?>(null) }
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val down = when (event.key) {
            Key.DirectionDown -> true
            Key.DirectionUp -> false
            else -> return@onPreviewKeyEvent false
        }
        if (wrapJob?.isActive == true) return@onPreviewKeyEvent true
        val order = rowOrder()
        if (focus.move(order, down)) {
            // Exactly one tick per move: self-sounding tiles already play it on focus.
            if (focus.lastMoveNeedsSound) uiSounds?.playFocus()
            return@onPreviewKeyEvent true
        }
        val column = scrollToTop?.let { focus.wrapColumn(order, down) } ?: return@onPreviewKeyEvent false
        val first = order.firstOrNull() ?: return@onPreviewKeyEvent false
        wrapJob = scope.launch {
            scrollToTop()
            // The first row composes and settles its tiles a few frames after the glide.
            repeat(12) {
                withFrameNanos { }
                if (focus.focusNearest(first, column)) {
                    if (focus.lastMoveNeedsSound) uiSounds?.playFocus()
                    return@launch
                }
            }
        }
        true
    }
}
