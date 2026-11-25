package com.ambient.tv.moonlight

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import com.limelight.nvstream.av.video.VideoDecoderRenderer
import com.limelight.nvstream.jni.MoonBridge
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Low-latency Moonlight NAL-unit -> hardware MediaCodec -> Android Surface path.
 *
 * Input and output use separate threads (the native network callback and a dedicated drain
 * thread). CSD is sent at IDR boundaries. There is no GPU copy or image shader path and no
 * Media3/MPV/VLC involvement. This remains experimental until exercised on real devices.
 */
class MoonlightMediaCodecRenderer(
    private val surface: Surface,
    private val requested: MoonlightStreamProfile,
    private val onError: (String) -> Unit,
) : VideoDecoderRenderer() {
    private val inputLock = Any()
    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null
    @Volatile private var playing = false
    @Volatile private var closing = false
    @Volatile private var failed = false
    private var ptsUs = 0L
    private var decoderName: String? = null
    private var currentMime: String? = null
    private var decodedWidth = 0
    private var decodedHeight = 0
    private var negotiatedHdr10 = false
    private var activeHdr = false
    private var hdrMasteringInfo: ByteArray? = null
    private var hdrReconfigure = false
    private var lastCsdFrame = -1
    private val vps = ArrayList<ByteArray>(2)
    private val sps = ArrayList<ByteArray>(2)
    private val pps = ArrayList<ByteArray>(2)

    override fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int =
        synchronized(inputLock) {
            if (!surface.isValid || closing || codec != null) return@synchronized -1
            val mime = MoonlightHardwareVideo.mimeFor(format) ?: return@synchronized -1
            val hdr = MoonlightHardwareVideo.isHdr10Format(format)
            val decoder = MoonlightHardwareVideo.decoder(mime, width, height, requested.fps, hdr10 = hdr)
                ?: return@synchronized -1
            negotiatedHdr10 = hdr
            activeHdr = hdr
            decoderName = decoder.name
            currentMime = mime
            decodedWidth = width
            decodedHeight = height
            val created = newConfiguredDecoder(decoder.name, mime, width, height)
                ?: return@synchronized -1
            codec = created
            ptsUs = 0L
            lastCsdFrame = -1
            vps.clear(); sps.clear(); pps.clear()
            0
        }

    private fun decoderFormat(mime: String, width: Int, height: Int): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_FRAME_RATE, requested.fps)
            setInteger(MediaFormat.KEY_COLOR_STANDARD,
                if (activeHdr) MediaFormat.COLOR_STANDARD_BT2020 else MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER,
                if (activeHdr) MediaFormat.COLOR_TRANSFER_ST2084 else MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            if (activeHdr) {
                moonlightHdrStaticInfo(hdrMasteringInfo)?.let {
                    setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, it)
                }
            }
            // Hardware can reject these hints; retry without them on configure failure.
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setFloat(MediaFormat.KEY_OPERATING_RATE, requested.fps.toFloat())
        }

    private fun newConfiguredDecoder(name: String, mime: String, width: Int, height: Int): MediaCodec? {
        val format = decoderFormat(mime, width, height)
        fun create(): MediaCodec? {
            val decoder = MediaCodec.createByCodecName(name)
            try {
                decoder.configure(format, surface, null, 0)
                return decoder
            } catch (error: Exception) {
                runCatching { decoder.release() }
                throw error
            }
        }
        return runCatching { create() }.recoverCatching {
            format.removeKey(MediaFormat.KEY_LOW_LATENCY)
            format.removeKey(MediaFormat.KEY_OPERATING_RATE)
            create()
        }.getOrNull()
    }

    /** Called only with inputLock held and the upcoming IDR needed for fresh SPS/PPS. */
    private fun reconfigureAtIdr(): Boolean {
        val name = decoderName ?: return false
        val mime = currentMime ?: return false
        playing = false
        val worker = outputThread
        worker?.interrupt()
        if (worker != null && worker !== Thread.currentThread()) runCatching { worker.join(900) }
        outputThread = null
        val previous = codec
        codec = null
        runCatching { previous?.stop() }
        runCatching { previous?.release() }
        val next = newConfiguredDecoder(name, mime, decodedWidth, decodedHeight)
        if (next == null) {
            reportFailure("Moonlight HDR color transition could not reconfigure decoder")
            return false
        }
        codec = next
        return try {
            next.start()
            playing = true
            outputThread = Thread({ drainOutput(next) }, "moonlight-decoder-output").apply {
                priority = Thread.MAX_PRIORITY - 1
                isDaemon = true
                start()
            }
            hdrReconfigure = false
            ptsUs = 0L
            lastCsdFrame = -1
            vps.clear(); sps.clear(); pps.clear()
            true
        } catch (_: Exception) {
            reportFailure("Moonlight HDR color transition could not start decoder")
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
            } catch (_: Exception) {
                reportFailure("Could not start hardware Moonlight decoder")
                return
            }
            outputThread = Thread({ drainOutput(decoder) }, "moonlight-decoder-output").apply {
                priority = Thread.MAX_PRIORITY - 1
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Moonlight supplies Annex-B H264/HEVC (and AV1) units, often several entries per IDR.
     * Each picture unit is submitted directly, matching the official client's frame handling:
     * buffering until the next frame would add an avoidable 16ms at 60Hz.
     */
    override fun submitDecodeUnit(
        data: ByteArray, length: Int, type: Int, frameNumber: Int, frameType: Int,
        frameHostProcessingLatency: Char, receiveTimeUs: Long, enqueueTimeUs: Long,
    ): Int = synchronized(inputLock) {
        if (closing || failed || !playing || codec == null) return@synchronized MoonBridge.DR_NEED_IDR
        if (hdrReconfigure) {
            if (frameType != MoonBridge.FRAME_TYPE_IDR) return@synchronized MoonBridge.DR_NEED_IDR
            if (!reconfigureAtIdr()) return@synchronized MoonBridge.DR_NEED_IDR
        }
        if (length <= 0 || length > data.size || length > MAX_NAL_BYTES)
            return@synchronized MoonBridge.DR_NEED_IDR
        if (type != MoonBridge.BUFFER_TYPE_PICDATA) {
            val target = when (type) {
                MoonBridge.BUFFER_TYPE_VPS -> vps
                MoonBridge.BUFFER_TYPE_SPS -> sps
                MoonBridge.BUFFER_TYPE_PPS -> pps
                else -> return@synchronized MoonBridge.DR_NEED_IDR
            }
            if (target.size < 4) target += data.copyOf(length)
            return@synchronized MoonBridge.DR_OK
        }
        // Repeated IDR SPS/PPS are a normal recovery signal. Send all CSD together, once per IDR.
        if (frameType == MoonBridge.FRAME_TYPE_IDR && lastCsdFrame != frameNumber) {
            lastCsdFrame = frameNumber
            val config = ByteArrayOutputStream(2048)
            (vps + sps + pps).forEach { config.write(it) }
            val bytes = config.toByteArray()
            if (bytes.isNotEmpty() && !queueInput(bytes, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG))
                return@synchronized MoonBridge.DR_NEED_IDR
            vps.clear(); sps.clear(); pps.clear()
        }
        val timestamp = max(ptsUs + 1, SystemClock.elapsedRealtimeNanos() / 1000L)
        ptsUs = timestamp
        val flags = if (frameType == MoonBridge.FRAME_TYPE_IDR) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        if (queueInput(data, length, timestamp, flags)) MoonBridge.DR_OK else MoonBridge.DR_NEED_IDR
    }

    /** Must be called under inputLock; native callbacks may enter this from any streamer thread. */
    private fun queueInput(data: ByteArray, timestamp: Long, flags: Int): Boolean =
        queueInput(data, data.size, timestamp, flags)

    private fun queueInput(data: ByteArray, length: Int, timestamp: Long, flags: Int): Boolean {
        val decoder = codec ?: return false
        return try {
            val index = decoder.dequeueInputBuffer(3_000)
            if (index < 0) return false
            val buffer = decoder.getInputBuffer(index) ?: return false
            if (length > buffer.capacity()) {
                // An oversized frame cannot be fragmented arbitrarily into codec input packets.
                decoder.queueInputBuffer(index, 0, 0, timestamp, 0)
                return false
            }
            buffer.clear()
            buffer.put(data, 0, length)
            decoder.queueInputBuffer(index, 0, length, timestamp, flags)
            true
        } catch (_: Exception) {
            reportFailure("Moonlight decoder rejected an input frame")
            false
        }
    }

    private fun drainOutput(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (playing && !closing) {
            try {
                val index = decoder.dequeueOutputBuffer(info, 8_000)
                when {
                    index >= 0 -> decoder.releaseOutputBuffer(index, info.size > 0)
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                if (playing && !closing) reportFailure("Moonlight hardware decoder output failed")
                return
            }
        }
    }

    override fun getCapabilities(): Int = 0 // Do not advertise unchecked direct-submit/invalidations.

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        synchronized(inputLock) {
            if (closing || failed) return
            if (enabled && !negotiatedHdr10) {
                reportFailure("Unexpected HDR request to SDR Moonlight decoder")
                return
            }
            if (enabled && hdrMetadata != null && moonlightHdrStaticInfo(hdrMetadata) == null) {
                reportFailure("Sunshine sent malformed HDR mastering metadata")
                return
            }
            val changed = activeHdr != enabled || !hdrMasteringInfo.contentEqualsNullable(hdrMetadata)
            if (!changed) return
            activeHdr = enabled
            hdrMasteringInfo = if (enabled) hdrMetadata?.copyOf() else null
            // The official Moonlight renderer requires a codec restart to apply static metadata.
            // Request IDR and rebuild at that boundary, not mid-frame, so colors never become
            // incorrectly interpreted as BT.709 or lose their color transfer.
            hdrReconfigure = codec != null
        }
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        if (this == null) other == null else other != null && contentEquals(other)

    override fun stop() {
        closing = true
        playing = false
        val thread = outputThread
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread())
            runCatching { thread.join(900) }
        synchronized(inputLock) {
            runCatching { codec?.stop() }
        }
        outputThread = null
    }

    override fun cleanup() {
        stop()
        synchronized(inputLock) {
            val current = codec
            codec = null
            runCatching { current?.release() }
        }
    }

    private fun reportFailure(message: String) {
        if (closing || failed) return
        failed = true
        onError(message)
    }

    companion object {
        private const val MAX_NAL_BYTES = 4 * 1024 * 1024
    }
}
