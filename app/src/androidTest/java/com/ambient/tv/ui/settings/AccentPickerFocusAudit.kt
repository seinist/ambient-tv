package com.ambient.tv.ui.settings

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.theme.AmbientTvTheme

/** Production picker exercised with remote events, isolated from saved settings. */
class AccentPickerFocusAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
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
        check(instrumentation.targetContext.packageName.endsWith(".selectaudit"))
        val value = mutableStateOf("#FFFFFF")
        val open = mutableStateOf(false)
        val handheld = mutableStateOf(false)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            UiCatalogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        try {
            main {
                activity.setContent {
                    AmbientTvTheme {
                        androidx.compose.runtime.CompositionLocalProvider(com.ambient.tv.ui.mobile.LocalHandheldUi provides handheld.value) {
                        Column {
                            CustomAccentSetting(AccentColorRole.PRIMARY, value.value, { open.value = true })
                        }
                        if (open.value) AccentColorPickerDialog(value.value, { value.value = it }, { open.value = false })
                        }
                    }
                }
            }
            Thread.sleep(1000)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(open.value) { "Picker trigger was unreachable" } }
            Thread.sleep(500)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(value.value == "#FF8080") { "Bottom-to-top wrap failed: ${value.value}" } }
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(value.value == "#FFFFFF") { "Top-to-bottom wrap failed: ${value.value}" } }
            log("PASS opening focuses selected color; vertical wrap works both ways")
            repeat(7) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(value.value == "#000000") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(value.value != "#000000" && value.value != "#FFFFFF") { "Hex return lost last color column" } }
            log("PASS all columns reachable; hex field returns to last focused color")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            // Navigating onto the field must not enter editing: Back closes the picker.
            key(KeyEvent.KEYCODE_BACK)
            main { check(!open.value) { "Hex field activated editing on navigation" } }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(open.value) { "Dismissal did not restore trigger focus" } }
            Thread.sleep(500)
            // Selected chromatic swatch is column 7. Right reaches hex; OK enables editing.
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            Thread.sleep(400)
            // The IME may consume the first Back. In either case editing must not dismiss the dialog.
            key(KeyEvent.KEYCODE_BACK)
            main { check(open.value) { "Back from editing dismissed the picker" } }
            repeat(3) { if (open.value) key(KeyEvent.KEYCODE_BACK) }
            main { check(!open.value) }
            log("PASS hex editing requires OK; Back exits editing; dialog returns focus to opener")
            main { handheld.value = true; value.value = "#000000"; open.value = true }
            Thread.sleep(750)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(open.value && value.value == "#000000") { "Done did not return to last color" } }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(!open.value) { "Handheld Done button was unreachable by D-pad" } }
            log("PASS handheld layout: grid to hex to Done; Done returns to last color and closes on OK")
        } finally { main { activity.finish() } }
    }
}
