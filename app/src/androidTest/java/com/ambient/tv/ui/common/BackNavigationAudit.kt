package com.ambient.tv.ui.common

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.player.PlayerOverlayVisibility
import com.ambient.tv.ui.player.PlayerPanelVisibility
import com.ambient.tv.ui.player.RequestPlayerOverlayFocus
import com.ambient.tv.ui.theme.AmbientTvTheme

/** Hardware Back down/up through the real panel, selector and focus restoration components. */
internal class BackNavigationAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private var focused: String? = null
    private lateinit var nav: PlayerPanelNavigation
    private val controls = mutableStateOf(true)
    private var exits = 0
    private fun mark(id: String) = Modifier.onFocusChanged {
        if (it.hasFocus) focused = id else if (focused == id) focused = null
    }
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun await(label: String, condition: () -> Boolean) {
        repeat(100) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            Thread.sleep(30)
        }
        error("$label: focused=$focused panel=${nav.panel} controls=${controls.value} exits=$exits")
    }
    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
    }
    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".settingsaudit") || instrumentation.targetContext.packageName.endsWith(".backaudit"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        try {
            for (handheld in listOf(false, true)) auditPlayer(activity, handheld)
        } finally { main { activity.finish() } }
    }
    private fun auditPlayer(activity: UiCatalogActivity, handheld: Boolean) {
        val root = FocusRequester()
        val first = FocusRequester()
        val selector = FocusRequester()
        main {
            focused = null; controls.value = true; exits = 0
            activity.setContent {
                key(handheld) {
                    CompositionLocalProvider(LocalHandheldUi provides handheld) {
                        AmbientTvTheme {
                            nav = rememberPlayerPanelNavigation()
                            val panel = nav.panel
                            RequestPlayerOverlayFocus(first, controls.value && panel == UnifiedPlayerPanel.NONE, panelNavigation = nav)
                            BackHandler(enabled = panel == UnifiedPlayerPanel.NONE) {
                                if (controls.value) { controls.value = false } else exits++
                            }
                            CompositionLocalProvider(LocalPlayerPanelNavigation provides nav) {
                                Box(Modifier.fillMaxSize().focusRequester(root).focusable()) {
                                    PlayerOverlayVisibility(controls.value) {
                                    Column(Modifier.padding(32.dp)) {
                                        TvActionButton("Playback settings", {
                                            nav.fromOrigin("bar:settings", true) { nav.open(UnifiedPlayerPanel.SETTINGS) }
                                        }, mark("bar:settings").focusRequester(first).focusRequester(nav.focusRequester("bar:settings")))
                                        TvActionButton("Direct video", {
                                            nav.fromOrigin("bar:video", true) { nav.open(UnifiedPlayerPanel.VIDEO) }
                                        }, mark("bar:video").focusRequester(nav.focusRequester("bar:video")))
                                    }
                                    }
                                    PlayerPanelVisibility(panel) { level ->
                                        UnifiedPlayerSidePanel(level.name, onBack = { nav.back(level) }) {
                                            if (level == UnifiedPlayerPanel.SETTINGS) {
                                                PlayerPanelEntry(Icons.Default.Movie, "Video", "", {
                                                    nav.open(UnifiedPlayerPanel.VIDEO)
                                                }, mark("entry:Video"))
                                            } else {
                                                PlayerDropUpSelector(label = "Mode", value = "One", options = listOf("1" to "One", "2" to "Two"),
                                                    selectedKey = "1", onSelected = {}, modifier = mark("selector").focusRequester(selector))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Thread.sleep(500)
        main { check(first.requestFocus()) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("settings entry") { nav.panel == UnifiedPlayerPanel.SETTINGS && focused == "entry:Video" }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("video selector") { nav.panel == UnifiedPlayerPanel.VIDEO && focused == "selector" }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        Thread.sleep(200)
        // A long press cannot dismiss on down and let up close the parent as well.
        val downTime = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        instrumentation.sendKeySync(KeyEvent(downTime, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 1))
        instrumentation.sendKeySync(KeyEvent(downTime, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        await("popup returns to its selector") { nav.panel == UnifiedPlayerPanel.VIDEO && focused == "selector" }
        Thread.sleep(100)
        main { check(nav.panel == UnifiedPlayerPanel.VIDEO && controls.value && exits == 0) }
        key(KeyEvent.KEYCODE_BACK)
        await("video returns to parent entry") { nav.panel == UnifiedPlayerPanel.SETTINGS && focused == "entry:Video" }
        key(KeyEvent.KEYCODE_BACK)
        await("panel returns to opener") { nav.panel == UnifiedPlayerPanel.NONE && focused == "bar:settings" }
        main { check(nav.focusRequester("bar:video").requestFocus()) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("direct video") { nav.panel == UnifiedPlayerPanel.VIDEO && focused == "selector" }
        key(KeyEvent.KEYCODE_BACK)
        await("direct video closes to opener") { nav.panel == UnifiedPlayerPanel.NONE && focused == "bar:video" }
        // No fade timeout between these presses: the old panel must no longer own Back.
        key(KeyEvent.KEYCODE_BACK)
        await("OSD closes") { !controls.value && exits == 0 }
        key(KeyEvent.KEYCODE_BACK)
        await("player leaves once") { exits == 1 }
        Thread.sleep(500)
        main { check(!controls.value && exits == 1) { "Delayed hand-back reopened or exited another level" } }
        log("PASS Back hierarchy handheld=$handheld: selector -> video -> settings -> OSD -> player exit; direct panel returns to opener; held Back consumes one level")
    }
}
