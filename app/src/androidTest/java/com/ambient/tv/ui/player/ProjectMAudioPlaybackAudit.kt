package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.ui.common.projectm.ProjectMHdrView
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.ui.tvheadend.MpvTvheadendPlaybackSurface
import com.ambient.tv.ui.tvheadend.VlcTvheadendPlaybackSurface
import com.ambient.tv.tvheadend.domain.TvhPlaybackSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/** Production radio surfaces, real native decoders and real projectM buffers; no synthetic PCM feed. */
internal class ProjectMAudioPlaybackAudit(private val test: Instrumentation, private val log: (String) -> Unit) {
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        test.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun view(root: View): ProjectMHdrView? = when (root) {
        is ProjectMHdrView -> root
        is ViewGroup -> (0 until root.childCount).firstNotNullOfOrNull { view(root.getChildAt(it)) }
        else -> null
    }

    private fun frame(surface: ProjectMHdrView): Pair<Int, Int> {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var status = -1
        main { PixelCopy.request(surface, bitmap, { status = it; latch.countDown() }, Handler(Looper.getMainLooper())) }
        check(latch.await(5, TimeUnit.SECONDS) && status == PixelCopy.SUCCESS)
        try {
            val colors = mutableSetOf<Int>()
            var hash = 1
            for (y in 0 until 180 step 3) for (x in 0 until 320 step 3) {
                val color = bitmap.getPixel(x, y)
                colors += color
                hash = 31 * hash + color
            }
            return colors.size to hash
        } finally { bitmap.recycle() }
    }

    fun run() {
        check(test.targetContext.packageName.endsWith(".projectmaudit"))
        val file = File(test.targetContext.cacheDir, "projectm-audio.wav")
        val rate = 44100
        val bytes = ByteBuffer.allocate(44 + rate * 90 * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(bytes.capacity() - 44)
        repeat(rate * 90) { i ->
            val t = i.toDouble() / rate
            val envelope = .15 + .75 * (0.5 + 0.5 * sin(t * 9))
            bytes.putShort((sin(t * 2 * Math.PI * 220) * envelope * 28000).toInt().toShort())
        }
        file.writeBytes(bytes.array())
        for (backend in listOf("mpv", "vlc")) for (live in listOf(false, true)) {
            main { BackgroundPlaybackSession.stop() }
            val activity = test.startActivitySync(Intent(test.targetContext, PlaybackAuditActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("startDelayMs", 600000L)) as PlaybackAuditActivity
            val source = TvhPlaybackSource(android.net.Uri.fromFile(file).toString(), emptyMap(), "projectM audio", isLive = live)
            var playbackError: String? = null
            main {
                val preferences = videoImagePreferences(activity)
                preferences.audioSettings = preferences.audioSettings.updateForRoute(
                    AudioOptions(output = "bitstream", localFallback = "auto", localAc3 = true), "tv")
                preferences.projectmPresetCategoryKey = "all"
                preferences.projectmPresetIntervalSeconds = 15
                activity.setContent {
                    AmbientTvTheme {
                        // Live starts unknown, exercising late track discovery. Files start audio-only.
                        CompositionLocalProvider(LocalPlaybackMetadata provides PlaybackMetadata(
                            "projectmaudit/$backend/$live", "projectM audio", backend, "audit", "audio",
                            hasVideo = live, isLive = live,
                        )) {
                            PersistentPlayerChrome("projectmaudit/$backend/$live", initialZapOsd = live) {
                                androidx.compose.foundation.layout.Box {
                                    if (backend == "mpv") MpvTvheadendPlaybackSurface(
                                        source, null, emptyMap(), null, emptyList(), null, null,
                                        {}, {}, { playbackError = it }, "off", "auto", { _, _ -> },
                                        10000, backend, { _, _, _ -> }, "direct", "projectm", true,
                                        null, false, { _, _, _ -> }, {},
                                    ) else VlcTvheadendPlaybackSurface(
                                        source, null, emptyMap(), null, emptyList(), null, null,
                                        {}, {}, { playbackError = it }, "off", "auto", { _, _ -> },
                                        "bilinear", 10000, backend, { _, _, _ -> }, "projectm", true,
                                        null, false, { _, _, _ -> },
                                    )
                                    PersistentPlayerOverlays()
                                }
                            }
                        }
                    }
                }
            }
            try {
                val deadline = android.os.SystemClock.elapsedRealtime() + 35000
                var surface: ProjectMHdrView? = null
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    surface = main { view(activity.window.decorView) }
                    val readySurface = surface
                    if (readySurface != null && main { readySurface.holder.surface.isValid &&
                            activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                            BackgroundPlaybackSession.currentStatus()?.playing == true }) break
                    check(main { playbackError } == null) { "Playback error: $playbackError" }
                    Thread.sleep(200)
                }
                val output = checkNotNull(surface) { "$backend live=$live: projectM never composed" }
                Thread.sleep(2500)
                val first = frame(output)
                Thread.sleep(1000)
                val second = frame(output)
                check(first.first > 10 && second.first > 10 && first.second != second.second) {
                    "$backend live=$live: blank or frozen projectM buffers $first / $second"
                }
                main {
                    val handle = BackgroundPlaybackSession.activeHandle()
                    val session = when (handle) {
                        is MpvPlaybackController -> handle.audioSession
                        is VlcPlaybackController -> handle.audioSession.also { check(!handle.audioVisualizationEnabled) }
                        else -> error("Wrong backend: $handle")
                    }
                    check(session.audioOnly && session.outputPlan().mode == AudioOutputMode.PCM)
                    check((BackgroundPlaybackSession.currentStatus()?.positionMs ?: 0) > 1500)
                }
                log("PASS $backend live=$live: PCM, advancing playback, nonblank moving projectM Surface (audibility unverified)")
            } finally {
                main {
                    val retiring = BackgroundPlaybackSession.activeHandle()
                    BackgroundPlaybackSession.stop()
                    if (retiring is VlcPlaybackController) check(retiring.runtimeStats() == null)
                    activity.finish()
                }
            }
        }
        file.delete()
    }
}
