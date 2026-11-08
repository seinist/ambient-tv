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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.navigation.SidebarContentEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class BrowseFocusSlot(val key: BrowseFocusKey, val index: Int)

/**
 * Registrations publish immutable slot snapshots. Build their lookup once per snapshot, not on
 * every D-pad transition; a dense library can register thousands of offscreen slots too.
 */
internal class BrowseFocusSlotIndex(private val slots: () -> List<BrowseFocusSlot>) {
    private var indexedSlots: List<BrowseFocusSlot>? = null
    private var slotsByKey: Map<BrowseFocusKey, BrowseFocusSlot> = emptyMap()

    operator fun get(key: BrowseFocusKey): BrowseFocusSlot? {
        val current = slots()
        // Identity is intentional: comparing the list's contents would itself scan the library.
        if (current !== indexedSlots) {
            slotsByKey = buildMap(current.size) {
                current.forEach { slot ->
                    // Keep the same first-match semantics as the original firstOrNull lookups.
                    if (slot.key !in this) put(slot.key, slot)
                }
            }
            indexedSlots = current
        }
        return slotsByKey[key]
    }
}

/** A removed selection stays in its row when possible, otherwise at the top of the content. */
internal fun missingBrowseFocusFallback(lost: BrowseFocusKey, slots: List<BrowseFocusSlot>): BrowseFocusKey? {
    val available = slots.map { it.key }.filter { it.content }
    return available.firstOrNull { it.row == lost.row } ?: available.firstOrNull()
}

