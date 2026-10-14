package com.ambient.tv.companion

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.theme.AmbientTvTheme

/** Real mouse events, remote keys and physical D-pad input through production focus modifiers. */
internal class CompanionPointerFocusAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(block: () -> Unit) {
        var error: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (failure: Throwable) { error = failure } }
        error?.let { throw it }
    }

    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".focusaudit"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        var focused = -1
        val bounds = mutableMapOf<Int, Rect>()
        val actions = mutableListOf<Int>()
        var original: Window.Callback? = null
        var lastMotion: MotionEvent? = null
        var dialog: android.app.Dialog? = null
        fun settle(expected: Int) {
            Thread.sleep(500)
            instrumentation.waitForIdleSync()
            main { check(focused == expected) { "Expected row $expected, focused=$focused, events=$actions" } }
        }
        try {
            main {
                original = activity.window.callback
                activity.window.callback = object : Window.Callback by original!! {
                    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                        actions += event.actionMasked
                        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                            lastMotion?.recycle()
                            lastMotion = MotionEvent.obtain(event)
                        }
                        return original!!.dispatchGenericMotionEvent(event)
                    }
                }
                CompanionRemoteInput.register(activity.window)
                activity.setContent {
                    AmbientTvTheme {
                        TvLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((0 until 40).toList()) { index ->
                                val rowModifier = Modifier
                                    .fillMaxWidth().height(56.dp)
                                    .onGloballyPositioned { bounds[index] = it.boundsInWindow() }
                                    .onFocusChanged { if (it.isFocused) focused = index }
                                if (index == 3) TvTextField("192.0.2.1", {}, "Server IP", modifier = rowModifier)
                                else TvSettingRow("Pointer row $index", checked = false, onCheckedChange = {}, modifier = rowModifier)
                            }
                        }
                    }
                }
            }
            Thread.sleep(800)
            main {
                val target = bounds.getValue(3).center
                check(CompanionRemoteInput.dispatch(CompanionInput("move",
                    x = target.x / activity.window.decorView.width - 0.5f,
                    y = target.y / activity.window.decorView.height - 0.5f)))
            }
            settle(3)
            main { check(CompanionRemoteInput.dispatch(CompanionInput("key", key = "up"))) }
            settle(2)
            main {
                check(actions.count { it == MotionEvent.ACTION_HOVER_EXIT } == 1)
                // A late/stationary hover at the old position must not undo the D-pad move.
                val stale = MotionEvent.obtain(lastMotion!!)
                try { activity.window.callback.dispatchGenericMotionEvent(stale) } finally { stale.recycle() }
            }
            settle(2)
            main { check(CompanionRemoteInput.dispatch(CompanionInput("move", x = 0.01f))) }
            settle(3)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            settle(2)
            main {
                val stale = MotionEvent.obtain(lastMotion!!)
                try { activity.window.callback.dispatchGenericMotionEvent(stale) } finally { stale.recycle() }
            }
            settle(2)
            main {
                CompanionRemoteInput.hide()
                val exits = actions.count { it == MotionEvent.ACTION_HOVER_EXIT }
                CompanionRemoteInput.hide()
                check(actions.count { it == MotionEvent.ACTION_HOVER_EXIT } == exits)
                check(CompanionRemoteInput.dispatch(CompanionInput("move", x = 0.01f)))
            }
            settle(3)
            main {
                val exits = actions.count { it == MotionEvent.ACTION_HOVER_EXIT }
                CompanionRemoteInput.unregister(activity.window)
                check(actions.count { it == MotionEvent.ACTION_HOVER_EXIT } == exits + 1)
                check(!CompanionRemoteInput.dispatch(CompanionInput("move", x = 0.01f)))
            }
            repeat(12) { index ->
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                settle(4 + index)
            }
            repeat(12) { index ->
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                settle(14 - index)
            }
            log("PASS remote touchpad -> remote/physical Up: no stationary-hover focus theft; movement resumes focus; hide/unregister end hover; long list scrolls down and back up")
            val dialogActions = mutableListOf<Int>()
            main {
                CompanionRemoteInput.register(activity.window)
                check(CompanionRemoteInput.dispatch(CompanionInput("move", x = 0.01f)))
                val exits = actions.count { it == MotionEvent.ACTION_HOVER_EXIT }
                dialog = android.app.Dialog(activity).apply {
                    setContentView(android.widget.Button(activity).apply { text = "Dialog pointer target" })
                    show()
                }
                val window = dialog!!.window!!
                val callback = window.callback
                window.callback = object : Window.Callback by callback {
                    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                        dialogActions += event.actionMasked
                        return callback.dispatchGenericMotionEvent(event)
                    }
                }
                CompanionRemoteInput.register(window)
                check(actions.count { it == MotionEvent.ACTION_HOVER_EXIT } == exits + 1)
            }
            Thread.sleep(500)
            main {
                check(CompanionRemoteInput.dispatch(CompanionInput("move", x = 0.01f)))
                check(dialogActions.contains(MotionEvent.ACTION_HOVER_ENTER))
                CompanionRemoteInput.unregister(dialog!!.window!!)
                check(dialogActions.last() == MotionEvent.ACTION_HOVER_EXIT)
                dialog!!.dismiss()
                dialog = null
            }
            log("PASS opening a dialog ends activity hover; closing it ends hover in the dialog's own window")
        } finally {
            main {
                dialog?.let { CompanionRemoteInput.unregister(it.window!!); it.dismiss() }
                CompanionRemoteInput.unregister(activity.window)
                original?.let { activity.window.callback = it }
                lastMotion?.recycle()
                activity.finish()
            }
        }
    }
}
