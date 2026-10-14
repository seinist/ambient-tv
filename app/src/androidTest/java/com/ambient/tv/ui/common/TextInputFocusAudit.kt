package com.ambient.tv.ui.common

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme

/** Actual remote events: browse focus must never create an input connection. */
internal class TextInputFocusAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun settle() { Thread.sleep(300); instrumentation.waitForIdleSync() }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); settle() }
    private fun await(label: String, condition: () -> Boolean) {
        repeat(60) { var ready = false; main { ready = condition() }; if (ready) return; Thread.sleep(50) }
        error(label)
    }
    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".settingsaudit"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        val ime = activity.getSystemService(InputMethodManager::class.java)
        try {
            for (handheld in listOf(false, true)) for (password in listOf(false, true)) {
                val field = FocusRequester()
                val opener = FocusRequester()
                val open = mutableStateOf(false)
                var fieldFocused = false
                var openerFocused = false
                var afterFocused = false
                var value by mutableStateOf("example")
                main {
                    activity.setContent {
                        key(handheld, password) {
                        CompositionLocalProvider(LocalHandheldUi provides handheld) {
                            AmbientTvTheme {
                                Column(Modifier.fillMaxSize().padding(40.dp)) {
                                    TvActionButton("Open input", { open.value = true }, modifier = Modifier.focusRequester(opener)
                                        .onFocusChanged { openerFocused = it.hasFocus })
                                }
                                if (open.value) AppDialog(title = "Input audit", onDismiss = { open.value = false }) {
                                    TvTextField(value, { value = it }, "Field", modifier = Modifier.fillMaxWidth()
                                        .focusRequester(field).onFocusChanged { fieldFocused = it.hasFocus },
                                        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None)
                                    TvActionButton("After field", {}, modifier = Modifier.onFocusChanged { afterFocused = it.hasFocus })
                                    LaunchedEffect(Unit) { field.requestFocus() }
                                }
                                LaunchedEffect(open.value) { if (!open.value) { withFrameNanos { }; opener.requestFocus() } }
                            }
                        }
                        }
                    }
                }
                settle()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Dialog did not focus field") { fieldFocused }
                main { check(!ime.isAcceptingText) { "Opening dialog activated editor: handheld=$handheld password=$password" } }
                // A release from the opener, or a held OK, must not arm the new editor.
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                val now = android.os.SystemClock.uptimeMillis()
                instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                settle()
                main { check(!ime.isAcceptingText) { "Stray OK release/repeat activated input" } }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                main { check(afterFocused) { "Down cannot leave browse field" } }
                key(KeyEvent.KEYCODE_DPAD_UP)
                main { check(fieldFocused && !ime.isAcceptingText) { "Up activated/skipped input" } }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("OK did not create input session") { ime.isAcceptingText }
                key(KeyEvent.KEYCODE_BACK)
                await("Back did not end editing") { !ime.isAcceptingText }
                main { check(open.value && fieldFocused) { "Back escaped the dialog or lost field focus" } }
                key(KeyEvent.KEYCODE_BACK)
                await("Second Back did not close dialog") { !open.value }
                await("Dialog did not restore opener focus") { openerFocused }
                log("PASS dialog input handheld=$handheld password=$password: focus/stray OK/held OK leave IME closed; OK edits; Back restores field then opener")
            }
        } finally { main { activity.finish() } }
    }
}
