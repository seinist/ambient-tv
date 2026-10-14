package com.ambient.tv.ui.common

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.ambient.tv.R
import com.ambient.tv.ui.catalog.AppearanceSettingsActivity
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.navigation.Sidebar
import com.ambient.tv.ui.settings.SettingsHub
import com.ambient.tv.ui.theme.AmbientTvTheme

/** Runs the production settings hierarchy and sidebar in an isolated Hilt host. */
internal class BackShellAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); Thread.sleep(150); instrumentation.waitForIdleSync() }
    private fun await(label: String, condition: () -> Boolean) {
        repeat(100) { var ready = false; main { ready = condition() }; if (ready) return; Thread.sleep(40) }
        error("$label; focused=${focusedLabels()}")
    }
    private fun click(label: String) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        repeat(50) {
            var node = find(instrumentation.uiAutomation.rootInActiveWindow)
            while (node != null) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { Thread.sleep(200); return }
                node = node.parent
            }
            Thread.sleep(100)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(instrumentation.targetContext.filesDir, "back-shell.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        val labels = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.let(labels::add)
            node.contentDescription?.toString()?.let(labels::add)
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(instrumentation.uiAutomation.rootInActiveWindow)
        error("Missing clickable $label; visible=$labels")
    }
    private fun focusedLabels(): List<String> {
        val node = instrumentation.uiAutomation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val labels = mutableListOf<String>()
        fun collect(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.toString()?.let(labels::add)
            n.contentDescription?.toString()?.let(labels::add)
            for (i in 0 until n.childCount) collect(n.getChild(i))
        }
        collect(node)
        return labels
    }
    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".settingsaudit") || instrumentation.targetContext.packageName.endsWith(".backaudit"))
        val prefs = com.ambient.tv.ui.player.videoImagePreferences(instrumentation.targetContext)
        prefs.filesEnabled = true
        prefs.youtubeEnabled = true
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, AppearanceSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppearanceSettingsActivity
        try {
            for (direct in listOf(false, true)) {
                var exited = 0
                var menu = 0
                var childVisible = false
                var childCategory: String? = null
                main {
                    activity.setContent {
                        key(direct) {
                            AmbientTvTheme {
                                val state by activity.settingsViewModel.uiState.collectAsState()
                                SettingsHub(state, activity.settingsViewModel, if (direct) "appearance" else null,
                                    onExitGuardChanged = {}, onBack = { exited++ }, onFocusMenu = { menu++ },
                                    onDone = {}, onSave = { true }) { category, _, modifier, _, _ ->
                                    DisposableEffect(category) { childVisible = true; childCategory = category.name; onDispose { childVisible = false } }
                                    TvActionButton("Child setting", {}, modifier)
                                }
                            }
                        }
                    }
                }
                if (!direct) click(activity.getString(R.string.settings_library_title))
                await("Settings child did not open") { childVisible }
                key(KeyEvent.KEYCODE_BACK)
                if (!direct) {
                    await("Settings did not return to its index") { !childVisible && exited == 0 && menu == 0 }
                    // OK must reopen the same non-first category after Back restored focus.
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("Settings lost the opening category focus") { childVisible && childCategory == "LIBRARY" }
                    key(KeyEvent.KEYCODE_BACK)
                    key(KeyEvent.KEYCODE_BACK)
                    main { check(exited == 0 && menu == 0) { "Settings skipped its area level" } }
                    key(KeyEvent.KEYCODE_BACK)
                }
                await("Settings must return to caller, not reopen sidebar") { exited == 1 && menu == 0 }
                log("PASS real SettingsHub direct=$direct: parent hierarchy then caller, no sidebar jump")
            }
            var contentFocused = false
            var sidebarFocused = false
            var entryRequested = true
            var contentBackCalls = 0
            val content = FocusRequester()
            val menu = FocusRequester()
            main {
                activity.setContent {
                    AmbientTvTheme {
                        Row(Modifier.fillMaxSize()) {
                            Sidebar(Destinations.HOME, null, onNavigate = {},
                                requestContentFocus = { content.requestFocus() },
                                isEntryRequested = { entryRequested }, onEntryRequestConsumed = { entryRequested = false },
                                onFocusOwnershipChanged = { sidebarFocused = it }, modifier = Modifier.focusRequester(menu))
                            TvActionButton("Current grid item", {}, Modifier.focusRequester(content).onFocusChanged { contentFocused = it.hasFocus })
                        }
                        // Match NavHost ordering: the underlying destination registers after Sidebar.
                        BackHandler { contentBackCalls++ }
                    }
                }
            }
            Thread.sleep(700)
            main { entryRequested = true; check(menu.requestFocus()) }
            await("Sidebar did not gain focus") { sidebarFocused }
            // Enter a configured system using real remote activation.
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_BACK)
            main { check(sidebarFocused && !contentFocused) { "Submenu Back skipped the parent menu" } }
            key(KeyEvent.KEYCODE_BACK)
            await("Root menu did not close to content") { contentFocused && !sidebarFocused }
            main { entryRequested = true; check(menu.requestFocus()) }
            await("Menu did not reopen") { sidebarFocused }
            key(KeyEvent.KEYCODE_BACK)
            await("Reopened root menu did not close") { contentFocused && !sidebarFocused }
            main { check(contentBackCalls == 0) { "Menu Back leaked to the underlying destination" } }
            key(KeyEvent.KEYCODE_BACK)
            main { check(contentBackCalls == 1) { "Closed menu still owns Back" } }
            log("PASS real Sidebar: submenu -> parent -> current content; immediate reopen/Back; underlying destination receives only the next Back")
        } finally { main { activity.finish() } }
    }
}
