package com.ambient.tv.ui.common

import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.navigation.SidebarContentEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class BrowseFocusKey(val row: Any?, val item: Any, val content: Boolean)

internal data class BrowseFocusSlot(val key: BrowseFocusKey, val index: Int)

/** A removed selection stays in its row when possible, otherwise at the top of the content. */
internal fun missingBrowseFocusFallback(lost: BrowseFocusKey, slots: List<BrowseFocusSlot>): BrowseFocusKey? {
    val available = slots.map { it.key }.filter { it.content }
    return available.firstOrNull { it.row == lost.row } ?: available.firstOrNull()
}

/** One destination's return address; neither Android saved state nor preferences retain its nodes. */
internal class BrowseFocusReturnState(private val scope: CoroutineScope, val view: View) {
    internal class Target(
        val key: BrowseFocusKey,
        val requester: FocusRequester,
        val bringIntoView: BringIntoViewRequester,
        val pinnable: PinnableContainer?,
    ) {
        var coordinates: LayoutCoordinates? = null
        var hasFocus = false
        val visible: Boolean get() = coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.let {
            it.width > 0f && it.height > 0f
        } == true
    }

    private class Collection(
        val slots: () -> List<BrowseFocusSlot>,
        val visible: (Int) -> Boolean,
        val scroll: suspend (Int) -> Unit,
        val allowRecovery: () -> Boolean,
    )
    private val targets = linkedMapOf<Target, Unit>()
    private val collections = linkedMapOf<Any, Collection>()
    private var last: Target? = null
    private var lastKey: BrowseFocusKey? = null
    private var pin: PinnableContainer.PinnedHandle? = null
    private var preparation: Job? = null
    private var recovery: Job? = null
    private var recoveryGeneration = 0L
    private var prepared: BrowseFocusKey? = null
    private var contentIdentity: Any? = null
    private var initialTargetChosen = false
    var managesInitialFocus: Boolean = false
        private set
    private var fallbackOwner: Any? = null
    private var fallback: (() -> Boolean?)? = null
    private var active = true

    fun register(target: Target) { targets[target] = Unit }
    fun unregister(target: Target) {
        if (target.hasFocus && last === target && target.key.content) recoverMissingContent(target.key)
        targets.remove(target)
        if (last === target) {
            last = null
            releasePin()
        }
    }

    fun focusChanged(target: Target, hasFocus: Boolean) {
        if (!hasFocus && target.hasFocus && last === target && target.key.content) recoverMissingContent(target.key)
        target.hasFocus = hasFocus
        // A shared MediaTile may also have a precise row/item modifier supplied by its
        // caller (for example Detail). Prefer that address regardless of callback order.
        val selected = targets.keys.filter { it.hasFocus }.maxByOrNull { if (it.key.row != null) 1 else 0 }
        if (selected != null) focused(selected)
    }

    private fun focused(target: Target) {
        if (!active || target !in targets) return
        // When a focused lazy tile disappears, Compose can temporarily move focus even to
        // a category chip. Do not cancel pending recovery on that automatic move: the next
        // frame verifies whether the old item still exists. Normal user navigation keeps
        // its destination because recovery then exits without changing focus.
        if (recovery?.isActive != true) cancelRecovery()
        if (last !== target) {
            releasePin()
            pin = target.pinnable?.pin()
        }
        last = target
        lastKey = target.key
        preparation?.cancel()
        preparation = null
        prepared = null
    }

    fun contentChanged(identity: Any, restoreInitialTarget: Boolean) {
        if (contentIdentity == identity) return
        cancelRecovery()
        contentIdentity = identity
        if (restoreInitialTarget) initialTargetChosen = false
        if (lastKey?.content == true) forget()
    }

    fun initialTarget(key: BrowseFocusKey?) {
        managesInitialFocus = true
        if (initialTargetChosen || key == null) return
        initialTargetChosen = true
        if (lastKey == null) lastKey = key
    }

    /**
     * While true, entering the page waits (requestFocus answers null, so the Sidebar and the
     * return-from-player handoff retry): the item to return to is still being loaded.
     */
    var holdEntry: Boolean = false

    fun forget() {
        cancelRecovery()
        last = null
        lastKey = null
        releasePin()
        preparation?.cancel()
        preparation = null
        prepared = null
    }

    private fun releasePin() { pin?.release(); pin = null }

