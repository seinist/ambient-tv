package com.ambient.tv.ui.input

import kotlin.math.abs

internal enum class PointerIdleAction {
    HOVER_ENTER, MOVE, BUTTON_PRESS, BUTTON_RELEASE, SCROLL, CANCEL, HOVER_EXIT,
}

/**
 * Mouse visibility only: callers still deliver every input event unchanged. Coordinates must
 * share one stable screen space; each source keeps its own anchor so window changes and a
 * companion pointer cannot turn a stationary hover into apparent mouse activity.
 *
 * All times use the same monotonic clock. A single delayed callback can use [deadlineMs];
 * calling [onTimeout] for an old deadline is harmless after newer input has rearmed it.
 */
internal class PointerIdleState(
    timeoutMillis: Long = 2_000L,
    private val movementSlopPx: Float = 1f,
) {
    private data class Position(val x: Float, val y: Float)

    private val positions = mutableMapOf<Int, Position>()
    private val pressedSources = mutableSetOf<Int>()
    private var timeoutMillis = timeoutMillis.coerceAtLeast(0L)
    private var lastActivityMs: Long? = null

    var visible: Boolean = this.timeoutMillis == 0L
        private set

    var deadlineMs: Long? = null
        private set

    init {
        require(movementSlopPx.isFinite() && movementSlopPx >= 0f)
    }

    /** Returns true only for deliberate activity, allowing callers to select its cursor owner. */
    fun onPointer(
        nowMs: Long,
        x: Float,
        y: Float,
        action: PointerIdleAction,
        buttonsDown: Boolean = false,
        sourceId: Int = 0,
    ): Boolean {
        if (action == PointerIdleAction.CANCEL) {
            val wasPressed = pressedSources.remove(sourceId)
            if (wasPressed && visible && pressedSources.isEmpty()) armAfterRelease(nowMs)
            return false
        }
        if (!x.isFinite() || !y.isFinite()) return false

        val previous = positions[sourceId]
        if (action == PointerIdleAction.HOVER_ENTER || action == PointerIdleAction.HOVER_EXIT) {
            if (previous == null) positions[sourceId] = Position(x, y)
            return false
        }

        val wasPressed = sourceId in pressedSources
        val held = action == PointerIdleAction.BUTTON_PRESS || buttonsDown
        if (held) pressedSources.add(sourceId) else pressedSources.remove(sourceId)
        val moved = previous == null || abs(x - previous.x) > movementSlopPx ||
            abs(y - previous.y) > movementSlopPx
        val deliberate = when (action) {
            PointerIdleAction.MOVE -> moved || (held && !wasPressed)
            PointerIdleAction.BUTTON_PRESS, PointerIdleAction.SCROLL -> true
            // A delayed UP after a lost window must not resurrect its old cursor.
            PointerIdleAction.BUTTON_RELEASE -> wasPressed || held || visible
        }
        if (deliberate) {
            positions[sourceId] = Position(x, y)
            lastActivityMs = nowMs
            visible = true
            deadlineMs = if (timeoutMillis == 0L || pressedSources.isNotEmpty()) null else deadlineFrom(nowMs)
        } else if (held && visible) {
            deadlineMs = null
        } else if (wasPressed && !held && visible && pressedSources.isEmpty()) {
            // A move reporting no buttons also recovers from a missing button-up event.
            armAfterRelease(nowMs)
        }
        return deliberate
    }

    /** True only if callers need to publish visibility or update the expiry callback. */
    fun onNonPointerInput(): Boolean {
        if (timeoutMillis == 0L || pressedSources.isNotEmpty()) return false
        val changed = visible || deadlineMs != null
        visible = false
        deadlineMs = null
        lastActivityMs = null
        return changed
    }

    fun onFocusLost() {
        pressedSources.clear()
        visible = timeoutMillis == 0L
        deadlineMs = null
        lastActivityMs = null
    }

    /** Own dialogs/embedded roots transfer focus without ending the app's pointer session. */
    fun onWindowFocusLost(nowMs: Long) {
        val wasPressed = pressedSources.isNotEmpty()
        pressedSources.clear()
        if (wasPressed && visible) armAfterRelease(nowMs)
    }

    fun onTimeout(nowMs: Long) {
        val deadline = deadlineMs ?: return
        if (nowMs < deadline || pressedSources.isNotEmpty() || timeoutMillis == 0L) return
        visible = false
        deadlineMs = null
    }

    fun setTimeoutMillis(timeoutMillis: Long, nowMs: Long) {
        val next = timeoutMillis.coerceAtLeast(0L)
        if (this.timeoutMillis == next) return
        this.timeoutMillis = next
        if (next == 0L) {
            visible = true
            deadlineMs = null
        } else if (pressedSources.isNotEmpty()) {
            visible = true
            deadlineMs = null
        } else if (visible) {
            val activity = lastActivityMs
            if (activity == null) {
                visible = false
                deadlineMs = null
            } else {
                deadlineMs = deadlineFrom(activity)
                onTimeout(nowMs)
            }
        }
    }

    private fun armAfterRelease(nowMs: Long) {
        lastActivityMs = nowMs
        deadlineMs = if (timeoutMillis == 0L) null else deadlineFrom(nowMs)
    }

    private fun deadlineFrom(nowMs: Long): Long =
        if (nowMs > Long.MAX_VALUE - timeoutMillis) Long.MAX_VALUE else nowMs + timeoutMillis
}
