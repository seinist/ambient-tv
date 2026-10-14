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
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.AppBackground
import com.ambient.tv.ui.common.RightPanelValueSlider
import com.ambient.tv.ui.common.RightSidePanel
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.isTelevision
import com.ambient.tv.ui.performance.*
import com.ambient.tv.ui.theme.AmbientTvTheme
import java.io.File

class NotificationAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        Thread.sleep(200)
        instrumentation.waitForIdleSync()
    }
    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".notificationaudit")) { "Use isolated .notificationaudit build" }
        val low = mutableStateOf(false)
        val dialog = mutableStateOf(false)
        val panel = mutableStateOf(false)
        val first = FocusRequester()
        var firstFocused = false
        var secondFocused = false
        var dialogFocused = false
        var clicks = 0
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        try {
            main {
                activity.setContent {
                    CompositionLocalProvider(
                        LocalHandheldUi provides !isTelevision(instrumentation.targetContext),
                        LocalPerformanceProfile provides PerformanceProfile.resolve(if (low.value) PerformanceMode.LOW else PerformanceMode.BALANCED, false),
                    ) {
                        AmbientTvTheme {
                            Box(Modifier.fillMaxSize()) {
                                AppBackground(Modifier.fillMaxSize())
                                Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    TvActionButton("First item", { clicks++ }, Modifier.focusRequester(first).onFocusChanged { firstFocused = it.isFocused })
                                    TvActionButton("Second item", { clicks++ }, Modifier.onFocusChanged { secondFocused = it.isFocused })
                                }
                                AppNotificationHost()
                                if (dialog.value) AppDialog(onDismiss = { dialog.value = false }, title = "Login", footer = {
                                    TvActionButton("Continue", { clicks++ }, Modifier.onFocusChanged { dialogFocused = it.isFocused })
                                }) { Text("Account confirmation") }
                                if (panel.value) RightSidePanel(
                                    title = "Playback settings",
                                    onBack = { panel.value = false },
                                    modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd),
                                ) {
                                    RightPanelValueSlider("Delay", 125, -1000..1000, {}, step = 25, valueLabel = "125 ms", subtitle = "Audio synchronization")
                                    TvSettingRow("Normalize volume", "Keep dialogue clearly audible", false, {})
                                }
                            }
                        }
                    }
                }
            }
            Thread.sleep(800)
            main { check(first.requestFocus()) }
            val wake = AppNotice("audit:wake", R.string.notice_waking, listOf("plex", "jellyfin"), "Medienserver", NoticePhase.WORKING, NoticeKind.WAKE)
            AppNotifications.post(wake)
            Thread.sleep(1_100)
            main { check(firstFocused) { "Progress stole focus" } }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            main { check(secondFocused) { "Notification intercepted Down" } }
            AppNotifications.post(wake.copy(message = R.string.notice_connected, phase = NoticePhase.SUCCESS))
            Thread.sleep(300)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(secondFocused && clicks == 1) { "Completion stole focus or Enter" } }
            screenshot("notification-success.png")
            log("PASS full animation: progress and completion preserve focus; Down and Enter reach content")
            main { low.value = true }
            notifyResult("audit:error", "files", "NAS Filme", success = false, kind = NoticeKind.WAKE, failureMessage = R.string.notice_wake_failed)
            Thread.sleep(350)
            main { check(secondFocused) }
            screenshot("notification-economy.png")
            log("PASS economy presentation updates without changing focus")
            main { dialog.value = true }
            Thread.sleep(800)
            main { check(dialogFocused) { "Dialog did not get its action focus" } }
            notifyLogin("jellyfin", NoticePhase.SUCCESS, "Medienserver", "Max")
            Thread.sleep(500)
            main {
                check(dialogFocused) { "Dialog notification stole focus" }
                check(AppNotifications.store.state.value.visible.any { it.notice.key == "login:jellyfin" }) { "Latest login result was hidden by an older success" }
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(clicks == 2) }
            screenshot("notification-dialog.png")
            key(KeyEvent.KEYCODE_BACK)
            main { check(!dialog.value) { "Notification intercepted Back" } }
            log("PASS active dialog: notification leaves action focus and Back handling intact")
            main { panel.value = true }
            Thread.sleep(500)
            screenshot("notification-wide-panel.png")
            main { panel.value = false }
            Thread.sleep(7_500)
            main { check(AppNotifications.store.state.value.visible.isEmpty()) { "Terminal notices did not expire" } }
            check(AppNotifications.store.state.value.history.size >= 3)
            log("PASS notices expire and their results remain in history")
        } finally { main { activity.finish() } }
    }

    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(instrumentation.targetContext.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
