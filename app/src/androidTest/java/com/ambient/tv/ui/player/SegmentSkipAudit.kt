package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.ambient.tv.domain.model.PlaybackSegment
import com.ambient.tv.domain.model.PlaybackTimeline
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.theme.AmbientTvTheme
import java.io.File

/** Real D-pad events on the shared production overlay, with a deterministic playback clock. */
class SegmentSkipAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun settle() { Thread.sleep(350); instrumentation.waitForIdleSync() }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); settle() }
    private fun screenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.filesDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".skipaudit"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        val clock = object : PlaybackHandle {
            var position = 5_000L
            var playing = false
            val seeks = mutableListOf<Long>()
            override fun status() = PlaybackStatus(position, 100_000, playing)
            override fun play() { playing = true }
            override fun pause() { playing = false }
            override fun seekTo(positionMs: Long) { seeks += positionMs; position = positionMs }
            override fun release() = Unit
        }
        val metadata = mutableStateOf(PlaybackMetadata("audit/segments", "Segments", source = "audit", mediaId = "segments",
            durationMs = 110_000, timelineOffsetMs = 10_000, introSkipMode = "button", creditsSkipMode = "button"))
        val navigation = PlaybackNavigationState().apply { timeline = PlaybackTimeline(segments = listOf(
            PlaybackSegment("intro", 10_000, 40_000), PlaybackSegment("credits", 80_000, 90_000))) }
        val playerFocus = FocusRequester()
        var playerFocused = false
        var nextCount = 0
        var savedIntroMode: String? = null
        try {
            main {
                BackgroundPlaybackSession.acquire(activity, metadata.value, "fixture", "segments") { clock }
                activity.setContent {
                    AmbientTvTheme {
                        CompositionLocalProvider(LocalSegmentSkipControls provides SegmentSkipControls(
                            introMode = "button",
                            creditsMode = "button",
                            onIntroModeChanged = { savedIntroMode = it },
                        )) {
                            SegmentSkipOverlay(metadata.value, "segments", navigation, null, false, { nextCount++ }, true) {
                                Box(Modifier.fillMaxSize().focusRequester(playerFocus).onFocusChanged { playerFocused = it.isFocused }.focusable())
                            }
                        }
                        SideEffect { navigation.revealControls = { navigation.chromeVisible = true; playerFocus.requestFocus() } }
                    }
                }
            }
            settle()
            main { playerFocus.requestFocus() }
            settle()
            main { check(playerFocused) { "The prompt stole player focus" } }
            key(KeyEvent.KEYCODE_DPAD_UP)
            main { check(!playerFocused) { "Up did not focus skip" } }
            screenshot("segment-skip-always.png")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main {
                check(savedIntroMode == "auto") { "Always skip did not persist auto mode: $savedIntroMode" }
                check(clock.seeks == listOf(30_000L)) { "Wrong transcode-offset seek: ${clock.seeks}" }
                check(playerFocused) { "Skip did not return player focus" }
            }
            log("PASS intro always-skip persists auto mode, seeks, and restores focus")
            main { clock.position = 5_000; navigation.chromeVisible = false; BackgroundPlaybackSession.tick(); playerFocus.requestFocus() }
            settle()
            key(KeyEvent.KEYCODE_DPAD_UP)
            main { clock.position = 35_000; BackgroundPlaybackSession.tick() }
            settle()
            main { check(playerFocused) { "Expired prompt left focus stranded" } }
            log("PASS focus returns when segment expires")
            main { clock.position = 75_000; navigation.chromeVisible = false; BackgroundPlaybackSession.tick(); playerFocus.requestFocus() }
            settle()
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(clock.seeks.last() == 80_000L) { "Credits skipped the post-credit scene" } }
            log("PASS credits stop before post-credit scene")
            main {
                navigation.timeline = PlaybackTimeline(segments = listOf(PlaybackSegment("credits", 80_000, -1)))
                clock.position = 75_000; navigation.chromeVisible = false; BackgroundPlaybackSession.tick(); playerFocus.requestFocus()
            }
            settle()
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_BACK)
            main { check(nextCount == 0); check(playerFocused) }
            log("PASS Back dismisses without changing episode")
            main { clock.position = 50_000; BackgroundPlaybackSession.tick() }
            settle()
            main { clock.position = 75_000; navigation.chromeVisible = false; BackgroundPlaybackSession.tick(); playerFocus.requestFocus() }
            settle()
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(nextCount == 1) }
            log("PASS open-ended credits use next item")
            main {
                metadata.value = metadata.value.copy(creditsSkipMode = "off", introSkipMode = "auto")
                navigation.timeline = PlaybackTimeline(segments = listOf(PlaybackSegment("intro", 95_000, 100_000)))
                clock.position = 87_000; clock.playing = true; navigation.chromeVisible = false
                BackgroundPlaybackSession.updateMetadata(metadata.value); BackgroundPlaybackSession.tick()
            }
            settle()
            main { check(clock.seeks.last() == 90_000L) }
            val count = clock.seeks.size
            main { clock.position = 87_000; BackgroundPlaybackSession.tick() }
            settle()
            main { check(clock.seeks.size == count) { "Auto skip repeated after rewind" } }
            log("PASS auto skip only once after rewind")
            main {
                metadata.value = metadata.value.copy(introSkipMode = "off")
                BackgroundPlaybackSession.updateMetadata(metadata.value)
                navigation.chromeVisible = false; playerFocus.requestFocus()
            }
            settle()
            key(KeyEvent.KEYCODE_DPAD_UP)
            main { check(playerFocused) { "Disabled skip captured D-pad" }; check(clock.seeks.size == count) }
            log("PASS disabled mode leaves D-pad unchanged")
            main {
                metadata.value = metadata.value.copy(introSkipMode = "button")
                BackgroundPlaybackSession.updateMetadata(metadata.value)
                navigation.skipOverlayBlocked = true
            }
            settle()
            key(KeyEvent.KEYCODE_DPAD_UP)
            main { check(playerFocused) { "Skip stole focus while menu was open" } }
            log("PASS open menus retain focus")
        } finally {
            main { BackgroundPlaybackSession.stop(); activity.finish() }
        }
    }
}