    private fun cancelRecovery() {
        recoveryGeneration++
        recovery?.cancel()
        recovery = null
    }

    /** Only a content tile losing its actual focus can trigger recovery. Leaving for controls,
     * scrolling a still-present item out of composition, and changing pages must not steal focus. */
    private fun recoverMissingContent(lost: BrowseFocusKey) {
        cancelRecovery()
        if (!active) return
        val generation = recoveryGeneration
        val identity = contentIdentity
        recovery = scope.launch {
            // Let the new lazy item registry and focus tree settle before inspecting survivors.
            withFrameNanos { }
            val sources = collections.values.toList()
            if (!active || generation != recoveryGeneration || identity != contentIdentity ||
                NavReselect.restoreTop.value) return@launch
            // A page may register many independent Rows (Search) or an outer list plus
            // nested Rows (Home/SmartTube). Match the entire visible registry first.
            val available = sources.flatMap { it.slots() }
            if (available.any { it.key == lost } || sources.any { !it.allowRecovery() }) return@launch
            val destination = missingBrowseFocusFallback(lost, available) ?: return@launch
            repeat(12) {
                if (!active || generation != recoveryGeneration || identity != contentIdentity ||
                    NavReselect.restoreTop.value || collections.values.any { !it.allowRecovery() } ||
                    collections.values.any { collection -> collection.slots().any { slot -> slot.key == lost } }
                ) return@launch
                // Nested horizontal rows need their outer row and inner tile both composed.
                try {
                    collections.values.toList().forEach { collection ->
                        collection.slots().firstOrNull { it.key == destination }?.let { slot ->
                            if (!collection.visible(slot.index)) collection.scroll(slot.index)
                        }
                    }
                    withFrameNanos { }
                    val target = targets.keys.firstOrNull { it.key == destination && it.visible }
                    if (target != null && runCatching { target.requester.requestFocus() }.getOrDefault(false)) return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalStateException) {
                    return@launch // The list was replaced while focus was being reconciled.
                }
            }
        }
    }

    fun registerCollection(owner: Any, slots: () -> List<BrowseFocusSlot>, visible: (Int) -> Boolean,
                           scroll: suspend (Int) -> Unit, allowRecovery: () -> Boolean) {
        collections[owner] = Collection(slots, visible, scroll, allowRecovery)
    }
    fun unregisterCollection(owner: Any) { collections.remove(owner) }
    fun setFallback(owner: Any, action: () -> Boolean?) { fallbackOwner = owner; fallback = action }
    fun clearFallback(owner: Any) { if (fallbackOwner === owner) { fallbackOwner = null; fallback = null } }

    /** null means the target is being laid out. Only this synchronous call may transfer focus. */
    fun requestFocus(): Boolean? {
        if (!active) return false
        if (holdEntry) return null
        if (NavReselect.restoreTop.value && lastKey != null) forget()
        if (preparation?.isActive == true) return null

        if (lastKey == null) {
            fallback?.let { action ->
                val result = action()
                if (result != false) return result
            }
        }
        val remembered = lastKey
        val collection = remembered?.takeIf { it.content }?.let { key ->
            collections.values.firstOrNull { source -> source.slots().any { it.key == key } }
        }
        val existing = (last?.takeIf { it in targets }
            ?: targets.keys.lastOrNull { it.key == remembered })?.takeIf {
            !it.key.content || collections.isEmpty() || collection != null
        }
        val initial = collections.values.firstNotNullOfOrNull { it.slots().firstOrNull() }
        val wanted = when {
            existing != null -> existing.key
            collection != null -> remembered
            initial != null -> initial.key
            else -> targets.keys.firstOrNull { it.key.content }?.key ?: targets.keys.firstOrNull()?.key
        } ?: return false
        val target = existing?.takeIf { it.key == wanted } ?: targets.keys.firstOrNull { it.key == wanted }
        val laidOut = collections.values.all { source ->
            source.slots().firstOrNull { it.key == wanted }?.let { source.visible(it.index) } ?: true
        }
        if (target?.visible == true && laidOut &&
            runCatching { target.requester.requestFocus() }.getOrDefault(false)
        ) return true
        if (prepared == wanted) return false
        if (target == null && collections.values.none { source -> source.slots().any { it.key == wanted } }) return false
        prepared = wanted
        preparation = scope.launch {
            try {
                // An outer row must be composed before its inner horizontal list can expose
                // the item's index. Re-read the registry after each layout, from outside in.
                val scrolled = mutableSetOf<Collection>()
                while (true) {
                    val source = collections.values.firstOrNull { source ->
                        source !in scrolled && source.slots().any { it.key == wanted }
                    } ?: break
                    scrolled += source
                    val index = source.slots().firstOrNull { it.key == wanted }?.index ?: continue
                    source.scroll(index)
                    withFrameNanos { }
                }
                val placed = targets.keys.firstOrNull { it.key == wanted }
                if (placed != null) placed.bringIntoView.bringIntoView()
                withFrameNanos { }
                // Do not request focus here: the page/Sidebar may have changed while scrolling.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalStateException) {
                // A removed lazy list/target is resolved by the next synchronous entry attempt.
            }
        }
        return null
    }

