package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ambient.tv.R

/** Navigate real footer controls without assuming that every translated label fits on one line. */
internal class NextUpAuditFocus(private val instrumentation: Instrumentation) {
    private data class Action(val label: String, val bounds: Rect, val focused: Boolean)

    private val labels = listOf(
        R.string.next_up_autoplay_on, R.string.next_up_autoplay_off,
        R.string.next_up_close, R.string.next_up_keep_watching,
        R.string.player_skip_credits, R.string.next_up_play_now, R.string.next_up_continue,
    ).map { instrumentation.targetContext.getString(it) }.toSet()

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList()
        else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    private fun actions(): List<Action> {
        instrumentation.waitForIdleSync()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return nodes(instrumentation.uiAutomation.rootInActiveWindow).mapNotNull { node ->
            val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .firstOrNull { it in labels } ?: return@mapNotNull null
            var button: AccessibilityNodeInfo? = node
            while (button != null && !button.isClickable) button = button.parent
            val target = button?.takeIf { it.isVisibleToUser } ?: return@mapNotNull null
            val bounds = Rect().also(target::getBoundsInScreen)
            if (bounds.isEmpty) return@mapNotNull null
            Action(label, bounds, nodes(target).any { it.isFocused })
        }.distinctBy { it.label }
    }

    /** Observe focus only, so initial-focus and focus-retention regressions are not repaired by the audit. */
    fun assertFocused(labelResource: Int) {
        val label = instrumentation.targetContext.getString(labelResource)
        var visible = emptyList<Action>()
        repeat(20) {
            visible = actions()
            if (visible.singleOrNull { it.focused }?.label == label) return
            Thread.sleep(100)
        }
        error("Expected Next Up focus on '$label'; actions=$visible")
    }

    fun focus(labelResource: Int) {
        val label = instrumentation.targetContext.getString(labelResource)
        var visible = emptyList<Action>()
        val path = mutableListOf<String>()
        repeat(20) {
            visible = actions()
            val target = visible.firstOrNull { it.label == label }
            val current = visible.singleOrNull { it.focused }
            if (target != null && current != null) {
                if (current.label == label) return
                val overlap = minOf(current.bounds.bottom, target.bounds.bottom) -
                    maxOf(current.bounds.top, target.bounds.top)
                val sameRow = overlap > minOf(current.bounds.height(), target.bounds.height()) / 2
                val key = if (sameRow) {
                    if (target.bounds.centerX() < current.bounds.centerX()) KeyEvent.KEYCODE_DPAD_LEFT
                    else KeyEvent.KEYCODE_DPAD_RIGHT
                } else {
                    if (target.bounds.centerY() < current.bounds.centerY()) KeyEvent.KEYCODE_DPAD_UP
                    else KeyEvent.KEYCODE_DPAD_DOWN
                }
                path += "${current.label}:${KeyEvent.keyCodeToString(key)}"
                instrumentation.sendKeyDownUpSync(key)
            }
            Thread.sleep(150)
        }
        error("Cannot reach Next Up action '$label' with D-pad; path=$path; actions=$visible")
    }
}
