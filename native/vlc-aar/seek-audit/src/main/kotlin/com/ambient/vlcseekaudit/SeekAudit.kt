package com.ambient.vlcseekaudit

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.ambient.tv.ui.player.auditVlcMetadataSocket
import com.ambient.tv.ui.player.backend.VlcTimedMetadataInbox
import com.ambient.tv.ui.player.backend.VlcTimedMetadataPacket
import com.ambient.tv.ui.player.backend.VlcTimedMetadataReceiver
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Actual LibVLC JNI → player queue → MP4 demux → native worker → production Android receiver. */
class SeekAudit : Instrumentation() {
    private var displayFailure = false
    override fun onCreate(arguments: Bundle?) {
        displayFailure = arguments?.getString("displayFailure") == "true"
        start()
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        error("Timed out: $label")
    }
    private fun auditFailedDisplay(file: File) {
        // Force a real vout-display module selection failure, not a synthetic
        // mock. The shell checks the native failure-path marker in logcat.
        val lib = LibVLC(targetContext, arrayListOf("--codec=avcodec", "--no-audio", "--verbose=2"))
        try {
            check(!MediaPlayer.ambientOutputQuarantined())
            val player = MediaPlayer(lib)
            try {
                check(player.ambientAuditHeadless(true)) { "Cannot configure real failed display audit" }
                repeat(3) {
                    val media = Media(lib, Uri.fromFile(file))
                    try { player.media = media } finally { media.release() }
                    player.play()
                    // If the demux is still active, exercise video track
                    // reselection against the failed output before reopening.
                    SystemClock.sleep(1500)
                    val video = player.getTracks(1)?.firstOrNull()
                    if (video != null && player.isPlaying) {
                        player.unselectTrackType(1)
                        SystemClock.sleep(100)
                        player.selectTrack(video.id)
                        SystemClock.sleep(500)
                    }
                    player.stop()
                    check(!MediaPlayer.ambientOutputQuarantined()) { "Ordinary display failure must not poison the process" }
                }
            } finally { player.release() }
        } finally { lib.release() }
    }

    override fun onStart() {
        try {
            val file = File(targetContext.filesDir, "seek-fixture.mp4")
            check(file.length() > 0) { "Missing generated fragmented MP4" }
            if (displayFailure) {
                auditFailedDisplay(file)
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "VLC DISPLAY FAILURE RECOVERY PASSED\n") })
                return
            }
            // Run the exact app instrumentation socket test against the real
            // Android LocalServerSocket, not a separately maintained copy.
            auditVlcMetadataSocket { android.util.Log.i("VlcSeekAudit", it) }
            val inbox = VlcTimedMetadataInbox()
            val session = inbox.beginSession()
            val received = LinkedBlockingQueue<VlcTimedMetadataPacket.Message.Epoch>()
            val lastWireEpoch = java.util.concurrent.atomic.AtomicReference<String>("none")
            val wireEvents = java.util.concurrent.atomic.AtomicInteger()
            checkNotNull(VlcTimedMetadataReceiver.open {
                val accepted = inbox.offer(session, it)
                when (it) {
                    is VlcTimedMetadataPacket.Message.Epoch -> {
                        lastWireEpoch.set("epoch=${it.epoch} id=${it.seekRequestId} target=${it.seekTargetUs} accepted=$accepted")
                        if (accepted) received.offer(it)
                    }
                    is VlcTimedMetadataPacket.Message.Event -> wireEvents.incrementAndGet()
                }
                accepted
            }).use { receiver ->
                val lib = LibVLC(targetContext, arrayListOf("--codec=avcodec", "--no-audio", "--verbose=2"))
                try {
                    val player = MediaPlayer(lib)
                    try {
                        check(player.ambientAuditHeadless(false)) { "Cannot configure real dummy display" }
                        val tagged = MediaPlayer::class.java.getMethod("ambientSetTimeWithId", java.lang.Long.TYPE, java.lang.Long.TYPE)
                        val media = Media(lib, Uri.fromFile(file))
                        try {
                            media.addOption(":demux=mp4")
                            media.addOption(":mp4-ambient-metadata-socket=" + receiver.socketName)
                            player.media = media
                        } finally { media.release() }
                        player.play()
                        waitFor("initial native playback") { player.isPlaying && player.time in 200L..1500L }
                        var lastEpoch = 0L
                        fun seek(id: Long) {
                            val fence = inbox.clearForSeek(2000000, id)
                            check(tagged.invoke(player, 2000L, id) == true) { "Native queue rejected $id" }
                            // JNI returned queue acceptance, not demux completion.
                            val deadline = SystemClock.elapsedRealtime() + 12000
                            var barrier: VlcTimedMetadataPacket.Message.Epoch? = null
                            var nativeResult = 0L
                            while (SystemClock.elapsedRealtime() < deadline) {
                                val status = player.ambientSeekResult()
                                if (status != 0L) nativeResult = status
                                check(status != -id) { "Native demux explicitly rejected seek $id; time=${player.time}, lastWireEpoch=${lastWireEpoch.get()}" }
                                val candidate = received.poll(100, TimeUnit.MILLISECONDS)
                                if (candidate?.seekRequestId == id) { barrier = candidate; break }
                            }
                            val completed = checkNotNull(barrier) {
                                "No native completed seek for $id; lastNativeResult=$nativeResult, time=${player.time}, lastWireEpoch=${lastWireEpoch.get()}, wireEvents=${wireEvents.get()}"
                            }
                            check(completed.epoch > lastEpoch && completed.seekTargetUs == 2000000L)
                            lastEpoch = completed.epoch
                            waitFor("native demux result for $id") { player.ambientSeekResult() == id }
                            inbox.acceptSeek(fence)
                            waitFor("native position and real emsg for $id") {
                                player.time in 2000L..3900L && inbox.events.value.any {
                                    it.seekRequestId == id && it.seekEpoch == lastEpoch &&
                                        it.presentationTimeUs in 2000000L..3900000L
                                }
                            }
                            check(inbox.events.value.all { it.seekRequestId == id })
                        }
                        seek(41)
                        seek(42) // identical target, independent completed native epoch
                        inbox.clearForSeek(2000000, 43)
                        check(tagged.invoke(player, 2000L, 43L) == true)
                        seek(44) // may coalesce with 43; never accept 43's metadata
                    } finally {
                        player.stop()
                        player.release()
                    }
                } finally { lib.release() }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "VLC AFM3 REAL SEEK E2E PASSED\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: " + e.stackTraceToString()) })
        }
    }
}
