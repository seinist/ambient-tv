package com.ambient.tv.ui.sound

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.common.*
import com.ambient.tv.ui.settings.*
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.ui.sound.navigationSoundInput

/** Production controls and real remote events; records feedback requests, not acoustic output. */
internal class MenuSoundsAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private val heard = mutableListOf<UiSound>()
    private val callbacks = mutableMapOf<String, Int>()
    private val sounds = object : UiSoundFeedback {
        val gate = UiSoundEventGate()
        val actions = UiSoundActions { if (gate.accept(it, android.os.SystemClock.elapsedRealtime())) heard += it }
        override fun navigationInput(keyCode: Int, eventTime: Long) = gate.input(keyCode, eventTime, android.os.SystemClock.elapsedRealtime())
        override fun play(kind: UiSound) = actions.request(kind)
        override fun perform(kind: UiSound, action: () -> Unit) = actions.perform(kind, action)
    }
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        Thread.sleep(200)
        main { /* Queue barrier: the loading button intentionally animates continuously. */ }
    }
    private fun checkSound(kind: UiSound?, label: String) {
        main {
            val clicks = heard.toList()
            check(clicks == listOfNotNull(kind)) { "$label: $heard" }
            heard.clear()
        }
        log("PASS $label")
    }
    private fun count(id: String) { callbacks[id] = (callbacks[id] ?: 0) + 1 }
    private fun checkHandheldPcmOutput() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        val output = HandheldNavSoundOutput(scope)
        try {
            for (kind in listOf(UiSound.FOCUS, UiSound.SELECT, UiSound.BACK)) {
                val finished = java.util.concurrent.CountDownLatch(1)
                var started = false
                main {
                    output.play(kind, NavSoundDesign.preset("warm", kind), .3f, { true }) {
                        started = it
                        finished.countDown()
                    }
                }
                check(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "PCM start timed out: $kind" }
                check(started) { "PCM start failed: $kind" }
                Thread.sleep(200)
            }
            val muted = java.util.concurrent.CountDownLatch(1)
            main {
                output.play(UiSound.SELECT, NavSoundDesign(), 0f, { true }) {
                    check(!it) { "Zero volume reported a successful preview" }
                    muted.countDown()
                }
            }
            check(muted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            main {
                output.play(UiSound.SELECT, NavSoundDesign(voice = "pad", lengthMs = 1490), .3f, { true }) {
                    error("Stopped PCM request was played")
                }
                output.stop()
            }
            Thread.sleep(250)
            log("PASS handset PCM starts on media output, rejects zero gain and cancels pending sound")
        } finally {
            main {
                output.stop()
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
    }

    fun runHandheld() {
        checkHandheldPcmOutput()
        check(instrumentation.targetContext.packageName.endsWith(".soundsaudit"))
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        val previews = mutableListOf<UiSound>()
        var applied: String? = null
        val feedback = object : UiSoundFeedback {
            override fun play(kind: UiSound) { error("Editing emitted a navigation sound") }
            override fun perform(kind: UiSound, action: () -> Unit) { error("Editing emitted a navigation action") }
            override fun preview(kind: UiSound, design: NavSoundDesign, volume: Float, onResult: (Boolean) -> Unit): Boolean {
                previews += kind
                return true
            }
        }
        fun nodes(node: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> =
            if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        fun all(): List<android.view.accessibility.AccessibilityNodeInfo> {
            instrumentation.uiAutomation.clearCache()
            return instrumentation.uiAutomation.windows.flatMap { nodes(it.root) }
        }
        fun find(label: Int): android.view.accessibility.AccessibilityNodeInfo {
            val text = activity.getString(label)
            repeat(20) {
                all().firstOrNull { it.isVisibleToUser && it.text?.toString() == text }?.let { node -> return node }
                Thread.sleep(100)
            }
            error("Missing handheld control: $text")
        }
        fun click(label: Int) {
            var node = find(label)
            repeat(4) { if (!node.isClickable) node.parent?.let { node = it } }
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            check(!bounds.isEmpty && bounds.top >= 0 && bounds.bottom <= activity.resources.displayMetrics.heightPixels)
            check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            Thread.sleep(200)
            main { }
        }
        try {
            main {
                activity.setContent {
                    CompositionLocalProvider(LocalUiSoundPlayer provides feedback, com.ambient.tv.ui.mobile.LocalHandheldUi provides true) {
                        AmbientTvTheme {
                            val draft = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
                            NavSoundEditor(draft.value, .3f, { draft.value = it; applied = it }, true, {}, {})
                        }
                    }
                }
            }
            Thread.sleep(700)
            find(com.ambient.tv.R.string.settings_nav_sounds)
            click(com.ambient.tv.R.string.nav_editor_play)
            main { check(previews == listOf(UiSound.FOCUS)) }
            click(com.ambient.tv.R.string.nav_editor_stop)
            repeat(10) {
                if (all().none { it.isVisibleToUser && it.text?.toString() == activity.getString(com.ambient.tv.R.string.nav_editor_enabled) }) {
                    all().firstOrNull { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Thread.sleep(150)
                }
            }
            click(com.ambient.tv.R.string.nav_editor_enabled)
            main { check(!NavSoundBank.import(requireNotNull(applied)).design(UiSound.FOCUS).enabled) }
            // File actions remain reachable while preview controls stay in the viewport.
            repeat(10) {
                if (all().none { it.isVisibleToUser && it.text?.toString() == activity.getString(com.ambient.tv.R.string.nav_editor_export) }) {
                    all().firstOrNull { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Thread.sleep(150)
                }
            }
            find(com.ambient.tv.R.string.nav_editor_import)
            find(com.ambient.tv.R.string.nav_editor_export)
            val orientation = if (activity.resources.configuration.screenWidthDp > activity.resources.configuration.screenHeightDp) "landscape" else "portrait"
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.cacheDir, "nav-sound-$orientation.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            log("PASS handheld $orientation: preview, stop, reachable import/export and immediate settings draft update")
        } finally { main { activity.finish() } }
    }

    private fun checkGeneratedSamples() {
        val designs = UiSound.entries.map { it to NavSoundDesign.preset("deep", it) } +
            (UiSound.SELECT to NavSoundDesign(lengthMs = 1500, releaseMs = 1000, reverb = 70,
                tailMs = 4000, echo = 60, echoMs = 600, feedback = 60))
        val loaded = java.util.concurrent.CountDownLatch(designs.size)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        lateinit var pool: android.media.SoundPool
        main {
            pool = android.media.SoundPool.Builder().setMaxStreams(3).build()
            pool.setOnLoadCompleteListener { _, _, status -> if (status != 0) failures.add(status); loaded.countDown() }
        }
        try {
            for ((kind, design) in designs) {
                val file = java.io.File.createTempFile("nav-audit-", ".wav", instrumentation.targetContext.cacheDir)
                try {
                    file.writeBytes(NavSoundRenderer.wav(design, kind))
                    main { check(pool.load(file.absolutePath, 1) != 0) }
                } finally { file.delete() }
            }
            check(loaded.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Generated sounds did not finish loading" }
            check(failures.isEmpty()) { "Generated sound decode failures: $failures" }
            log("PASS all event samples and maximum effect length decode in SoundPool")
        } finally { main { pool.release() } }
    }

    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".soundsaudit"))
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        checkGeneratedSamples()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        val refs = List(8) { FocusRequester() }
        val value = mutableStateOf(0)
        val checked = mutableStateOf(false)
        try {
            main {
                activity.setContent {
                    CompositionLocalProvider(LocalUiSoundPlayer provides sounds, com.ambient.tv.ui.mobile.LocalHandheldUi provides false) {
                        AmbientTvTheme {
                            SilenceSystemMenuSounds()
                            Column(Modifier.navigationSoundInput().fillMaxSize()) {
                                PageUtilityBar(onBack = { count("back"); sounds.playBack() }, onSettings = { count("settings") }, requestInitialFocus = true)
                            }
                        }
                    }
                }
            }
            Thread.sleep(700)
            main { heard.clear() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            checkSound(UiSound.BACK, "top-left Back gives exactly one Back sound")
            main { check(callbacks["back"] == 1) }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            main { check(UiSound.FOCUS in heard); heard.clear() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            checkSound(UiSound.SELECT, "header Settings gives Select feedback")
            main { check(callbacks["settings"] == 1) }
            main {
                activity.setContent {
                    CompositionLocalProvider(LocalUiSoundPlayer provides sounds, com.ambient.tv.ui.mobile.LocalHandheldUi provides false) {
                        AmbientTvTheme {
                            SilenceSystemMenuSounds()
                            Column(Modifier.navigationSoundInput().fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                TvChip("Header", modifier = Modifier.focusRequester(refs[0]), onClick = { count("chip"); refs[1].requestFocus() })
                                TvActionButton("Save", { count("save") }, Modifier.focusRequester(refs[1]))
                                SettingsHubRow("Category", null, false, Icons.Default.Settings, Modifier.focusRequester(refs[2]), onClick = { count("category") })
                                ServerSurface("Server", "Saved source", onClick = { count("server") }, modifier = Modifier.focusRequester(refs[3]))
                                RightPanelChoice("Choice", false, { count("choice") }, Modifier.focusRequester(refs[4]))
                                RightPanelValueSlider("Level", value.value, 0..1, { value.value = it }, Modifier.focusRequester(refs[5]))
                                SettingsSelect(listOf(SettingsSelectOption("One", value.value == 0, { value.value = 0 }),
                                    SettingsSelectOption("Disabled", false, {}, enabled = false), SettingsSelectOption("Three", value.value == 2, { value.value = 2 })), modifier = Modifier.focusRequester(refs[6]))
                                TvSettingRow("Toggle", checked = checked.value, onCheckedChange = { checked.value = it }, modifier = Modifier.focusRequester(refs[7]))
                                TvActionButton("Busy", { error("Loading action fired") }, loading = true)
                            }
                        }
                    }
                }
            }
            Thread.sleep(700)
            for ((index, id) in listOf("chip", "save", "category", "server", "choice").withIndex()) {
                main { check(refs[index].requestFocus()); heard.clear() }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                checkSound(UiSound.SELECT, "$id accepts one activation sound")
                main { check(callbacks[id] == 1) }
            }
            main { check(callbacks["save"] == 1) { "Focus-transfer key release activated Save" }; refs[1].requestFocus(); heard.clear() }
            val pressTime = android.os.SystemClock.uptimeMillis()
            instrumentation.sendKeySync(KeyEvent(pressTime, pressTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            instrumentation.sendKeySync(KeyEvent(pressTime, pressTime + 30, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
            instrumentation.sendKeySync(KeyEvent(pressTime, pressTime + 60, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            main { /* Queue barrier: the loading button intentionally animates continuously. */ }
            checkSound(UiSound.SELECT, "held OK does not repeat activation sounds")
            main { check(callbacks["save"] == 2); refs[5].requestFocus(); heard.clear() }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            checkSound(null, "disabled decrease remains silent")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            checkSound(UiSound.VALUE, "enabled step changes value with one sound")
            main { check(value.value == 1) }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            checkSound(null, "step at its upper limit remains silent")
            main { refs[6].requestFocus(); heard.clear() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            checkSound(UiSound.SELECT, "settings selector opening has feedback")
            key(KeyEvent.KEYCODE_DPAD_DOWN) // Selected One, skips Disabled to Three.
            main { check(UiSound.FOCUS in heard); heard.clear() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            checkSound(UiSound.SELECT, "settings option selection has one sound")
            main { check(value.value == 2); heard.clear() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            checkSound(UiSound.SELECT, "selector reopens")
            key(KeyEvent.KEYCODE_BACK)
            checkSound(UiSound.BACK, "selector Back has one Back sound")
            main { refs[7].requestFocus(); heard.clear() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            checkSound(UiSound.TOGGLE_ON, "existing toggle still gives one sound")
            main { check(checked.value) }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            main { heard.clear() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            checkSound(null, "loading action remains silent")
            main {
                heard.clear()
                activity.setContent {
                    CompositionLocalProvider(LocalUiSoundPlayer provides sounds, com.ambient.tv.ui.mobile.LocalHandheldUi provides false) {
                        AmbientTvTheme { NavSoundEditor("", .3f, {}, true, {}, {}) }
                    }
                }
            }
            Thread.sleep(700)
            main { /* Queue barrier: the loading button intentionally animates continuously. */ }
            fun nodes(node: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> =
                if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
            fun editorNodes() = instrumentation.uiAutomation.windows.flatMap { nodes(it.root) }
            val title = instrumentation.targetContext.getString(com.ambient.tv.R.string.settings_nav_sounds)
            var editorVisible = false
            repeat(20) {
                if (!editorVisible) {
                    // Compose virtual descendants are exposed by traversal; platform text search
                    // can return an empty result even when those descendants have visible text.
                    editorVisible = editorNodes().any { it.text?.toString() == title }
                    if (!editorVisible) Thread.sleep(100)
                }
            }
            check(editorVisible) { "Editor title is not accessible" }
            fun selectSection(label: Int, expected: Int) {
                val text = instrumentation.targetContext.getString(label)
                var node = editorNodes().first { it.text?.toString() == text }
                repeat(4) { if (!node.isClickable) node.parent?.let { parent -> node = parent } }
                check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                val expectedText = instrumentation.targetContext.getString(expected)
                var found = false
                repeat(15) {
                    if (!found) {
                        Thread.sleep(100)
                        main { }
                        instrumentation.uiAutomation.clearCache()
                        found = editorNodes().any { it.text?.toString() == expectedText }
                    }
                }
                check(found) { "Missing editor control: $expectedText; visible: ${editorNodes().mapNotNull { it.text }}" }
            }
            selectSection(com.ambient.tv.R.string.nav_editor_space, com.ambient.tv.R.string.nav_editor_tail)
            selectSection(com.ambient.tv.R.string.nav_editor_echo_section, com.ambient.tv.R.string.nav_editor_delay)
            selectSection(com.ambient.tv.R.string.nav_editor_tone, com.ambient.tv.R.string.nav_editor_pitch)
            log("PASS Tone, Reverb and Echo sections expose their controls")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.cacheDir, "nav-sound-editor.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            checkSound(null, "editor controls stay silent without explicit preview")
            log("PASS editor opens with accessible title and silent editing controls")
        } finally { main { activity.finish() } }
    }
}
