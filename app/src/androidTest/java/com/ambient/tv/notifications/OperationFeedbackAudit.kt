package com.ambient.tv.notifications

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.common.*
import com.ambient.tv.ui.theme.AmbientTvTheme
import java.io.File

/** Isolated UI fixture: no media accounts, network mutations, or production preferences. */
class OperationFeedbackAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    // The animated app background need not become globally idle; flush the main-thread queue.
    private fun settle() { Thread.sleep(350); main {} }
    private fun awaitReady(label: String, ready: () -> Boolean) {
        repeat(50) {
            var matched = false
            main { matched = ready() }
            if (matched) return
            Thread.sleep(100)
        }
        error("Fixture not ready: $label")
    }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); settle() }

    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".feedbackaudit"))
        val notices = mutableListOf<AppNotice>()
        val reporter = OperationFeedbackReporter { notices.add(it) }
        val message = mutableStateOf<OperationFeedback?>(null)
        val dialog = mutableStateOf(false)
        val page = mutableStateOf(false)
        val busy = mutableStateOf(false)
        val focus = FocusRequester()
        var contentFocused = false
        var retryFocused = false
        var dialogFocused = false
        var clicks = 0
        val error = OperationFeedback("test", "content", R.string.dlna_page_failed,
            detail = R.string.feedback_connection_hint)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        try {
            main { activity.setContent {
                AmbientTvTheme {
                    Box(Modifier.fillMaxSize()) {
                        AppBackground(Modifier.fillMaxSize())
                        OperationFeedbackScope(reporter, "content", !dialog.value)
                        Column(Modifier.padding(40.dp).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            TvActionButton("Content item", { clicks++ }, Modifier.focusRequester(focus).onFocusChanged { contentFocused = it.isFocused })
                            message.value?.let {
                                OperationFeedbackContent(it, Modifier.fillMaxWidth(), if (page.value) FeedbackLayout.PAGE else FeedbackLayout.COMPACT,
                                    actionLabel = "Retry", onAction = { busy.value = true }, actionLoading = busy.value,
                                    actionModifier = Modifier.onFocusChanged { retryFocused = it.isFocused }, requestActionFocus = page.value)
                            }
                        }
                        if (dialog.value) {
                            OperationFeedbackScope(reporter, "dialog")
                            AppDialog(onDismiss = { dialog.value = false }, title = "Network source", footer = {
                                TvActionButton("Test", {}, Modifier.onFocusChanged { dialogFocused = it.isFocused })
                            }) {
                                message.value?.let { OperationFeedbackContent(it, Modifier.fillMaxWidth(), FeedbackLayout.FIELD) }
                            }
                        }
                    }
                }
            } }
            awaitReady("foreground window") {
                activity.hasWindowFocus() && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            }
            settle()
            main { check(focus.requestFocus()) }
            awaitReady("initial content focus") { contentFocused }
            main { message.value = reporter.complete(reporter.begin("test"), error) }
            settle()
            main { check(contentFocused && notices.isEmpty()) {
                "Inline failure: focused=$contentFocused, notices=${notices.size}, window=${activity.hasWindowFocus()}, lifecycle=${activity.lifecycle.currentState}"
            } }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(clicks == 1) }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            main { check(retryFocused) { "Retry is not reachable by D-pad" } }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(busy.value && retryFocused) { "Retry lost focus while running" } }
            screenshot("feedback-compact.png")
            log("PASS compact feedback: no focus theft or duplicate notice; Retry reachable and focused while loading")

            main { busy.value = false; page.value = true; check(focus.requestFocus()) }
            settle()
            main { check(retryFocused) { "Page failure did not focus Retry" } }
            screenshot("feedback-page.png")
            log("PASS full page feedback: explicit Retry focus")

            main { dialog.value = true }
            settle()
            main {
                check(dialogFocused)
                message.value = reporter.complete(reporter.begin("test"), error.copy(scope = "dialog"))
            }
            settle()
            main { check(dialogFocused && notices.isEmpty()) }
            screenshot("feedback-dialog.png")
            key(KeyEvent.KEYCODE_BACK)
            main {
                check(!dialog.value)
                reporter.complete(reporter.begin("test"), error.copy(scope = "dialog"))
                check(notices.size == 1) { "Hidden dialog error did not notify once" }
                reporter.complete(reporter.begin("test"), error.copy(phase = FeedbackPhase.SUCCESS, delivery = FeedbackDelivery.ACTION))
                check(notices.size == 2 && notices.last().phase == NoticePhase.SUCCESS)
            }
            log("PASS dialog feedback: preserves focus and Back; hidden failure and explicit success notify once")
            main {
                page.value = false
                message.value = OperationFeedback("mac", "content", R.string.settings_status_mac_mismatch,
                    args = listOf("02:11:22:33:44:55", "02:AA:BB:CC:DD:EE"))
                check(focus.requestFocus())
            }
            settle()
            main { check(contentFocused) { "Formatted feedback stole focus" } }
            screenshot("feedback-formatted.png")
            log("PASS formatted MAC feedback renders with both arguments without focus theft")
        } finally { main { activity.finish() } }
    }

    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(instrumentation.targetContext.filesDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
