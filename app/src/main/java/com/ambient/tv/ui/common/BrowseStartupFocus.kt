package com.ambient.tv.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.ambient.tv.ui.mobile.LocalHandheldUi

/**
 * A focus callback is NOT evidence of navigation: Compose also focuses the first tile during
 * initial entry and when an asynchronously loaded row replaces its cached counterpart.
 * Only a real user input or an explicit return commits this page's focus for the app session.
 */
internal class BrowseStartupFocusGate {
    var claimed by mutableStateOf(false)
        private set

    fun claim() { claimed = true }

    fun followsFirst(explicitReturn: Boolean = false): Boolean = !claimed && !explicitReturn
}

/**
 * Only in-memory, never SavedState / SharedPreferences. A new UI session starts every browse
 * view at card zero, while revisiting a view during that same session keeps its chosen position.
 * Bound the registry so visiting dynamically generated detail/collection pages cannot grow it
 * without limit.
 */
internal object BrowseStartupFocusSessions {
    private const val MAX_SESSIONS = 256
    private val sessions = LinkedHashMap<String, BrowseStartupFocusGate>()
    var generation by mutableLongStateOf(0L)
        private set

    @Synchronized
    fun get(key: String): BrowseStartupFocusGate {
        sessions[key]?.let { return it }
        if (sessions.size >= MAX_SESSIONS) sessions.remove(sessions.keys.first())
        return BrowseStartupFocusGate().also { sessions[key] = it }
    }

    /** A background player may keep the process alive after its browse UI session ended. */
    @Synchronized
    fun clear() {
        sessions.clear()
        generation++
    }
}

@Composable
internal fun rememberBrowseStartupFocusGate(scope: String, explicitReturn: Boolean = false): BrowseStartupFocusGate {
    val gate = remember(scope, BrowseStartupFocusSessions.generation) { BrowseStartupFocusSessions.get(scope) }
    if (explicitReturn) SideEffect { gate.claim() }
    return gate
}

/**
 * Focus reconciliation alone is too late: LazyListState restores saved scroll offsets and
 * preserves the old first visible item's key when fresh rows/cards are inserted ahead of it.
 * Request index zero during composition's apply phase, before the first/new list is measured,
 * rather than scrolling back in a coroutine after an obsolete viewport was already drawn.
 * A real navigation input or explicit playback return disables this pin immediately.
 */
@Composable
internal fun BrowseStartupViewport(
    state: LazyListState,
    itemKeys: List<Any>,
    followFirst: Boolean,
    firstIndex: Int = 0,
) {
    if (followFirst) DisposableEffect(state, itemKeys, firstIndex) {
        state.requestScrollToItem(firstIndex)
        onDispose { }
    }
}

private val BrowseStartupNavigationKeys = setOf(
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar,
    Key.PageUp, Key.PageDown,
)

/** Observe inputs, without consuming them or altering the page's existing D-pad navigation. */
internal fun Modifier.claimBrowseStartupFocusOnInput(gate: BrowseStartupFocusGate): Modifier =
    this.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key in BrowseStartupNavigationKeys) gate.claim()
        false
    }.pointerInput(gate) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { (it.pressed && !it.previousPressed) || it.scrollDelta != Offset.Zero }) {
                    gate.claim()
                }
            }
        }
    }

/**
 * Cache-to-live reconciliation: while the viewer has not navigated, follow the first visible
 * item by its stable identity (both row and item). A new row or an insertion at index zero
 * causes one new target request, not a re-focus on every metadata/progress update.
 *
 * Return-from-playback and subsequent user input always win. Focus preparation uses the shared
 * two-level lazy-row registry and retries a few frames until the newly inserted tile is laid out.
 */
@Composable
internal fun BrowseStartupFollowFirst(
    gate: BrowseStartupFocusGate,
    target: BrowseFocusKey?,
    explicitReturn: Boolean = false,
    enabled: Boolean = true,
) {
    val owner = LocalBrowseFocusReturnState.current
    val active = isActiveNavigationDestination()
    val handheld = LocalHandheldUi.current
    val follows = gate.followsFirst(explicitReturn)
    LaunchedEffect(owner, gate, target, follows, enabled, active, handheld) {
        if (owner == null || target == null || !follows || !enabled || !active || handheld) return@LaunchedEffect
        repeat(16) {
            if (!gate.followsFirst(explicitReturn) || !enabled || !active) return@LaunchedEffect
            // Do not steal focus from the sidebar, a filter chip or the page header. Those
            // controls own the current navigation, even if the data is still updating.
            // The normal sidebar entry already requests the initial first tile; this
            // reconciliation only redirects a tile that actually holds content focus.
            if (owner.focusedContentKey != null) {
                if (owner.hasFocus(target)) return@LaunchedEffect
                owner.requestContentFocus(target)
                if (owner.hasFocus(target)) return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }
}
