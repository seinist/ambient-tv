package com.ambient.tv.ui.display

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.common.AppScreensaver
import com.ambient.tv.ui.theme.AmbientTvTheme

internal class ScreensaverHdrAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var error: Throwable? = null
        instrumentation.runOnMainSync { runCatching(action).onFailure { error = it } }
        error?.let { throw it }
    }
    fun run(activity: UiCatalogActivity) {
        val scene = mutableStateOf("ambient")
        var state: UiHdrState? = null
        main {
            activity.setContent {
                val hdr = rememberUiHdrState(activity, "auto")
                SideEffect { state = hdr }
                CompositionLocalProvider(LocalUiHdrState provides hdr) {
                    AmbientTvTheme {
                        AppScreensaver(scene.value, true, true, ClockSettings(), false, 30, 60, "balanced")
                    }
                }
            }
        }
        try {
            for (mode in listOf("ambient", "aurora", "oled")) {
                main { scene.value = mode }
                Thread.sleep(1100)
                instrumentation.waitForIdleSync()
                main {
                    check(state?.ambientOwner != null && state?.headroom == 1f && state?.failed == false)
                    check(activity.window.colorMode == ActivityInfo.COLOR_MODE_DEFAULT)
                }
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    var lit = 0
                    for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8)
                        if ((bitmap.getPixel(x, y) and 0xffffff) != 0) lit++
                    check(lit > 10) { "Screensaver $mode lost its scene/clock" }
                } finally { bitmap.recycle() }
            }
        } finally { main { activity.setContent {} } }
        // setContent schedules composition disposal for a later frame; an idle
        // main queue alone does not guarantee that DisposableEffect has run.
        val deadline = android.os.SystemClock.uptimeMillis() + 2000
        var disposed = false
        while (!disposed && android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            main { disposed = activity.window.colorMode == ActivityInfo.COLOR_MODE_DEFAULT && state?.ambientOwner == null }
            if (!disposed) Thread.sleep(50)
        }
        main { check(disposed) { "Screensaver disposal: colorMode=${activity.window.colorMode}, owner=${state?.ambientOwner}" } }
        log("PASS production ambient/GL/OLED screensavers and clock remain visible in SDR on an unsupported display; disposal restores ownership")
    }
}
