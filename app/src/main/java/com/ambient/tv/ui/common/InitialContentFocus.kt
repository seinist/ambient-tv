package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos

/**
 * A new navigation entry may need its first usable focus after loading. Category changes
 * inside that entry only update readiness; they must not enter its focusRestorer again.
 * The returned pending state also lets explicit user navigation cancel the handoff.
 */
@Composable
internal fun rememberInitialContentFocus(
    entryId: String?,
    waitsForContent: Boolean,
    contentPending: Boolean,
    enabled: Boolean,
    hasUsableFocus: () -> Boolean,
    requestFocus: () -> Unit,
): MutableState<Boolean> {
    val pending = remember(entryId) { mutableStateOf(waitsForContent) }
    val currentContentPending by rememberUpdatedState(contentPending)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentHasUsableFocus by rememberUpdatedState(hasUsableFocus)
    val currentRequestFocus by rememberUpdatedState(requestFocus)
    LaunchedEffect(entryId, contentPending, enabled) {
        if (contentPending || !pending.value || !enabled) return@LaunchedEffect
        withFrameNanos { }
        if (!pending.value || !currentEnabled || currentContentPending) return@LaunchedEffect
        pending.value = false
        // A chip, filter or tile can already own focus by the time layout settles. Re-entering
        // the page would briefly restore an older child before its local focus hold repairs it.
        if (!currentHasUsableFocus()) currentRequestFocus()
    }
    return pending
}