/** One destination's return address; neither Android saved state nor preferences retain its nodes. */
internal class BrowseFocusReturnState(
    private val scope: CoroutineScope,
    val view: View,
    private val isNavigationActive: () -> Boolean = { true },
) {
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
    ) {
        private val index = BrowseFocusSlotIndex(slots)
        fun slot(key: BrowseFocusKey): BrowseFocusSlot? = index[key]
    }
    private val targets = linkedMapOf<Target, Unit>()
    private val collections = linkedMapOf<Any, Collection>()
    private var last: Target? = null
    private var lastKey: BrowseFocusKey? = null
    /** Last actual tile, preserved while the user moves to the floating page-action header. */
    private var lastContentKey: BrowseFocusKey? = null
    private var pin: PinnableContainer.PinnedHandle? = null
    private var preparation: Job? = null
    private var recovery: Job? = null
    private var recoveryGeneration = 0L
    private var recoveringKey: BrowseFocusKey? = null
    private var refreshOwner: Any? = null
    private var refreshSnapshot: BrowseRefreshFocusSnapshot? = null
    private var refreshAnchor: BrowseRefreshFocusAnchor? = null
    private var refreshInput: Job? = null
    private var refreshInputPending = false
    private var cancelledRefreshKey: BrowseFocusKey? = null
    private var prepared: BrowseFocusKey? = null
    private var contentIdentity: Any? = null
    private var initialTargetChosen = false
    private var playbackReturnToken: Any? = null
    private var playbackReturnAddress: BrowseFocusKey? = null
    private var playbackReturnTarget: BrowseFocusKey? = null
    var managesInitialFocus: Boolean = false
        private set
    private var fallbackOwner: Any? = null
    private var fallback: (() -> Boolean?)? = null
    private var disposed = false
    private val active: Boolean get() = !disposed && isNavigationActive()

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
        var selected: Target? = null
        for (candidate in targets.keys) {
            if (!candidate.hasFocus) continue
            if (candidate.key == playbackReturnTarget) {
                selected = candidate
                break
            }
            if (selected == null) selected = candidate
            if (candidate.key.row != null && selected?.key?.row == null) {
                selected = candidate
            }
        }
        if (selected != null) focused(selected)
    }

    private fun focused(target: Target) {
        if (!active || target !in targets) return
        // A focusRestorer or a still-attached old child may report focus during return layout.
        // Keep the prepared player address until that exact leaf/group has accepted focus.
        if (playbackReturnTarget != null && target.key != playbackReturnTarget) return
        if (target.key == playbackReturnTarget) playbackReturnTarget = null
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
        if (target.key.content) {
            lastContentKey = target.key
            refreshSnapshot?.anchor(target.key)?.let { refreshAnchor = it }
        }
        preparation?.cancel()
        preparation = null
        prepared = null
    }

    fun contentChanged(identity: Any, restoreInitialTarget: Boolean) {
        if (contentIdentity == identity) return
        cancelRecovery()
        contentIdentity = identity
        refreshAnchor = null
        cancelledRefreshKey = null
        if (restoreInitialTarget) initialTargetChosen = false
        if (lastKey?.content == true) forget()
        else lastContentKey = null
    }

    fun initialTarget(key: BrowseFocusKey?) {
        managesInitialFocus = true
        if (initialTargetChosen || key == null) return
        initialTargetChosen = true
        if (lastKey == null) lastKey = key
        if (lastContentKey == null) lastContentKey = key
        refreshSnapshot?.anchor(key)?.let { refreshAnchor = it }
    }

    /** An explicit playback return takes precedence over remembered controls and old tiles. */
    fun preparePlaybackReturn(token: Any, target: BrowseFocusKey) {
        if (playbackReturnToken == token && playbackReturnAddress == target) return
        cancelRecovery()
        preparation?.cancel()
        preparation = null
        prepared = null
        releasePin()
        last = null
        lastKey = target
        if (target.content) lastContentKey = target
        playbackReturnToken = token
        playbackReturnAddress = target
        playbackReturnTarget = target
        managesInitialFocus = true
        initialTargetChosen = true
        refreshAnchor = refreshSnapshot?.anchor(target)
    }

    fun clearPlaybackReturn(token: Any, target: BrowseFocusKey) {
        if (playbackReturnToken != token || playbackReturnAddress != target) return
        if (playbackReturnTarget != null) {
            preparation?.cancel()
            preparation = null
            prepared = null
        }
        playbackReturnToken = null
        playbackReturnAddress = null
        playbackReturnTarget = null
    }

    fun hasFocus(target: BrowseFocusKey): Boolean =
        targets.keys.any { it.key == target && it.hasFocus && it.visible }

    /** Actual content ownership, excluding the remembered tile while a header owns focus. */
    val focusedContentKey: BrowseFocusKey?
        get() = (targets.keys.firstOrNull { it.key.content && it.hasFocus && it.key.row != null }
            ?: targets.keys.firstOrNull { it.key.content && it.hasFocus })?.key

    /**
     * While true, entering the page waits (requestFocus answers null, so the Sidebar and the
     * return-from-player handoff retry): the item to return to is still being loaded.
     */
    var holdEntry: Boolean = false

    fun forget() {
        cancelRecovery()
        playbackReturnToken = null
        playbackReturnAddress = null
        playbackReturnTarget = null
        last = null
        lastKey = null
        lastContentKey = null
        refreshAnchor = null
        cancelledRefreshKey = null
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
        recoveringKey = null
    }

    /** Publish the complete displayed order once; focus moves only do constant-time lookups. */
    fun refreshContent(owner: Any, snapshot: BrowseRefreshFocusSnapshot) {
        refreshOwner = owner
        val key = lastContentKey
        val before = key?.let { refreshSnapshot?.anchor(it) }
            ?: refreshAnchor?.takeIf { it.key == key }
        refreshSnapshot = snapshot
        refreshAnchor = key?.let(snapshot::anchor) ?: before
        // A reappearing tile starts a new removal transaction if it disappears again later.
        if (cancelledRefreshKey?.let(snapshot::anchor) != null) cancelledRefreshKey = null
    }

    fun clearRefreshContent(owner: Any) {
        if (refreshOwner !== owner) return
        cancelRecovery()
        refreshOwner = null
        refreshSnapshot = null
        refreshAnchor = null
        cancelledRefreshKey = null
        refreshInput?.cancel()
        refreshInput = null
        refreshInputPending = false
    }

    /** A real input owns the next focus move, including one arriving during lazy recovery. */
    fun cancelRefreshRecoveryOnInput() {
        if (refreshSnapshot == null) return
        val interrupted = recoveringKey.takeIf { recovery?.isActive == true }
        cancelRecovery()
        if (interrupted != null) cancelledRefreshKey = interrupted
        refreshInput?.cancel()
        refreshInputPending = true
        refreshInput = scope.launch {
            // The same key dispatch can emit focus-loss callbacks after this preview handler.
            // They must not start the recovery that the user's action just cancelled.
            withFrameNanos { }
            refreshInputPending = false
        }
    }

    /** Only a content tile losing its actual focus can trigger recovery. Leaving for controls,
     * scrolling a still-present item out of composition, and changing pages must not steal focus. */
    private fun recoverMissingContent(lost: BrowseFocusKey) {
        cancelRecovery()
        if (!active || holdEntry || refreshInputPending || cancelledRefreshKey == lost) return
        val generation = recoveryGeneration
        val identity = contentIdentity
        // Capture before composition publishes the replacement order. Keep these exact two
        // neighbours for the entire recovery, even if another refresh arrives in the meantime.
        val anchor = refreshSnapshot?.let { snapshot ->
            refreshAnchor?.takeIf { it.key == lost } ?: snapshot.anchor(lost)
                ?: BrowseRefreshFocusAnchor(lost, next = null, previous = null)
        }
        recoveringKey = lost
        recovery = scope.launch {
            // Let the new lazy item registry and focus tree settle before inspecting survivors.
            withFrameNanos { }
            val sources = collections.values
            if (!active || holdEntry || generation != recoveryGeneration || identity != contentIdentity ||
                NavReselect.restoreTop.value) return@launch
            // A page may register many independent Rows (Search) or an outer list plus
            // nested Rows (Home/SmartTube). Ordinary focus moves retain the old item, so check
            // cached membership before allocating/scanning a full merged fallback list.
            if (sources.any { it.slot(lost) != null } || sources.any { !it.allowRecovery() }) return@launch
            val defaultDestination = if (anchor == null) missingBrowseFocusFallback(lost, sources.flatMap { it.slots() }) else null
            repeat(12) {
                if (!active || holdEntry || generation != recoveryGeneration || identity != contentIdentity ||
                    NavReselect.restoreTop.value || collections.values.any { !it.allowRecovery() } ||
                    collections.values.any { it.slot(lost) != null }
                ) return@launch
                val destination = if (anchor != null) refreshSnapshot?.resolve(anchor) else defaultDestination
                if (destination == null) return@launch
                // Nested horizontal rows need their outer row and inner tile both composed.
                try {
                    collections.values.toList().forEach { collection ->
                        collection.slot(destination)?.let { slot ->
                            if (!collection.visible(slot.index)) collection.scroll(slot.index)
                        }
                    }
                    withFrameNanos { }
                    if (!active || holdEntry || generation != recoveryGeneration || identity != contentIdentity) return@launch
                    // A second snapshot can remove the chosen neighbour while its old tile is
                    // still fading out. Never focus that exiting node; resolve again next pass.
                    if ((anchor != null && refreshSnapshot?.resolve(anchor) != destination) ||
                        collections.values.none { it.slot(destination) != null }) return@repeat
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

    /** Generic sidebar entry: restoring a focused chip/header is correct for Back/navigation. */
    fun requestFocus(): Boolean? = requestFocusInternal(contentOnly = false, requested = playbackReturnTarget)

    /**
     * D-pad Down from a provider overview header must never select that same header again.
     * Use the last tile (or the first real tile after a cold start); if it is offscreen,
     * prepare its lazy row and let the caller retry after layout.
     */
    fun requestContentFocus(): Boolean? = requestFocusInternal(contentOnly = true, requested = playbackReturnTarget)

    /** A player return names its exact item even if the old tile is still actually focused. */
    fun requestContentFocus(target: BrowseFocusKey): Boolean? =
        requestFocusInternal(contentOnly = true, requested = target)

    /** null means the tile is being laid out; true means focus moved synchronously. */
    private fun requestFocusInternal(contentOnly: Boolean, requested: BrowseFocusKey? = null): Boolean? {
        if (!active) return false
        if (holdEntry) return null
        if (NavReselect.restoreTop.value && lastKey != null) forget()
        if (requested != null && prepared != requested) {
            preparation?.cancel()
            preparation = null
            prepared = null
        }
        if (preparation?.isActive == true) return null

        if (!contentOnly && lastKey == null) {
            fallback?.let { action ->
                val result = action()
                if (result != false) return result
            }
        }
        val original = requested ?: if (contentOnly) lastContentKey else lastKey
        val remembered = if (requested == null && original?.content == true) {
            val anchor = refreshSnapshot?.anchor(original) ?: refreshAnchor?.takeIf { it.key == original }
            anchor?.let { refreshSnapshot?.resolve(it) } ?: original
        } else original
        val collection = remembered?.takeIf { it.content }?.let { key ->
            collections.values.firstOrNull { it.slot(key) != null }
        }
        val existing = ((last?.takeIf { it in targets && (!contentOnly || it.key.content) &&
            (requested == null || it.key == requested) && (!it.key.content || it.key == remembered) }
            ?: targets.keys.lastOrNull { it.key == remembered && (!contentOnly || it.key.content) }))
            ?.takeIf { !it.key.content || collections.isEmpty() || collection != null }
        val initial = if (requested == null) collections.values.firstNotNullOfOrNull { it.slots().firstOrNull() } else null
        val wanted = requested ?: when {
            existing != null -> existing.key
            collection != null -> remembered
            initial != null -> initial.key
            else -> targets.keys.firstOrNull { it.key.content }?.key
                ?: if (contentOnly) null else targets.keys.firstOrNull()?.key
        } ?: return false
        val target = existing?.takeIf { it.key == wanted } ?: targets.keys.firstOrNull { it.key == wanted }
        val laidOut = collections.values.all { source ->
            source.slot(wanted)?.let { source.visible(it.index) } ?: true
        }
        if (target?.visible == true && laidOut &&
            runCatching { target.requester.requestFocus() }.getOrDefault(false)
        ) {
            // A container can accept entry before its leaf's focus event arrives. An explicit
            // return must never mistake a still-focused old child for the requested tile.
            return if (requested == null || targets.keys.any { it.key == wanted && it.hasFocus }) true else null
        }
        // A prepared row still needs a new frame before it exposes its inner tile.
        if (prepared == wanted && requested == null) return if (contentOnly) null else false
        if (target == null && collections.values.none { it.slot(wanted) != null }) return false
        prepared = wanted
        preparation = scope.launch {
            try {
                // An outer row must be composed before its inner horizontal list can expose
                // the item's index. Re-read the registry after each layout, from outside in.
                val scrolled = mutableSetOf<Collection>()
                while (true) {
                    val source = collections.values.firstOrNull { source ->
                        source !in scrolled && source.slot(wanted) != null
                    } ?: break
                    scrolled += source
                    val index = source.slot(wanted)?.index ?: continue
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

    fun dispose() {
        disposed = true
        forget()
        refreshInput?.cancel()
        refreshInput = null
        targets.clear()
        collections.clear()
    }

    /** A predictive preview/outgoing destination retains addresses, but owns no focus work. */
    fun navigationChanged(active: Boolean) {
        if (active) return
        cancelRecovery()
        preparation?.cancel()
        preparation = null
        prepared = null
        refreshInput?.cancel()
        refreshInput = null
        refreshInputPending = false
        releasePin()
    }
}

/**
 * Keeps a prepared return layout invisible until its exact focus address accepts entry. Lazy
 * states must already be constructed at the target indices; this is the final focus handoff,
 * not an animated scroll from the old position. The ordinary loading gate remains independent.
 */
internal class BrowsePlaybackReturn internal constructor(
    private val owner: BrowseFocusReturnState?,
    private val token: Any?,
    target: BrowseFocusKey?,
    private val canRequest: () -> Boolean,
    private val request: ((() -> Boolean?) -> Boolean?),
) {
    private var finished by mutableStateOf(false)
    internal var target by mutableStateOf(target)
        private set
    internal var laidOut by mutableStateOf(false)
        private set

    val pending: Boolean
        get() = owner != null && token != null && target != null && canRequest() && !finished

    internal fun updateTarget(address: BrowseFocusKey?) {
        // A finished return revision must never rearm when a later sort/filter/refresh resolves
        // its saved address differently. Only a new navigation event creates a new handoff.
        if (finished || target == address) return
        detach()
        target = address
    }

    internal fun prepare() {
        if (pending) owner?.preparePlaybackReturn(checkNotNull(token), checkNotNull(target))
    }

    internal fun tryFocus(): Boolean {
        if (!pending) return true
        prepare()
        val address = checkNotNull(target)
        request { owner?.requestContentFocus(address) }
        // requestFocus can enter a container while its previous child still owns focus.
        // Reveal only after the requested registration itself has received the focus event.
        if (owner?.hasFocus(address) == true) complete()
        return !pending
    }

    /** Also releases the gate if an external owner/dialog prevents the bounded handoff. */
    internal fun complete() {
        finished = true
        target?.let { if (token != null) owner?.clearPlaybackReturn(token, it) }
    }

    internal fun detach() {
        target?.let { if (token != null) owner?.clearPlaybackReturn(token, it) }
    }

    val modifier: Modifier
        get() = Modifier
            .onGloballyPositioned { coordinates ->
                laidOut = coordinates.isAttached && coordinates.size.width > 0 && coordinates.size.height > 0
                if (laidOut) tryFocus()
            }
            .drawWithContent { if (!pending) drawContent() }
            .onPreviewKeyEvent { event ->
                // A hidden subtree must not activate or navigate its invisible cards. Back
                // remains available and controls outside this content container are unaffected.
                pending && event.key != Key.Back && event.key != Key.Escape
            }
            .pointerInput(this) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (pending) event.changes.forEach { it.consume() }
                    }
                }
            }
}

/**
 * Install after the content identity/initial target, and put [BrowsePlaybackReturn.modifier]
 * on the content container. [token] belongs to an accepted navigation entry, not a background
 * player update. A null address means there is no explicit return to prepare.
 */
@Composable
internal fun rememberBrowsePlaybackReturn(
    token: Any?,
    target: BrowseFocusKey?,
    enabled: Boolean = true,
): BrowsePlaybackReturn {
    val owner = LocalBrowseFocusReturnState.current
    val active = isActiveNavigationDestination()
    val request = LocalPlaybackFocusRequest.current
    val currentActive = rememberUpdatedState(active)
    val currentEnabled = rememberUpdatedState(enabled)
    val currentRequest = rememberUpdatedState(request)
    val controller = remember(owner, token) {
        BrowsePlaybackReturn(owner, token, target,
            canRequest = { currentActive.value && currentEnabled.value },
            request = { currentRequest.value(it) })
    }
    SideEffect {
        controller.updateTarget(target)
        if (active && enabled) controller.prepare() else controller.detach()
    }
    DisposableEffect(controller) { onDispose { controller.detach() } }
    LaunchedEffect(controller, active, enabled, controller.target, controller.laidOut) {
        if (!active || !enabled || !controller.laidOut) return@LaunchedEffect
        // onGloballyPositioned normally completes before the first draw. A nested lazy row
        // or a shell transition can need a few additional layout passes; never hang the page.
        repeat(12) {
            if (controller.tryFocus()) return@LaunchedEffect
            withFrameNanos { }
        }
        controller.complete()
    }
    return controller
}

internal val LocalBrowseFocusReturnState = compositionLocalOf<BrowseFocusReturnState?> { null }
private val LocalBrowseFocusRow = compositionLocalOf<Any?> { null }

@Composable
internal fun BrowseFocusReturnScope(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val handheld = LocalHandheldUi.current
    val active = isActiveNavigationDestination()
    val currentActive = rememberUpdatedState(active)
    val owner = remember(scope, view, handheld) { BrowseFocusReturnState(scope, view) { currentActive.value } }
    SideEffect { owner.navigationChanged(active) }
    DisposableEffect(owner, handheld, active) {
        if (!handheld && active) SidebarContentEntry.register(owner, owner::requestFocus)
        onDispose { SidebarContentEntry.unregister(owner) }
    }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
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

/** Opt-in refresh recovery; rows must match the keys of the rendered rows or flattened grid. */
@Composable
internal fun BrowseFocusRefreshRecovery(rows: List<Pair<Any?, List<Any>>>) {
    val owner = LocalBrowseFocusReturnState.current ?: return
    val token = remember { Any() }
    val snapshot = remember(rows) { BrowseRefreshFocusSnapshot(rows) }
    SideEffect { owner.refreshContent(token, snapshot) }
    DisposableEffect(owner, token) { onDispose { owner.clearRefreshContent(token) } }
}

/** Observe input above the page's focus groups without consuming navigation or activation. */
internal fun Modifier.cancelBrowseRefreshRecoveryOnInput(): Modifier = composed {
    val owner = LocalBrowseFocusReturnState.current ?: return@composed this
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) owner.cancelRefreshRecoveryOnInput()
        false
    }
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
    if (LocalBrowseFocusReturnState.current == null) return
    val row = LocalBrowseFocusRow.current
    RegisterBrowseFocusCollection(state, remember(keys, row) { keys.mapIndexed { index, item ->
        BrowseFocusSlot(BrowseFocusKey(row, item, true), index)
    } }, visible = { index -> state.layoutInfo.visibleItemsInfo.any { it.index == index } }) { state.scrollToItem(it) }
}

@Composable
internal fun RegisterBrowseFocusList(state: LazyListState, keys: List<Any>, rowKey: Any? = null) {
    if (LocalBrowseFocusReturnState.current == null) return
    val row = rowKey ?: LocalBrowseFocusRow.current
    RegisterBrowseFocusCollection(state, remember(keys, row) { keys.mapIndexed { index, item ->
        BrowseFocusSlot(BrowseFocusKey(row, item, true), index)
    } }, visible = { index -> state.layoutInfo.visibleItemsInfo.any { it.index == index } }) { state.scrollToItem(it) }
}

/** Outer row indices also resolve items whose inner LazyRow is not composed yet. */
@Composable
internal fun RegisterBrowseFocusRows(state: LazyListState, rows: List<Pair<Any, List<Any>>>) {
    if (LocalBrowseFocusReturnState.current == null) return
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
    if (LocalBrowseFocusReturnState.current == null) return
    RegisterBrowseFocusCollection(state, remember(rows) { sectionSlots(rows) },
        visible = { index -> state.layoutInfo.visibleItemsInfo.any { it.index == index } }) { state.scrollToItem(it) }
}

@Composable
internal fun RegisterBrowseFocusSections(state: LazyGridState, rows: List<Pair<Any, List<Any>>>) {
    if (LocalBrowseFocusReturnState.current == null) return
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
