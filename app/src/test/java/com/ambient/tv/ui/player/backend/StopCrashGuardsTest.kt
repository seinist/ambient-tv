package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.player.startForegroundServiceSafely
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Stop-crash fixes: VLC post-release guards, MPV surface detach ordering, service start guard. */
class StopCrashGuardsTest {
    private fun source(path: String) = File("src/main/java/com/ambient/tv/$path").readText()

    // 1 VLC ---------------------------------------------------------------------------------

    @Test fun uiNeverCallsTheNativeVlcPlayerDirectly() {
        listOf("ui/player/VlcPlayerContent.kt", "ui/tvheadend/VlcTvheadendPlaybackSurface.kt",
            "ui/youtube/YouTubePlayerScreen.kt", "ui/player/backend/VlcPlaybackView.kt").forEach { path ->
            assertFalse("$path calls controller.player directly", Regex("""controller\.player\.""").containsMatchIn(source(path)))
        }
        assertTrue(source("ui/player/VlcPlayerContent.kt").contains("override fun play() { controller.play() }"))
    }

    @Test fun publicVlcMethodsTouchingNativeStateAreGuardedAfterRelease() {
        val vlc = source("ui/player/backend/VlcPlaybackView.kt")
        fun body(signature: String): String {
            val start = vlc.indexOf(signature)
            assertTrue("missing $signature", start >= 0)
            return vlc.substring(start, (start + 400).coerceAtMost(vlc.length))
        }
        listOf("fun seekBy(", "fun selectVideoTrack(", "fun selectAudioTrack(", "fun selectSubtitleTrack(",
            "fun cycleAudioTrack(", "fun cycleSubtitleTrack(", "fun preferredAudioTrack(",
            "override fun restoreAfterRendererSwitch(", "private fun mediaTrackLanguages(",
            "private fun refreshSelectedAudioCodec(").forEach {
            assertTrue("$it lacks a released guard", body(it).contains("if (released) return"))
        }
        assertTrue(body("fun currentVideoTrackId(").contains("if (released) -1"))
        assertTrue(body("fun isPlayingNow(").contains("!released"))
        assertTrue(vlc.contains("VlcPlaybackSnapshot(isPlaying = controller.isPlayingNow()"))
        // The player is only replaced inside the controller.
        assertTrue(Regex("""var player = MediaPlayer\(libVlc\)\s+private set""").containsMatchIn(vlc))
    }

    // 4 VLC: LibVLC outlives retiring DV-surface players ----------------------------------------

    @Test fun libVlcIsReleasedOnlyAfterItsRetiringPlayers() {
        val released = mutableListOf<String>()
        val gate = VlcLibraryReleaseGate<String> { released += it }
        gate.retiring("lib"); gate.retiring("lib")
        gate.releaseWhenIdle("lib")
        assertTrue(released.isEmpty())
        assertTrue(gate.pending("lib"))
        gate.retired("lib")
        assertTrue(released.isEmpty())
        gate.retired("lib")
        assertEquals(listOf("lib"), released)
        gate.retired("lib") // stray: no second release
        gate.releaseWhenIdle("other")
        assertEquals(listOf("lib", "other"), released)
        // Retired without a pending release: nothing released.
        gate.retiring("third"); gate.retired("third")
        assertEquals(2, released.size)
    }

    @Test fun libVlcReleaseFailureDoesNotEscape() {
        val gate = VlcLibraryReleaseGate<String> { error("native") }
        gate.releaseWhenIdle("lib")
        gate.retiring("b"); gate.releaseWhenIdle("b"); gate.retired("b")
    }

    @Test fun everyLibVlcReleaseGoesThroughTheGate() {
        val vlc = source("ui/player/backend/VlcPlaybackView.kt")
        assertFalse(Regex("""libVlc\.release\(\)|retiringLibVlc\.release\(\)""").containsMatchIn(vlc))
        assertEquals(3, Regex("""libVlcRelease\.releaseWhenIdle\(""").findAll(vlc).count())
        assertTrue(vlc.contains("libVlcRelease.retiring(retiringLibVlc)"))
        assertTrue(vlc.contains("libVlcRelease.retired(retiringLibVlc)"))
    }

    // 2 MPV ---------------------------------------------------------------------------------

    @Test fun closeRunsAPendingSurfaceDetachBeforeCleanup() {
        val scheduled = ArrayDeque<() -> Unit>()
        val order = mutableListOf<String>()
        var discarded = 0
        val queue = MpvCommandQueue({ scheduled.addLast(it) }, { throw AssertionError(it) })
        queue.submit("audio", discard = { discarded++ }) { order += "audio" }
        queue.submit("surface", keepOnClose = true) { order += "detach" }
        queue.submit("filter", discard = { discarded++ }) { order += "filter" }
        queue.close { order += "cleanup" }
        while (scheduled.isNotEmpty()) scheduled.removeFirst()()
        assertEquals(listOf("detach", "cleanup"), order)
        assertEquals(2, discarded)
    }

