package com.ambient.tv.ui.player

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Run with am instrument -w; fails the run on a stalled stream or changed session identity. */
class PlaybackAuditInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle
    override fun onCreate(arguments: Bundle?) { this.arguments = arguments ?: Bundle(); start() }
    private val results = StringBuilder()
    private fun log(text: String) {
        results.appendLine(text)
        sendStatus(0, Bundle().apply { putString("stream", text + "\n") })
    }
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        val done = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            result.set(runCatching(action)); done.countDown()
        }
        check(done.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
            "Main thread stalled:\n" + android.os.Looper.getMainLooper().thread.stackTrace.joinToString("\n")
        }
        return result.get().getOrThrow()
    }
    private fun await(label: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (main(condition)) return
            Thread.sleep(100)
        }
        error("$label: timeout; ${main { BackgroundPlaybackSession.snapshot.value }}")
    }
    private fun shell(command: String) {
        uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
        }
    }

    private fun assertVideo(label: String) {
        val end = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < end) {
            val bitmap = uiAutomation.takeScreenshot()
            if (bitmap != null) {
                val colors = mutableSetOf<Int>()
                for (y in 2..8) for (x in 2..8) {
                    colors.add(bitmap.getPixel(bitmap.width * x / 10, bitmap.height * y / 10))
                }
                if (colors.size > 10) { bitmap.recycle(); return }
                if (SystemClock.elapsedRealtime() + 600 >= end) {
                    File(targetContext.filesDir, "audit-video-failure.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                bitmap.recycle()
            }
            Thread.sleep(500)
        }
        error("$label: no rendered test pattern (audio/position alone is insufficient)")
    }

    private fun auditDisplayScaling(activity: PlaybackAuditActivity, backend: String) {
        check(targetContext.packageName.endsWith(".qualityaudit"))
        fun surfaces(view: android.view.View): List<android.view.SurfaceView> = when (view) {
            is android.view.SurfaceView -> listOf(view)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { surfaces(view.getChildAt(it)) }
            else -> emptyList()
        }
        val original = main { activity.window.attributes.preferredDisplayModeId }
        val modes = main { activity.display!!.supportedModes.toList() }
        val targets = listOf(Triple(3840, 2160, 60f), Triple(3840, 2160, 24f),
            Triple(1920, 1080, 60f), Triple(3840, 2160, 60f)).map { (w, h, hz) ->
            modes.filter { it.physicalWidth == w && it.physicalHeight == h }
                .minByOrNull { kotlin.math.abs(it.refreshRate - hz) }
                ?: error("Missing test display mode ${w}x$h")
        }
        try {
            for (mode in targets) {
                val intentBefore = main { checkNotNull(BackgroundPlaybackSession.activeHandle()).wantsToPlay() }
                main { DisplayRefreshRateMatcher.setPreferredMode(activity, DisplayRefreshRateMatcher.modeKey(mode)) }
                await("display mode ${mode.modeId}") { activity.display!!.mode.modeId == mode.modeId }
                await("$backend physical render buffer") {
                    val handle = checkNotNull(BackgroundPlaybackSession.activeHandle())
                    val surface = surfaces(activity.window.decorView).firstOrNull() ?: return@await false
                    val logical = android.graphics.Point()
                    @Suppress("DEPRECATION")
                    activity.display!!.getRealSize(logical)
                    val expected = physicalVideoRenderSize(surface.width, surface.height, logical.x, logical.y,
                        mode.physicalWidth, mode.physicalHeight) ?: return@await false
                    val frame = surface.holder.surfaceFrame
                    !handle.usesRendererScaling || (frame.width() == expected.first && frame.height() == expected.second)
                }
                Thread.sleep(700)
                val before = main {
                    check(checkNotNull(BackgroundPlaybackSession.activeHandle()).wantsToPlay() == intentBefore) {
                        "$backend lost play intent during HDMI switch"
                    }
                    BackgroundPlaybackSession.snapshot.value.status.positionMs
                }
                await("$backend advances after mode switch") { BackgroundPlaybackSession.snapshot.value.status.positionMs > before + 500 }
                assertVideo("$backend display ${mode.modeId}")
                assertPlaybackVideoMoves(this, "$backend display ${mode.modeId}")
                main {
                    val surface = surfaces(activity.window.decorView).first()
                    val frame = surface.holder.surfaceFrame
                    val handle = BackgroundPlaybackSession.activeHandle()
                    val fps = (handle as? com.ambient.tv.ui.player.backend.MpvPlaybackController)?.displayFps()
                    if (handle?.usesRendererScaling == true && fps != null) {
                        check(kotlin.math.abs(fps - mode.refreshRate) < 0.1) { "Stale MPV display rate $fps vs ${mode.refreshRate}" }
                    }
                    log("PASS DISPLAY $backend mode=${mode.physicalWidth}x${mode.physicalHeight}@${mode.refreshRate} view=${surface.width}x${surface.height} buffer=${frame.width()}x${frame.height()} mpvHz=$fps")
                }
            }
        } finally {
            main { activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = original } }
        }
    }

    /** Inspect native VLC time: status() intentionally exposes pending targets to the UI. */
    private fun auditVlcSeeksAndHandoffs(activity: PlaybackAuditActivity) {
        fun vlc() = BackgroundPlaybackSession.activeHandle() as? com.ambient.tv.ui.player.backend.VlcPlaybackController
        fun landed(target: Long, paused: Boolean) = vlc()?.let {
            it.errorMessage == null && !it.hasPendingSeek() && kotlin.math.abs(it.player.time - target) <= 1_000L &&
                it.player.isPlaying == !paused
        } == true
        main { BackgroundPlaybackSession.pause() }
        await("VLC native pause") { vlc()?.player?.isPlaying == false }
        main {
            val handle = checkNotNull(vlc())
            handle.seekTo(20_000); handle.seekTo(25_000); handle.seekTo(0)
        }
        await("VLC superseded seek to native zero", 35_000) { landed(0, true) }
        val zeroController = main { checkNotNull(vlc()) }
        main { activity.backend = "media3" }
        await("zero handoff to Media3", 35_000) {
            val handle = BackgroundPlaybackSession.activeHandle()
            handle is Media3PlaybackHandle && !handle.player.isPlaying &&
                handle.player.playbackState == androidx.media3.common.Player.STATE_READY && handle.player.currentPosition in 0..1_000
        }
        main { activity.backend = "vlc" }
        await("zero handoff back to native VLC", 35_000) { vlc() !== zeroController && landed(0, true) }
        main { checkNotNull(vlc()).seekTo(25_000) }
        await("VLC paused seek native 25s", 35_000) { landed(25_000, true) }
        Thread.sleep(500)
        check(main { landed(25_000, true) }) { "VLC paused restore drifted" }
        val previous = main { checkNotNull(vlc()) }
        main { activity.backend = "media3" }
        await("VLC to Media3 paused handoff", 35_000) {
            val handle = BackgroundPlaybackSession.activeHandle()
            handle is Media3PlaybackHandle && !handle.wantsToPlay() &&
                !handle.player.isPlaying && handle.player.playbackState == androidx.media3.common.Player.STATE_READY &&
                handle.player.currentPosition in 24_000..26_000
        }
        main { activity.backend = "vlc" }
        await("Media3 to VLC paused native handoff", 35_000) { vlc() !== previous && landed(25_000, true) }
        main { checkNotNull(vlc()).setDeinterlace("on", "yadif") }
        await("VLC filter reopen retains paused native clock", 35_000) { landed(25_000, true) }
        main { BackgroundPlaybackSession.play(); checkNotNull(vlc()).seekTo(5_000) }
        await("VLC backwards seek native clock", 35_000) { landed(5_000, false) }
        val before = main { checkNotNull(vlc()).player.time }
        await("VLC native clock advances after seek") { (vlc()?.player?.time ?: 0L) > before + 500 }
        assertVideo("VLC after seek and handoffs")
        assertPlaybackVideoMoves(this, "VLC after seek and handoffs")
        log("PASS VLC native seek zero / supersession / paused handoffs / filter reopen / moving picture")
    }

    private fun auditSeamlessSwitch(file: File) {
        check(targetContext.packageName.endsWith(".renderaudit"))
        val backends = listOf("media3", "vlc", "mpv")
        for (source in backends) for (target in backends.filter { it != source }) for (paused in listOf(false, true)) {
            main { BackgroundPlaybackSession.stop() }
            val activity = startActivitySync(Intent(targetContext, PlaybackAuditActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("backend", source)
                .putExtra("uri", android.net.Uri.fromFile(file).toString())
                .putExtra("hdr", arguments.getString("hdr"))
                .putExtra("startPositionMs", 12_000L).putExtra("output", "auto").putExtra("decoder", "auto")) as PlaybackAuditActivity
            try {
                await("$source start", 30_000) { BackgroundPlaybackSession.currentStatus()?.let { it.playing && it.positionMs > 12_500 } == true }
                assertPlaybackVideoMoves(this, "$source before switch")
                main { if (paused) BackgroundPlaybackSession.pause() }
                await("$source pause state") { BackgroundPlaybackSession.currentStatus()?.playing == !paused }
                val before = main { checkNotNull(BackgroundPlaybackSession.currentStatus()).positionMs }
                val started = SystemClock.elapsedRealtime()
                main { activity.backend = target }
                await("$source -> $target restored", 30_000) {
                    val h = BackgroundPlaybackSession.activeHandle()
                    val status = BackgroundPlaybackSession.currentStatus()
                    BackgroundPlaybackSession.snapshot.value.backend == target && status != null &&
                        kotlin.math.abs(status.positionMs - before) < 2_500 && status.playing == !paused &&
                        (h !is com.ambient.tv.ui.player.backend.VlcPlaybackController ||
                            (!h.hasPendingSeek() && kotlin.math.abs(h.player.time - before) < 2_500 && h.player.isPlaying == !paused))
                }
                val restoredMs = SystemClock.elapsedRealtime() - started
                if (paused) {
                    Thread.sleep(1_000)
                    check(main { kotlin.math.abs(checkNotNull(BackgroundPlaybackSession.currentStatus()).positionMs - before) < 750 &&
                        !checkNotNull(BackgroundPlaybackSession.activeHandle()).wantsToPlay() }) { "Pause or position lost after switch" }
                }
                main { BackgroundPlaybackSession.play() }
                await("$target advances") { BackgroundPlaybackSession.currentStatus()?.let { it.playing && it.positionMs > before + 500 } == true }
                assertPlaybackVideoMoves(this, "$source -> $target after switch")
                log("HANDOFF $source -> $target paused=$paused restoredMs=$restoredMs positionBefore=$before actual=${main { BackgroundPlaybackSession.currentStatus()?.positionMs }} picture=moving")
            } finally { main { BackgroundPlaybackSession.stop(); activity.finish() }; Thread.sleep(350) }
        }
    }

    private fun auditOutputSwitch(file: File) {
        check(targetContext.packageName.endsWith(".outputaudit") || targetContext.packageName.endsWith(".renderaudit"))
        val preferences = videoImagePreferences(targetContext)
        for (backend in (arguments.getString("backends") ?: "mpv,vlc").split(",")) {
            main { BackgroundPlaybackSession.stop() }
            val activity = startActivitySync(Intent(targetContext, PlaybackAuditActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("backend", backend)
                .putExtra("uri", android.net.Uri.fromFile(file).toString())
                .putExtra("startPositionMs", 5000L).putExtra("output", "auto").putExtra("decoder", "auto")) as PlaybackAuditActivity
            try {
                await("$backend initial playback", 30_000) {
                    BackgroundPlaybackSession.currentStatus()?.let { it.playing && it.positionMs > 5500 } == true
                }
                assertVideo("$backend initial output")
                if (backend == "vlc") auditVlcSeeksAndHandoffs(activity)
                val defaults = PlayerVideoSettings.from(preferences)
                val caps = com.ambient.tv.ui.player.backend.VideoAccelerationRuntime.capabilities.value
                val outputs = buildList {
                    if (backend == "mpv") addAll(listOf("gpu", "direct")) else add("android")
                    if ("vulkan" !in disabledPlayerVideoOutputs(backend, caps)) add("vulkan")
                    else log("SKIP $backend Vulkan: device/build capability unavailable")
                    add("auto")
                }
                for ((index, output) in outputs.withIndex()) {
                    val paused = index % 2 == 0
                    main { if (paused) BackgroundPlaybackSession.pause() else BackgroundPlaybackSession.play() }
                    await("$backend requested play state") { BackgroundPlaybackSession.currentStatus()?.playing == !paused }
                    val before = main { checkNotNull(BackgroundPlaybackSession.currentStatus()).positionMs }
                    val previous = main { checkNotNull(BackgroundPlaybackSession.activeHandle()) }
                    main {
                        val metadata = checkNotNull(BackgroundPlaybackSession.snapshot.value.metadata)
                        val old = BackgroundPlaybackSession.videoSettings(metadata, defaults)
                        BackgroundPlaybackSession.setVideoSettings(metadata,
                            if (backend == "mpv") old.withMpvOutput(output) else old.copy(vlcOutput = output))
                    }
                    await("$backend new controller") { BackgroundPlaybackSession.activeHandle() !== previous }
                    await("$backend restored position and play state", 30_000) {
                        BackgroundPlaybackSession.currentStatus()?.let {
                            kotlin.math.abs(it.positionMs - before) < 2500 && it.playing == !paused && it.error == null
                        } == true
                    }
                    Thread.sleep(600)
                    if (paused) check(main { !checkNotNull(BackgroundPlaybackSession.activeHandle()).wantsToPlay() })
                    check(PlayerVideoSettings.from(preferences) == defaults) { "Session override changed defaults" }
                    main { BackgroundPlaybackSession.play() }
                    await("$backend resumed") { BackgroundPlaybackSession.currentStatus()?.playing == true }
                    if (backend == "vlc") await("VLC $output native clock after replacement", 35_000) {
                        (BackgroundPlaybackSession.activeHandle() as? com.ambient.tv.ui.player.backend.VlcPlaybackController)?.let {
                            it.errorMessage == null && !it.hasPendingSeek() && kotlin.math.abs(it.player.time - before) < 5_000L &&
                                it.player.isPlaying
                        } == true
                    }
                    assertVideo("$backend $output picture")
                    if (output == "vulkan") main {
                        when (val handle = BackgroundPlaybackSession.activeHandle()) {
                            is com.ambient.tv.ui.player.backend.MpvPlaybackController ->
                                check(handle.configuredVideoOutput() == "gpu-next / Vulkan") {
                                    "MPV Vulkan output fell back: ${handle.configuredVideoOutput()} / ${handle.accelerationFallback}"
                                }
                            is com.ambient.tv.ui.player.backend.VlcPlaybackController ->
                                check(handle.videoOutputDescription.contains("Vulkan") && handle.accelerationFallback == null)
                        }
                    }
                    log("PASS $backend -> $output: position/play intent preserved, defaults unchanged, rendered picture")
                }
                main { BackgroundPlaybackSession.pause(); activity.showOutputPanel = true }
                Thread.sleep(700)
                fun key(code: Int) { sendKeyDownUpSync(code); Thread.sleep(250) }
                key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                val expected = if (backend == "mpv") "direct" else "android"
                await("$backend D-pad output selection") {
                    val metadata = BackgroundPlaybackSession.snapshot.value.metadata
                    val settings = BackgroundPlaybackSession.videoSettings(metadata, defaults)
                    (if (backend == "mpv") settings.mpvOutput else settings.vlcOutput) == expected
                }
                // The selector remains attached across the native-controller replacement.
                key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                key(android.view.KeyEvent.KEYCODE_BACK)
                check(main { !checkNotNull(BackgroundPlaybackSession.activeHandle()).wantsToPlay() })
                log("PASS $backend D-pad output selection and reopen/Back while paused")
            } finally {
                main { BackgroundPlaybackSession.stop(); activity.finish() }
            }
            val storedDefaults = PlayerVideoSettings.from(preferences)
            check(main { BackgroundPlaybackSession.videoSettings(null, storedDefaults) == storedDefaults })
        }
    }

    override fun onStart() {
        thread(name = "playback-audit") {
            try {
                if (arguments.getString("textInputs") == "true") {
                    com.ambient.tv.ui.common.TextInputFocusAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "TEXT INPUT FOCUS AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("networkSettings") == "true") {
                    com.ambient.tv.ui.settings.NetworkSettingsAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "NETWORK SETTINGS AUDIT PASSED\n$results") })
                    return@thread
                }
                arguments.getString("browseCache")?.let { phase ->
                    if (phase == "write-budget") com.ambient.tv.data.browse.BrowseWriteBudgetAudit(this, ::log).run()
                    else com.ambient.tv.data.browse.BrowseCacheAudit(this, ::log).run(phase)
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "BROWSE CACHE AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("osdNavigation") == "true") {
                    PlayerOsdNavigationAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "OSD NAVIGATION AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("backNavigation") == "true" || arguments.getString("backShell") == "true") {
                    if (arguments.getString("backShell") != "true") com.ambient.tv.ui.common.BackNavigationAudit(this, ::log).run()
                    com.ambient.tv.ui.common.BackShellAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "BACK NAVIGATION AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("liveProfiles") == "true") {
                    com.ambient.tv.ui.settings.LiveTvProfileAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "LIVE TV PROFILE AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("appearance") == "true") {
                    val activity = startActivitySync(android.content.Intent(targetContext,
                        com.ambient.tv.ui.catalog.UiCatalogActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        as com.ambient.tv.ui.catalog.UiCatalogActivity
                    try {
                        com.ambient.tv.ui.settings.AppearanceAudit(this, ::log).run(activity)
                    } finally {
                        runOnMainSync { activity.finish() }
                    }
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "APPEARANCE AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("rgbAccentSettings") == "true") {
                    com.ambient.tv.ui.settings.PlaybackAccentSettingsAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "RGB ACCENT SETTINGS AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("mpvUpscalers") == "true") {
                    MpvUpscalerAudit(this, ::log).run(requireNotNull(arguments.getString("clip")), arguments.getString("output") ?: "gpu", arguments.getString("scalers")?.split(","))
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "MPV UPSCALER AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("mpvResponsiveness") == "true") {
                    MpvResponsivenessAudit(this, ::log).run(requireNotNull(arguments.getString("clip")))
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "MPV RESPONSIVENESS AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("companionReceiver") == "true") {
                    com.ambient.tv.companion.CompanionReceiverAudit(this, ::log).run(requireNotNull(arguments.getString("clip")))
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "COMPANION RECEIVER AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("companion") == "true") {
                    com.ambient.tv.companion.CompanionSecurityAudit(this, ::log, testUi = arguments.getString("companionUi") != "false").run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "COMPANION SECURITY AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("youtubeLive") == "true") {
                    com.ambient.tv.ui.youtube.YouTubeLiveEventAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "YOUTUBE LIVE DIALOG AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("youtubeRenderer") == "true") {
                    YouTubeRendererAudit(this, ::log).run(arguments)
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "YOUTUBE RENDERER AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("trailer") == "true") {
                    TrailerPlaybackAudit(this, ::log).run(arguments.getString("videoId") ?: "8ugaeA-nMTc")
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "TRAILER PLAYBACK AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("uiHdr") == "true") {
                    com.ambient.tv.ui.display.UiHdrAudit(this, ::log).run(tmdbOnly = arguments.getString("tmdbOnly") == "true")
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "UI HDR FALLBACK AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("nextUpRendererTransition") == "true") {
                    NextUpRendererTransitionAudit(this, ::log).run(requireNotNull(arguments.getString("clip")),
                        arguments.getString("backend") ?: "media3", arguments.getString("output") ?: "direct",
                        releaseSource = arguments.getString("releaseSource") == "true")
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "NEXT UP RENDERER TRANSITION AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("nextUpTransition") == "true") {
                    NextUpTransitionAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "NEXT UP TRANSITION AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("nextUpPresentation") == "true") {
                    NextUpPresentationAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "NEXT UP PRESENTATION AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("segmentSkip") == "true") {
                    SegmentSkipAudit(this, ::log).run()
                    NextUpLinkAudit(this, ::log).run()
                    NextUpPresentationAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "SEGMENT SKIP AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("deinterlace") == "true") {
                    DeinterlaceAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "DEINTERLACE AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("projectmAudio") == "true") {
                    ProjectMAudioPlaybackAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PROJECTM AUDIO AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("identity") == "true") {
                    check(targetContext.packageName == com.ambient.tv.BuildConfig.APPLICATION_ID)
                    check(targetContext.applicationContext is com.ambient.tv.AmbientTvApplication)
                    check(targetContext.getString(com.ambient.tv.R.string.app_name) == "Ambient TV")
                    check(com.ambient.tv.ui.player.backend.MpvBackendAvailability.nativeRuntimeAvailable)
                    val mpv = com.ambient.tv.ui.player.backend.MpvNativeBridge.nativeCreate()
                    check(mpv != 0L)
                    com.ambient.tv.ui.player.backend.MpvNativeBridge.nativeDestroy(mpv)
                    log("PASS application identity and MPV JNI")
                    check(com.ambient.tv.ui.common.projectm.ProjectMAvailability.isAvailable)
                    log("PASS projectM JNI: ${com.ambient.tv.ui.common.projectm.ProjectMAvailability.version}")
                    try {
                        check(com.ambient.tv.ui.player.backend.SvpNative.updateGate(true))
                        check(com.ambient.tv.ui.player.backend.SvpNative.enabled())
                        check(com.ambient.tv.ui.player.backend.SvpNative.interpolatedFrames() >= 0)
                        check(com.ambient.tv.ui.player.backend.SvpNative.gpuFrames() >= 0)
                    } finally {
                        com.ambient.tv.ui.player.backend.SvpNative.updateGate(false)
                    }
                    log("PASS SVP JNI and enable/disable gate")
                    check(com.ambient.tv.ui.player.backend.VulkanNativeProbe.available)
                    check(com.ambient.tv.ui.player.backend.VulkanNativeProbe.probe(null).isNotBlank())
                    val vlc = org.videolan.libvlc.LibVLC(targetContext, arrayListOf("--verbose=0"))
                    try {
                        com.ambient.tv.ui.player.backend.VulkanNativeProbe.vlcModules()
                    } finally { vlc.release() }
                    log("PASS Vulkan probe JNI and VLC initialization")
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "IDENTITY AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("notifications") == "true") {
                    com.ambient.tv.notifications.NotificationAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "NOTIFICATION AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("operationFeedback") == "true") {
                    com.ambient.tv.notifications.OperationFeedbackAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "OPERATION FEEDBACK AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("progressiveLoading") == "true") {
                    com.ambient.tv.ui.common.ProgressiveLoadingAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PROGRESSIVE LOADING AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("menuSoundsHandheld") == "true") {
                    com.ambient.tv.ui.sound.MenuSoundsAudit(this, ::log).runHandheld()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "HANDHELD SOUND EDITOR AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("menuSounds") == "true") {
                    com.ambient.tv.ui.sound.MenuSoundsAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "MENU SOUNDS AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("pointerFocus") == "true") {
                    com.ambient.tv.companion.CompanionPointerFocusAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "POINTER FOCUS AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("scrollFocus") == "true") {
                    com.ambient.tv.ui.common.ScrollListFocusAudit(this, ::log).run(
                        dialogsOnly = arguments.getString("dialogButtonsOnly") == "true",
                    )
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "SCROLL FOCUS AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("uiPerformance") == "true") {
                    com.ambient.tv.ui.performance.UiPerformanceAudit(this, ::log).run(
                        arguments.getString("browsePerformance") == "true", arguments.getString("browseMode") ?: "combined",
                        arguments.getString("uiProfile") ?: "balanced", arguments.getString("uiScenario") ?: "all",
                        arguments.getString("uiTrace") == "true",
                    )
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "UI PERFORMANCE AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("accentPicker") == "true") {
                    com.ambient.tv.ui.settings.AccentPickerFocusAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "ACCENT PICKER FOCUS AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("settingsSelect") == "true") {
                    com.ambient.tv.ui.settings.SettingsSelectAudit(this, ::log).run(
                        displayOnly = arguments.getString("displayOnly") == "true",
                    )
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "SETTINGS SELECT AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("channelProfiles") == "true") {
                    ChannelPlaybackProfileAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "CHANNEL PROFILE AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("guideNavigation") == "true") {
                    com.ambient.tv.livetv.GuideNavigationAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "GUIDE NAVIGATION AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("liveTv") == "true") {
                    com.ambient.tv.livetv.LiveTvAudit(this, ::log).run()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "LIVE TV AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("capabilities") == "true") {
                    val caps = kotlinx.coroutines.runBlocking {
                        com.ambient.tv.ui.player.backend.VideoAccelerationRuntime.refresh(targetContext)
                    }
                    log("VULKAN $caps")
                    check(caps.checked)
                    val plan = com.ambient.tv.ui.player.backend.planMpvAcceleration("vulkan", "vulkan", caps)
                    log("VULKAN requested output+decode: $plan")
                    if (!caps.mpvVulkanOutput) check(plan.output == "direct")
                    if (!caps.mpvVulkanDecode) check(plan.decoder == "mediacodec")
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", results.toString()) })
                    return@thread
                }
                val file = File(targetContext.filesDir, "playback-audit.mp4")
                URL(arguments.getString("clip") ?: "http://127.0.0.1:18765/playback-test.mp4")
                    .openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                if (arguments.getString("seamless") == "true") {
                    auditSeamlessSwitch(file)
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "SEAMLESS SWITCH AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("matrix") == "true") {
                    RendererMatrixAudit(this, ::log).run(file, arguments)
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "RENDERER MATRIX CASE PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("svp") == "true") {
                    auditSvp(file)
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "SVP AUDIT PASSED\n$results") })
                    return@thread
                }
                if (arguments.getString("audioRecovery") == "true") {
                    auditLocalAudio(file)
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "LOCAL AUDIO AUDIT PASSED\n$results") })
                    return@thread
                }
                val backends = (arguments.getString("backends") ?: "media3,vlc,mpv").split(",")
                if (arguments.getString("outputSwitch") == "true") {
                    auditOutputSwitch(file)
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "OUTPUT SWITCH AUDIT PASSED\n$results") })
                    return@thread
                }
                shell("input keyevent KEYCODE_WAKEUP")
                // Do not replace the app's MODIFY_AUDIO_SETTINGS permission while testing
                // acceleration: Android 16 otherwise rejects its normal audio-focus request.
                if (arguments.getString("acceleration") != "true")
                    uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL", "android.permission.MODIFY_AUDIO_SETTINGS")
                if (arguments.getString("switchOnly") != "true") for (backend in backends) {
                    main { BackgroundPlaybackSession.stop() }
                    val intent = Intent(targetContext, PlaybackAuditActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("backend", backend).putExtra("uri", android.net.Uri.fromFile(file).toString())
                        .putExtra("headless", arguments.getString("headless") != "false")
                        .putExtra("output", arguments.getString("output"))
                        .putExtra("decoder", arguments.getString("decoder"))
                        .putExtra("startDelayMs", arguments.getString("startDelayMs")?.toLongOrNull() ?: 0L)
                    var activity = startActivitySync(intent) as PlaybackAuditActivity
                    await("$backend starts", 30_000) { BackgroundPlaybackSession.snapshot.value.let {
                        it.backend == backend && it.playing && it.status.positionMs > 31_000 } }
                    val generation = main { BackgroundPlaybackSession.snapshot.value.generation }
                    fun position() = main { BackgroundPlaybackSession.snapshot.value.status.positionMs }
                    fun assertSame() = check(main { BackgroundPlaybackSession.snapshot.value.generation } == generation) {
                        "$backend was recreated instead of reattached"
                    }
                    log("PASS $backend start at resume position 30s (headless=${arguments.getString("headless") != "false"})")
                    intent.putExtra("headless", false)
                    main { activity.renderSurface = true }
                    assertVideo("$backend initial video")
                    if (arguments.getString("displayScaling") == "true") auditDisplayScaling(activity, backend)

                    if (arguments.getString("runtimeFallback") == "true") {
                        val beforeFallback = position()
                        main {
                            val handle = checkNotNull(BackgroundPlaybackSession.activeHandle())
                            val method = if (backend == "vlc") handle.javaClass.getDeclaredMethod("fallbackVulkan", String::class.java)
                                else handle.javaClass.getDeclaredMethod("fallbackAcceleration", String::class.java, Boolean::class.javaPrimitiveType)
                            method.isAccessible = true
                            val accepted = if (backend == "vlc") method.invoke(handle, "audit") else method.invoke(handle, "audit", true)
                            check(accepted == true) { "$backend was not using Vulkan before fallback" }
                        }
                        await("$backend runtime fallback", 30_000) {
                            BackgroundPlaybackSession.snapshot.value.let { it.playing && it.status.positionMs > beforeFallback + 500 }
                        }
                        assertSame()
                        assertVideo("$backend runtime fallback picture")
                        log("PASS $backend runtime fallback preserving session and position")
                    }

                    main { activity.showPlayer = false }
                    val beforeBrowse = position()
                    await("$backend browsing advances") { BackgroundPlaybackSession.snapshot.value.status.positionMs > beforeBrowse + 1800 }
                    main { activity.showPlayer = true }
                    await("$backend return") { BackgroundPlaybackSession.snapshot.value.playing }
                    assertSame()
                    assertVideo("$backend video after browsing")
                    log("PASS $backend browse / reattach")

                    if (arguments.getString("acceleration") == "true") {
                        main {
                            when (val handle = BackgroundPlaybackSession.activeHandle()) {
                                is Media3PlaybackHandle -> {
                                    val format = checkNotNull(handle.player.audioFormat)
                                    val session = checkNotNull(Media3AudioProcessing.session(handle.player))
                                    check(session.sourceCodec != null && session.sourceChannels == format.channelCount)
                                    log("AUDIO Media3 source=${session.sourceCodec} channels=${session.sourceChannels} mime=${format.sampleMimeType} plan=${session.outputPlan()}")
                                }
                                is com.ambient.tv.ui.player.backend.MpvPlaybackController -> {
                                    check(handle.audioOutputDriver() != null && handle.audioOutputFormat() != null)
                                    log("OUTPUT ${handle.configuredVideoOutput()} DECODE ${handle.decoder()} AUDIO ${handle.audioOutputDriver()}/${handle.audioOutputFormat()} FALLBACK ${handle.accelerationFallback}")
                                }
                                is com.ambient.tv.ui.player.backend.VlcPlaybackController -> {
                                    check((handle.runtimeStats()?.decodedAudio ?: 0) > 0)
                                    log("OUTPUT ${handle.videoOutputDescription} STATS ${handle.runtimeStats()} FALLBACK ${handle.accelerationFallback}")
                                }
                            }
                            BackgroundPlaybackSession.pause()
                        }
                        await("$backend pause") { !BackgroundPlaybackSession.snapshot.value.playing }
                        val pausedAt = position()
                        main { activity.showPlayer = false }
                        Thread.sleep(400)
                        main { activity.showPlayer = true }
                        Thread.sleep(1200)
                        check(main { !BackgroundPlaybackSession.snapshot.value.playing }) { "$backend reattachment resumed paused playback" }
                        check(kotlin.math.abs(position() - pausedAt) < 800) { "$backend moved while paused" }
                        main { BackgroundPlaybackSession.play() }
                        await("$backend resume") { BackgroundPlaybackSession.snapshot.value.playing }
                        assertVideo("$backend resumed video")
                        main { BackgroundPlaybackSession.stop(); activity.finish() }
                        log("PASS $backend acceleration / picture / audio pipeline / pause-resume")
                        continue
                    }

                    val controller = targetContext.getSystemService(MediaSessionManager::class.java)
                        .getActiveSessions(null).first { it.packageName == targetContext.packageName }
                    controller.transportControls.pause()
                    await("$backend notification pause") { !BackgroundPlaybackSession.snapshot.value.playing }
                    val paused = position()
                    main { activity.showPlayer = false }
                    Thread.sleep(350)
                    main { activity.showPlayer = true }
                    Thread.sleep(1200)
                    check(kotlin.math.abs(position() - paused) < 800) { "$backend moved while paused" }
                    check(main { !BackgroundPlaybackSession.snapshot.value.playing }) { "$backend resumed on surface attachment" }
                    controller.transportControls.play()
                    await("$backend notification play") { BackgroundPlaybackSession.snapshot.value.playing }
                    assertVideo("$backend paused surface reattachment")
                    log("PASS $backend system media controls")

                    val audio = targetContext.getSystemService(android.media.AudioManager::class.java)
                    val interruption = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                        .setOnAudioFocusChangeListener { }.build()
                    check(main { audio.requestAudioFocus(interruption) } == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    await("$backend transient audio focus loss") { !BackgroundPlaybackSession.snapshot.value.playing }
                    main { audio.abandonAudioFocusRequest(interruption) }
                    await("$backend audio focus restored") { BackgroundPlaybackSession.snapshot.value.playing }
                    log("PASS $backend audio focus interruption / resume")

                    shell("input keyevent KEYCODE_HOME")
                    val beforeHome = position()
                    await("$backend Home advances") { BackgroundPlaybackSession.snapshot.value.status.positionMs > beforeHome + 2500 }
                    assertSame()
                    main { activity.finish() }
                    await("$backend Activity destroyed") { PlaybackAuditActivity.instance == null }
                    val beforeDestroy = position()
                    await("$backend survives destruction") { BackgroundPlaybackSession.snapshot.value.status.positionMs > beforeDestroy + 1800 }
                    activity = startActivitySync(intent) as PlaybackAuditActivity
                    await("$backend reopen") { BackgroundPlaybackSession.snapshot.value.playing }
                    assertSame()
                    assertVideo("$backend video after Activity destruction")
                    log("PASS $backend Android Home / Activity destruction / reopen")

                    val oldActivity = activity
                    main { activity.recreate() }
                    await("$backend recreation") { PlaybackAuditActivity.instance != null && PlaybackAuditActivity.instance !== oldActivity }
                    activity = PlaybackAuditActivity.instance!!
                    val beforeRecreate = position()
                    await("$backend recreated surface advances") { BackgroundPlaybackSession.snapshot.value.status.positionMs > beforeRecreate + 1200 }
                    assertSame()
                    repeat(3) {
                        main { activity.showPlayer = false }
                        Thread.sleep(400)
                        main { activity.showPlayer = true }
                        Thread.sleep(500)
                        assertSame()
                    }
                    log("PASS $backend recreation / repeated surfaces")
                    assertVideo("$backend video after repeated surfaces")

                    controller.transportControls.seekTo(35_000)
                    await("$backend seek") { BackgroundPlaybackSession.snapshot.value.status.positionMs in 34_000..40_000 }
                    main {
                        activity.showPlayer = false
                        BackgroundPlaybackSession.setSleepTimer(SleepTimerConfig(SleepTimerKind.DURATION,
                            deadlineRealtimeMs = SystemClock.elapsedRealtime() + 1500))
                    }
                    await("$backend background sleep timer") { !BackgroundPlaybackSession.snapshot.value.active }
                    log("PASS $backend seek / background sleep timer / release")
                    main { activity.finish() }
                    Thread.sleep(1200)
                }
                if (arguments.getString("acceleration") == "true") {
                    uiAutomation.dropShellPermissionIdentity()
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "ALL ACCELERATION AUDITS PASSED\n$results") })
                    return@thread
                }
                var activity = startActivitySync(Intent(targetContext, PlaybackAuditActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("backend", backends.first())
                    .putExtra("uri", android.net.Uri.fromFile(file).toString())) as PlaybackAuditActivity
                for (backend in backends + backends.reversed()) {
                    main { activity.backend = backend }
                    await("switch to $backend", 30_000) {
                        BackgroundPlaybackSession.snapshot.value.let { it.backend == backend && it.playing && it.status.positionMs > 800 }
                    }
                    main { activity.showPlayer = false }
                    Thread.sleep(350)
                    main { activity.showPlayer = true }
                    assertVideo("switch to $backend video")
                    log("PASS switch to $backend")
                }
                targetContext.getSystemService(MediaSessionManager::class.java)
                    .getActiveSessions(null).first { it.packageName == targetContext.packageName }
                    .transportControls.stop()
                await("system stop releases session") { !BackgroundPlaybackSession.snapshot.value.active }
                main { activity.showPlayer = false; activity.finish() }
                log("PASS system Stop after renderer switches")
                uiAutomation.dropShellPermissionIdentity()
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nALL PLAYBACK AUDITS PASSED\n$results") })
            } catch (error: Throwable) {
                log("FAIL: ${error.stackTraceToString()}")
                finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", results.toString()) })
            }
        }
    }

    private fun auditSvp(file: File) {
        // Never change the user's real installation or its preferences.
        check(targetContext.packageName.endsWith(".svpaudit") || targetContext.packageName.endsWith(".selectaudit"))
        shell("input keyevent KEYCODE_WAKEUP")
        val preferences = videoImagePreferences(targetContext)
        val savedAvailable = preferences.svpAvailable
        val savedMode = preferences.performanceModeKey
        val savedMpv = preferences.mpvImageOptions
        val savedVlc = preferences.vlcImageOptions
        var activity: PlaybackAuditActivity? = null
        try {
            for (backend in (arguments.getString("backends") ?: "mpv,vlc").split(",")) {
                main {
                    BackgroundPlaybackSession.stop()
                    preferences.svpAvailable = true
                    preferences.performanceModeKey = "maximum"
                    preferences.mpvImageOptions = com.ambient.tv.data.local.MpvImageOptions(
                        scaler="bilinear",chromaScaler="bilinear",downscaler="bilinear",deband="off",sigmoid=false,svpMode="light")
                    preferences.vlcImageOptions = com.ambient.tv.data.local.VlcImageOptions(svpMode="light")
                    check(performanceProfile(targetContext).allowSvpInterpolation)
                    check(com.ambient.tv.ui.player.backend.SvpNative.enabled())
                }
                val initial = com.ambient.tv.ui.player.backend.SvpNative.interpolatedFrames()
                val gpuInitial = com.ambient.tv.ui.player.backend.SvpNative.gpuFrames()
                activity = startActivitySync(Intent(targetContext, PlaybackAuditActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("backend", backend)
                    .putExtra("output", "gpu").putExtra("decoder", "mediacodec")
                    .putExtra("auditId", "svp-${System.nanoTime()}").putExtra("panelSection", "svp")
                    .putExtra("uri", android.net.Uri.fromFile(file).toString())
                    .putExtra("headless", false)) as PlaybackAuditActivity
                await("$backend SVP starts",30_000) { BackgroundPlaybackSession.snapshot.value.let {
                    it.playing && it.status.positionMs > 31_000
                } }
                assertVideo("$backend SVP picture")
                main {
                    when (val handle = BackgroundPlaybackSession.activeHandle()) {
                        is com.ambient.tv.ui.player.backend.MpvPlaybackController -> log("SVP initial: ${handle.svpStatus()} filters=${handle.activeVideoFilters()} options=${handle.imageOptions} gpu=${handle.supportsGpuFilters}")
                        is com.ambient.tv.ui.player.backend.VlcPlaybackController -> log("SVP initial: ${handle.svpStatus()} options=${handle.imageOptions}")
                    }
                }
                await("$backend real intermediate pictures",25_000) {
                    com.ambient.tv.ui.player.backend.SvpNative.interpolatedFrames() > initial+10
                }
                val running = com.ambient.tv.ui.player.backend.SvpNative.interpolatedFrames()
                await("$backend sustained interpolation",6_000) {
                    com.ambient.tv.ui.player.backend.SvpNative.interpolatedFrames() > running+40
                }
                // MPV getters request asynchronous samples; their first read may be empty.
                await("$backend SVP audio pipeline") {
                    when (val handle=BackgroundPlaybackSession.activeHandle()) {
                        is com.ambient.tv.ui.player.backend.MpvPlaybackController ->
                            handle.audioOutputFormat()!=null && handle.audioOutputDriver()!=null
                        is com.ambient.tv.ui.player.backend.VlcPlaybackController -> (handle.runtimeStats()?.decodedAudio ?: 0)>0
                        else -> false
                    }
                }
                log("PASS $backend new pictures=${com.ambient.tv.ui.player.backend.SvpNative.interpolatedFrames()-initial} Vulkan jobs=${com.ambient.tv.ui.player.backend.SvpNative.gpuFrames()-gpuInitial}")
                if (arguments.getString("svpSwitch") == "true") {
                    val choices = listOf(
                        com.ambient.tv.data.local.SvpOptions(algorithm = 21, artifacts = 2, scene = 1, search = 1, backend = 1),
                        com.ambient.tv.data.local.SvpOptions(algorithm = 23, artifacts = 3, block = 16, overlap = 3, pel = 2, search = 1, backend = 1),
                        com.ambient.tv.data.local.SvpOptions(rateDivisor = 2, algorithm = 13, backend = 1),
                        com.ambient.tv.data.local.SvpOptions(algorithm = 13, block = 8, overlap = 2, backend = 1),
                        com.ambient.tv.data.local.SvpOptions(algorithm = 21, backend = 2),
                        com.ambient.tv.data.local.SvpOptions(algorithm = 23, backend = 2),
                    )
                    fun apply(options: com.ambient.tv.data.local.SvpOptions) = main {
                        when (val handle = BackgroundPlaybackSession.activeHandle()) {
                            is com.ambient.tv.ui.player.backend.MpvPlaybackController -> {
                                val next = handle.imageOptions.copy(svp = options)
                                preferences.mpvImageOptions = next
                                handle.updateImageOptions(next)
                            }
                            is com.ambient.tv.ui.player.backend.VlcPlaybackController -> {
                                val next = handle.imageOptions.copy(svp = options)
                                preferences.vlcImageOptions = next
                                handle.updateImageOptions(next)
                            }
                            else -> error("Missing native player")
                        }
                    }
                    for (options in choices) {
                        val position = main { BackgroundPlaybackSession.snapshot.value.status.positionMs }
                        val count = com.ambient.tv.ui.player.backend.SvpNative.interpolatedFrames()
                        val gpuCount = com.ambient.tv.ui.player.backend.SvpNative.gpuFrames()
                        apply(options)
                        await("$backend live SVP override", 20_000) {
                            BackgroundPlaybackSession.snapshot.value.let {
                                it.playing && it.status.positionMs in (position + 500)..(position + 15_000)
                            } && com.ambient.tv.ui.player.backend.SvpNative.interpolatedFrames() > count + 4
                        }
                        if (backend == "mpv") await("MPV applied SVP parameters") {
                            (BackgroundPlaybackSession.activeHandle() as? com.ambient.tv.ui.player.backend.MpvPlaybackController)
                                ?.activeVideoFilters()?.contains("algo=${options.algorithm}") == true
                        }
                        if (options.backend == 2) await("$backend Vulkan output after override") {
                            com.ambient.tv.ui.player.backend.SvpNative.gpuFrames() > gpuCount + 4
                        }
                        assertVideo("$backend after live SVP override")
                    }
                    main { BackgroundPlaybackSession.activeHandle()?.pause() }
                    Thread.sleep(500)
                    val paused = main { BackgroundPlaybackSession.snapshot.value.status.positionMs }
                    apply(choices.first())
                    Thread.sleep(2500)
                    main {
                        check(!BackgroundPlaybackSession.snapshot.value.playing)
                        check(kotlin.math.abs(BackgroundPlaybackSession.snapshot.value.status.positionMs - paused) < 1500)
                        BackgroundPlaybackSession.activeHandle()?.play()
                    }
                    apply(com.ambient.tv.data.local.SvpOptions())
                    await("$backend resumes after paused SVP switch") { BackgroundPlaybackSession.snapshot.value.playing }
                    log("PASS $backend live SVP overrides / position / paused switch / reset")
                    if (arguments.getString("svpPanel") == "true") {
                        main { activity?.showScalingPanel = true }
                        waitForIdleSync()
                        fun openTarget() {
                            val label = targetContext.getString(com.ambient.tv.R.string.svp_target)
                            fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                                if (node.text?.toString()?.contains(label) == true) return node
                                for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { found -> return found } }
                                return null
                            }
                            repeat(30) {
                                var node = uiAutomation.rootInActiveWindow?.let(::find)
                                while (node != null && !node.isClickable) node = node.parent
                                if (node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) {
                                    Thread.sleep(500)
                                    return
                                }
                                Thread.sleep(200)
                            }
                            error("No SVP target control in production panel")
                        }
                        for (divisor in listOf(2, 1)) {
                            openTarget()
                            sendKeyDownUpSync(if (divisor == 2) android.view.KeyEvent.KEYCODE_DPAD_DOWN else android.view.KeyEvent.KEYCODE_DPAD_UP)
                            Thread.sleep(150)
                            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                            await("$backend D-pad SVP target $divisor") {
                                when (val handle = BackgroundPlaybackSession.activeHandle()) {
                                    is com.ambient.tv.ui.player.backend.MpvPlaybackController -> handle.imageOptions.svp.rateDivisor == divisor
                                    is com.ambient.tv.ui.player.backend.VlcPlaybackController -> handle.imageOptions.svp.rateDivisor == divisor
                                    else -> false
                                }
                            }
                        }
                        log("PASS $backend production SVP target via D-pad / restore")
                        Thread.sleep(800) // Capture the composed production panel for visual inspection.
                        uiAutomation.takeScreenshot()?.let { bitmap ->
                            File(targetContext.filesDir, "svp-panel-$backend.png").outputStream().use {
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                            }
                            bitmap.recycle()
                        }
                        main { activity?.showScalingPanel = false }
                        log("CAPTURE $backend production SVP panel")
                    }
                }
                // Auto keeps SVP on a full device; only test it here when Auto resolves to Sparsam.
                val autoReduced = performanceProfile(targetContext).lowEndDevice
                for (mode in listOf("balanced","auto","low").filter { it != "auto" || autoReduced }) {
                    main {
                        preferences.performanceModeKey=mode
                        check(!performanceProfile(targetContext).allowSvpInterpolation)
                        check(!com.ambient.tv.ui.player.backend.SvpNative.enabled())
                    }
                    Thread.sleep(400) // Allow an already dispatched GPU job to finish.
                    val stopped=com.ambient.tv.ui.player.backend.SvpNative.interpolatedFrames()
                    val position=main { BackgroundPlaybackSession.snapshot.value.status.positionMs }
                    await("$backend continues without SVP in $mode") {
                        BackgroundPlaybackSession.snapshot.value.status.positionMs > position+1000
                    }
                    check(stopped==com.ambient.tv.ui.player.backend.SvpNative.interpolatedFrames())
                    assertVideo("$backend $mode picture")
                    log("PASS $backend $mode: SVP off, playback continues")
                }
                val beforeRestart = com.ambient.tv.ui.player.backend.SvpNative.interpolatedFrames()
                main {
                    check(preferences.mpvImageOptions.svpMode=="light" && preferences.vlcImageOptions.svpMode=="light")
                    preferences.performanceModeKey="maximum"
                    check(performanceProfile(targetContext).allowSvpInterpolation)
                }
                await("$backend reactivates SVP in Maximum",15_000) {
                    com.ambient.tv.ui.player.backend.SvpNative.interpolatedFrames()>beforeRestart+10
                }
                main { BackgroundPlaybackSession.activeHandle()?.seekTo(45_000) }
                await("$backend SVP seek",15_000) {
                    BackgroundPlaybackSession.snapshot.value.status.positionMs in 45_000..49_000
                }
                assertVideo("$backend SVP after seek")
                log("PASS $backend Maximum reactivation / seek / picture and audio pipeline")
                main { BackgroundPlaybackSession.stop(); activity?.finish() }
                Thread.sleep(600)
            }
        } finally {
            main {
                BackgroundPlaybackSession.stop(); activity?.finish()
                preferences.svpAvailable=savedAvailable
                preferences.performanceModeKey=savedMode
                preferences.mpvImageOptions=savedMpv; preferences.vlcImageOptions=savedVlc
                performanceProfile(targetContext)
            }
        }
    }

    private fun auditLocalAudio(file: File) {
        // Use a separate AUDIT_APP_SUFFIX installation so these settings cannot affect users.
        check(targetContext.packageName.endsWith(".audioaudit") || targetContext.packageName.endsWith(".selectaudit"))
        val preferences = videoImagePreferences(targetContext)
        val saved = preferences.audioSettings
        val route = main { AudioPassthroughPolicy.probe(targetContext) }
        log("AUDIO route=$route")
        var activity: PlaybackAuditActivity? = null
        try {
            main {
                BackgroundPlaybackSession.stop()
                preferences.audioSettings = com.ambient.tv.data.local.AudioSettings()
            }
            activity = startActivitySync(Intent(targetContext, PlaybackAuditActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("backend", "mpv")
                .putExtra("output", "direct").putExtra("decoder", "mediacodec")
                .putExtra("uri", android.net.Uri.fromFile(file).toString())
                .putExtra("headless", false)) as PlaybackAuditActivity
            await("local audio starts", 30_000) {
                BackgroundPlaybackSession.snapshot.value.let { it.playing && it.status.positionMs > 31_000 }
            }
            assertVideo("local audio original video")
            val mpv = main { BackgroundPlaybackSession.activeHandle() as com.ambient.tv.ui.player.backend.MpvPlaybackController }
            main {
                check(mpv.audioCodec() != null)
                check(mpv.audioOutputDriver() == "audiotrack")
                check(mpv.audioOutputFormat() != null)
                log("AUTO source=${mpv.audioCodec()} channels=${mpv.audioSession.sourceChannels} plan=${mpv.audioSession.outputPlan()} actual=${mpv.audioOutputFormat()} rejected=${mpv.audioSession.warning}")
                check(!mpv.audioSession.localEncoderFailed)
            }
            if ("ac3" in route.passthrough) {
                main {
                    // Also exercise the existing explicit AC3 path on multichannel PCM routes.
                    mpv.audioSession.update(com.ambient.tv.data.local.AudioOptions(
                        output = "auto", localAc3 = true, allowedCodecs = listOf("ac3")))
                }
                await("native AC3 output") {
                    mpv.audioSession.outputPlan().mode == AudioOutputMode.LOCAL_AC3 &&
                        mpv.audioOutputFormat()?.contains("ac3", ignoreCase = true) == true
                }
                main { log("AC3 actual=${mpv.audioOutputFormat()} warning=${mpv.audioSession.warning}") }
            } else log("SKIP AC3 hardware output: active route does not confirm AC3")
            main { mpv.audioSession.update(com.ambient.tv.data.local.AudioOptions(output = "pcm", localFallback = "pcm")) }
            await("local PCM output") {
                mpv.audioSession.outputPlan().mode == AudioOutputMode.PCM &&
                    mpv.audioOutputFormat()?.let { !it.contains("spdif", true) } == true
            }
            val before = main { mpv.positionMs() }
            await("PCM clock advances") { mpv.positionMs() > before + 1500 }
            assertVideo("video survives AC3/PCM switch")
            main { log("PCM actual=${mpv.audioOutputFormat()} video=${mpv.videoCodec()} position=${mpv.positionMs()}") }
        } finally {
            main { BackgroundPlaybackSession.stop(); activity?.finish(); preferences.audioSettings = saved }
        }
    }
}
