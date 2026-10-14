package com.ambient.tv.ui.youtube

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.youtube.YouTubeLiveEvent
import java.io.File

/** Production dialog, real D-pad events, deterministic offline fixtures; no YouTube account. */
class YouTubeLiveEventAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        Thread.sleep(250)
        instrumentation.waitForIdleSync()
    }
    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".youtubeliveaudit"))
        val state = mutableStateOf(YouTubePlayerState(loading = false,
            liveEvent = YouTubeLiveEvent("YouTube Live · geplanter Teststream", System.currentTimeMillis() + 90_000)))
        var retries = 0
        var dismissed = false
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            UiCatalogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        try {
            main {
                activity.setContent { AmbientTvTheme {
                    YouTubeLiveEventDialog(state.value,
                        onWait = { state.value = state.value.copy(waitingForLive = !state.value.waitingForLive) },
                        onRetry = { retries++ }, onBack = { dismissed = true })
                } }
            }
            Thread.sleep(1000)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(state.value.waitingForLive) { "Initial focus did not activate waiting" } }
            Thread.sleep(1200) // Countdown recomposition must retain focused action.
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(!state.value.waitingForLive) { "Countdown or label change lost focus" } }
            log("PASS initial D-pad focus and stop waiting survive countdown recomposition")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(retries == 1) { "Retry action not reachable" } }
            main { state.value = state.value.copy(liveEvent = state.value.liveEvent!!.copy(scheduledStartMs = System.currentTimeMillis() - 1000)) }
            Thread.sleep(500)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.filesDir, "youtube-live-delayed.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            main { state.value = state.value.copy(liveEvent = state.value.liveEvent!!.copy(scheduledStartMs = null), error = "fixture") }
            Thread.sleep(500)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(retries == 2) { "Schedule/error update lost retry focus" } }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(dismissed) { "Back action not reachable" }; dismissed = false }
            key(KeyEvent.KEYCODE_BACK)
            main { check(dismissed) { "Remote Back did not dismiss dialog" } }
            log("PASS retry, delayed/unknown time, recoverable error, Back button and remote Back")
        } finally { main { activity.finish() } }
    }
}