    @Test fun replacedDetachIsNotKeptAndAttachIsDiscardedOnClose() {
        val scheduled = ArrayDeque<() -> Unit>()
        val order = mutableListOf<String>()
        var copyReleased = false
        val queue = MpvCommandQueue({ scheduled.addLast(it) }, { throw AssertionError(it) })
        queue.submit("surface", keepOnClose = true) { order += "detach" }
        queue.submit("surface", discard = { copyReleased = true }) { order += "attach" }
        queue.close { order += "cleanup" }
        while (scheduled.isNotEmpty()) scheduled.removeFirst()()
        assertEquals(listOf("cleanup"), order)
        assertTrue(copyReleased)
    }

    @Test fun keptDetachRunsAfterTheInFlightOperation() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val done = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val queue = MpvCommandQueue({ executor.execute(it) }, { throw AssertionError(it) })
        try {
            queue.submit("native") { entered.countDown(); unblock.await(5, TimeUnit.SECONDS); order += "native" }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.submit("surface", keepOnClose = true) { order += "detach" }
            queue.close { order += "cleanup"; done.countDown() }
            unblock.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("native", "detach", "cleanup"), order.toList())
        } finally { unblock.countDown(); executor.shutdownNow() }
    }

    @Test fun surfaceWaitReturnsWhenTheOwnerDetaches() {
        val gate = MpvSurfaceDetachGate()
        gate.attaching()
        val owner = Thread { Thread.sleep(50); gate.detached() }
        owner.start()
        val started = System.nanoTime()
        assertTrue(gate.await(2_000))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
        owner.join()
    }

    @Test fun surfaceWaitIsBoundedWhenTheOwnerIsBlocked() {
        val gate = MpvSurfaceDetachGate()
        gate.attaching()
        val started = System.nanoTime()
        assertFalse(gate.await(100))
        val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("waited $waited ms", waited in 90..1_000)
        // A later detach still releases future waiters at once.
        gate.detached()
        assertTrue(gate.await(10_000))
    }

    @Test fun surfaceWaitDoesNotBlockWithoutAnAttachedVoOrAfterClose() {
        val gate = MpvSurfaceDetachGate()
        assertTrue(gate.await(10_000)) // never attached
        gate.attaching()
        gate.closed()
        assertTrue(gate.await(10_000))
        gate.attaching() // a stale attach after close must not block
        assertTrue(gate.await(10_000))
    }

    @Test fun surfaceDestroyedWaitsBoundedAndNeverOnTheOwnerThread() {
        val controller = source("ui/player/backend/MpvPlaybackController.kt")
        val view = source("ui/player/backend/MpvPlaybackView.kt")
        assertTrue(view.contains("override fun surfaceDestroyed(holder: SurfaceHolder) {\n        // The window is gone when this returns; wait (bounded) until the VO stopped using it.\n        controller.detachSurfaceAndWait(holder.surface)"))
        assertTrue(view.contains("controller.detachSurfaceAndWait(videoSurface.holder.surface)"))
        val wait = controller.substring(controller.indexOf("fun detachSurfaceAndWait("))
        assertTrue(wait.indexOf("Looper.myLooper() === thread.looper) return") < wait.indexOf("surfaceGate.await("))
        assertTrue(controller.contains("mailbox.submit(\"surface\", keepOnClose = true)"))
        assertTrue(controller.contains("} finally { surfaceGate.detached() }"))
        assertTrue(controller.contains("surfaceGate.closed()"))
        assertTrue(controller.indexOf("surfaceGate.attaching()") < controller.indexOf("player.attachSurface(copy, width, height)"))
        assertEquals(500L, SURFACE_DETACH_WAIT_MS)
    }

    // 3 Foreground service ---------------------------------------------------------------------

    @Test fun refusedServiceStartIsReportedNotThrown() {
        val blocked = mutableListOf<Throwable>()
        // ForegroundServiceStartNotAllowedException extends IllegalStateException.
        assertFalse(startForegroundServiceSafely({ throw IllegalStateException("startForegroundService() not allowed") }, blocked::add))
        assertFalse(startForegroundServiceSafely({ throw SecurityException("vendor") }, blocked::add))
        assertTrue(startForegroundServiceSafely({}, blocked::add))
        assertEquals(2, blocked.size)
        assertThrows(OutOfMemoryError::class.java) { startForegroundServiceSafely({ throw OutOfMemoryError() }, blocked::add) }
    }

    @Test fun acquireUsesTheGuardedStartAndActivityRetriesIt() {
        val session = source("ui/player/BackgroundPlaybackSession.kt")
        val acquire = session.substring(session.indexOf("fun <T : PlaybackHandle> acquire("), session.indexOf("val created = try {"))
        assertTrue(acquire.contains("startPlaybackService(context.applicationContext)"))
        assertFalse(acquire.contains("ContextCompat.startForegroundService"))
        assertTrue(source("ui/MainActivity.kt").contains("BackgroundPlaybackSession.retryBlockedPlaybackService(this)"))
    }
}
