package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The actual top entry, unlike STARTED which also includes a predictive-back preview. */
internal val LocalActiveNavigationEntryId = staticCompositionLocalOf<String?> { null }

/** Exact playback returns enter content through the shell's normal focus ownership guard. */
internal val LocalPlaybackFocusRequest = staticCompositionLocalOf<((() -> Boolean?) -> Boolean?)> {
    { request -> request() }
}

@Composable
internal fun isActiveNavigationDestination(): Boolean {
    val entry = LocalLifecycleOwner.current as? NavBackStackEntry ?: return true
    val activeId = LocalActiveNavigationEntryId.current ?: return true
    return entry.id == activeId
}

/**
 * A player event is a return address, not a command to move an already visible selection.
 * Accept the event present when this destination becomes current. Later Next/Previous events
 * from a mini player stay pending until the user actually enters the destination again.
 */
internal class PlaybackReturnEntry {
    private var entered = false
    private var accepted: Any? = null

    fun accepts(active: Boolean, revision: Any?): Boolean {
        if (!active) {
            entered = false
            accepted = null
            return false
        }
        if (!entered) {
            entered = true
            accepted = revision
        }
        return revision != null && revision == accepted
    }
}

/** Read-only projection; consuming the event must first commit the same revision to UI state. */
@Composable
internal fun <T : Any> rememberPlaybackReturnOnEntry(pending: T?, revision: (T) -> Any): T? {
    val entry = remember { PlaybackReturnEntry() }
    val active = isActiveNavigationDestination()
    val accepted = entry.accepts(active, pending?.let(revision))
    return pending.takeIf { accepted }
}

/** The preview may disappear after commit; its focus permission lasts only for this visit. */
internal class PlaybackReturnVisit<T : Any> {
    private var token: T? = null
    fun token(active: Boolean, accepted: T?): T? {
        if (!active) token = null
        else if (accepted != null) token = accepted
        return token
    }
}

@Composable
internal fun <T : Any> rememberPlaybackReturnVisitToken(accepted: T?): T? {
    val visit = remember { PlaybackReturnVisit<T>() }
    return visit.token(isActiveNavigationDestination(), accepted)
}

/** Ordinary media players report their final item to the exact destination underneath them. */
internal data class NavigationPlaybackReturn(val entryId: String, val itemId: String, val revision: Long)

internal object NavigationPlaybackReturns {
    private var nextRevision = 0L
    private val returns = MutableStateFlow<Map<String, NavigationPlaybackReturn>>(emptyMap())
    val pending = returns.asStateFlow()

    @Synchronized
    fun prepare(entryId: String?, itemId: String?) {
        if (entryId.isNullOrBlank() || itemId.isNullOrBlank()) return
        val next = returns.value - entryId + (entryId to NavigationPlaybackReturn(entryId, itemId, ++nextRevision))
        returns.value = next.entries.toList().takeLast(32).associate { it.toPair() }
    }

    @Synchronized
    fun consume(event: NavigationPlaybackReturn): Boolean {
        if (returns.value[event.entryId] != event) return false
        returns.value = returns.value - event.entryId
        return true
    }

    @Synchronized
    fun clear() { returns.value = emptyMap() }
}

internal class NavigationPlaybackReturnEntry {
    private var entered = false
    private var accepted: NavigationPlaybackReturn? = null

    fun select(active: Boolean, pending: NavigationPlaybackReturn?): NavigationPlaybackReturn? {
        if (!active) {
            entered = false
            accepted = null
        } else if (!entered) {
            entered = true
            accepted = pending
        }
        return accepted
    }
}

/** Keep the accepted event for this visit, including after its one-shot mailbox is acknowledged. */
@Composable
internal fun rememberNavigationPlaybackReturn(): NavigationPlaybackReturn? {
    val owner = LocalLifecycleOwner.current as? NavBackStackEntry ?: return null
    val pending by NavigationPlaybackReturns.pending.collectAsState()
    val entry = remember(owner.id) { NavigationPlaybackReturnEntry() }
    val accepted = entry.select(isActiveNavigationDestination(), pending[owner.id])
    SideEffect { accepted?.let(NavigationPlaybackReturns::consume) }
    return accepted
}
