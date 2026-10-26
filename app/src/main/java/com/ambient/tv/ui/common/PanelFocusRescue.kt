package com.ambient.tv.ui.common

import androidx.compose.runtime.withFrameNanos

/**
 * How long a player panel keeps trying to put focus back on a row that left composition, e.g.
 * while a renderer switch rebuilds the level. Time based: a slow device renders fewer frames in
 * the same time, and a frame count ran out before the row was back.
 */
internal const val PANEL_FOCUS_RESTORE_MS = 600L

/** Whether a focus retry started at [startNanos] still runs at frame [frameNanos]. */
internal fun focusRetryWithin(startNanos: Long, frameNanos: Long, windowMs: Long = PANEL_FOCUS_RESTORE_MS): Boolean =
    frameNanos - startNanos < windowMs * 1_000_000L

/**
 * Calls [attempt] once per frame for [windowMs]; it returns true once focus is placed (or the
 * retry no longer applies). Returns whether [attempt] finished it within the window.
 */
internal suspend fun retryFocusFor(windowMs: Long = PANEL_FOCUS_RESTORE_MS, attempt: () -> Boolean): Boolean {
    var start = Long.MIN_VALUE
    while (true) {
        val now = withFrameNanos { it }
        if (start == Long.MIN_VALUE) start = now
        if (attempt()) return true
        if (!focusRetryWithin(start, now, windowMs)) return false
    }
}

/** Where a panel puts focus when the remembered row does not come back. */
internal enum class PanelFocusFallback { FIRST_ROW, FOOTER }

/**
 * Only a level that was just opened and never held focus may start on the footer's Back
 * button (e.g. an empty level). A level the user already worked in goes to its first row,
 * because the next OK on a focused Back would silently leave the level.
 */
internal fun panelFocusFallback(levelFresh: Boolean): PanelFocusFallback =
    if (levelFresh) PanelFocusFallback.FOOTER else PanelFocusFallback.FIRST_ROW

/** Row to return to: a pending hand-back from a closed child level first, else the last focused row. */
internal fun panelFocusRescueTarget(restoreKey: String?, focusedKey: String?): String? = restoreKey ?: focusedKey

/** Lets a [RightSidePanel] put focus back on the level's last focused row after its content changed. */
class PanelFocusRescue internal constructor(
    /** The level never held focus: opened just now. */
    val levelFresh: Boolean,
    val target: () -> String?,
    val request: (String) -> Boolean,
    val onFocused: () -> Unit,
)
