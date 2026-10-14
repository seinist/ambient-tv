package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ambient.tv.data.local.DeinterlaceCatalog
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.player.backend.MpvBackendAvailability
import com.ambient.tv.ui.player.backend.MpvNativeBridge as Mpv
import com.ambient.tv.ui.player.backend.NnediDeinterlaceWeights
import com.ambient.tv.ui.player.backend.mpvDeinterlaceGraph
import com.ambient.tv.ui.player.backend.vlcDeinterlaceOptions
import com.ambient.tv.ui.theme.AmbientTvTheme
import java.io.File

/** Isolated Android filter execution plus the production grouped D-pad control. No server access. */
class DeinterlaceAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".deintaudit"))
        check(MpvBackendAvailability.nativeRuntimeAvailable)
        val fixture = File(instrumentation.targetContext.cacheDir, "deint-tff.mkv")
        instrumentation.context.assets.open("deint-tff.mkv").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        val weights = NnediDeinterlaceWeights.path(instrumentation.targetContext)
        for (method in DeinterlaceCatalog.mpv) {
            val handle = Mpv.nativeCreate()
            check(handle != 0L)
            try {
                val options = mutableMapOf("vo" to "null", "ao" to "null", "hwdec" to "no", "audio" to "no",
                    "idle" to "yes", "keep-open" to "yes", "deinterlace" to if (method.key == "auto") "auto" else "no")
                mpvDeinterlaceGraph(method.key, false, weights)?.let { options["vf"] = "lavfi=[$it]" }
                options.forEach { (name, value) -> check(Mpv.nativeSetOptionString(handle, name, value) >= 0) { "$name=$value" } }
                check(Mpv.nativeInitialize(handle) >= 0)
                check(Mpv.nativeCommand(handle, arrayOf("loadfile", fixture.absolutePath)) >= 0)
                val deadline = SystemClock.elapsedRealtime() + 15_000
                var progress = 0.0
                while (SystemClock.elapsedRealtime() < deadline && progress < 0.4) {
                    repeat(50) { Mpv.nativePollEvent(handle) }
                    check(Mpv.nativePlaybackError(handle) >= 0) { "MPV ${method.key} rejected filter" }
                    progress = Mpv.nativeGetPropertyDouble(handle, "time-pos", 0.0)
                    Thread.sleep(20)
                }
                check(progress >= 0.4) { "MPV ${method.key} stalled at $progress" }
                check(Mpv.nativeGetPropertyDouble(handle, "video-out-params/w", 0.0) > 0) { "No filtered frames: ${method.key}" }
                log("PASS MPV ${method.key}: Android native filter produced frames")
            } finally { Mpv.nativeDestroy(handle) }
        }
        auditVlc(fixture)
        auditSelector()
    }

    private fun auditVlc(fixture: File) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, UiCatalogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as UiCatalogActivity
        lateinit var surface: android.view.SurfaceView
        instrumentation.runOnMainSync {
            surface = android.view.SurfaceView(activity)
            activity.setContentView(surface)
        }
        val surfaceDeadline = SystemClock.elapsedRealtime() + 5000
        while (!surface.holder.surface.isValid && SystemClock.elapsedRealtime() < surfaceDeadline) Thread.sleep(50)
        check(surface.holder.surface.isValid)
        try {
        for (method in DeinterlaceCatalog.vlc) {
            val options = arrayListOf("--no-audio", "--verbose=2", "--avcodec-hw=none")
            options.addAll(vlcDeinterlaceOptions("on", method.key))
            val lib = org.videolan.libvlc.LibVLC(instrumentation.targetContext, options)
            val player = org.videolan.libvlc.MediaPlayer(lib)
            val media = org.videolan.libvlc.Media(lib, android.net.Uri.fromFile(fixture))
            try {
                instrumentation.runOnMainSync {
                    player.vlcVout.setVideoView(surface)
                    player.vlcVout.attachViews()
                    player.vlcVout.setWindowSize(surface.width, surface.height)
                }
                media.setHWDecoderEnabled(false, false)
                player.media = media
                player.play()
                val deadline = SystemClock.elapsedRealtime() + 8000
                var displayed = 0
                while (SystemClock.elapsedRealtime() < deadline && displayed < 3) {
                    val stats = media.stats
                    displayed = stats?.displayedPictures?.toInt() ?: 0
                    Thread.sleep(50)
                }
                check(displayed >= 3) { "VLC ${method.key}: no filtered output" }
                if (method.key == "phosphor") {
                    // Regression: Flush used to clear the IVTC union member,
                    // corrupting Phosphor's chroma mode during seek/stop.
                    player.setTime(0L, true)
                    val beforeSeek = displayed
                    val seekDeadline = SystemClock.elapsedRealtime() + 8000
                    while (SystemClock.elapsedRealtime() < seekDeadline && displayed < beforeSeek + 5) {
                        Thread.sleep(50)
                        displayed = media.stats?.displayedPictures?.toInt() ?: 0
                    }
                    check(displayed >= beforeSeek + 5) { "VLC phosphor stalled after seek/flush" }
                }
                log("PASS VLC ${method.key}: Android surface displayed $displayed frames")
            } finally {
                player.stop()
                instrumentation.runOnMainSync { player.vlcVout.detachViews() }
                player.release(); media.release(); lib.release()
            }
        }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        Thread.sleep(180)
        instrumentation.waitForIdleSync()
    }

    private fun auditSelector() {
        val selected = mutableStateOf("auto")
        val backend = mutableStateOf("mpv")
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, UiCatalogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as UiCatalogActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    AmbientTvTheme {
                        Column(Modifier.padding(40.dp)) {
                            DeinterlaceMethodChoice(backend.value, selected.value, onSelected = { selected.value = it })
                        }
                    }
                }
            }
            Thread.sleep(600)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(selected.value == "bwdif") { "Grouped selection failed: ${selected.value}" }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            repeat(DeinterlaceCatalog.mpv.size - 2) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(selected.value == "ivtc") { "Long group navigation failed: ${selected.value}" }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_BACK)
            check(selected.value == "ivtc")
            log("PASS MPV grouped selector: skips headings, scrolls to IVTC, Back preserves choice")
            instrumentation.runOnMainSync { backend.value = "vlc"; selected.value = "auto" }
            Thread.sleep(250)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(selected.value == "discard") { "VLC group selection failed: ${selected.value}" }
            log("PASS VLC grouped selector: restored trigger focus and selected Discard")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
