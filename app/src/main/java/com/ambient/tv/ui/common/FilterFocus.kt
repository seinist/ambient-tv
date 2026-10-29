package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

@Stable
internal class FilterFocusState {
    private var policy by mutableStateOf(FilterFocusPolicy())
    private val controls = linkedMapOf<FilterFocusTarget, FocusRequester>()
    internal var returnGeneration by mutableIntStateOf(0)
        private set

    val allowContentAutoFocus: Boolean get() = policy.allowContentAutoFocus
    internal val pendingSurface: FilterFocusSurface? get() = policy.pending?.surface

    fun keepControls() { policy = policy.keepControls() }
    fun enterContent() { policy = policy.enterContent() }
    internal fun edited(key: String) { policy = policy.edited(key) }

    fun restoreFilter(surface: FilterFocusSurface) {
        policy = policy.restoreFilter(surface)
        returnGeneration++
    }

    internal fun register(target: FilterFocusTarget, requester: FocusRequester) { controls[target] = requester }
    internal fun unregister(target: FilterFocusTarget, requester: FocusRequester) {
        if (controls[target] === requester) controls.remove(target)
    }

    internal fun restorePending(): Boolean {
        val pending = policy.pending ?: return true
        val target = filterFocusReturnTarget(pending, controls.keys) ?: return false
        val focused = runCatching { controls[target]?.requestFocus() == true }.getOrDefault(false)
        if (focused && policy.pending == pending) policy = policy.copy(pending = null)
        return focused
    }
}

internal val LocalFilterFocusState = staticCompositionLocalOf<FilterFocusState?> { null }

@Composable
internal fun rememberFilterFocusState(vararg keys: Any?): FilterFocusState {
    val state = remember(*keys) { FilterFocusState() }
    LaunchedEffect(state, state.returnGeneration) {
        if (state.pendingSurface == null) return@LaunchedEffect
        // A reset may recompose the controls or reopen their panel. Wait for their nodes to attach.
        withFrameNanos { }
        retryFocusFor { state.restorePending() }
    }
    return state
}

/** Stable per-control requesters work for both the inline bar and its hidden-bar panel equivalent. */
@Composable
internal fun Modifier.filterControlFocus(key: String, surface: FilterFocusSurface): Modifier {
    val state = LocalFilterFocusState.current ?: return this
    val target = remember(key, surface) { FilterFocusTarget(key, surface) }
    val requester = remember(state, target) { FocusRequester() }
    DisposableEffect(state, target, requester) {
        state.register(target, requester)
        onDispose { state.unregister(target, requester) }
    }
    return focusRequester(requester)
        .then(if (surface == FilterFocusSurface.BAR) Modifier.browseFocusTarget("filter:$key", content = false) else Modifier)
        .onFocusChanged { if (it.hasFocus) state.keepControls() }
}
