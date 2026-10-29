package com.ambient.tv.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.sound.LocalUiSoundPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Up/Down between stacked horizontal rows, Plex style: every row remembers the tile it was
 * left on and is re-entered there; a row never entered starts at its first tile. Geometry
 * plays no part, so differing tile widths, scale animations or a row still gliding into
 * place cannot shift the target.
 */
class RowColumnFocus(initialMemory: Map<String, String> = emptyMap()) {
    private class Target(val requester: FocusRequester) {
        var coordinates: LayoutCoordinates? = null
        /** The tile plays its own focus sound (MediaTile, TvActionButton). */
        var soundsOnFocus = false
    }

    /** A row able to scroll its own list, so an entry tile not composed yet can be reached. */
    private class RowHandle(
        val itemKeys: () -> List<Any>,
        val state: LazyListState,
        val defaultItemKey: () -> Any?,
    )

    private val rows = HashMap<Any, LinkedHashMap<Any, Target>>()
    private val handles = HashMap<Any, RowHandle>()
    private val memory = HashMap<Any, Any>(initialMemory)
    private var focusedRow: Any? = null
    private var focused: Target? = null
    private var lastMoveTarget: Target? = null

    /** Whether the target of the last successful move is silent itself and needs the tick. */
    internal val lastMoveNeedsSound: Boolean get() = lastMoveTarget?.soundsOnFocus != true

    /** Tile last focused in [rowKey], for a recreated row's initial scroll position. */
    fun rememberedItem(rowKey: Any): Any? = memory[rowKey]

    /** Next entry of [rowKey] starts at its first tile. */
    fun forget(rowKey: Any) { memory.remove(rowKey) }

    internal fun nextRow(rowOrder: List<Any>, down: Boolean): Any? {
        val index = rowOrder.indexOf(focusedRow ?: return null).takeIf { it >= 0 } ?: return null
        return rowOrder.getOrNull(if (down) index + 1 else index - 1)
    }

    internal fun isLastRow(rowOrder: List<Any>): Boolean = focusedRow != null && focusedRow == rowOrder.lastOrNull()

    internal fun isFirstRow(rowOrder: List<Any>): Boolean = focusedRow != null && focusedRow == rowOrder.firstOrNull()

    /** Remembered tile, the row's suggested first entry, or its first tile. */
    private fun entry(rowKey: Any): Pair<Any, Int>? {
        val handle = handles[rowKey]
        val keys = handle?.itemKeys?.invoke() ?: rows[rowKey]?.keys?.toList() ?: return null
        val index = rowColumnEntryIndex(keys, memory[rowKey], handle?.defaultItemKey?.invoke()) ?: return null
        return keys[index] to index
    }

    /** Focuses [rowKey]'s entry tile if it is composed. */
    internal fun focusEntry(rowKey: Any): Boolean {
        val (itemKey, _) = entry(rowKey) ?: return false
        val target = rows[rowKey]?.get(itemKey)?.takeIf { it.coordinates?.isAttached == true } ?: return false
        lastMoveTarget = target
        return runCatching { target.requester.requestFocus() }.getOrDefault(false)
    }

    internal fun canScrollToEntry(rowKey: Any): Boolean = handles[rowKey] != null && entry(rowKey) != null

    internal suspend fun scrollToEntry(rowKey: Any) {
        val handle = handles[rowKey] ?: return
        val (_, index) = entry(rowKey) ?: return
        // One tile of context to the left, as ContentRow's own restore does.
        handle.state.scrollToItem((index - 1).coerceAtLeast(0))
    }

    /** Re-focuses the last focused tile when focus re-enters the rows (e.g. back from the sidebar). */
    fun restoreLast(): Boolean {
        val target = focused?.takeIf { it.coordinates?.isAttached == true } ?: return false
        return runCatching { target.requester.requestFocus() }.getOrDefault(false)
    }

    internal fun registerRow(
        rowKey: Any,
        state: LazyListState,
        itemKeys: () -> List<Any>,
        defaultItemKey: () -> Any?,
    ): () -> Unit {
        val handle = RowHandle(itemKeys, state, defaultItemKey)
        handles[rowKey] = handle
        return { if (handles[rowKey] === handle) handles.remove(rowKey) }
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
                    memory[rowKey] = itemKey
                }
            }
    }

    companion object {
        /** Keeps every row's remembered tile across Detail/Player; String keys are all screens use. */
        val Saver: Saver<RowColumnFocus, HashMap<String, String>> = Saver(
            save = { focus ->
                HashMap<String, String>().apply {
                    focus.memory.forEach { (row, item) -> if (row is String && item is String) put(row, item) }
                }
            },
            restore = { RowColumnFocus(it) },
        )
    }
}

