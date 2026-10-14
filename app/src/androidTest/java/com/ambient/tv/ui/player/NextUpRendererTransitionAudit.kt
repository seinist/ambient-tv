@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.ui.player.backend.*
import com.ambient.tv.ui.theme.AmbientTvTheme

/** Real decoders/output paths; local clip, production session/Next Up/OSD state and D-pad. */
class NextUpRendererTransitionAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < until) {
            var passed = false
            main { passed = condition() }
            if (passed) return
            Thread.sleep(150)
        }
        main {
            val handle = BackgroundPlaybackSession.activeHandle()
            if (handle is VlcPlaybackController) {
                for (name in listOf("attachedView", "prepared", "videoRestoreTask", "released", "userVideoDisabled")) {
                    val value = handle.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(handle)
                    log("VLC $name=$value")
                    if (value is android.view.SurfaceView) {
                        log("surface attached=${value.isAttachedToWindow} shown=${value.isShown} valid=${value.holder.surface.isValid} size=${value.width}x${value.height} window=${value.windowVisibility}")
                    }
                }
            }
        }
        error("$label: ${BackgroundPlaybackSession.snapshot.value}")
    }

    fun run(clip: String, backend: String, output: String, releaseSource: Boolean = false) {
        check(instrumentation.targetContext.packageName.endsWith(".skipaudit"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        val item = mutableStateOf(0)
        val autoplay = mutableStateOf(false)
        val changing = mutableStateOf(false)
        var mounts = 0
        var disposals = 0
        var stops = 0
        val owner = Any()
        try {
            main {
                BackgroundPlaybackSession.stop()
                val prefs = videoImagePreferences(activity)
                prefs.titleVideoSettings = emptyMap()
                prefs.mpvVideoOutputModeKey = output
                prefs.vlcVideoOutputModeKey = output
                prefs.mpvVideoDecoderKey = "software"
                prefs.mpvImageOptions = MpvImageOptions(scaler = "bilinear", chromaScaler = "bilinear", downscaler = "bilinear", deband = "off", sigmoid = false)
                activity.setContent {
                    AmbientTvTheme {
                        val scope = rememberCoroutineScope()
                        PersistentPlayerChrome(owner, changing.value) {
                            CompositionLocalProvider(LocalPersistentPlaybackVideoHost provides true) {
                                Box(Modifier.fillMaxSize()) {
                                    PersistentPlaybackVideoHost(Modifier.fillMaxSize())
                                    val metadata = PlaybackMetadata("audit/native/${item.value}", "Clip ${item.value}", source = "audit", mediaId = item.value.toString(),
                                        nextUp = NextUpCandidate((item.value + 1).toString(), "Next clip", source = "audit", mediaType = "episode"),
                                        nextUpConfig = NextUpConfig(enabled = true, leadSeconds = 60, autoplay = autoplay.value))
                                    PlaybackSessionContent(metadata,
                                        onStopped = { stops++ }, onAutoplayChanged = { autoplay.value = it },
                                        onPlayNext = {
                                            if (releaseSource) {
                                                changing.value = true
                                                BackgroundPlaybackSession.beginSourceReplacement(playWhenReadyOverride = true)
                                                scope.launch {
                                                    delay(800) // A live provider opens the replacement subscription asynchronously.
                                                    item.value++
                                                    changing.value = false
                                                }
                                            } else {
                                                BackgroundPlaybackSession.beginItemTransition()
                                                item.value++
                                            }
                                        }) {
                                        when (backend) {
                                            "mpv" -> rememberMpvPlaybackController(activity, clip, 0, emptyMap(), videoOutputMode = output)
                                            "vlc" -> rememberVlcPlaybackController(activity, clip, 0, emptyMap())
                                            else -> {
                                                val activeMetadata = LocalPlaybackMetadata.current
                                                remember(metadata.route) { PlaybackEngineFactory.create(activity, clip, 0, metadata = activeMetadata) }
                                            }
                                        }
                                        DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                                        Box(Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                    }
                }
            }
            await("first playback") { BackgroundPlaybackSession.snapshot.value.status.let { it.playing && it.positionMs > 1_000 && it.durationMs > 6_000 } }
            Thread.sleep(500)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("manual next") { item.value == 1 && BackgroundPlaybackSession.snapshot.value.let { it.metadata?.mediaId == "1" && it.status.playing && it.status.positionMs > 1_000 } }
            main { BackgroundPlaybackSession.seekTo(BackgroundPlaybackSession.snapshot.value.status.durationMs - 700) }
            await("EOF with autoplay disabled") { BackgroundPlaybackSession.snapshot.value.status.ended }
            main { check(item.value == 1 && stops == 0) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            await("Previous reopens ended item") { BackgroundPlaybackSession.snapshot.value.status.let { !it.ended && it.playing && it.positionMs in 500..2_500 } }
            main { check(mounts == 1 && disposals == 0 && stops == 0); BackgroundPlaybackSession.seekTo(4_000) }
            await("seek before Previous") { BackgroundPlaybackSession.snapshot.value.status.positionMs >= 3_500 }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            await("Previous restarts") { BackgroundPlaybackSession.snapshot.value.status.let { !it.ended && it.playing && it.positionMs in 0..2_000 } }
            main {
                autoplay.value = true
                val duration = BackgroundPlaybackSession.snapshot.value.status.durationMs
                BackgroundPlaybackSession.seekTo(duration - 700)
            }
            await("automatic next at EOF") { item.value == 2 && BackgroundPlaybackSession.snapshot.value.let { it.metadata?.mediaId == "2" && it.status.playing && it.status.positionMs > 500 } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_NEXT)
            await("media Next with Next Up focused") { item.value == 3 && BackgroundPlaybackSession.snapshot.value.let { it.metadata?.mediaId == "3" && it.status.playing && it.status.positionMs > 500 } }
            if (backend == "vlc") await("incoming VLC item displays video") {
                ((BackgroundPlaybackSession.activeHandle() as? VlcPlaybackController)?.runtimeStats()?.displayedPictures ?: 0) > 0
            }
            main {
                check(mounts == 1 && disposals == 0 && stops == 0)
                val handle = BackgroundPlaybackSession.activeHandle()
                val effective = when (handle) {
                    is MpvPlaybackController -> "${handle.configuredVideoOutput()} fallback=${handle.accelerationFallback}"
                    is VlcPlaybackController -> "${handle.videoOutputDescription} fallback=${handle.accelerationFallback}"
                    else -> "Media3"
                }
                log("PASS $backend/$output: dialog/media Next, Previous during playback and after EOF, automatic EOF Next, one OSD mount; releaseSource=$releaseSource effective=$effective")
            }
        } finally {
            main { BackgroundPlaybackSession.stop(); activity.finish() }
        }
    }
}
