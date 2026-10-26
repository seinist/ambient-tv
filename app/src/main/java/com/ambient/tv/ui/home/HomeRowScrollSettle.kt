package com.ambient.tv.ui.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Quiet time after the vertical row glide ends before hero/backdrop work is committed. */
internal const val ROW_SCROLL_SETTLE_MS = 80L

/** Safety cap: a single glide never runs this long, so the hero cannot stay stale. */
internal const val ROW_SCROLL_MAX_DEFER_MS = 1_500L

/** For hero hosts without a vertical row list (handheld list, Main overlay). */
internal val RowsNeverScrolling: StateFlow<Boolean> = MutableStateFlow(false)

/**
 * Suspends while the Home row column is gliding to a new row, then for [settleMs] of quiet.
 * A glide that restarts within the quiet time keeps waiting. Returns at once when no glide
 * runs (horizontal moves, rows already in place), so those keep their usual latency.
 * Callers run this inside `collectLatest`, so a newer focus target always wins.
 */
internal suspend fun awaitRowScrollSettled(
    scrolling: StateFlow<Boolean>,
    settleMs: Long = ROW_SCROLL_SETTLE_MS,
    maxWaitMs: Long = ROW_SCROLL_MAX_DEFER_MS,
) {
    if (!scrolling.value) return
    withTimeoutOrNull(maxWaitMs) {
        do {
            scrolling.first { !it }
        } while (withTimeoutOrNull(settleMs) { scrolling.first { it } } != null)
    }
}
