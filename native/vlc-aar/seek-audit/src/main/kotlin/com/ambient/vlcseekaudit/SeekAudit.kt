package com.ambient.vlcseekaudit

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
    override fun onCreate(arguments: Bundle?) { start() }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        error("Timed out: $label")
    }
    override fun onStart() {
        try {
            val file = File(targetContext.filesDir, "seek-fixture.mp4")
            check(file.length() > 0) { "Missing generated fragmented MP4" }
            val inbox = VlcTimedMetadataInbox()
            val session = inbox.beginSession()
            val received = LinkedBlockingQueue<VlcTimedMetadataPacket.Message.Epoch>()
            checkNotNull(VlcTimedMetadataReceiver.open {
                inbox.offer(session, it)
                if (it is VlcTimedMetadataPacket.Message.Epoch) received.offer(it)
            }).use { receiver ->
                val lib = LibVLC(targetContext, arrayListOf("--window=wdummy", "--vout=dummy", "--codec=avcodec", "--avcodec-hw=none", "--no-audio", "--verbose=2"))
                try {
                    val player = MediaPlayer(lib)
                    try {
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
                            inbox.clearForSeek(2000000, id)
                            check(tagged.invoke(player, 2000L, id) == true) { "Native queue rejected $id" }
                            val deadline = SystemClock.elapsedRealtime() + 12000
                            var barrier: VlcTimedMetadataPacket.Message.Epoch? = null
                            while (SystemClock.elapsedRealtime() < deadline) {
                                val candidate = received.poll(100, TimeUnit.MILLISECONDS) ?: continue
                                if (candidate.seekRequestId == id) { barrier = candidate; break }
                            }
                            val completed = checkNotNull(barrier) { "No native completed seek for $id" }
                            check(completed.epoch > lastEpoch && completed.seekTargetUs == 2000000L)
                            lastEpoch = completed.epoch
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
