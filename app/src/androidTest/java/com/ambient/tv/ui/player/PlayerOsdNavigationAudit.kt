package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.PlayerPanelAction
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
import com.ambient.tv.ui.common.rememberPlayerPanelNavigation
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme

/** Real key events through the production bar and timeline, without a decoder or configured server. */
internal class PlayerOsdNavigationAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private var focused: String? = null
    private var overlaysFocused = false
    private lateinit var chrome: PlayerChromeState
    private val panelLoading = mutableStateOf(true)
    private val panelAction = FocusRequester()
    private val metadataLoaded = mutableStateOf(false)
    private val timelineVisible = mutableStateOf(true)
    private val duration = mutableLongStateOf(120_000L)
    private val settingsVisible = mutableStateOf(true)
    private var seekDelta = 0L
    private var toggles = 0
    private var selectedTrack = "1"
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
    private fun awaitPanelFocus() = awaitState("focused control inside right panel") {
        overlaysFocused && !focusState.hasFocus && focused != "engine"
    }

    private fun awaitState(label: String, condition: () -> Boolean) {
        repeat(100) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            Thread.sleep(30)
        }
        error("$label did not settle (track=$selectedTrack, focus=$focused, panel=${chrome.panels.panel})")
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
    @Composable
    private fun AuditPanelRows() {
        if (panelLoading.value) {
            PlayerPanelAction(Icons.Default.Settings, "Loading control", {},
                mark("panel-loading").focusRequester(panelAction))
        } else {
            PlayerPanelAction(Icons.Default.Settings, "Ready control", {},
                mark("panel-ready").focusRequester(panelAction))
        }
        PlayerPanelAction(Icons.Default.Settings, "Next control", {}, mark("panel-next"))
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
                                chrome = checkNotNull(LocalPlayerChromeState.current)
                                val panels = rememberPlayerPanelNavigation()
                                RequestPlayerOverlayFocus(play, chrome.osd.show && panels.panel == UnifiedPlayerPanel.NONE, panelNavigation = panels)
                                Box(Modifier.fillMaxSize()) {
                                    Box(Modifier.fillMaxSize().focusRequester(rememberPlayerEngineFocus()).then(mark("engine")).focusable())
                                    PublishPlayerOsd(object : PlayerOsdSpec {
                                        @Composable
                                        override fun Content(visible: Boolean, panel: UnifiedPlayerPanel) {
                                            PlayerOverlayVisibility(visible) {
                                                ReferencePlayerBottomContent(modifier = mark("info"), onTogglePlayback = { toggles++ }) {
                                                    ReferencePlayerHeader(
                                                        title = if (metadataLoaded.value) "Loaded title" else "Loading",
                                                        artworkUrl = null,
                                                        contextLine = if (metadataLoaded.value) "2026 • Episode 1" else null,
                                                        subtitle = if (metadataLoaded.value) "Loaded metadata" else null,
                                                    )

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
                                    }, visible = true)
                                    PublishPlayerPanel(PlayerPanelModel(
                                        scrimAlpha = .3f, projectMBrowser = null, audioSession = null,
                                        hasVideo = metadataLoaded.value || chrome.panels.panel == UnifiedPlayerPanel.VIDEO_SECTION, isLive = false,
                                        scalingKey = "fit", aspectKey = "auto", zoomFactor = 1f, speed = 1f,
                                        onScalingChanged = {}, onAspectChanged = {}, onZoomChanged = {}, onSpeedChanged = {},
                                        audioOptions = if (metadataLoaded.value) listOf("1" to "English", "2" to "Deutsch") else emptyList(),
                                        selectedAudio = "1", onAudioSelected = { selectedTrack = it },
                                        subtitleOptions = listOf("off" to "Off"), selectedSubtitle = "off", onSubtitleSelected = {},
                                        sleepTimer = SleepTimerConfig(), onSleepChanged = {},
                                        infoLines = if (metadataLoaded.value) listOf("Codec" to "H.264") else emptyList(),
                                        settingsExtras = { AuditPanelRows() }, audioExtras = { AuditPanelRows() },
                                        hasVideoExtras = true, videoExtras = { AuditPanelRows() },
                                    ))
                                    Box(Modifier.fillMaxSize().onFocusChanged { overlaysFocused = it.hasFocus }.focusGroup()) {
                                        PersistentPlayerOverlays()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            awaitFocus("timeline")
            main {
                check(!chrome.osd.expanded)
                check(!runCatching { settings.requestFocus() }.getOrDefault(false)) { "Hidden action accepted focus" }
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocus("play")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus("settings")
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("timeline")
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("timeline")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocus("settings")
            log("PASS compact startup, hidden buttons reject focus, Down expands and Up returns to compact")

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
            instrumentation.waitForIdleSync()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocus("play")
            log("PASS removed originating button falls back to play")

            main { timelineVisible.value = false }
            awaitState("timeline unregistered") { focusState.seekTarget() == null }
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("info")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(toggles == 1) { "Compact info OK did not toggle playback" } }
            main { timelineVisible.value = true; duration.longValue = 0 }
            instrumentation.waitForIdleSync()
            awaitFocus("info")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocus("play")
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("info")
            main { duration.longValue = 120_000 }
            awaitState("timeline registered") { focusState.seekTarget() != null }
            awaitFocus("timeline")
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("timeline")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocus("play")
            log("PASS compact info without a timeline, OK toggle, and late seek availability restore focus")
            main { timelineVisible.value = false; settingsVisible.value = true }
            instrumentation.waitForIdleSync()
            requestFocus(settings)
            awaitFocus("settings")
            main { metadataLoaded.value = true; timelineVisible.value = true }
            instrumentation.waitForIdleSync()
            Thread.sleep(350)
            main { check(focused == "settings") { "Metadata insertion stole focus: $focused" } }
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("timeline")
            log("PASS late metadata/timeline insertion preserves selected control and D-pad navigation")
            for (panel in listOf(UnifiedPlayerPanel.SETTINGS, UnifiedPlayerPanel.AUDIO, UnifiedPlayerPanel.VIDEO_SECTION)) {
                main {
                    metadataLoaded.value = false
                    panelLoading.value = true
                    chrome.osd.expand()
                    chrome.panels.fromOrigin("bar:Settings", root = true) { chrome.panels.open(panel) }
                    chrome.panels.videoState.section = "output"
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(500)
                requestFocus(panelAction)
                awaitFocus("panel-loading")
                main { metadataLoaded.value = true }
                instrumentation.waitForIdleSync()
                Thread.sleep(100)
                main { check(focused == "panel-loading") { "Metadata update stole panel focus: $focused" } }
                main { panelLoading.value = false }
                instrumentation.waitForIdleSync()
                awaitPanelFocus()
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitPanelFocus()
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                awaitPanelFocus()
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                awaitPanelFocus()
                if (panel == UnifiedPlayerPanel.AUDIO) {
                    requestFocus(panelAction)
                    key(KeyEvent.KEYCODE_DPAD_UP)
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    Thread.sleep(350) // Popup attaches and focuses its selected option on a later frame.
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    Thread.sleep(700)
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitState("popup selects second track") { selectedTrack == "2" }
                    log("PASS audio selector retains popup focus and applies D-pad selection")
                }
                main { chrome.panels.close() }
                instrumentation.waitForIdleSync()
                awaitFocus("settings")
                Thread.sleep(100)
                main { check(focused == "settings") { "Closed panel reclaimed focus" } }
                log("PASS $panel metadata update and replaced control retain panel focus and D-pad navigation")
            }
            main { chrome.osd.close() }
            Thread.sleep(150)
            main { chrome.osd.reveal() }
            awaitFocus("timeline")
            Thread.sleep(1_000)
            main { check(chrome.osd.show && !chrome.osd.expanded) { "An outgoing animation closed the reopened compact OSD" } }
            main { chrome.osd.close() }
            awaitState("compact fade completes") { chrome.osd.mode == PlayerOsdMode.HIDDEN }
            main { chrome.osd.reveal() }
            awaitFocus("timeline")
            main { chrome.osd.close(); chrome.osd.close() }
            awaitState("second close finishes immediately") { chrome.osd.mode == PlayerOsdMode.HIDDEN }
            log("PASS close reversal preserves compact focus; close completion and repeated Back reach hidden")
        } finally { main { activity.finish() } }
    }
}
