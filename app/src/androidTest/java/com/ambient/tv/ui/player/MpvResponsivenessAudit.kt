package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import java.io.File
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real libmpv/surface playback plus deterministic owner-thread stalls. Never uses user accounts. */
class MpvResponsivenessAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { result.set(runCatching(action)); done.countDown() }
        check(done.await(2, TimeUnit.SECONDS)) { "UI blocked by MPV" }
        return result.get().getOrThrow()
    }
    private fun await(label: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            if (main(predicate)) return
            Thread.sleep(100)
        }
        error("Timeout: $label")
    }

    fun run(clip: String) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".mpvresponsiveness"))
        val file = File(context.cacheDir, "mpv-ui-test.mp4")
        URL(clip).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackAuditActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("backend", "mpv")
            .putExtra("uri", android.net.Uri.fromFile(file).toString()).putExtra("startPositionMs", 0L)
            .putExtra("output", "gpu").putExtra("decoder", "software")) as PlaybackAuditActivity
        var releaseStall = CountDownLatch(0)
        try {
            await("native playback", 30_000) {
                (BackgroundPlaybackSession.activeHandle() as? MpvPlaybackController)?.let {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    it.isPlaying() && it.positionMs() > 1_000
                } == true
            }
            val controller = main { BackgroundPlaybackSession.activeHandle() as MpvPlaybackController }
            assertPlaybackVideoMoves(instrumentation, "MPV owner-thread playback")
            log("PASS native MPV GPU playback with moving video")
            main { controller.pause() }
            await("pause") { !controller.isPlaying() && !controller.wantsToPlay() }
            Thread.sleep(1_500)
            val pausedGeneration = main { controller.stateChanges.value }
            Thread.sleep(800)
            check(main { controller.stateChanges.value == pausedGeneration }) { "Unchanged paused state wakes the UI" }
            main { controller.play() }
            await("resume") { controller.isPlaying() }
            log("PASS paused snapshots remain stable; pause/resume works")
            // Reflection belongs only to this test: hold exactly the production owner loop.
            val worker = MpvPlaybackController::class.java.getDeclaredField("worker").let {
                it.isAccessible = true; it.get(controller) as Handler
            }
            val entered = CountDownLatch(1)
            releaseStall = CountDownLatch(1)
            val gate = releaseStall
            worker.post { entered.countDown(); check(gate.await(20, TimeUnit.SECONDS)) }
            check(entered.await(3, TimeUnit.SECONDS))
            val started = SystemClock.elapsedRealtime()
            main {
                repeat(100) { i ->
                    controller.status(); controller.audioTracks(); controller.frameDropCount()
                    controller.pause(); controller.play()
                    controller.updateImageOptions(controller.imageOptions.copy(brightness = i % 10))
                }
                activity.showOutputPanel = true
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            check(elapsed < 500) { "Cached reads/commands blocked UI: ${elapsed}ms" }
            await("independent stall monitor", 8_000) { controller.backendUnresponsive }
            main { activity.showOutputPanel = false }
            log("PASS 100 status/track/stats reads and command bursts while owner blocked: ${elapsed}ms; watchdog responsive")
            releaseStall.countDown()
            await("owner recovery", 15_000) { !controller.backendUnresponsive && controller.isPlaying() }
            assertPlaybackVideoMoves(instrumentation, "MPV after command backlog")
            log("PASS recovery and moving video after coalesced filter changes")

            main { activity.renderSurface = false }
            Thread.sleep(350)
            val beforeAttach = main { controller.positionMs() }
            main { activity.renderSurface = true }
            await("surface reattach") { controller.isPlaying() && controller.positionMs() > beforeAttach + 500 }
            assertPlaybackVideoMoves(instrumentation, "MPV after asynchronous surface reattach")
            log("PASS surface detach/reattach and moving video")

            val secondEntered = CountDownLatch(1)
            releaseStall = CountDownLatch(1)
            val secondGate = releaseStall
            worker.post { secondEntered.countDown(); check(secondGate.await(10, TimeUnit.SECONDS)) }
            check(secondEntered.await(3, TimeUnit.SECONDS))
            val stopStarted = SystemClock.elapsedRealtime()
            main { activity.showPlayer = false; BackgroundPlaybackSession.stop() }
            val stopMs = SystemClock.elapsedRealtime() - stopStarted
            check(stopMs < 500) { "Stop waited for native teardown: ${stopMs}ms" }
            check(worker.looper.thread.isAlive) { "Destroyed owner while native work was in flight" }
            releaseStall.countDown()
            worker.looper.thread.join(10_000)
            check(!worker.looper.thread.isAlive) { "Owner leaked after deferred destroy" }
            log("PASS stop returned in ${stopMs}ms while owner blocked; deferred native destroy completed")
        } finally {
            releaseStall.countDown()
            main { BackgroundPlaybackSession.stop(); activity.finish() }
        }
    }
}