    fun dispose() { active = false; forget(); targets.clear(); collections.clear() }
}

internal val LocalBrowseFocusReturnState = compositionLocalOf<BrowseFocusReturnState?> { null }
private val LocalBrowseFocusRow = compositionLocalOf<Any?> { null }

@Composable
internal fun BrowseFocusReturnScope(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val handheld = LocalHandheldUi.current
    val owner = remember(scope, view, handheld) { BrowseFocusReturnState(scope, view) }
    DisposableEffect(owner, handheld) {
        if (!handheld) SidebarContentEntry.register(owner, owner::requestFocus)
        onDispose { SidebarContentEntry.unregister(owner); owner.dispose() }
    }
    val reselectStart = remember { NavReselect.signal.value }
    LaunchedEffect(owner) { NavReselect.signal.collect { if (it != reselectStart) owner.forget() } }
    CompositionLocalProvider(LocalBrowseFocusReturnState provides owner.takeUnless { handheld }) { content() }
}

/** Sort/filter/view changes invalidate content selection; an originating control keeps its address. */
@Composable
internal fun BrowseFocusContentIdentity(identity: Any, restoreInitialTarget: Boolean = false) {
    val owner = LocalBrowseFocusReturnState.current
    SideEffect { owner?.contentChanged(identity, restoreInitialTarget) }
}

/** Existing return-from-detail state is session-only; on a cold start its target is the first item. */
@Composable
internal fun BrowseFocusInitialTarget(key: Any?, row: Any? = null) {
    val owner = LocalBrowseFocusReturnState.current
    SideEffect { owner?.initialTarget(key?.let { BrowseFocusKey(row, it, true) }) }
}

/** Holds page entry while [hold], e.g. until a post played after Next has been loaded. */
@Composable
internal fun BrowseFocusHoldEntry(hold: Boolean) {
    val owner = LocalBrowseFocusReturnState.current ?: return
    SideEffect { owner.holdEntry = hold }
    DisposableEffect(owner) { onDispose { owner.holdEntry = false } }
}

@Composable
internal fun BrowseFocusRow(key: Any, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBrowseFocusRow provides key, content = content)
}

@Composable
internal fun RegisterBrowseFocusFallback(action: () -> Boolean?) {
    val owner = LocalBrowseFocusReturnState.current ?: return
    val current = rememberUpdatedState(action)
    val token = remember { Any() }
    DisposableEffect(owner, token) {
        owner.setFallback(token) { current.value() }
        onDispose { owner.clearFallback(token) }
    }
}

