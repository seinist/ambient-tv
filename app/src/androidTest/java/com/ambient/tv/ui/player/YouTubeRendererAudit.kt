@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.SavedStateHandle
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.ui.youtube.*
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.ui.player.backend.*
import com.ambient.tv.data.local.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Provider integration test: real format lookup, relay, native player and shared video host. */
internal class YouTubeRendererAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun <T> main(action: () -> T): T {
        val value = AtomicReference<Result<T>>()
        val done = CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post { value.set(runCatching(action)); done.countDown() }
        check(done.await(10, TimeUnit.SECONDS)) {
            val stack = android.os.Looper.getMainLooper().thread.stackTrace.joinToString("\n")
            "Main thread stalled\n$stack"
        }
        return value.get().getOrThrow()
    }

    fun run(args: Bundle) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".renderaudit"))
        val backend = args.getString("backends") ?: "media3"
        val prefs = videoImagePreferences(context)
        main {
            BackgroundPlaybackSession.stop()
            prefs.titleVideoSettings = emptyMap()
            prefs.playbackBackendKey = backend
            prefs.youtubeMaxVideoHeight = args.getString("height")?.toIntOrNull() ?: 1080
            prefs.youtubeAutoplayNextEnabled = false
            prefs.performanceModeKey = "maximum"
            prefs.media3ImageOptions = Media3ImageOptions()
            prefs.vlcImageOptions = VlcImageOptions()
            prefs.mpvImageOptions = MpvImageOptions(scaler = "bilinear", chromaScaler = "bilinear", downscaler = "bilinear", deband = "off", sigmoid = false)
            prefs.mpvVideoOutputModeKey = "auto"
            prefs.mpvVideoDecoderKey = "auto"
            prefs.vlcVideoOutputModeKey = "auto"
            performanceProfile(context)
        }
        val activity = instrumentation.startActivitySync(Intent(context, UiCatalogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        lateinit var model: YouTubePlayerViewModel
        fun await(label: String, limit: Long = 45_000, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + limit
            while (SystemClock.elapsedRealtime() < end) {
                if (main {
                    model.uiState.value.error?.let { error("YouTube: $it") }
                    BackgroundPlaybackSession.snapshot.value.status.error?.let { error("Playback: $it") }
                    predicate()
                }) return
                Thread.sleep(200)
            }
            error("$label timeout; ${main { BackgroundPlaybackSession.snapshot.value.status }}")
        }
        fun diagnostics(): String = when (val h = BackgroundPlaybackSession.activeHandle()) {
            is Media3PlaybackHandle -> "Media3 ${h.player.videoFormat} rendered=${h.player.videoDecoderCounters?.renderedOutputBufferCount} dropped=${h.player.videoDecoderCounters?.droppedBufferCount} audio=${h.player.audioFormat}"
            is VlcPlaybackController -> "VLC ${h.runtimeStats()} seekPending=${h.hasPendingSeek()} nativeTime=${h.player.time}"
            is MpvPlaybackController -> "MPV output=${h.configuredVideoOutput()} decoder=${h.decoder()} drops=${h.frameDropCount()} decodeDrops=${h.decoderFrameDropCount()} audio=${h.audioOutputDriver()}/${h.audioOutputFormat()}"
            else -> "no player"
        }
        val start = SystemClock.elapsedRealtime()
        try {
            main {
                model = YouTubePlayerViewModel(context.applicationContext, SavedStateHandle(mapOf("videoId" to (args.getString("videoId") ?: "aqz-KE-bpKQ"))), SmartTubeEngine(context, prefs), auditPlaybackAvailability(context), auditSourceAvailability(context))
                activity.viewModelStore.put("youtube-renderer-audit", model)
                activity.setContent {
                    AmbientTvTheme {
                        Box(Modifier.fillMaxSize()) {
                            PersistentPlaybackVideoHost(Modifier.fillMaxSize())
                            CompositionLocalProvider(LocalPersistentPlaybackVideoHost provides true) {
                                YouTubePlayerScreen(onBack = { activity.finish() }, onPlayNext = {}, viewModel = model)
                            }
                        }
                    }
                }
            }
            await("requested renderer starts", 75_000) {
                val s = BackgroundPlaybackSession.snapshot.value
                s.backend == backend && s.playing && s.status.positionMs > 1_000
            }
            log("YOUTUBE START_MS=${SystemClock.elapsedRealtime() - start} requested=$backend actual=${main { BackgroundPlaybackSession.snapshot.value.backend }} ${main { diagnostics() }}")
            assertPlaybackVideoMoves(instrumentation, "$backend YouTube initial")
            val sampleCount = args.getString("samples")?.toIntOrNull()?.coerceIn(3, 12) ?: 3
            repeat(sampleCount) { sample ->
                val before = main { BackgroundPlaybackSession.snapshot.value.status.positionMs }
                log("YOUTUBE SAMPLE $sample BEFORE ${main { diagnostics() }}")
                Thread.sleep(10_000)
                val after = main { BackgroundPlaybackSession.snapshot.value.status.positionMs }
                log("YOUTUBE SAMPLE $sample deltaMs=${after - before} DISPLAY_HZ=${main { androidx.core.content.ContextCompat.getDisplayOrDefault(activity).refreshRate }} AFTER ${main { diagnostics() }}")
                if (sample == sampleCount - 1) main {
                    val sourceFps = (BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)?.player?.videoFormat?.frameRate
                    val displayHz = androidx.core.content.ContextCompat.getDisplayOrDefault(activity).refreshRate
                    if (sourceFps != null && sourceFps > 0f) check(displayHz + 1f >= sourceFps) {
                        "Adaptive playback upgraded to $sourceFps fps while HDMI remains at $displayHz Hz"
                    }
                }
                check(after - before in 8_000..12_000) { "YouTube stalled" }
                check(main { BackgroundPlaybackSession.snapshot.value.backend == backend }) { "Renderer silently changed" }
                assertPlaybackVideoMoves(instrumentation, "$backend YouTube sample $sample")
            }
            log("YOUTUBE PAUSE_AND_SEEK_BEGIN")
            main { BackgroundPlaybackSession.pause() }
            await("pause") { !BackgroundPlaybackSession.snapshot.value.playing }
            val forwardMs = maxOf(60_000L, (sampleCount + 2) * 10_000L)
            main { BackgroundPlaybackSession.activeHandle()?.seekTo(forwardMs) }
            Thread.sleep(1_000)
            main { BackgroundPlaybackSession.play() }
            await("forward seek") { BackgroundPlaybackSession.snapshot.value.status.positionMs in (forwardMs - 1_000)..(forwardMs + 5_000) }
            assertPlaybackVideoMoves(instrumentation, "$backend YouTube forward seek")
            main { BackgroundPlaybackSession.activeHandle()?.seekTo(30_000) }
            await("backward seek") { BackgroundPlaybackSession.snapshot.value.status.positionMs in 29_000..35_000 }
            await("backward seek native completion") {
                when (val h = BackgroundPlaybackSession.activeHandle()) {
                    is VlcPlaybackController -> !h.hasPendingSeek() && h.player.time in 29_000..36_000 && h.player.isPlaying
                    else -> BackgroundPlaybackSession.snapshot.value.playing
                }
            }
            assertPlaybackVideoMoves(instrumentation, "$backend YouTube backward seek")
            log("PASS YouTube requested renderer, moving picture, $sampleCount sustained samples, pause, forward/backward seek ${main { diagnostics() }}")
        } finally {
            log("YOUTUBE CLEANUP_BEGIN")
            main { activity.setContent {}; BackgroundPlaybackSession.stop() }
            // Do not let Instrumentation kill the process before async VLC stop/release.
            val releaseDeadline = SystemClock.elapsedRealtime() + 15_000
            fun stoppingVlc() = Thread.getAllStackTraces().keys.any { it.name == "vlc-stop" && it.isAlive }
            while (stoppingVlc() && SystemClock.elapsedRealtime() < releaseDeadline) Thread.sleep(100)
            check(!stoppingVlc()) { "VLC native teardown did not complete within 15 seconds" }
            main { activity.finish() } // drains the native-release callback posted before worker exit
            log("YOUTUBE CLEANUP_END")
        }
    }
}