@Composable
fun rememberRowColumnFocus(): RowColumnFocus = rememberSaveable(saver = RowColumnFocus.Saver) { RowColumnFocus() }

/** Scrolls to a remembered tile, or [defaultItemKey] on first entry, even before it is composed. */
@Composable
fun RegisterRowColumnFocus(
    focus: RowColumnFocus?,
    rowKey: Any,
    state: LazyListState,
    itemKeys: List<Any>,
    defaultItemKey: Any? = null,
) {
    if (focus == null) return
    val keys by rememberUpdatedState(itemKeys)
    val defaultKey by rememberUpdatedState(defaultItemKey)
    DisposableEffect(focus, rowKey, state) {
        val unregister = focus.registerRow(rowKey, state, { keys }, { defaultKey })
        onDispose { unregister() }
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
 * Down from the last row wraps to the first via [scrollToTop]; Up from the first row reaches a
 * registered page header or whatever lies above the rows, else wraps to the last via [scrollToBottom]. The
 * list's own wrap would land on whichever tile geometry picks and the row would keep it.
 *
 * [scrollToRow] brings a row into the vertical list before its tiles are focused. A row that is
 * only prefetched (taller than the viewport, so nothing of it is placed) has no focus target yet;
 * scrolling the horizontal row alone would swallow Down.
 */
fun Modifier.rowColumnFocusNavigation(
    focus: RowColumnFocus,
    scrollToTop: (suspend () -> Unit)? = null,
    scrollToBottom: (suspend () -> Unit)? = null,
    scrollToRow: (suspend (Any) -> Unit)? = null,
    rowOrder: () -> List<Any>,
): Modifier = composed {
    if (LocalHandheldUi.current) return@composed Modifier
    val uiSounds = LocalUiSoundPlayer.current
    val scrollListActions = LocalScrollListActions.current
    val actionFocus = LocalScrollListActionFocus.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var moveJob by remember { mutableStateOf<Job?>(null) }
    // The target row composes and settles its tiles a few frames after a scroll.
    suspend fun focusSettled(rowKey: Any) {
        repeat(12) {
            withFrameNanos { }
            if (focus.focusEntry(rowKey)) {
                if (focus.lastMoveNeedsSound) uiSounds?.playFocus()
                return
            }
        }
    }
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val down = when (event.key) {
            Key.DirectionDown -> true
            Key.DirectionUp -> false
            else -> return@onPreviewKeyEvent false
        }
        if (moveJob?.isActive == true) return@onPreviewKeyEvent true
        val order = rowOrder()
        val next = focus.nextRow(order, down)
        if (next != null) {
            if (focus.focusEntry(next)) {
                // Exactly one tick per move: self-sounding tiles already play it on focus.
                if (focus.lastMoveNeedsSound) uiSounds?.playFocus()
                return@onPreviewKeyEvent true
            }
            if (scrollToRow == null && !focus.canScrollToEntry(next)) return@onPreviewKeyEvent false
            val rowKey = next
            moveJob = scope.launch {
                scrollToRow?.invoke(rowKey)
                // The row's tiles compose after the vertical scroll. Wait for them before
                // scrolling sideways to the remembered one.
                var waited = 0
                while (waited < 12 && !focus.canScrollToEntry(rowKey)) {
                    withFrameNanos { }
                    waited++
                }
                if (focus.canScrollToEntry(rowKey)) focus.scrollToEntry(rowKey)
                focusSettled(rowKey)
            }
            return@onPreviewKeyEvent true
        }
        if (!down && scrollToBottom != null && focus.isFirstRow(order)) {
            val exited = if (actionFocus != null) runCatching { actionFocus.requestFocus() }.getOrDefault(false)
                else scrollListActions?.requestUpFocus(view) == true
            // Anything focusable above the rows (the app home's update card) comes first; only
            // with nothing there does Up wrap to the last row.
            if (exited || runCatching { focusManager.moveFocus(FocusDirection.Up) }.getOrDefault(false)) {
                uiSounds?.playFocus()
                return@onPreviewKeyEvent true
            }
            val last = order.lastOrNull() ?: return@onPreviewKeyEvent false
            moveJob = scope.launch {
                scrollToBottom()
                focus.scrollToEntry(last)
                focusSettled(last)
            }
            return@onPreviewKeyEvent true
        }
        if (scrollToTop == null || !down || !focus.isLastRow(order)) return@onPreviewKeyEvent false
        val first = order.firstOrNull() ?: return@onPreviewKeyEvent false
        moveJob = scope.launch {
            scrollToTop()
            focus.scrollToEntry(first)
            focusSettled(first)
        }
        true
    }
}