/** Registers the actual tile, or an already-restorable header/chip group, without replacing its keys. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.browseFocusTarget(key: Any, content: Boolean = true, rowKey: Any? = null): Modifier = composed {
    val owner = LocalBrowseFocusReturnState.current
    val view = LocalView.current
    if (owner == null || owner.view !== view) return@composed this
    val row = if (content) rowKey ?: LocalBrowseFocusRow.current else null
    val pinnable = LocalPinnableContainer.current
    val target = remember(owner, row, key, content, pinnable) {
        BrowseFocusReturnState.Target(BrowseFocusKey(row, key, content), FocusRequester(), BringIntoViewRequester(), pinnable)
    }
    DisposableEffect(owner, target) {
        owner.register(target)
        onDispose { owner.unregister(target) }
    }
    this.focusRequester(target.requester).bringIntoViewRequester(target.bringIntoView)
        .onPlaced { target.coordinates = it }
        .onFocusChanged { owner.focusChanged(target, it.hasFocus) }
}

@Composable
internal fun RegisterBrowseFocusGrid(state: LazyGridState, keys: List<Any>) {
    val row = LocalBrowseFocusRow.current
    RegisterBrowseFocusCollection(state, remember(keys, row) { keys.mapIndexed { index, item ->
        BrowseFocusSlot(BrowseFocusKey(row, item, true), index)
    } }, visible = { index -> state.layoutInfo.visibleItemsInfo.any { it.index == index } }) { state.scrollToItem(it) }
}

@Composable
internal fun RegisterBrowseFocusList(state: LazyListState, keys: List<Any>, rowKey: Any? = null) {
    val row = rowKey ?: LocalBrowseFocusRow.current
    RegisterBrowseFocusCollection(state, remember(keys, row) { keys.mapIndexed { index, item ->
        BrowseFocusSlot(BrowseFocusKey(row, item, true), index)
    } }, visible = { index -> state.layoutInfo.visibleItemsInfo.any { it.index == index } }) { state.scrollToItem(it) }
}

/** Outer row indices also resolve items whose inner LazyRow is not composed yet. */
@Composable
internal fun RegisterBrowseFocusRows(state: LazyListState, rows: List<Pair<Any, List<Any>>>) {
    RegisterBrowseFocusCollection(state, remember(rows) { rows.flatMapIndexed { index, (row, items) ->
        items.map { BrowseFocusSlot(BrowseFocusKey(row, it, true), index) }
    } }, visible = { index -> state.layoutInfo.visibleItemsInfo.any { it.index == index } }) { state.scrollToItem(it) }
}

/** A section header occupies one lazy item, but is not a focus destination. */
private fun sectionSlots(rows: List<Pair<Any, List<Any>>>): List<BrowseFocusSlot> = buildList {
    var index = 0
    rows.forEach { (row, items) ->
        index++
        items.forEach { add(BrowseFocusSlot(BrowseFocusKey(row, it, true), index++)) }
    }
}

@Composable
internal fun RegisterBrowseFocusSections(state: LazyListState, rows: List<Pair<Any, List<Any>>>) {
    RegisterBrowseFocusCollection(state, remember(rows) { sectionSlots(rows) },
        visible = { index -> state.layoutInfo.visibleItemsInfo.any { it.index == index } }) { state.scrollToItem(it) }
}

@Composable
internal fun RegisterBrowseFocusSections(state: LazyGridState, rows: List<Pair<Any, List<Any>>>) {
    RegisterBrowseFocusCollection(state, remember(rows) { sectionSlots(rows) },
        visible = { index -> state.layoutInfo.visibleItemsInfo.any { it.index == index } }) { state.scrollToItem(it) }
}

/** Physical indices for lists containing non-focusable header/loading slots. */
@Composable
internal fun RegisterBrowseFocusSlots(state: LazyListState, slots: List<BrowseFocusSlot>) {
    RegisterBrowseFocusCollection(state, slots,
        visible = { index -> state.layoutInfo.visibleItemsInfo.any { it.index == index } }) { state.scrollToItem(it) }
}

@Composable
internal fun RegisterBrowseFocusSlots(state: LazyGridState, slots: List<BrowseFocusSlot>) {
    RegisterBrowseFocusCollection(state, slots,
        visible = { index -> state.layoutInfo.visibleItemsInfo.any { it.index == index } }) { state.scrollToItem(it) }
}

@Composable
private fun RegisterBrowseFocusCollection(
    token: Any,
    slots: List<BrowseFocusSlot>,
    visible: (Int) -> Boolean,
    scroll: suspend (Int) -> Unit,
) {
    val owner = LocalBrowseFocusReturnState.current ?: return
    val currentSlots = rememberUpdatedState(slots)
    val currentScroll = rememberUpdatedState(scroll)
    val currentVisible = rememberUpdatedState(visible)
    val filterFocus = LocalFilterFocusState.current
    val allowRecovery = rememberUpdatedState(filterFocus?.allowContentAutoFocus != false)
    DisposableEffect(owner, token) {
        owner.registerCollection(token, { currentSlots.value }, { currentVisible.value(it) },
            { currentScroll.value(it) }, { allowRecovery.value })
        onDispose { owner.unregisterCollection(token) }
    }
}
