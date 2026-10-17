package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ambient.tv.R
import com.ambient.tv.data.local.VideoImageCatalog
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VideoAccelerationRuntime
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/** Real MPV plus the production video panel; exercises remote selection, not a mock catalog. */
class MpvUpscalerAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < end) {
            if (main(predicate)) return
            Thread.sleep(150)
        }
        error("Timeout: $label")
    }
    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        Thread.sleep(120)
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    private fun nativeErrors(): List<String> = instrumentation.uiAutomation.executeShellCommand(
        "logcat -d --pid=${android.os.Process.myPid()} -s AmbientMPV",
    ).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readLines() }
    }.filter { line ->
        line.contains("vo/") && Regex("failed|error|status=0|unsupported value", RegexOption.IGNORE_CASE).containsMatchIn(line)
    }
    private fun openScaler() {
        val label = instrumentation.targetContext.getString(R.string.player_upscaler)
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString()?.contains(label) == true) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { found -> return found } }
            return null
        }
        repeat(30) {
            var node = instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                Thread.sleep(700)
                return
            }
            Thread.sleep(200)
        }
        error("No clickable upscaler in production panel (label=$label)")
    }
    fun run(clip: String, output: String, targets: List<String>? = null) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".upscaleraudit"))
        main {
            videoImagePreferences(context).apply {
                playbackPerformanceModeKey = "maximum"
                titleVideoSettings = emptyMap()
                resolutionVideoProfiles = emptyMap()
                mpvImageOptions = com.ambient.tv.data.local.MpvImageOptions()
                media3ImageOptions = com.ambient.tv.data.local.Media3ImageOptions(enabled = true)
            }
        }
        val file = File(context.cacheDir, "upscale.mp4")
        URL(clip).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        // Output controls run this probe before offering Vulkan; mirror that precondition.
        kotlinx.coroutines.runBlocking { VideoAccelerationRuntime.refresh(context) }
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackAuditActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("backend", "mpv")
            .putExtra("uri", android.net.Uri.fromFile(file).toString()).putExtra("startPositionMs", 0L)
            .putExtra("output", output).putExtra("decoder", "software").putExtra("scalingPanel", true)) as PlaybackAuditActivity
        try {
            await("MPV playback") { (BackgroundPlaybackSession.activeHandle() as? MpvPlaybackController)?.positionMs()?.let { it > 500 } == true }
            val controller = main { BackgroundPlaybackSession.activeHandle() as MpvPlaybackController }
            await("native output") { controller.configuredVideoOutput() != null }
            val nativeOutput = main { controller.configuredVideoOutput() }
            log("CAPABILITIES ${VideoAccelerationRuntime.current(context)}")
            check(nativeOutput == if (output == "vulkan") "gpu-next / Vulkan" else "gpu") { "Requested $output but running $nativeOutput" }
            await("GPU/SDR capabilities") { controller.supportsGpuFilters && controller.sdrImageProcessing }
            log("START output=$output native=$nativeOutput")
            screenshot("upscaler-$output-panel")
            val keys = VideoImageCatalog.mpvScalers.keys.toList()
            val renderFailures = mutableListOf<String>()
            for (target in targets ?: listOf("bicubic", "ewa_lanczossharp", "nnedi3_nns16", "ravu_lite_r2", "fsrcnnx8", "artcnn16", "artcnn32", "artcnn16_ds", "artcnn16_dn", "artcnn32_ds", "artcnn32_dn", "nis", "fsr")) {
                val errorsBefore = nativeErrors().size
                val current = main { controller.imageOptions.scaler }
                openScaler()
                val delta = keys.indexOf(target) - keys.indexOf(current)
                repeat(kotlin.math.abs(delta)) { key(if (delta > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
                screenshot("upscaler-$output-$target-menu")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("panel selects $target") { controller.imageOptions.scaler == target }
                await("native applies $target") {
                    if (target in VideoImageCatalog.mpvShaders) controller.activeShaderPreset() == target
                    else controller.activeScaler() == target
                }
                log("PASS panel/native $output $target; scale=${main { controller.activeScaler() }} shader=${main { controller.activeShaderPreset() }} warnings=${main { controller.imageOptionWarnings }}")
                main { activity.showScalingPanel = false }
                assertPlaybackVideoMoves(instrumentation, "$output $target")
                val errors = nativeErrors().drop(errorsBefore)
                if (errors.isNotEmpty()) {
                    renderFailures += target
                    log("FAIL shader rendering $target: ${errors.take(3).joinToString()}")
                } else log("PASS moving video without native shader errors: $target")
                screenshot("upscaler-$output-$target-video")
                main { activity.showScalingPanel = true }
                Thread.sleep(500)
            }
            main { controller.audioSession.performanceHint = true; controller.audioSession.showFpsOverlay = true }
            var previous = controller
            val metadata = main { checkNotNull(BackgroundPlaybackSession.snapshot.value.metadata) }
            for ((mode, decoder) in listOf("direct" to "mediacodec", "gpu" to "mediacodec", "gpu" to "auto", output to "auto").distinct()) {
                main { BackgroundPlaybackSession.setVideoSettings(metadata, PlayerVideoSettings(mpvOutput = mode, mpvDecoder = decoder)) }
                await("restart $mode/$decoder") {
                    (BackgroundPlaybackSession.activeHandle() as? MpvPlaybackController)?.let {
                        it !== previous && it.isPlaying()
                    } == true
                }
                previous = main { BackgroundPlaybackSession.activeHandle() as MpvPlaybackController }
                main { check(previous.audioSession.performanceHint && previous.audioSession.showFpsOverlay) }
                await("output capability $mode") { previous.supportsGpuFilters == (mode != "direct") }
                if (mode != "direct") { openScaler(); screenshot("upscaler-after-$mode-$decoder"); key(KeyEvent.KEYCODE_BACK) }
                log("PASS output/decoder $mode/$decoder: stats and FPS retained, gpu=${main { previous.supportsGpuFilters }}")
            }
            main { activity.backend = "media3" }
            await("Media3 renderer") { BackgroundPlaybackSession.activeHandle() is Media3PlaybackHandle }
            main { check(playbackProfileAudio(BackgroundPlaybackSession.activeHandle())?.performanceHint == true) }
            screenshot("upscaler-media3-panel")
            main { activity.backend = "mpv" }
            await("MPV renderer") { (BackgroundPlaybackSession.activeHandle() as? MpvPlaybackController)?.sdrImageProcessing == true }
            main { check(playbackProfileAudio(BackgroundPlaybackSession.activeHandle())?.performanceHint == true) }
            openScaler()
            screenshot("upscaler-after-media3-mpv")
            val restored = main { BackgroundPlaybackSession.activeHandle() as MpvPlaybackController }
            val delta = keys.indexOf("nnedi3_nns16") - keys.indexOf(main { restored.imageOptions.scaler })
            repeat(kotlin.math.abs(delta)) { key(if (delta > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("NNEDI3 after renderer switch") { restored.imageOptions.scaler == "nnedi3_nns16" && restored.activeShaderPreset() == "nnedi3_nns16" }
            log("PASS MPV -> Media3 -> MPV: stats retained and NNEDI3 selectable/applied again")
            check(renderFailures.isEmpty()) { "Native shader failures despite accepted selection: $renderFailures" }
        } finally {
            screenshot("upscaler-$output-final")
            main { BackgroundPlaybackSession.stop(); activity.finish() }
        }
    }
}
