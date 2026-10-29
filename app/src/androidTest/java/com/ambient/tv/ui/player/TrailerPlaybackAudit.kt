package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.lifecycle.SavedStateHandle
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.ui.youtube.YouTubePlayerScreen
import com.ambient.tv.ui.youtube.YouTubePlayerViewModel
import com.ambient.tv.ui.youtube.YouTubePrefsEntryPoint
import com.ambient.tv.youtube.SmartTubeEngine
import dagger.hilt.android.EntryPointAccessors

/** Real YouTube resolution and production trailer player, without requiring a TMDB account. */
class TrailerPlaybackAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    fun run(videoId: String) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".traileraudit")) { "Use the isolated .traileraudit package" }
        val preferences = EntryPointAccessors.fromApplication(context.applicationContext, YouTubePrefsEntryPoint::class.java).prefs()
        preferences.playbackBackendKey = "media3"
        preferences.youtubeEnabled = false // Trailer playback must work independently of the YouTube menu.
        preferences.nextUpEnabled = true
        preferences.youtubeAutoplayNextEnabled = true
        val activity = instrumentation.startActivitySync(
            Intent(context, UiCatalogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as UiCatalogActivity
        lateinit var model: YouTubePlayerViewModel
        fun main(action: () -> Unit) {
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        fun await(label: String, timeout: Long = 60_000, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) {
                var ready = false
                main {
                    model.uiState.value.error?.let { error("YouTube resolution: $it") }
                    BackgroundPlaybackSession.snapshot.value.status.error?.let { error("Playback: $it") }
                    ready = predicate()
                }
                if (ready) return
                Thread.sleep(200)
            }
            error("$label timed out")
        }
        try {
            main {
                model = YouTubePlayerViewModel(context.applicationContext, SavedStateHandle(mapOf("videoId" to videoId, "trailer" to true)), SmartTubeEngine(context.applicationContext, preferences), auditPlaybackAvailability(context), auditSourceAvailability(context), preferences)
                activity.viewModelStore.put("trailer-audit", model)
                activity.setContent {
                    AmbientTvTheme {
                        YouTubePlayerScreen(onBack = { activity.finish() }, onPlayNext = { error("Trailer autoplayed another video") }, viewModel = model)
                    }
                }
            }
            await("video and audio formats") {
                val handle = BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle
                handle?.player?.videoFormat != null && handle.player.audioFormat != null && handle.player.videoSize.width > 0 && handle.player.isPlaying
            }
            var start = 0L
            main {
                val snapshot = BackgroundPlaybackSession.snapshot.value
                check(snapshot.metadata?.isTrailer == true)
                check(snapshot.metadata?.nextUp == null && snapshot.metadata?.nextUpConfig?.enabled == false)
                check(model.uiState.value.playback?.resumeMs == 0L)
                start = snapshot.status.positionMs
            }
            await("advancing playback", 20_000) { BackgroundPlaybackSession.snapshot.value.status.positionMs > start + 3_000 }
            log("PASS trailer resolves and plays with YouTube menu disabled; video/audio formats present, position advances, Next Up disabled")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.filesDir, "trailer-playback.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            main { activity.finish() }
            await("trailer stopped on leaving", 10_000) { !BackgroundPlaybackSession.snapshot.value.active }
            log("PASS leaving trailer stops playback")
        } finally {
            main { BackgroundPlaybackSession.stop(); activity.finish() }
        }
    }
}
