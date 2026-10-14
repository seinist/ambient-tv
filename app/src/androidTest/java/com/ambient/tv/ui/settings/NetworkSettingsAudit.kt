package com.ambient.tv.ui.settings

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import com.ambient.tv.R
import com.ambient.tv.ui.catalog.AppearanceSettingsActivity
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.theme.AmbientTvTheme

/** Real remote events in a separate application: never changes the user's servers. */
internal class NetworkSettingsAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun settle() { Thread.sleep(250); instrumentation.waitForIdleSync() }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); settle() }
    private fun await(label: String, predicate: () -> Boolean) {
        repeat(60) { var ready = false; main { ready = predicate() }; if (ready) return; Thread.sleep(50) }
        error(label)
    }
    private fun texts(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        return listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
            (0 until node.childCount).flatMap { texts(node.getChild(it)) }
    }
    private fun focused(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        if (node.isFocused) return texts(node)
        return (0 until node.childCount).flatMap { focused(node.getChild(it)) }
    }
    private fun focusLabels(): List<String> {
        val labels = mutableListOf<String>()
        fun nodeText(node: SemanticsNode): List<String> =
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } + node.children.flatMap(::nodeText)
        fun focusedNode(node: SemanticsNode) {
            if (node.config.getOrElse(SemanticsProperties.Focused) { false }) labels += nodeText(node)
            else node.children.forEach(::focusedNode)
        }
        fun visit(view: android.view.View) {
            val owner = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
                ?.invoke(view) as? SemanticsOwner
            if (owner != null) focusedNode(owner.rootSemanticsNode)
            if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        main {
            android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }.forEach(::visit)
        }
        return labels.distinct().ifEmpty { focused(instrumentation.uiAutomation.rootInActiveWindow) }
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".settingsaudit") || instrumentation.targetContext.packageName.endsWith(".networkaudit"))
        val prefs = com.ambient.tv.ui.player.videoImagePreferences(instrumentation.targetContext)
        prefs.host = "192.0.2.1"
        prefs.macAddress = "02:00:00:00:00:01"
        prefs.plexEnabled = true
        prefs.upsertServerProfile(com.ambient.tv.domain.model.ServerProfile("network-a", "NAS A", "192.0.2.1", macAddress = "02:00:00:00:00:01"), true)
        prefs.upsertServerProfile(com.ambient.tv.domain.model.ServerProfile("network-b", "NAS B", "192.0.2.2", macAddress = "02:00:00:00:00:02"), false)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, AppearanceSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppearanceSettingsActivity
        try {
            val previousPerformance = activity.settingsViewModel.uiState.value.playbackPerformanceModeKey
            main {
                activity.settingsViewModel.checkPlaybackPerformance()
                activity.settingsViewModel.cancelPerformanceProbe()
                check(activity.settingsViewModel.uiState.value.performanceProbe == null)
            }
            settle()
            main { check(activity.settingsViewModel.uiState.value.playbackPerformanceModeKey == previousPerformance) }
            log("PASS cancelling performance probe clears dialog without applying a late result")
            val input = FocusRequester()
            var beforeFocused = false
            var afterFocused = false
            var inputFocused = false
            var exits = 0
            main {
                activity.setContent {
                    NetworkAuditTheme {
                        androidx.activity.compose.BackHandler { exits++ }
                        Column(Modifier.fillMaxSize().padding(40.dp)) {
                            TvActionButton("Before", {}, Modifier.onFocusChanged { beforeFocused = it.isFocused }, fillWidth = true)
                            TvTextField("192.0.2.1", {}, "Server", Modifier.focusRequester(input).onFocusChanged { inputFocused = it.hasFocus }, requireExplicitEditing = true)
                            TvActionButton("After", {}, Modifier.onFocusChanged { afterFocused = it.isFocused }, fillWidth = true)
                        }
                    }
                }
            }
            settle()
            main { check(input.requestFocus()) }
            val ime = activity.getSystemService(InputMethodManager::class.java)
            settle()
            main { check(inputFocused && !ime.isAcceptingText) { "D-pad focus started text editing" } }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            main { check(afterFocused) { "Cannot leave input downwards" } }
            key(KeyEvent.KEYCODE_DPAD_UP)
            main { check(inputFocused) { "Up skips the input" } }
            key(KeyEvent.KEYCODE_DPAD_UP)
            main { check(beforeFocused) { "Cannot leave input upwards" } }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            main { check(inputFocused) { "Down skips the input" } }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK did not start editing") { ime.isAcceptingText }
            key(KeyEvent.KEYCODE_BACK)
            if (ime.isAcceptingText) key(KeyEvent.KEYCODE_BACK)
            main { check(exits == 0) { "Back from input escaped the page" } }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            main { check(afterFocused) { "Input did not release D-pad focus after editing" } }
            log("PASS input focus leaves IME closed; OK starts editing; Back and Up/Down restore navigation")

            var category: SettingsCategory? = null
            var exitCount = 0
            main {
                activity.setContent {
                    NetworkAuditTheme {
                        val state by activity.settingsViewModel.uiState.collectAsState()
                        SettingsHub(state, activity.settingsViewModel, "plex", {}, { exitCount++ }, onDone = {}, onSave = { true }) { current, listState, modifier, _, _ ->
                            SideEffect { category = current }
                            androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = modifier) {
                                when (current) {
                                    SettingsCategory.PLEX -> {
                                        item { TvActionButton("Before network link", {}, fillWidth = true) }
                                        item { SettingsPageLink(SettingsCategory.WAKE, "plex-network") }
                                    }
                                    SettingsCategory.WAKE -> item { SettingsPageLink(SettingsCategory.WIREGUARD, "wake-wireguard") }
                                    else -> item { TvActionButton("Tunnel control", {}, fillWidth = true) }
                                }
                            }
                        }
                    }
                }
            }
            await("Plex page did not mount") { category == SettingsCategory.PLEX }
            settle()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Network link did not open WOL") { category == SettingsCategory.WAKE }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Nested link did not open WireGuard") { category == SettingsCategory.WIREGUARD }
            key(KeyEvent.KEYCODE_BACK)
            await("Back skipped the WOL page") { category == SettingsCategory.WAKE }
            key(KeyEvent.KEYCODE_BACK)
            await("Back skipped the Plex page") { category == SettingsCategory.PLEX }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Back did not focus the exact non-first network link") { category == SettingsCategory.WAKE }
            main { check(exitCount == 0) }
            log("PASS linked settings return one level per Back and restore the exact opener")

            fun show(page: String) {
                main {
                    activity.setContent {
                        NetworkAuditTheme {
                            SettingsScreen(onBack = {}, onDone = {}, initialCategoryKey = page, viewModel = activity.settingsViewModel)
                        }
                    }
                }
                Thread.sleep(800)
                instrumentation.waitForIdleSync()
            }
            show("wol")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main {
                check(activity.settingsViewModel.uiState.value.activeServerId == "network-b") { "Server selector did not switch profile" }
                check(activity.settingsViewModel.uiState.value.macAddress == "02:00:00:00:00:02") { "MAC belongs to the wrong profile" }
            }
            log("PASS WOL profile selector changes to the correct server and MAC and returns focus")
            val reached = mutableSetOf<String>()
            repeat(30) {
                reached += focusLabels()
                if (focusLabels().any { it.contains(activity.getString(R.string.settings_resolve_mac)) }) {
                    key(KeyEvent.KEYCODE_DPAD_RIGHT)
                    reached += focusLabels()
                    key(KeyEvent.KEYCODE_DPAD_LEFT)
                }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            screenshot("network-wol")
            for (res in listOf(R.string.settings_resolve_mac, R.string.settings_verify_mac, R.string.settings_network_wake_now, R.string.settings_test_connection,
                R.string.files_manage, R.string.dlna_servers)) {
                val label = activity.getString(res)
                check(reached.any { it.contains(label) }) { "D-pad cannot reach $label: $reached" }
            }
            main { check(!ime.isAcceptingText) { "WOL traversal opened keyboard" } }
            log("PASS real WOL page reaches MAC lookup, connection test, wake, SMB/NFS and DLNA controls with Down; no keyboard")
            show("receiver")
            main { check(!ime.isAcceptingText) }
            val receiverReached = mutableSetOf<String>()
            repeat(10) { receiverReached += focusLabels(); key(KeyEvent.KEYCODE_DPAD_DOWN) }
            for (res in listOf(R.string.settings_network_receiver_name, R.string.files_save, R.string.renderer_enabled)) {
                check(receiverReached.any { it.contains(activity.getString(res)) }) { "Receiver control unreachable: ${activity.getString(res)}; $receiverReached" }
            }
            screenshot("network-receiver")
            log("PASS real receiver page name, save and receiver controls remain traversable without opening keyboard")
            show("companion")
            val deviceReached = mutableSetOf<String>()
            repeat(14) { deviceReached += focusLabels(); key(KeyEvent.KEYCODE_DPAD_DOWN) }
            check(deviceReached.any { it.contains(activity.getString(R.string.companion_pair_phone)) }) { "Pairing action unreachable: $deviceReached" }
            check(deviceReached.any { it.contains(activity.getString(R.string.settings_companion_transfer)) }) { "Transfer link unreachable: $deviceReached" }
            check(deviceReached.any { it.contains(activity.getString(R.string.companion_receiver_background)) }) { "Background receiver unreachable: $deviceReached" }
            screenshot("network-devices")
            log("PASS real device page reaches pairing, receiver/background settings and account-transfer link")
            var savedSource: com.ambient.tv.data.files.FileSource? = null
            val source = com.ambient.tv.data.files.FileSource(name = "Audit NAS", kind = "nfs", host = "192.0.2.9",
                port = 2049, share = "/data", rootPath = "keep-this-folder", wolMac = "02:00:00:00:00:09")
            main {
                activity.setContent {
                    NetworkAuditTheme {
                        com.ambient.tv.ui.files.NetworkSourceDialog(source, false, false, null, true, {},
                            onTest = { _, _, _, _ -> }, onSave = { value, _, _, _ -> savedSource = value }, networkOnly = true)
                    }
                }
            }
            Thread.sleep(700)
            main { check(!ime.isAcceptingText) { "Opening the NAS editor opened the keyboard" } }
            repeat(6) {
                if (focusLabels().none { it.contains(activity.getString(R.string.files_cancel)) }) key(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            check(focusLabels().any { it.contains(activity.getString(R.string.files_cancel)) }) { "NAS footer unreachable: ${focusLabels()}" }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(savedSource?.rootPath == source.rootPath && savedSource?.host == source.host) { "Network-only save altered the source location" } }
            log("PASS NAS dialog opens without keyboard; footer Cancel/Save reachable; network save preserves source location")
        } catch (failure: Throwable) {
            screenshot("network-failure")
            throw failure
        } finally { main { activity.finish() } }
    }
}

@Composable
private fun NetworkAuditTheme(content: @Composable () -> Unit) {
    AmbientTvTheme {
        CompositionLocalProvider(
            androidx.tv.material3.LocalContentColor provides androidx.tv.material3.MaterialTheme.colorScheme.onSurface,
            androidx.compose.material3.LocalContentColor provides androidx.tv.material3.MaterialTheme.colorScheme.onSurface,
        ) { content() }
    }
}
