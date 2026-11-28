package com.ambient.tv.ui.player.backend

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Separate output surface with an actively drained consumer. The display is
 * never attached to this player. HDR statistics are measured before the
 * display transform, on the same synthesized native textures as playback.
 * Some Android Vulkan/codec drivers reject ImageReader swapchains. Such a
 * run is not usable unless native EOF published the entire scene cache.
 */
internal object HdrOffscreenAnalysis {
    private const val STALL_MS = 60_000L
    private const val MAX_MS = 12L*60*60*1000

    private class Sink : AutoCloseable {
        private val thread = HandlerThread("hdr-analysis-sink").apply { start() }
        val reader: ImageReader
        init {
            try {
                reader = ImageReader.newInstance(320,180,ImageFormat.PRIVATE,3,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
                reader.setOnImageAvailableListener({ r ->
                    runCatching { r.acquireLatestImage()?.close() }
                },Handler(thread.looper))
            } catch (e: Throwable) {
                thread.quitSafely()
                throw e
            }
        }
        override fun close() {
            reader.setOnImageAvailableListener(null,null)
            reader.close()
            thread.quitSafely()
        }
    }

    suspend fun mpv(session: HdrSceneSession, filter: String, hwdec: String, device: String): Boolean =
        withContext(Dispatchers.IO) {
            Sink().use { sink ->
                val api = MpvNativeBridge
                val handle = api.nativeCreate()
                if (handle == 0L) return@withContext false
                var attached = false
                try {
                    val options = linkedMapOf(
                        "vo" to "gpu-next", "gpu-api" to "vulkan", "gpu-context" to "androidvk",
                        "vulkan-device" to device, "hwdec" to hwdec, "hwdec-codecs" to "hevc",
                        "aid" to "no", "sid" to "no", "untimed" to "yes",
                        "video-sync" to "desync", "framedrop" to "no", "keep-open" to "no",
                        "idle" to "yes", "force-window" to "no", "sub-auto" to "no",
                        "target-prim" to "bt.709", "target-trc" to "srgb", "target-peak" to "100",
                        "vf" to filter,
                    )
                    if (options.any { (name,value) -> api.nativeSetOptionString(handle,name,value)<0 })
                        return@withContext false
                    if (api.nativeInitialize(handle)<0) return@withContext false
                    api.nativeAttachSurface(handle,sink.reader.surface)
                    attached = true
                    if (api.nativeSetPropertyString(handle,"android-surface-size","320x180")<0 ||
                        api.nativeSetPropertyString(handle,"force-window","yes")<0 ||
                        api.nativeCommand(handle,arrayOf("loadfile",session.analysisPath(),"replace"))<0)
                        return@withContext false
                    val start = SystemClock.elapsedRealtime()
                    var last = start
                    var position = -1.0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (!session.valid()) return@withContext false
                        for (i in 0 until 128) {
                            when(api.nativePollEvent(handle)) {
                                0 -> break
                                1 -> return@withContext false
                                7 -> return@withContext api.nativeEndReason(handle)==0 &&
                                    api.nativePlaybackError(handle)>=0 && session.advance()
                            }
                        }
                        val next = api.nativeGetPropertyDouble(handle,"time-pos",-1.0)
                        val now = SystemClock.elapsedRealtime()
                        if (next>position) { position=next;last=now }
                        if (now-last>STALL_MS || now-start>MAX_MS) return@withContext false
                        delay(25)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                } finally {
                    if (attached) {
                        runCatching { api.nativeCommand(handle,arrayOf("stop")) }
                        runCatching { api.nativeDetachSurface(handle) }
                    }
                    api.nativeDestroy(handle)
                }
            }
        }

    suspend fun vlc(context: Context, session: HdrSceneSession, options: List<String>): Boolean =
        withContext(Dispatchers.Main.immediate) {
            VlcNativeRetirement.ensureAvailable()
            val sink = Sink()
            var library: LibVLC? = null
            var player: MediaPlayer? = null
            try {
                val lib = LibVLC(context, ArrayList(options + listOf("--no-audio", "--no-spu",
                    "--no-drop-late-frames", "--no-skip-frames", "--clock-jitter=0")))
                library = lib
                val p = MediaPlayer(lib)
                player = p
                var terminal = false
                var error = false
                p.setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.EndReached, MediaPlayer.Event.Stopped -> terminal = true
                        MediaPlayer.Event.EncounteredError -> { terminal = true; error = true }
                    }
                }
                p.vlcVout.setVideoSurface(sink.reader.surface, null)
                p.vlcVout.attachViews()
                val media = Media(lib, Uri.fromFile(File(session.analysisPath())))
                try { p.media = media } finally { media.release() }
                p.play()
                val start = SystemClock.elapsedRealtime()
                var last = start
                var position = -1L
                var rateSet = false
                while (!terminal) {
                    currentCoroutineContext().ensureActive()
                    if (!session.valid() || VlcNativeRetirement.isQuarantined) return@withContext false
                    if (!rateSet && p.isPlaying) { p.setRate(4f); rateSet = true }
                    val now = SystemClock.elapsedRealtime()
                    val next = p.time
                    if (next > position) { position = next; last = now }
                    if (now - last > STALL_MS || now - start > MAX_MS) return@withContext false
                    delay(50)
                }
                !error && session.advance()
            } finally {
                // Cancellation must not race native retirement or delete the backing file.
                withContext(NonCancellable) {
                    val lib = library
                    val p = player
                    if (lib == null) sink.close() else {
                        try {
                            if (p != null) {
                                VlcNativeRetirement.ensureAvailable()
                                p.setEventListener(null)
                                p.vlcVout.detachViews()
                                withContext(Dispatchers.IO) {
                                    VlcNativeRetirement.stop(p, lib)
                                    VlcNativeRetirement.release(p, lib)
                                    VlcNativeRetirement.releaseLibrary(p, lib) { lib.release() }
                                }
                            } else withContext(Dispatchers.IO) {
                                VlcNativeRetirement.ensureAvailable()
                                boundedNativeOperation(15_000L) { lib.release() }
                                VlcNativeRetirement.ensureAvailable()
                            }
                            sink.close()
                        } catch (failure: Throwable) {
                            session.quarantine()
                            VlcNativeRetirement.quarantineAnalysis(p, lib, sink, failure)
                            throw NativeOutputReleaseUncertain(failure)
                        }
                    }
                }
            }
        }
}
