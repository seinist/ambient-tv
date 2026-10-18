@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VlcImageOptions
import com.ambient.tv.ui.player.backend.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One process per case: a native hang must not contaminate the rest of the matrix. */
internal class RendererMatrixAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        val done = CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            result.set(runCatching(action)); done.countDown()
        }
        check(done.await(10, TimeUnit.SECONDS)) { "Main thread stalled" }
        return result.get().getOrThrow()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < end) {
            if (main(condition)) return
            Thread.sleep(100)
        }
        error("$label timeout: ${main { BackgroundPlaybackSession.snapshot.value.status }}")
    }

    private fun surfaces(view: View): List<SurfaceView> = when (view) {
        is SurfaceView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { surfaces(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun evidence(): String = when (val h = BackgroundPlaybackSession.activeHandle()) {
        is Media3PlaybackHandle -> {
            val c = h.player.videoDecoderCounters?.also { it.ensureUpdated() }
            "media3 format=${h.player.videoFormat} rendered=${c?.renderedOutputBufferCount} dropped=${c?.droppedBufferCount} audio=${h.player.audioFormat}"
        }
        is MpvPlaybackController -> "mpv output=${h.configuredVideoOutput()} decoder=${h.decoder()} drops=${h.frameDropCount()} decodeDrops=${h.decoderFrameDropCount()} hdr=${h.sourceHdrStandard} audio=${h.audioOutputDriver()}/${h.audioOutputFormat()} fallback=${h.accelerationFallback}"
        is VlcPlaybackController -> "vlc output=${h.videoOutputDescription} stats=${h.runtimeStats()} fallback=${h.accelerationFallback} pendingSeek=${h.hasPendingSeek()} nativeTime=${h.player.time}"
        else -> "no active engine"
    }

    private fun logMpvColour() {
        val controller = main { BackgroundPlaybackSession.activeHandle() as? MpvPlaybackController } ?: return
        val done = CountDownLatch(1)
        val worker = controller.javaClass.getDeclaredField("worker").apply { isAccessible = true }
            .get(controller) as android.os.Handler
        worker.post {
            try {
                val engine = controller.javaClass.getDeclaredField("engine").apply { isAccessible = true }.get(controller)!!
                val getter = engine.javaClass.getDeclaredMethod("getPropertyString", String::class.java).apply { isAccessible = true }
                val names = listOf("video-dec-params/gamma", "video-params/gamma", "video-out-params/gamma",
                    "video-params/primaries", "video-target-params/gamma", "video-target-params/primaries",
                    "video-target-params/sig-peak", "video-target-params/pixelformat", "target-colorspace-hint",
                    "target-trc", "sdr-adjust-gamma", "treat-srgb-as-power22")
                val surface = engine.javaClass.getDeclaredField("attachedSurface").apply { isAccessible = true }.get(engine) as? android.view.Surface
                log("COLOUR surface=${surface?.let { VulkanNativeProbe.dataSpace(it) }} " + names.joinToString(" ") { "$it=${getter.invoke(engine, it)}" })
            } finally { done.countDown() }
        }
        check(done.await(10, TimeUnit.SECONDS))
    }

    private fun droppedFrames(): Long? = when (val h = BackgroundPlaybackSession.activeHandle()) {
        is Media3PlaybackHandle -> h.player.videoDecoderCounters?.also { it.ensureUpdated() }?.droppedBufferCount?.toLong()
        is MpvPlaybackController -> h.frameDropCount()
        is VlcPlaybackController -> h.runtimeStats()?.lostPictures?.toLong()
        else -> null
    }

    fun run(file: File, args: Bundle) {
        check(instrumentation.targetContext.packageName.endsWith(".renderaudit"))
        val backend = args.getString("backends") ?: "media3"
        val context = instrumentation.targetContext
        val staleShader = if (args.getString("staleShader") == "true") {
            File(context.noBackupFilesDir, "mpv-shaders/quality_adaptive_44e81f12.glsl").apply {
                parentFile!!.mkdirs()
                writeText("//!HOOK OUTPUT\n//!BIND HOOKED\nvec4 hook() { return vec4(0.0, 0.0, 1.0, 1.0); }\n")
            }
        } else null
        val caps = kotlinx.coroutines.runBlocking { VideoAccelerationRuntime.refresh(context) }
        log("CAPS $caps DISPLAY_HDR=${HdrSupport.displayStandards(context)} SDK=${android.os.Build.VERSION.SDK_INT}")
        main {
            val p = videoImagePreferences(context)
            val audioOutput = args.getString("audioOutput") ?: "pcm"
            p.audioSettings = com.ambient.tv.data.local.AudioSettings(profiles =
                com.ambient.tv.data.local.AudioCatalog.profiles.associateWith {
                    com.ambient.tv.data.local.AudioOptions(output = if (audioOutput == "reject-eac3") "bitstream" else audioOutput,
                        allowedCodecs = if (audioOutput == "reject-eac3") emptyList() else com.ambient.tv.data.local.AudioCatalog.codecs,
                        localFallback = "pcm")
                })
            p.titleVideoSettings = emptyMap()
            p.performanceModeKey = "maximum"
            performanceProfile(context)
            p.media3ImageOptions = Media3ImageOptions()
            p.vlcImageOptions = VlcImageOptions(softwareProcessing = backend == "vlc" && args.getString("decoder") == "software")
            p.mpvImageOptions = MpvImageOptions(scaler = "bilinear", chromaScaler = "bilinear",
                downscaler = "bilinear", deband = "off", sigmoid = false,
                sharpening = args.getString("sharpening") ?: "off")
            if (args.getString("filters") == "heavy") {
                p.vlcImageOptions = VlcImageOptions(svpMode = "light")
                p.mpvImageOptions = MpvImageOptions(scaler = "ewa_robidouxsharp", chromaScaler = "robidoux",
                    sharpening = "medium", denoise = "medium", svpMode = "light")
                p.media3ImageOptions = Media3ImageOptions(enabled = true, scaler = "lanczos")
            }
            BackgroundPlaybackSession.stop()
        }
        val start = SystemClock.elapsedRealtime()
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackAuditActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("backend", backend).putExtra("uri", android.net.Uri.fromFile(file).toString())
            .putExtra("unsupportedVideoGuard", args.getString("unsupportedVideoGuard") == "true")
            .putExtra("audioOnly", args.getString("audioOnly") == "true")
            .putExtra("headless", args.getString("headless") == "true")
            .putExtra("startPositionMs", args.getString("startPositionMs")?.toLongOrNull() ?: 0L).putExtra("output", args.getString("output") ?: "auto")
            .putExtra("decoder", args.getString("decoder") ?: "auto").putExtra("hdr", args.getString("hdr"))) as PlaybackAuditActivity
        try {
            await("start") { BackgroundPlaybackSession.snapshot.value.let { it.backend == (args.getString("expectedBackend") ?: backend) && it.playing && it.status.positionMs > 500 } }
            main {
                val session = when (val h = BackgroundPlaybackSession.activeHandle()) {
                    is Media3PlaybackHandle -> Media3AudioProcessing.session(h.player)
                    is VlcPlaybackController -> h.audioSession
                    is MpvPlaybackController -> h.audioSession
                    else -> null
                }
                log("AUDIO_ROUTE ${AudioPassthroughPolicy.probe(context)} SOURCE=${session?.sourceCodec} PLAN=${session?.outputPlan()}")
                if (args.getString("audioOutput") in listOf("pcm", "reject-eac3"))
                    check(session?.outputPlan()?.mode == AudioOutputMode.PCM) { "Unsupported/disabled bitstream did not use PCM" }
            }
            log("START_MS=${SystemClock.elapsedRealtime() - start} ${main { evidence() }}")
            args.getString("deinterlace")?.let { mode ->
                main {
                    when (val h = BackgroundPlaybackSession.activeHandle()) {
                        is MpvPlaybackController -> h.setDeinterlace(mode != "off", "yadif2x", mode == "auto")
                        is VlcPlaybackController -> h.setDeinterlace(mode, "yadif2x")
                    }
                }
                Thread.sleep(2_000)
            }
            assertPlaybackVideoMoves(instrumentation, "$backend initial")
            staleShader?.let {
                val bundled = context.resources.openRawResource(com.ambient.tv.R.raw.quality_adaptive_44e81f12)
                    .bufferedReader().use { source -> source.readText() }
                check(it.readText() == bundled) { "Stale shader survived APK update" }
                log("SHADER_CACHE replaced stale shader with bundled source")
            }
            val requestedStart = args.getString("startPositionMs")?.toLongOrNull() ?: 0L
            if (requestedStart > 0) {
                await("requested initial position") {
                    BackgroundPlaybackSession.snapshot.value.status.positionMs in requestedStart..(requestedStart + 15_000)
                }
                log("RESUME requested=$requestedStart actual=${main { BackgroundPlaybackSession.snapshot.value.status.positionMs }}")
                main { (BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)?.let {
                    check(it.player.volume == 1f) { "Resume left Media3 muted" }
                } }
            }
            main {
                when (val h = BackgroundPlaybackSession.activeHandle()) {
                    is Media3PlaybackHandle -> if (args.getString("videoOnly") != "true") check(h.player.audioFormat != null)
                    is VlcPlaybackController -> if (args.getString("videoOnly") != "true") check((h.runtimeStats()?.decodedAudio ?: 0) > 0)
                    is MpvPlaybackController -> if (args.getString("videoOnly") != "true") check(h.audioOutputDriver() != null && h.audioOutputFormat() != null)
                }
                val view = surfaces(activity.window.decorView).firstOrNull()
                log("SURFACE ${view?.holder?.surfaceFrame} DATASPACE=${view?.let { VulkanNativeProbe.dataSpace(it.holder.surface) }}")
                val request = com.ambient.tv.ui.display.SdrToHdrRequest(400, activity.display!!.displayId, activity.display!!.mode.modeId)
                if (android.os.Build.VERSION.SDK_INT < 34) {
                    val status = BackgroundPlaybackSession.activeHandle()?.updateSdrToHdr(request)
                    check(status != com.ambient.tv.ui.display.SdrToHdrStatus.ACTIVE)
                    BackgroundPlaybackSession.activeHandle()?.updateSdrToHdr(null)
                    log("SDR_TO_HDR unavailable on API ${android.os.Build.VERSION.SDK_INT}: $status")
                }
            }
            // Audit-only option writes still run on the native owner's thread.
            val colourController = main { BackgroundPlaybackSession.activeHandle() as? MpvPlaybackController }
            if (colourController != null && (args.getString("colorHint") == "true" || args.getString("sdrExact") == "true" || args.getString("glBt709") == "true")) {
                val done = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                val worker = colourController.javaClass.getDeclaredField("worker").apply { isAccessible = true }
                    .get(colourController) as android.os.Handler
                worker.post {
                    try {
                        val engine = colourController.javaClass.getDeclaredField("engine").apply { isAccessible = true }
                            .get(colourController)!!
                        val setter = engine.javaClass.getDeclaredMethod("setProperty", String::class.java, String::class.java)
                            .apply { isAccessible = true }
                        if (args.getString("sdrExact") == "true") {
                            setter.invoke(engine, "sdr-adjust-gamma", "yes")
                            setter.invoke(engine, "treat-srgb-as-power22", "no")
                        }
                        if (args.getString("glBt709") == "true") {
                            setter.invoke(engine, "target-trc", "bt.1886")
                            setter.invoke(engine, "target-prim", "bt.709")
                            val surface = engine.javaClass.getDeclaredField("attachedSurface").apply { isAccessible = true }
                                .get(engine) as android.view.Surface
                            check(VulkanNativeProbe.restoreDataSpace(surface, (1 shl 16) or (3 shl 22) or (1 shl 27)))
                        }
                        setter.invoke(engine, "target-colorspace-hint", "yes")
                        setter.invoke(engine, "target-colorspace-hint-mode", "source")
                        setter.invoke(engine, "target-colorspace-hint-strict", "yes")
                    } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
                }
                check(done.await(10, TimeUnit.SECONDS)) { "Colour audit owner thread stalled" }
                failure.get()?.let { throw it }
            }
            // MPV diagnostic reads are asynchronous: keep subscriptions active throughout sampling.
            main { droppedFrames() }
            Thread.sleep(1_000)
            val dropsBefore = main { droppedFrames() }
            val before = main { BackgroundPlaybackSession.snapshot.value.status.positionMs }
            log("DISPLAY_MODE ${main { activity.display?.mode }}")
            log("SAMPLE_BEFORE svpFrames=${SvpNative.interpolatedFrames()} ${main { evidence() }}")
            val sampleMs = (args.getString("sampleMs")?.toLongOrNull() ?: 6_000L).coerceIn(6_000L, 120_000L)
            repeat((sampleMs / 250).toInt()) {
                Thread.sleep(250)
                main {
                    droppedFrames(); evidence()
                    if (args.getString("convertHdr") == "true") {
                        val request = com.ambient.tv.ui.display.SdrToHdrRequest(300, activity.display!!.displayId, activity.display!!.mode.modeId)
                        val status = BackgroundPlaybackSession.activeHandle()?.updateSdrToHdr(request)
                        val surface = surfaces(activity.window.decorView).firstOrNull()?.holder?.surface
                        log("HDR_PROBE status=$status dataspace=${surface?.let { VulkanNativeProbe.dataSpace(it) }}")
                    }
                }
                if (args.getString("convertHdr") == "true") logMpvColour()
            }
            logMpvColour()
            val dropsAfter = main { droppedFrames() }
            val dropped = if (dropsBefore != null && dropsAfter != null) dropsAfter - dropsBefore else null
            log("SMOOTHNESS droppedDelta=$dropped durationMs=$sampleMs")
            val after = main { BackgroundPlaybackSession.snapshot.value.status.positionMs }
            log("SAMPLE_AFTER deltaMs=${after - before} svpFrames=${SvpNative.interpolatedFrames()} ${main { evidence() }}")
            if (args.getString("strictSmoothness") == "true") {
                check(dropped != null && dropped in 0..2) { "Steady playback lost $dropped frames in $sampleMs ms" }
            }
            check(after - before in (sampleMs * 3 / 4)..(sampleMs * 5 / 4 + 1_000)) { "Playback stalled or paused during sustained sample" }
            if (args.getString("reduceSharpening") == "true") {
                await("sharpening overload") { (BackgroundPlaybackSession.activeHandle() as MpvPlaybackController).renderOverloaded }
                val saved = main { videoImagePreferences(context).mpvImageOptions }
                main {
                    val h = BackgroundPlaybackSession.activeHandle() as MpvPlaybackController
                    check(h.canReduceAfterOverload)
                    h.reduceAfterOverload()
                }
                await("sharpening reduction applied") {
                    val h = BackgroundPlaybackSession.activeHandle() as MpvPlaybackController
                    h.imageOptions.sharpening == "cas" && "cas" in h.loadedImageShaders() && h.isPlaying()
                }
                main {
                    check(videoImagePreferences(context).mpvImageOptions == saved) { "Reduction overwrote saved settings" }
                }
                Thread.sleep(3_000)
                val reducedBefore = main { droppedFrames() }!!
                repeat(40) { Thread.sleep(250); main { droppedFrames() } }
                val reducedDelta = main { droppedFrames() }!! - reducedBefore
                log("SHARPEN_REDUCED cas droppedDelta=$reducedDelta durationMs=10000 ${main { evidence() }}")
                check(reducedDelta in 0..2) { "Reduced sharpening still loses frames" }
            }
            assertPlaybackVideoMoves(instrumentation, "$backend sustained")
            if (args.getString("sharpenSwitch") == "true") {
                for (sharpening in listOf("off", "adaptive", "off", "adaptive")) {
                    main {
                        val h = BackgroundPlaybackSession.activeHandle() as MpvPlaybackController
                        h.updateImageOptions(h.imageOptions.copy(sharpening = sharpening))
                    }
                    Thread.sleep(2_000)
                    assertPlaybackVideoMoves(instrumentation, "mpv sharpening=$sharpening")
                    log("SHARPEN_SWITCH $sharpening ${main { evidence() }}")
                }
            }
            main { BackgroundPlaybackSession.pause() }
            await("pause") { !BackgroundPlaybackSession.snapshot.value.playing }
            val paused = main { BackgroundPlaybackSession.snapshot.value.status.positionMs }
            if (args.getString("skipSurfaceReattach") != "true") {
                main { activity.renderSurface = false }
                Thread.sleep(400)
                main { activity.renderSurface = true }
            } else log("SURFACE_REATTACH skipped: seek/pause isolation only")
            Thread.sleep(1_000)
            check(main { !BackgroundPlaybackSession.snapshot.value.playing && kotlin.math.abs(BackgroundPlaybackSession.snapshot.value.status.positionMs - paused) < 1_000 }) { "Paused surface return drifted/resumed" }
            main { BackgroundPlaybackSession.play() }
            await("resume") { BackgroundPlaybackSession.snapshot.value.playing }
            val seekTarget = args.getString("seekPositionMs")?.toLongOrNull() ?: 2_000L
            main { BackgroundPlaybackSession.activeHandle()?.seekTo(seekTarget) }
            await("native seek") {
                val h = BackgroundPlaybackSession.activeHandle()
                if (h is VlcPlaybackController) !h.hasPendingSeek() && h.player.time in (seekTarget - 1_000)..(seekTarget + 3_000)
                else BackgroundPlaybackSession.snapshot.value.status.positionMs in (seekTarget - 1_000)..(seekTarget + 3_000)
            }
            assertPlaybackVideoMoves(instrumentation, "$backend seek/reattach")
            await("resume primer restores audio") {
                when (val h = BackgroundPlaybackSession.activeHandle()) {
                    is MpvPlaybackController -> h.isMuted() == false
                    is VlcPlaybackController -> !h.hasPendingSeek() && h.player.volume > 0
                    else -> true
                }
            }
            log("AUDIO_RESUME unmuted after seek/surface return")
            if (args.getString("decoderSwitch") == "true" && backend != "media3") {
                for (pauseSwitch in listOf(false, true)) for (alternate in listOf(true, false)) {
                    main { if (pauseSwitch) BackgroundPlaybackSession.pause() else BackgroundPlaybackSession.play() }
                    await("decoder switch initial state") { BackgroundPlaybackSession.currentStatus()?.playing == !pauseSwitch }
                    val position = main { checkNotNull(BackgroundPlaybackSession.currentStatus()).positionMs }
                    main {
                        when (val h = BackgroundPlaybackSession.activeHandle()) {
                            is VlcPlaybackController -> h.updateImageOptions(VlcImageOptions(softwareProcessing = alternate))
                            is MpvPlaybackController -> {
                                val metadata = checkNotNull(BackgroundPlaybackSession.snapshot.value.metadata)
                                val old = BackgroundPlaybackSession.videoSettings(metadata, PlayerVideoSettings.from(videoImagePreferences(context)))
                                BackgroundPlaybackSession.setVideoSettings(metadata, old.copy(
                                    mpvOutput = if (alternate) "gpu" else "direct",
                                    mpvDecoder = if (alternate) "auto" else "mediacodec"))
                            }
                        }
                    }
                    await("decoder restored") {
                        val h = BackgroundPlaybackSession.activeHandle()
                        val status = BackgroundPlaybackSession.currentStatus()
                        status != null && status.playing == !pauseSwitch && kotlin.math.abs(status.positionMs - position) < 2_500 &&
                            (h !is VlcPlaybackController || (!h.hasPendingSeek() &&
                                kotlin.math.abs(h.player.time - position) < 2_500 && h.player.isPlaying == !pauseSwitch))
                    }
                    Thread.sleep(750)
                    if (pauseSwitch) check(main { !checkNotNull(BackgroundPlaybackSession.activeHandle()).wantsToPlay() })
                    main { BackgroundPlaybackSession.play() }
                    await("decoder resumed") { BackgroundPlaybackSession.currentStatus()?.playing == true }
                    assertPlaybackVideoMoves(instrumentation, "$backend decoder alternate=$alternate paused=$pauseSwitch")
                    log("DECODER_SWITCH alternate=$alternate paused=$pauseSwitch ${main { evidence() }}")
                }
            }
            val surfaceResult = if (args.getString("skipSurfaceReattach") == "true") "pause only (no reattach)" else "pause/reattach"
            log("PASS moving picture, audio pipeline, sample, $surfaceResult, seek; audible audio and physical HDMI HDR require observer")
        } finally {
            main { log("FINAL ${evidence()}"); BackgroundPlaybackSession.stop(); activity.finish() }
        }
    }
}
