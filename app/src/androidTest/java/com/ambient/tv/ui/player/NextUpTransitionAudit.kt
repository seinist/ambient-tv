package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.theme.AmbientTvTheme

/** Real Compose/D-pad hand-offs with a deterministic engine clock and delayed successors. */
class NextUpTransitionAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun settle() { Thread.sleep(650); instrumentation.waitForIdleSync() }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); settle() }

    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".skipaudit"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        class Clock : PlaybackHandle {
            var position = 75_000L
            var duration = 100_000L
            var seeks = 0
            var ended = false
            var released = false
            var playIntent = true
            override fun status() = PlaybackStatus(position, duration, playing = !ended && !released, ended = ended)
            override fun wantsToPlay() = playIntent && !ended && !released
            override fun play() { playIntent = true }
            override fun pause() { playIntent = false }
            override fun seekTo(positionMs: Long) { seeks++; position = positionMs; ended = false }
            override fun release() { released = true }
        }
        val first = Clock()
        val second = Clock().apply { position = 0 }
        val candidate = NextUpCandidate("second", "Second episode", source = "audit", mediaType = "episode")
        val metadata = mutableStateOf(PlaybackMetadata("audit/first", "First episode", source = "audit", mediaId = "first",
            durationMs = 100_000, nextUp = candidate,
            nextUpConfig = NextUpConfig(enabled = true, autoplay = true)))
        val changing = mutableStateOf(false)
        var advances = 0
        var stops = 0
        var persisted = true
        var mounts = 0
        var disposals = 0
        var chrome: PlayerChromeState? = null
        val owner = Any()
        try {
            main {
                BackgroundPlaybackSession.stop()
                activity.setContent {
                    AmbientTvTheme {
                        PersistentPlayerChrome(owner, changing.value) {
                            val currentChrome = LocalPlayerChromeState.current
                            SideEffect { chrome = currentChrome }
                            PlaybackSessionContent(metadata.value,
                                onStopped = { stops++ },
                                onAutoplayChanged = { persisted = it },
                                onPlayNext = {
                                    advances++
                                    changing.value = true
                                    BackgroundPlaybackSession.beginItemTransition()
                                }) {
                                val current = LocalPlaybackMetadata.current!!
                                remember(current.route) {
                                    BackgroundPlaybackSession.acquire(activity, current, "fixture", current.route) {
                                        if (current.mediaId == "first") first else second
                                    }
                                }
                                DisposableEffect(Unit) {
                                    mounts++
                                    onDispose { disposals++ }
                                }
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            settle()
            val originalChrome = chrome
            // Initial focus is Play now. Up reaches the full-width autoplay switch.
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main {
                check(!persisted) { "Autoplay switch did not persist Off" }
                check(BackgroundPlaybackSession.snapshot.value.metadata?.nextUpConfig?.autoplay == false)
                metadata.value = metadata.value.copy(durationMs = 0)
                first.duration = 0 // Native EOF may clear its length before notifying the UI.
                first.position = 100_000; first.ended = true; BackgroundPlaybackSession.tick()
            }
            settle()
            main { check(advances == 0 && stops == 0) { "Disabled autoplay advanced or closed the player" } }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main {
                check(persisted && advances == 1) { "Enabling autoplay at EOF must advance once" }
                BackgroundPlaybackSession.tick()
                check(BackgroundPlaybackSession.snapshot.value.wantsToPlay) { "Outgoing EOF erased the next item's play intent" }
            }
            settle()
            main { check(advances == 1 && stops == 0) }
            val oldGeneration = BackgroundPlaybackSession.snapshot.value.generation
            main {
                metadata.value = PlaybackMetadata("audit/second", "Second episode", source = "audit", mediaId = "second",
                    durationMs = 100_000, successorsLoading = true)
                changing.value = false
            }
            settle()
            main {
                check(mounts == 1 && disposals == 0) { "OSD remounted: $mounts/$disposals" }
                check(chrome === originalChrome)
                check(first.released && BackgroundPlaybackSession.owns(second))
                check(second.seeks == 0) { "A fresh item received an unnecessary renderer-restoration seek" }
                BackgroundPlaybackSession.stopIfCurrent("audit/first", oldGeneration)
                check(BackgroundPlaybackSession.owns(second)) { "Stale Stop killed the incoming item" }
                check(stops == 0 && advances == 1)
                second.position = 100_000; second.ended = true; BackgroundPlaybackSession.tick()
            }
            settle()
            main {
                check(stops == 0 && BackgroundPlaybackSession.snapshot.value.active) { "EOF raced successor loading" }
                metadata.value = metadata.value.copy(successorsLoading = false)
            }
            settle()
            main { check(stops == 1 && !BackgroundPlaybackSession.snapshot.value.active) }
            log("PASS autoplay toggle via D-pad, EOF hand-off once, OSD retained, stale Stop ignored, delayed successor loading")
        } finally {
            main { BackgroundPlaybackSession.stop(); activity.finish() }
        }
    }
}
