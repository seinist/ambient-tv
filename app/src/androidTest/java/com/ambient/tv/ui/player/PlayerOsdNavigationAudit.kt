package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme

/** Real key events through the production bar and timeline, without a decoder or configured server. */
internal class PlayerOsdNavigationAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private var focused: String? = null
    private val timelineVisible = mutableStateOf(true)
    private val duration = mutableLongStateOf(120_000L)
    private val settingsVisible = mutableStateOf(true)
    private var seekDelta = 0L
    private lateinit var focusState: PlayerControlFocus
    private fun mark(id: String) = Modifier.onFocusChanged {
        if (it.isFocused) focused = id else if (focused == id) focused = null
    }
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun awaitFocus(expected: String) {
        repeat(100) {
            var ready = false
            main { ready = focused == expected }
            if (ready) return
            Thread.sleep(30)
        }
        error("Expected focus=$expected, actual=$focused")
    }
    private fun awaitState(label: String, condition: () -> Boolean) {
        repeat(100) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            Thread.sleep(30)
        }
        error("$label did not settle")
    }
    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
    }
    private fun requestFocus(requester: FocusRequester) {
        repeat(100) {
            var accepted = false
            main { accepted = runCatching { requester.requestFocus() }.getOrDefault(false) }
            if (accepted) return
            Thread.sleep(30)
        }
        error("Initial focus target did not become ready")
    }
    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".osdaudit"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        val play = FocusRequester()
        val settings = FocusRequester()
        try {
            main {
                activity.setContent {
                    CompositionLocalProvider(LocalHandheldUi provides false) {
                        AmbientTvTheme {
                            PersistentPlayerChrome("osd-navigation-audit") {
                                focusState = checkNotNull(LocalPlayerControlFocus.current)
                                Box(Modifier.fillMaxSize()) {
                                    ReferencePlayerBottomContent {
                                        if (timelineVisible.value) TvPlayerSeekBar(
                                            positionMs = 30_000L, durationMs = duration.longValue,
                                            onSeekBy = { seekDelta += it }, onInteraction = {},
                                            modifier = mark("timeline"),
                                        )
                                        PlayerControlBar(leftContent = {
                                            PlayerIconButton(Icons.Default.PlayArrow, "Play", {},
                                                modifier = mark("play").focusRequester(play), primary = true)
                                        }, rightContent = {
                                            if (settingsVisible.value) PlayerIconButton(Icons.Default.Settings, "Settings", {},
                                                modifier = mark("settings").focusRequester(settings))
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            requestFocus(settings)
            awaitFocus("settings")
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("timeline")
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("settings")
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("timeline")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocus("settings")
            log("PASS settings -> Up -> timeline -> Up/Down -> settings")

            key(KeyEvent.KEYCODE_DPAD_LEFT)
            awaitFocus("play")
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("timeline")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            main { check(seekDelta > 0) { "Timeline Right did not seek" } }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocus("play")
            log("PASS play -> timeline -> seek -> play")

            main { check(settings.requestFocus()) }
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("timeline")
            main { settingsVisible.value = false }
            awaitState("settings unregistered") { focusState.buttonTarget(play) === play }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocus("play")
            log("PASS removed originating button falls back to play")

            main { timelineVisible.value = false }
            awaitState("timeline unregistered") { focusState.seekTarget() == null }
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("play")
            main { timelineVisible.value = true; duration.longValue = 0 }
            instrumentation.waitForIdleSync()
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("play")
            main { duration.longValue = 120_000 }
            awaitState("timeline registered") { focusState.seekTarget() != null }
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("timeline")
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("play")
            log("PASS missing/unknown-duration timeline retains focus; becoming seekable restores navigation")
        } finally { main { activity.finish() } }
    }
}
