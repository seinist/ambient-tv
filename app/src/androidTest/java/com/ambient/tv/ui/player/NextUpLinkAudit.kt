package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.ambient.tv.R
import com.ambient.tv.domain.model.PlaybackSegment
import com.ambient.tv.domain.model.PlaybackTimeline
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.theme.AmbientTvTheme

/** Production Next Up + segment overlays, deterministic clock, real D-pad events. */
class NextUpLinkAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun settle() { Thread.sleep(550); instrumentation.waitForIdleSync() }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); settle() }
    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".skipaudit"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        val clock = object : PlaybackHandle {
            var position = 50_000L
            val seeks = mutableListOf<Long>()
            override fun status() = PlaybackStatus(position, 110_000, playing = position < 110_000, ended = position >= 110_000)
            override fun play() = Unit
            override fun pause() = Unit
            override fun seekTo(positionMs: Long) { seeks += positionMs; position = positionMs }
            override fun release() = Unit
        }
        val playerFocus = FocusRequester()
        var playerFocused = false
        var advances = 0
        var stops = 0
        fun display(key: String, autoplay: Boolean, openEnded: Boolean, creditMode: String = "button") {
            val metadata = PlaybackMetadata("audit/$key", "Episode", source = "audit", mediaId = key,
                durationMs = 120_000, timelineOffsetMs = 10_000, creditsSkipMode = creditMode,
                nextUp = NextUpCandidate("next", "Next episode", source = "audit"),
                nextUpConfig = NextUpConfig(enabled = true, leadSeconds = 30, autoplay = autoplay))
            val timeline = PlaybackTimeline(segments = listOf(PlaybackSegment("credits", 80_000, if (openEnded) -1 else 90_000)))
            main {
                BackgroundPlaybackSession.acquire(activity, metadata, "fixture", key) { clock }
                activity.setContent {
                    AmbientTvTheme {
                        PlaybackSessionContent(metadata, onStopped = { stops++ }, onPlayNext = { advances++ }, timeline = timeline) {
                            val navigation = LocalPlaybackNavigation.current
                            SideEffect { navigation?.revealControls = { playerFocus.requestFocus() } }
                            Box(Modifier.fillMaxSize().focusRequester(playerFocus).onFocusChanged { playerFocused = it.isFocused }.focusable())
                        }
                    }
                }
            }
            settle()
        }
        try {
            display("bounded", autoplay = false, openEnded = false)
            main { playerFocus.requestFocus() }
            main { clock.position = 75_000; BackgroundPlaybackSession.tick() }
            settle()
            main { check(!playerFocused) { "Next Up did not appear at the credits marker" }; check(advances == 0) }
            val footerFocus = NextUpAuditFocus(instrumentation)
            footerFocus.assertFocused(R.string.next_up_play_now)
            footerFocus.focus(R.string.player_skip_credits)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main {
                check(clock.seeks.lastOrNull() == 80_000L) { "Integrated credits action missed its end: ${clock.seeks}" }
                check(advances == 0); check(playerFocused) { "Post-credit scene did not regain focus" }
            }
            log("PASS credits trigger Next Up and integrated skip preserves the scene")
            main { clock.position = 110_000; BackgroundPlaybackSession.tick() }
            settle()
            main { check(BackgroundPlaybackSession.snapshot.value.active); check(stops == 0); check(advances == 0) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(advances == 1) }
            log("PASS autoplay off keeps the manual choice at EOF")

            main { clock.position = 75_000 }
            display("open", autoplay = false, openEnded = true, creditMode = "auto")
            settle()
            main { check(advances == 1) { "Credits auto bypassed Next Up autoplay setting" } }
            key(KeyEvent.KEYCODE_BACK)
            main { clock.position = 110_000; BackgroundPlaybackSession.tick() }
            settle()
            main { check(advances == 1); check(!BackgroundPlaybackSession.snapshot.value.active) }
            log("PASS open-ended credit auto respects Next Up settings and Back cancellation")

            main { clock.position = 75_000 }
            display("auto", autoplay = true, openEnded = true, creditMode = "auto")
            main { check(advances == 1) }
            main { clock.position = 110_000; BackgroundPlaybackSession.tick() }
            settle()
            main { check(advances == 2) }
            settle()
            main { check(advances == 2) { "Next Up advanced twice" } }
            log("PASS automatic advance only at actual EOF and only once")

            main { clock.position = 75_000 }
            display("confirmation", autoplay = true, openEnded = true, creditMode = "auto")
            main {
                repeat(3) { BackgroundPlaybackSession.recordAutomaticNextUp(NextUpCandidate("next", "Next episode", source = "audit")) }
                clock.position = 110_000; BackgroundPlaybackSession.tick()
            }
            settle()
            main { check(advances == 2); check(BackgroundPlaybackSession.snapshot.value.active) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(advances == 3) }
            log("PASS still-watching confirmation remains actionable at EOF")

            main { clock.position = 50_000 }
            display("sleep", autoplay = true, openEnded = true, creditMode = "auto")
            main {
                BackgroundPlaybackSession.setSleepTimer(SleepTimerConfig(kind = SleepTimerKind.END_OF_ITEM))
                clock.position = 75_000; BackgroundPlaybackSession.tick()
            }
            settle()
            main { check(advances == 3) }
            log("PASS sleep timer prevents automatic credit advance")
        } finally {
            main { BackgroundPlaybackSession.stop(); activity.finish() }
        }
    }
}
