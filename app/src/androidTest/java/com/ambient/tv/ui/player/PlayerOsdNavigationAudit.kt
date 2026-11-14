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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import com.ambient.tv.domain.model.PlaybackTimeline
import com.ambient.tv.domain.model.VideoChapter
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
    private lateinit var activity: UiCatalogActivity
    private val chapterNavigation = PlaybackNavigationState()
    private val chapterPlayer = mutableStateOf(OsdPlayer.YOUTUBE)
    private val chapters = List(20) { index -> VideoChapter(index * 6_000L, "Audit chapter ${index + 1}") }
    private var chapterSeekCalls = 0
    private var chapterSeekTarget: Long? = null
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

    /** Observe the production chapter cards without repairing their focus from the audit. */
    private fun focusedChapterTitle(): String? {
        var focusedTitle: String? = null
        fun text(node: SemanticsNode): List<String> =
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
                node.children.flatMap(::text)
        fun visitNode(node: SemanticsNode) {
            if (node.config.getOrElse(SemanticsProperties.Focused) { false } &&
                node.config.contains(SemanticsActions.OnClick)) {
                focusedTitle = text(node).firstOrNull { title -> chapters.any { it.title == title } }
            }
            if (focusedTitle == null) node.children.forEach(::visitNode)
        }
        fun visitView(view: android.view.View) {
            val owner = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
                ?.invoke(view) as? SemanticsOwner
            if (owner != null) visitNode(owner.rootSemanticsNode)
            if (focusedTitle == null && view is android.view.ViewGroup) repeat(view.childCount) { visitView(view.getChildAt(it)) }
        }
        visitView(activity.window.decorView)
        return focusedTitle
    }

    private fun awaitChapterFocus(expected: String? = null) = awaitState("chapter card focus${expected?.let { " on $it" }.orEmpty()}") {
        val title = focusedChapterTitle()
        chapterNavigation.chaptersOpen && title != null && (expected == null || title == expected)
    }

    private fun openChapters() {
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitChapterFocus()
    }

    private fun assertFoldedToAction(expected: String) {
        awaitFocus(expected)
        // The old implementation could keep focus on a card until its 350 ms exit ended.
        Thread.sleep(450)
        main {
            check(!chapterNavigation.chaptersOpen && chrome.osd.show && chrome.osd.expanded)
            check(focused == expected && focusState.hasFocus) { "Chapter fold lost action focus after exit: $focused" }
        }
    }

    private fun auditChapterNavigation(settings: FocusRequester) {
        val preferences = videoImagePreferences(instrumentation.targetContext)
        for (player in listOf(OsdPlayer.YOUTUBE, OsdPlayer.MEDIA)) {
            main {
                chapterPlayer.value = player
                chapterNavigation.timeline = PlaybackTimeline(chapters = chapters)
                chapterNavigation.onChapterSeek = { target -> chapterSeekCalls++; chapterSeekTarget = target }
                setOsdButtonVisible(preferences, player, OsdButton.CHAPTERS, visible = false)
            }
            instrumentation.waitForIdleSync()
            requestFocus(settings)
            // Down opens chapters independently of whether their optional toolbar button exists.
            openChapters()
            key(KeyEvent.KEYCODE_DPAD_UP)
            assertFoldedToAction("settings")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            awaitFocus("play")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus("settings")
            openChapters()
            key(KeyEvent.KEYCODE_BACK)
            assertFoldedToAction("settings")

            main { setOsdButtonVisible(preferences, player, OsdButton.CHAPTERS, visible = true) }
            instrumentation.waitForIdleSync()
            openChapters()
            key(KeyEvent.KEYCODE_DPAD_UP)
            assertFoldedToAction("settings")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus("play")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            awaitFocus("settings")
            log("PASS $player chapters return to the originating action on Up/Back with visible or hidden chapter button")

            main { setOsdButtonVisible(preferences, player, OsdButton.CHAPTERS, visible = false) }
            instrumentation.waitForIdleSync()
            openChapters()
            var adjacentIndex = 0
            var moveLeft = false
            main {
                val selected = chapters.indexOfFirst { it.title == focusedChapterTitle() }
                check(selected >= 0)
                moveLeft = selected == chapters.lastIndex
                adjacentIndex = if (moveLeft) selected - 1 else selected + 1
                chapterSeekCalls = 0
                chapterSeekTarget = null
            }
            key(if (moveLeft) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitChapterFocus(chapters[adjacentIndex].title)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus("timeline")
            main {
                check(chapterSeekCalls == 1 && chapterSeekTarget == chapters[adjacentIndex].startMs)
                check(!chapterNavigation.chaptersOpen && !chrome.osd.expanded && chrome.osd.show)
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocus("settings")
            log("PASS $player chapter selection seeks once and returns to the compact timeline")

            openChapters()
            var currentChapterIndex = 0
            main {
                currentChapterIndex = chapters.indexOfFirst { it.title == focusedChapterTitle() }
                check(currentChapterIndex >= 0)
            }
            // Move far enough for LazyRow to detach the currently playing chapter's card.
            val lastBrowsedIndex = if (currentChapterIndex < chapters.size / 2) chapters.lastIndex else 0
            val direction = if (lastBrowsedIndex > currentChapterIndex) 1 else -1
            var browsedIndex = currentChapterIndex
            while (browsedIndex != lastBrowsedIndex) {
                key(if (direction > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                browsedIndex += direction
                awaitChapterFocus(chapters[browsedIndex].title)
            }
            val foldedAt = android.os.SystemClock.uptimeMillis()
            key(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("settings")
            // Re-enter while AnimatedVisibility still retains the departing chapter row.
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            val reopenDelayMs = android.os.SystemClock.uptimeMillis() - foldedAt
            awaitChapterFocus(chapters[currentChapterIndex].title)
            Thread.sleep(450)
            awaitChapterFocus(chapters[currentChapterIndex].title)
            key(KeyEvent.KEYCODE_DPAD_UP)
            assertFoldedToAction("settings")
            log("PASS $player chapter reopen after $reopenDelayMs ms restores the offscreen current card and retains focus")

            openChapters()
            main { settingsVisible.value = false }
            instrumentation.waitForIdleSync()
            key(KeyEvent.KEYCODE_DPAD_UP)
            assertFoldedToAction("play")
            main { settingsVisible.value = true }
            instrumentation.waitForIdleSync()
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus("settings")
            log("PASS $player chapter return falls back to Play when the originating action disappears")
        }
        main { chapterNavigation.timeline = PlaybackTimeline() }
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
        val preferences = videoImagePreferences(instrumentation.targetContext)
        val originalChapterVisibility = listOf(OsdPlayer.YOUTUBE, OsdPlayer.MEDIA).associateWith {
            osdButtonVisible(OsdButton.CHAPTERS, it, preferences.osdHiddenButtons)
        }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        val play = FocusRequester()
        val settings = FocusRequester()
        try {
            main {
                activity.setContent {
                    CompositionLocalProvider(LocalHandheldUi provides false, LocalPlaybackNavigation provides chapterNavigation) {
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
                                                    PlayerControlBar(player = chapterPlayer.value, leftContent = {
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
            auditChapterNavigation(settings)
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
        } finally { main {
            originalChapterVisibility.forEach { (player, visible) ->
                setOsdButtonVisible(preferences, player, OsdButton.CHAPTERS, visible)
            }
            activity.finish()
        } }
    }
}
