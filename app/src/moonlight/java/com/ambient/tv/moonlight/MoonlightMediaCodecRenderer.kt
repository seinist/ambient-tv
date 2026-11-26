package com.ambient.tv.moonlight

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.nvstream.av.video.VideoDecoderRenderer
import com.limelight.nvstream.jni.MoonBridge
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** Direct, bounded MediaCodec -> Surface path; each decoder owns its drain thread. */
class MoonlightMediaCodecRenderer(
    private val surface: Surface,
    private val requested: MoonlightStreamProfile,
    private val onError: (String) -> Unit,
) : VideoDecoderRenderer() {
    private val inputLock = Any()
    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null
    private var outputRunning: AtomicBoolean? = null
    @Volatile private var playing = false
    @Volatile private var closing = false
    private val failed = AtomicBoolean(false)
    private var ptsUs = 0L
    @Volatile private var decoderName = ""
    @Volatile private var currentMime = ""
    @Volatile private var decodedWidth = 0
    @Volatile private var decodedHeight = 0
    @Volatile private var decodedFps = 0
    private var negotiatedHdr10 = false
    @Volatile private var activeHdr = false
    private var hdrMasteringInfo: ByteArray? = null
    private var hdrReconfigure = false
    private var lastFrame: Int? = null
    private var lastCsdFrame: Int? = null
    private val vps = ArrayList<ByteArray>(2)
    private val sps = ArrayList<ByteArray>(2)
    private val pps = ArrayList<ByteArray>(2)
    private val received = AtomicLong()
    private val missing = AtomicLong()
    private val rendered = AtomicLong()
    private val dropped = AtomicLong()
    private val decodeMicros = AtomicLong()
    private val hostTenthsMs = AtomicLong()
    private val hostSamples = AtomicLong()

    override fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int =
        synchronized(inputLock) {
            if (!surface.isValid || closing || codec != null) return@synchronized -1
            val mime = MoonlightHardwareVideo.mimeFor(format) ?: return@synchronized -1
            negotiatedHdr10 = MoonlightHardwareVideo.isHdr10Format(format)
            if (requested.hdrMode == "on" && !negotiatedHdr10) return@synchronized -1
            activeHdr = negotiatedHdr10
            currentMime = mime
            decodedWidth = width
            decodedHeight = height
            decodedFps = redrawRate.takeIf { it in 1..240 } ?: requested.fps
            codec = newConfiguredDecoder() ?: return@synchronized -1
            clearCsd()
            0
        }

    private fun decoderFormat(): MediaFormat =
        MediaFormat.createVideoFormat(currentMime, decodedWidth, decodedHeight).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, decodedFps)
            if (activeHdr) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                moonlightHdrStaticInfo(hdrMasteringInfo)?.let { setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, it) }
            }
            // Let the bitstream advertise its actual SDR color standard.
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }

    private fun newConfiguredDecoder(): MediaCodec? {
        val candidates = MoonlightHardwareVideo.decoders(currentMime, decodedWidth, decodedHeight,
            decodedFps, hdr10 = negotiatedHdr10)
        for (info in candidates) {
            // Try reviewed vendor-specific options, then a clean MediaFormat.
            for (attempt in 0..5) {
                val format = decoderFormat()
                if (attempt < 5) runCatching {
                    MediaCodecHelper.setDecoderLowLatencyOptions(format, info, attempt)
                }
                val decoder = runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull() ?: break
                try {
                    decoder.configure(format, surface, null, 0)
                    decoderName = info.name
                    return decoder
                } catch (_: Exception) {
                    runCatching { decoder.release() }
                }
            }
        }
        return null
    }

    private fun startDrain(decoder: MediaCodec) {
        val running = AtomicBoolean(true)
        outputRunning = running
        outputThread = Thread({ drainOutput(decoder, running) }, "moonlight-decoder-output").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
            start()
        }
    }

    /** No decoder is stopped or freed while its output thread still accesses it. */
    private fun stopDrain(): Boolean {
        outputRunning?.set(false)
        val worker = outputThread ?: return true
        worker.interrupt()
        if (worker === Thread.currentThread()) return false
        runCatching { worker.join(1500) }
        if (worker.isAlive) return false
        outputThread = null
        outputRunning = null
        return true
    }

    private fun clearCsd() {
        lastCsdFrame = null
        vps.clear(); sps.clear(); pps.clear()
    }

    /** Called with inputLock held on a new IDR, never mid-frame. */
    private fun reconfigureAtIdr(): Boolean {
        playing = false
        if (!stopDrain()) {
            reportFailure("Moonlight decoder drain did not stop")
            return false
        }
        val previous = codec
        codec = null
        runCatching { previous?.stop() }
        runCatching { previous?.release() }
        val next = newConfiguredDecoder() ?: run {
            reportFailure("Moonlight HDR decoder reconfiguration failed")
            return false
        }
        codec = next
        return try {
            next.start()
            playing = true
            startDrain(next)
            hdrReconfigure = false
            clearCsd()
            true
        } catch (_: Exception) {
            reportFailure("Moonlight HDR decoder restart failed")
            false
        }
    }

    override fun start() {
        synchronized(inputLock) {
            val decoder = codec ?: return
            if (playing || closing) return
            try {
                decoder.start()
                playing = true
                startDrain(decoder)
            } catch (_: Exception) {
                reportFailure("Could not start hardware Moonlight decoder")
            }
        }
    }

    override fun submitDecodeUnit(
        data: ByteArray, length: Int, type: Int, frameNumber: Int, frameType: Int,
        frameHostProcessingLatency: Char, receiveTimeUs: Long, enqueueTimeUs: Long,
    ): Int = synchronized(inputLock) {
        if (closing || failed.get() || !playing || codec == null) return@synchronized MoonBridge.DR_NEED_IDR
        if (length <= 0 || length > data.size || length > MAX_NAL_BYTES)
            return@synchronized MoonBridge.DR_NEED_IDR
        val idr = frameType == MoonBridge.FRAME_TYPE_IDR
        val newFrame = lastFrame != frameNumber
        if (hdrReconfigure) {
            if (!idr || !newFrame) return@synchronized MoonBridge.DR_NEED_IDR
            if (!reconfigureAtIdr()) return@synchronized MoonBridge.DR_NEED_IDR
        }
        if (newFrame) {
            val previous = lastFrame
            if (previous != null) {
                val gap = frameNumber - previous - 1
                if (gap in 1..10_000) missing.addAndGet(gap.toLong())
            }
            lastFrame = frameNumber
            if (idr) clearCsd()
        }
        if (type != MoonBridge.BUFFER_TYPE_PICDATA) {
            if (!idr || length > MAX_CSD_BYTES) return@synchronized MoonBridge.DR_NEED_IDR
            val target = when (type) {
                MoonBridge.BUFFER_TYPE_VPS -> vps
                MoonBridge.BUFFER_TYPE_SPS -> sps
                MoonBridge.BUFFER_TYPE_PPS -> pps
                else -> return@synchronized MoonBridge.DR_NEED_IDR
            }
            if (target.size >= 4) return@synchronized MoonBridge.DR_NEED_IDR
            target += data.copyOf(length)
            return@synchronized MoonBridge.DR_OK
        }
        if (idr && lastCsdFrame != frameNumber) {
            val buffers = vps + sps + pps
            if (buffers.isNotEmpty() && !queueInput(buffers.sumOf { it.size }, 0L,
                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG) { buffer -> buffers.forEach { buffer.put(it) } })
                return@synchronized MoonBridge.DR_NEED_IDR
            lastCsdFrame = frameNumber
            vps.clear(); sps.clear(); pps.clear()
        }
        received.incrementAndGet()
        if (frameHostProcessingLatency.code != 0) {
            hostTenthsMs.addAndGet(frameHostProcessingLatency.code.toLong())
            hostSamples.incrementAndGet()
        }
        val timestamp = max(ptsUs + 1, System.nanoTime() / 1000L)
        ptsUs = timestamp
        val flags = if (idr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        if (queueInput(length, timestamp, flags) { it.put(data, 0, length) }) MoonBridge.DR_OK
        else { dropped.incrementAndGet(); MoonBridge.DR_NEED_IDR }
    }

    private inline fun queueInput(length: Int, timestamp: Long, flags: Int,
                                  fill: (java.nio.ByteBuffer) -> Unit): Boolean {
        val decoder = codec ?: return false
        return try {
            val index = decoder.dequeueInputBuffer(3_000)
            if (index < 0) return false
            val buffer = decoder.getInputBuffer(index)
            if (buffer == null || length > buffer.capacity()) {
                decoder.queueInputBuffer(index, 0, 0, timestamp, 0)
                return false
            }
            buffer.clear()
            fill(buffer)
            decoder.queueInputBuffer(index, 0, length, timestamp, flags)
            true
        } catch (_: Exception) {
            reportFailure("Moonlight decoder rejected an input frame")
            false
        }
    }

    private fun drainOutput(decoder: MediaCodec, running: AtomicBoolean) {
        val info = MediaCodec.BufferInfo()
        val next = MediaCodec.BufferInfo()
        while (running.get() && !closing) {
            try {
                var index = decoder.dequeueOutputBuffer(info, 8_000)
                if (index < 0) continue
                var pts = info.presentationTimeUs
                var size = info.size
                // Keep the newest ready picture if the decoder has accumulated a backlog.
                // Never add an extra frame of waiting just to pace an otherwise empty queue.
                for (i in 0 until 3) {
                    val newer = decoder.dequeueOutputBuffer(next, 0)
                    if (newer < 0) break
                    decoder.releaseOutputBuffer(index, false)
                    dropped.incrementAndGet()
                    index = newer
                    pts = next.presentationTimeUs
                    size = next.size
                }
                decoder.releaseOutputBuffer(index, size > 0 && running.get() && !closing)
                if (size > 0 && running.get() && !closing) {
                    rendered.incrementAndGet()
                    decodeMicros.addAndGet((System.nanoTime() / 1000L - pts).coerceIn(0L, 10_000_000L))
                }
            } catch (_: Exception) {
                if (running.get() && !closing) reportFailure("Moonlight hardware decoder output failed")
                return
            }
        }
    }

    internal fun metrics(): MoonlightVideoMetrics = MoonlightVideoMetrics(
        currentMime, decoderName, decodedWidth, decodedHeight, decodedFps, activeHdr,
        received.get(), missing.get(), rendered.get(), dropped.get(), decodeMicros.get(),
        hostTenthsMs.get(), hostSamples.get(),
    )

    override fun getCapabilities(): Int = 0

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        synchronized(inputLock) {
            if (closing || failed.get()) return
            if (enabled && !negotiatedHdr10) {
                reportFailure("Unexpected HDR request to SDR Moonlight decoder")
                return
            }
            val validMetadata = hdrMetadata?.takeIf { moonlightHdrStaticInfo(it) != null }
            val newMetadata = if (enabled) validMetadata else null
            val sameMetadata = hdrMasteringInfo?.contentEquals(newMetadata ?: byteArrayOf()) ?: (newMetadata == null)
            if (activeHdr == enabled && sameMetadata) return
            activeHdr = enabled
            hdrMasteringInfo = newMetadata?.copyOf()
            hdrReconfigure = codec != null
        }
    }

    override fun stop() {
        closing = true
        synchronized(inputLock) {
            playing = false
            if (stopDrain()) runCatching { codec?.stop() }
        }
    }

    override fun cleanup() { finishCleanup() }

    internal fun finishCleanup(): Boolean {
        closing = true
        synchronized(inputLock) {
            playing = false
            if (!stopDrain()) return false
            val current = codec ?: return true
            runCatching { current.stop() }
            if (runCatching { current.release() }.isFailure) return false
            codec = null
            return true
        }
    }

    private fun reportFailure(message: String) {
        if (!closing && failed.compareAndSet(false, true)) onError(message)
    }

    companion object {
        private const val MAX_NAL_BYTES = 4 * 1024 * 1024
        private const val MAX_CSD_BYTES = 64 * 1024
    }
}
