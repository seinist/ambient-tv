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
    /** Runs a stand-in focus request (parking) that must not become the remembered row. */
    val park: (() -> Boolean) -> Boolean = { it() },
    /** The rescue ended (or the user pressed a key): rows focused from now on are remembered again. */
    val parkingDone: () -> Unit = {},
    /** A player dialog's focus return owns the hand-back right now; the rescue stays out of it. */
    val dialogOwnsFocus: () -> Boolean = { false },
    /** Back returned to a parent; retry its opening row even if focus landed on a stand-in. */
    val pendingHandBack: () -> Boolean = { false },
)

/**
 * The row a level returns to after [focused] took focus. A row that only holds focus while a
 * rescue waits for the real one (parking) keeps the earlier row remembered.
 */
internal fun rememberedPanelFocusKey(current: String?, focused: String, parking: Boolean): String? =
    if (parking && current != null) current else focused

/** What a running focus rescue does in this frame. */
internal enum class PanelRescueStep { CONTINUE, PAUSE, STOP }

/**
 * A dialog or popup window took the window focus: the rescue pauses and resumes with the same
 * target once the window is focused again, instead of counting as done. A key press by the user
 * ends it.
 */
internal fun panelRescueStep(windowFocused: Boolean, userMoved: Boolean, dialogOwnsFocus: Boolean = false): PanelRescueStep = when {
    // Exactly one owner: the dialog's focus return puts the row back after it closes.
    userMoved || dialogOwnsFocus -> PanelRescueStep.STOP
    !windowFocused -> PanelRescueStep.PAUSE
    else -> PanelRescueStep.CONTINUE
}

/**
 * Whether a rescue starts now. A paused rescue resumes even though a stand-in row holds the
 * panel's focus; otherwise only a panel that lost focus is rescued.
 */
internal fun panelRescueShouldStart(retainFocus: Boolean, handheld: Boolean, hadPanelFocus: Boolean,
    panelHasFocus: Boolean, windowFocused: Boolean, pausedTarget: String?, dialogOwnsFocus: Boolean = false,
    pendingHandBack: Boolean = false): Boolean =
    retainFocus && !handheld && hadPanelFocus && windowFocused && !dialogOwnsFocus &&
        (!panelHasFocus || pausedTarget != null || pendingHandBack)
