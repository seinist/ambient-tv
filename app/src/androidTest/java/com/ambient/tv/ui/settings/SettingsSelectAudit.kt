package com.ambient.tv.ui.settings

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.ambient.tv.ui.player.findActivity
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.theme.AmbientTvTheme
import java.io.File

/** Real remote events against the production select; no preferences or server access. */
class SettingsSelectAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
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

    fun run(displayOnly: Boolean = false) {
        check(instrumentation.targetContext.packageName.let { it.endsWith(".settingsaudit") || it.endsWith(".selectaudit") || it.endsWith(".focusaudit") }) {
            "Use the isolated .selectaudit/.focusaudit application"
        }
        val value = mutableStateOf(18)
        val selected = mutableStateOf(emptySet<Int>())
        val first = FocusRequester()
        val second = FocusRequester()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, UiCatalogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as UiCatalogActivity
        try {
            if (displayOnly) {
                auditDisplayModes(activity)
                return
            }
            main {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    AmbientTvTheme {
                        Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Settings select · D-pad audit", color = androidx.tv.material3.MaterialTheme.colorScheme.onSurface)
                            SettingsSelect(options = (0..99).map { index ->
                                SettingsSelectOption("Mode $index", value.value == index, { value.value = index }, enabled = index != 19)
                            }, modifier = Modifier.focusRequester(first))
                            SettingsSelect(options = buildList {
                                add(SettingsSelectOption("All modes", selected.value.isEmpty(), { selected.value = emptySet() }))
                                (1..29).forEach { index -> add(SettingsSelectOption("Allowed $index", index in selected.value, {
                                    selected.value = if (index in selected.value) selected.value - index else selected.value + index
                                })) }
                            }, multiple = true, modifier = Modifier.focusRequester(second))
                        }
                    }
                }
            }
            Thread.sleep(750)
            main { check(first.requestFocus()) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(value.value == 20) { "Long list or disabled option navigation failed: ${value.value}" } }
            log("PASS long select scrolls to current value; D-pad skips disabled Mode 19 and selects Mode 20")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_BACK)
            main { check(value.value == 20) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(value.value == 18) { "Back did not restore trigger focus" } }
            log("PASS selection and Back both restore trigger focus; Back keeps the saved choice")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            Thread.sleep(500)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(value.value > 18) { "Scrollbar paging did not return to a visible option: ${value.value}" } }
            log("PASS select scrollbar accepts Right, pages Down and returns to a visible option with Left")
            main { value.value = 99 }
            Thread.sleep(250)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(value.value == 0) { "Lazy selector did not wrap from last to first" } }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(value.value == 99) { "Lazy selector did not wrap from first to last" } }
            log("PASS long select starts at saved tail and wraps both ways across virtualized options")
            main { check(second.requestFocus()) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(selected.value == setOf(1, 2)) { "Multi-select closed or lost focus: ${selected.value}" } }
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap != null) {
                File(instrumentation.targetContext.filesDir, "settings-select-multi.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(selected.value.isEmpty()) { "All modes did not clear the whitelist" } }
            key(KeyEvent.KEYCODE_BACK)
            log("PASS multi-select stays open, retains focus across toggles and clears with All modes")
            auditDisplayModes(activity)
        } finally {
            main { activity.finish() }
        }
    }

    private fun auditDisplayModes(activity: UiCatalogActivity) {
        val matcher = com.ambient.tv.ui.player.DisplayRefreshRateMatcher
        var original: Int? = null
        var originalPreference = 0
        main {
            original = matcher.currentModeId(activity)
            originalPreference = activity.window.attributes.preferredDisplayModeId
        }
        val target = matcher.supportedModes(activity).firstOrNull {
            it.width == 1920 && it.height == 1080 && kotlin.math.abs(it.refreshRate - 23.976f) < .01f && it.modeId != original
        }
        if (target == null) { log("SKIP display switch: no distinct 1080p23.976 mode"); return }
        fun awaitMode(id: Int?) {
            repeat(100) {
                var matches = false
                main { matches = matcher.currentModeId(activity) == id }
                if (matches) return
                Thread.sleep(100)
            }
            error("Display did not switch to mode $id")
        }
        try {
            main {
                matcher.matchContentFrameRate(activity, 23.976f, 1920, 1080, allowedModes = setOf(target.key))
            }
            awaitMode(target.modeId)
            log("PASS production display matcher changed the physical display to ${target.label}")
            main { matcher.restoreMode(activity, original) }
            awaitMode(original)
            val preferred = mutableStateOf(target.key)
            main {
                activity.setContent {
                    com.ambient.tv.ui.player.rememberDisplayModeRequest(
                        activity, "fixed-mode-audit", "off", preferred.value, emptySet(),
                    )
                }
            }
            awaitMode(target.modeId)
            log("PASS production playback hook applies a fixed resolution/rate with adjustment off and unknown source FPS")
            main { preferred.value = "system" }
            awaitMode(original)
            log("PASS selecting System during playback restores the original display mode")
            main { preferred.value = target.key }
            awaitMode(target.modeId)
            main { activity.setContent {} }
            awaitMode(original)
            log("PASS leaving fixed-mode playback restores the original display mode")
            val modes = matcher.supportedModes(activity)
            val base = modes.first { it.modeId == original }
            for (policy in listOf("auto_content", "auto_display")) {
                val expected = checkNotNull(matcher.selectContentMode(modes, base,
                    23.976f, 1920, 1080, preferredModeKey = policy, fallbackModeKey = base.key))
                main {
                    // Compose commonly supplies a wrapped Activity, including the YouTube path.
                    check(android.content.ContextWrapper(activity).findActivity() === activity)
                    activity.setContent {
                        val request = com.ambient.tv.ui.player.rememberDisplayModeRequest(
                            activity, "auto-mode-audit-$policy", "start_stop", policy, emptySet(),
                        )
                        androidx.compose.runtime.LaunchedEffect(policy) {
                            request(23.976f, null, null)
                            kotlinx.coroutines.delay(100)
                            request(0f, 1920, 1080)
                        }
                    }
                }
                awaitMode(expected.modeId)
                // Physical equality alone could pass before any request when already at target.
                Thread.sleep(750)
                main { check(activity.window.attributes.preferredDisplayModeId == expected.modeId) }
                log("PASS production $policy hook merges source callbacks and applies ${expected.label}")
                main { activity.setContent {} }
                awaitMode(original)
            }
            // Exercise panel policy changes without remounting the hook or replaying decoder events.
            val route = "display-policy-switch-audit"
            val policy = mutableStateOf("auto_content")
            main {
                activity.setContent {
                    val request = com.ambient.tv.ui.player.rememberDisplayModeRequest(
                        activity, route, "start_stop", policy.value, emptySet(), metadataRoute = route,
                    )
                    androidx.compose.runtime.LaunchedEffect(Unit) { request(23.976f, 1920, 1080) }
                }
            }
            awaitMode(target.modeId)
            // Let start-only matching latch before changing the policy as the panel does.
            Thread.sleep(3_500)
            for (next in listOf("auto_display", base.key, "auto_content", "auto_display")) {
                val automatic = next.startsWith("auto_")
                val expected = if (automatic) checkNotNull(matcher.selectContentMode(modes, base,
                    23.976f, 1920, 1080, preferredModeKey = next, fallbackModeKey = base.key)) else base
                main {
                    policy.value = next
                    com.ambient.tv.ui.player.PlaybackDisplayModeRevision.bump(
                        sessionFixedKey = next.takeUnless { automatic }, playbackKey = route,
                    )
                }
                awaitMode(expected.modeId)
                Thread.sleep(3_500)
                main { check(matcher.currentModeId(activity) == expected.modeId) }
                log("PASS in-session policy $next applies and retains ${expected.label} without new source callbacks")
            }
            main { activity.setContent {} }
            awaitMode(original)
        } finally {
            main { matcher.restoreMode(activity, original) }
            awaitMode(original)
            main { activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = originalPreference } }
        }
        log("PASS original physical display mode and window preference restored")
    }
}
