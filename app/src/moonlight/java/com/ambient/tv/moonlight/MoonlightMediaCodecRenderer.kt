package com.ambient.tv.moonlight

import android.media.MediaCodec
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
    private val capabilities: Int,
    private val onError: (String, Int) -> Unit,
) : VideoDecoderRenderer() {
    private val inputLock = Any()
    @Volatile private var codec: MediaCodec? = null
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
    @Volatile private var negotiatedFormat = 0
    @Volatile private var hdrSignal: Boolean? = null
    private var codecStarted = false
    private var forceRecovery = false
    private var recoveryAttempts = 0
    private val presented = AtomicLong()
    private var presentationThread: android.os.HandlerThread? = null
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
    private val assemblyLatency = MoonlightLatencyHistogram()
    private val releaseLatency = MoonlightLatencyHistogram()
    private val presentationLatency = MoonlightLatencyHistogram()
    private data class PresentationSample(val sampledAt: Long = 0L, val delayUs: Long = 0L)
    @Volatile private var presentationSample = PresentationSample()
    private val directSubmit get() = capabilities and MoonBridge.CAPABILITY_DIRECT_SUBMIT != 0

    override fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int =
        synchronized(inputLock) {
            if (!surface.isValid || closing || codec != null) return@synchronized -1
            val mime = MoonlightHardwareVideo.mimeFor(format) ?: return@synchronized -1
            negotiatedFormat = format
            negotiatedHdr10 = MoonlightHardwareVideo.isHdr10Format(format)
            if (requested.hdrMode == "on" && !negotiatedHdr10) {
                reportFailure("Moonlight negotiated SDR instead of required HDR", format)
                return@synchronized -1
            }
            // A 10-bit codec alone does not establish PQ. Wait for the native HDR signal.
            activeHdr = negotiatedHdr10 && hdrSignal == true
            currentMime = mime
            decodedWidth = width
            decodedHeight = height
            decodedFps = redrawRate.takeIf { it in 1..240 } ?: requested.fps
            val created = newConfiguredDecoder() ?: run {
                reportFailure("Moonlight hardware decoder could not be configured or started", format)
                return@synchronized -1
            }
            codec = created
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
            decodedFps, hdr10 = negotiatedHdr10).filter {
                MoonlightHardwareVideo.candidateMatchesCapabilities(it, currentMime, decodedHeight, capabilities)
            }
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
                    decoder.start()
                    codecStarted = true
                    decoderName = info.name
                    val handlerThread = presentationThread ?: android.os.HandlerThread("moonlight-frame-presented").also {
                        it.start()
                        presentationThread = it
                    }
                    runCatching {
                        decoder.setOnFrameRenderedListener({ renderedCodec, presentationUs, renderNanos ->
                            if (codec === renderedCodec && !closing) {
                                presented.incrementAndGet()
                                val elapsed = renderNanos / 1000L - presentationUs
                                presentationLatency.recordUs(elapsed)
                                val previous = presentationSample
                                if (elapsed in 0L..1_000_000L && renderNanos - previous.sampledAt >= 50_000_000L) {
                                    val filtered = if (previous.sampledAt == 0L ||
                                        renderNanos - previous.sampledAt > 1_000_000_000L) elapsed
                                        else (previous.delayUs * 3 + elapsed) / 4
                                    presentationSample = PresentationSample(renderNanos, filtered)
                                }
                            }
                        },
                            android.os.Handler(handlerThread.looper))
                    }
                    return decoder
                } catch (_: Exception) {
                    if (runCatching { decoder.release() }.isFailure) {
                        // Keep the handle for explicit teardown. Never open another codec over it.
                        codec = decoder
                        reportFailure("Moonlight decoder candidate could not be released")
                        return null
                    }
                    codecStarted = false
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
        if (!releaseCodecLocked()) {
            reportFailure("Moonlight previous decoder could not be released")
            return false
        }
        val next = newConfiguredDecoder() ?: run {
            reportFailure("Moonlight HDR decoder reconfiguration failed", negotiatedFormat)
            return false
        }
        codec = next
        return try {
            playing = true
            startDrain(next)
            hdrReconfigure = false
            forceRecovery = false
            clearCsd()
            true
        } catch (_: Exception) {
            reportFailure("Moonlight HDR decoder restart failed", negotiatedFormat)
            false
        }
    }

    override fun start() {
        synchronized(inputLock) {
            val decoder = codec ?: return
            if (playing || closing) return
            try {
                if (!codecStarted) { decoder.start(); codecStarted = true }
                playing = true
                startDrain(decoder)
            } catch (_: Exception) {
                reportFailure("Could not start hardware Moonlight decoder", negotiatedFormat)
            }
        }
    }

    override fun submitDecodeUnit(
        data: ByteArray, length: Int, type: Int, frameNumber: Int, frameType: Int,
        frameHostProcessingLatency: Char, receiveTimeUs: Long, enqueueTimeUs: Long,
    ): Int = submitUnit(data, length, type, frameNumber, frameType, frameHostProcessingLatency, receiveTimeUs, enqueueTimeUs)

    private var fallbackPicture = ByteArray(0)

    override fun supportsDirectPicture(): Boolean = true

    // Some vendor codecs expose non-direct buffers. Copy via one bounded reusable
    // array only on that compatibility path, inside the synchronous native callback.
    private fun copyPicture(buffer: java.nio.ByteBuffer, length: Int): Boolean {
        if (buffer.isReadOnly) return false
        if (buffer.isDirect && MoonBridge.copyActivePictureData(buffer, length)) return true
        if (fallbackPicture.size < length) fallbackPicture = ByteArray(length)
        if (!MoonBridge.copyActivePictureArray(fallbackPicture, length)) return false
        buffer.put(fallbackPicture, 0, length)
        return true
    }

    override fun submitDirectPicture(length: Int, frameNumber: Int, frameType: Int,
        hostLatency: Char, receiveTimeUs: Long, enqueueTimeUs: Long): Int =
        submitUnit(null, length, MoonBridge.BUFFER_TYPE_PICDATA, frameNumber, frameType, hostLatency, receiveTimeUs, enqueueTimeUs)

    private fun submitUnit(data: ByteArray?, length: Int, type: Int, frameNumber: Int, frameType: Int,
        frameHostProcessingLatency: Char, receiveTimeUs: Long, enqueueTimeUs: Long): Int = synchronized(inputLock) {
        if (closing || failed.get() || !playing || codec == null) return@synchronized MoonBridge.DR_NEED_IDR
        if (length <= 0 || (data != null && length > data.size) || length > MAX_NAL_BYTES)
            return@synchronized MoonBridge.DR_NEED_IDR
        val idr = frameType == MoonBridge.FRAME_TYPE_IDR
        val newFrame = lastFrame != frameNumber
        if (hdrReconfigure || forceRecovery) {
            if (directSubmit) {
                reportFailure("Moonlight direct-submit decoder needs a reconnect")
                return@synchronized MoonBridge.DR_NEED_IDR
            }
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
            if (data == null || !idr || length > MAX_CSD_BYTES) return@synchronized MoonBridge.DR_NEED_IDR
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
                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG) { buffer -> buffers.forEach { buffer.put(it) }; true })
                return@synchronized MoonBridge.DR_NEED_IDR
            lastCsdFrame = frameNumber
            vps.clear(); sps.clear(); pps.clear()
        }
        received.incrementAndGet()
        assemblyLatency.recordUs(enqueueTimeUs - receiveTimeUs)
        if (frameHostProcessingLatency.code != 0) {
            hostTenthsMs.addAndGet(frameHostProcessingLatency.code.toLong())
            hostSamples.incrementAndGet()
        }
        val timestamp = max(ptsUs + 1, System.nanoTime() / 1000L)
        ptsUs = timestamp
        val flags = if (idr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        if (queueInput(length, timestamp, flags) { buffer ->
                if (data == null) copyPicture(buffer, length)
                else { buffer.put(data, 0, length); true }
            }) MoonBridge.DR_OK
        else { dropped.incrementAndGet(); MoonBridge.DR_NEED_IDR }
    }

    private inline fun queueInput(length: Int, timestamp: Long, flags: Int,
                                  fill: (java.nio.ByteBuffer) -> Boolean): Boolean {
        val decoder = codec ?: return false
        return try {
            val timeout = moonlightDecoderInputTimeoutUs(requested.pacing, decodedFps, directSubmit,
                flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
            val index = decoder.dequeueInputBuffer(timeout)
            if (index < 0) return false
            val buffer = decoder.getInputBuffer(index)
            if (buffer == null || length > buffer.capacity()) {
                decoder.queueInputBuffer(index, 0, 0, timestamp, 0)
                return false
            }
            buffer.clear()
            if (!fill(buffer)) {
                decoder.queueInputBuffer(index, 0, 0, timestamp, 0)
                return false
            }
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
        val pacer = MoonlightFramePacer(requested.pacing, decodedFps)
        while (running.get() && !closing) {
            try {
                var index = decoder.dequeueOutputBuffer(info, 8_000)
                if (index < 0) {
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) pacer.reset()
                    continue
                }
                if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    decoder.releaseOutputBuffer(index, false)
                    continue
                }
                var pts = info.presentationTimeUs
                var size = info.size
                // Keep the newest ready picture if the decoder has accumulated a backlog.
                // Never add an extra frame of waiting just to pace an otherwise empty queue.
                var now = System.nanoTime()
                val deadline = now + 1_000_000L
                for (i in 0 until 16) {
                    if (now >= deadline || !pacer.shouldDrain(pts, now)) break
                    val newer = decoder.dequeueOutputBuffer(next, 0)
                    if (newer < 0) {
                        if (newer == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) pacer.reset()
                        break
                    }
                    // Metadata or empty output must not displace a ready picture.
                    if (next.size <= 0 || next.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        decoder.releaseOutputBuffer(newer, false)
                        now = System.nanoTime()
                        continue
                    }
                    decoder.releaseOutputBuffer(index, false)
                    dropped.incrementAndGet()
                    index = newer
                    pts = next.presentationTimeUs
                    size = next.size
                    now = System.nanoTime()
                }
                val render = size > 0 && running.get() && !closing
                // A real monotonic timestamp lets Surface drop superseded frames in the
                // same VSync interval, like the upstream low-latency pacing mode.
                if (render) decoder.releaseOutputBuffer(index, pacer.presentationTime(System.nanoTime()))
                else decoder.releaseOutputBuffer(index, false)
                if (render) {
                    rendered.incrementAndGet()
                    val elapsedUs = (System.nanoTime() / 1000L - pts).coerceIn(0L, 10_000_000L)
                    decodeMicros.addAndGet(elapsedUs)
                    releaseLatency.recordUs(elapsedUs)
                }
            } catch (_: Exception) {
                if (running.get() && !closing) reportFailure("Moonlight hardware decoder output failed")
                return
            }
        }
    }

    internal fun metrics(): MoonlightVideoMetrics {
        val timing = presentationSample
        return MoonlightVideoMetrics(
        currentMime, decoderName, decodedWidth, decodedHeight, decodedFps, activeHdr,
        received.get(), missing.get(), rendered.get(), dropped.get(), decodeMicros.get(),
        hostTenthsMs.get(), hostSamples.get(), presented.get(),
        assemblyLatency.p95Ms(), releaseLatency.p95Ms(), presentationLatency.p95Ms(),
        timing.delayUs.takeIf { timing.sampledAt != 0L }?.div(1000.0), timing.sampledAt,
        )
    }

    /** Called from the telemetry worker; next decoder input requests a fresh IDR. */
    internal fun requestRecovery(): Boolean = synchronized(inputLock) {
        if (closing || failed.get() || !playing || recoveryAttempts >= 1) return@synchronized false
        recoveryAttempts++
        if (directSubmit) reportFailure("Moonlight direct-submit decoder stalled") else forceRecovery = true
        true
    }

    override fun getCapabilities(): Int = capabilities

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        synchronized(inputLock) {
            if (closing || failed.get()) return
            hdrSignal = enabled
            if (enabled && negotiatedFormat == 0) {
                hdrMasteringInfo = hdrMetadata?.takeIf { moonlightHdrStaticInfo(it) != null }?.copyOf()
                return
            }
            if (enabled && !negotiatedHdr10) {
                reportFailure("Unexpected HDR request to SDR Moonlight decoder")
                return
            }
            val validMetadata = hdrMetadata?.takeIf { moonlightHdrStaticInfo(it) != null }
            val newMetadata = if (enabled) validMetadata?.copyOf(24) else null
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
            if (stopDrain() && codecStarted && runCatching { codec?.stop() }.isSuccess) codecStarted = false
        }
    }

    override fun cleanup() { finishCleanup() }

    internal fun finishCleanup(): Boolean {
        closing = true
        synchronized(inputLock) {
            playing = false
            if (!stopDrain()) return false
            if (!releaseCodecLocked()) return false
            presentationThread?.quitSafely()
            presentationThread = null
            fallbackPicture = ByteArray(0)
            return true
        }
    }

    private fun releaseCodecLocked(): Boolean {
        val current = codec ?: return true
        if (codecStarted && runCatching { current.stop() }.isSuccess) codecStarted = false
        if (runCatching { current.release() }.isFailure) return false
        codec = null
        codecStarted = false
        return true
    }

    private fun reportFailure(message: String, rejectedFormat: Int = 0) {
        if (!closing && failed.compareAndSet(false, true)) onError(message, rejectedFormat)
    }

    companion object {
        private const val MAX_NAL_BYTES = 4 * 1024 * 1024
        private const val MAX_CSD_BYTES = 64 * 1024
    }
}
