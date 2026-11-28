package com.ambient.tv.ui.player.backend

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

/** Decode without a display or playback clock. Only compact spatial luma
 * signatures are retained, never decoded pictures. This estimates editorial
 * cuts; it is not a substitute for measuring the synthesized HDR frames. */
internal object HdrContentSceneDetector {
    const val CELLS = 16 * 9
    internal data class Signature(val pts: Long, val luma: ByteArray)

    internal fun distance(a: ByteArray, b: ByteArray): Pair<Double, Double> {
        require(a.size == CELLS && b.size == CELLS)
        val ah = IntArray(16)
        val bh = IntArray(16)
        var spatial = 0L
        for (i in a.indices) {
            val x = a[i].toInt() and 255
            val y = b[i].toInt() and 255
            spatial += abs(x-y)
            ah[x ushr 4]++
            bh[y ushr 4]++
        }
        val hist = ah.indices.sumOf { abs(ah[it]-bh[it]) }.toDouble() / (2*CELLS)
        return spatial.toDouble() / (255*CELLS) to hist
    }

    private fun isCut(a: ByteArray, b: ByteArray): Boolean {
        val (spatial,hist) = distance(a,b)
        return spatial >= .36 || spatial >= .20 && hist >= .38
    }

    private class CutTracker {
        private var previous: Signature? = null
        private var pending: Signature? = null
        private val result = HashSet<Long>()
        fun push(frame: Signature): Boolean {
            val p = previous
            val candidate = pending
            if ((candidate ?: p)?.let { frame.pts <= it.pts } == true) return false
            if (p == null) { previous = frame;return true }
            if (candidate != null) {
                val (d,h) = distance(p.luma,frame.luma)
                if (isCut(candidate.luma,frame.luma) && d < .08 && h < .12) {
                    pending = null;previous = frame;return true
                }
                result += candidate.pts
                if (result.size > 100_000) return false
                previous = candidate
                pending = null
            }
            if (isCut(previous!!.luma,frame.luma)) pending = frame else previous = frame
            return true
        }
        fun finish(): Set<Long>? {
            pending?.let { result += it.pts }
            return result.takeIf { it.size <= 100_000 }
        }
    }

    internal fun cuts(frames: List<Signature>): Set<Long>? {
        if (frames.size < 2) return null
        val tracker = CutTracker()
        for (frame in frames.sortedBy { it.pts }) if (!tracker.push(frame)) return null
        return tracker.finish()
    }

    private fun signature(image: Image): ByteArray? {
        val p = image.planes.firstOrNull() ?: return null
        val rect = image.cropRect
        if (rect.width() < 16 || rect.height() < 9 || p.rowStride <= 0 || p.pixelStride <= 0) return null
        val tenBit = image.format == ImageFormat.YCBCR_P010
        if (!tenBit && image.format != ImageFormat.YUV_420_888) return null
        val buffer = p.buffer.duplicate()
        val base = buffer.position()
        val out = ByteArray(CELLS)
        // 4x4 samples in each cell reduce aliasing while bounding CPU work.
        for (cy in 0 until 9) for (cx in 0 until 16) {
            var sum = 0
            for (sy in 0 until 4) for (sx in 0 until 4) {
                val x = rect.left + ((cx*8+sx*2+1).toLong()*rect.width()/128).toInt()
                val y = rect.top + ((cy*8+sy*2+1).toLong()*rect.height()/72).toInt()
                val offset = base.toLong()+y.toLong()*p.rowStride+x.toLong()*p.pixelStride
                if (offset < base || offset+(if(tenBit)1 else 0) >= buffer.limit()) return null
                val lo = buffer.get(offset.toInt()).toInt() and 255
                sum += if(tenBit) ((lo or ((buffer.get(offset.toInt()+1).toInt() and 255) shl 8)) ushr 8) else lo
            }
            out[cy*16+cx] = ((sum+8)/16).toByte()
        }
        return out
    }

    suspend fun analyze(file: File, expectedPts: Set<Long>): Set<Long>? {
        if (expectedPts.size !in 2..1_000_000) return null
        val seen = HashSet<Long>()
        val result = scanInternal(file) { pts, _ -> pts in expectedPts && seen.add(pts) }
        return result?.takeIf { seen == expectedPts }
    }

    suspend fun scan(file: File, range: HdrRangeSource? = null, onSample: (Long, ByteArray) -> Boolean): Set<Long>? =
        scanInternal(file,range) { pts, input ->
            val bytes = ByteArray(input.remaining())
            input.get(bytes)
            onSample(pts,bytes)
        }

    private suspend fun scanInternal(file: File, range: HdrRangeSource? = null, onSample: (Long, java.nio.ByteBuffer) -> Boolean): Set<Long>? {
        val expectedPts = HashSet<Long>()
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            if(range!=null) ex.setDataSource(range.fd,0,range.length) else ex.setDataSource(file.path)
            val index = (0 until ex.trackCount).singleOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            val format = ex.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            if (mime != "video/hevc" || format.getInteger(MediaFormat.KEY_WIDTH) !in 32..3840 ||
                format.getInteger(MediaFormat.KEY_HEIGHT) !in 32..2160) return null
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            ex.selectTrack(index)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format,null,null,0)
            decoder.start()
            started = true
            val info = MediaCodec.BufferInfo()
            val tracker = CutTracker()
            var decoded = 0
            var inputEnded = false
            var outputEnded = false
            var lastProgress = android.os.SystemClock.elapsedRealtime()
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                var progress = false
                if (!inputEnded) {
                    val id = decoder.dequeueInputBuffer(0)
                    if (id >= 0) {
                        val input = decoder.getInputBuffer(id) ?: return null
                        input.clear()
                        val size = ex.readSampleData(input,0)
                        if (size < 0) {
                            decoder.queueInputBuffer(id,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val pts = ex.sampleTime
                            if (size > input.capacity() || size !in 1 until (16*1024*1024) ||
                                ex.sampleTrackIndex != index || (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0 ||
                                expectedPts.size >= 1_000_000 || !expectedPts.add(pts)) return null
                            val sample = input.duplicate().apply { position(0); limit(size) }
                            if (!onSample(pts,sample)) return null
                            decoder.queueInputBuffer(id,0,size,pts,0)
                            ex.advance()
                        }
                        progress = true
                    }
                }
                val id = decoder.dequeueOutputBuffer(info,10_000)
                if (id >= 0) {
                    try {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (info.presentationTimeUs !in expectedPts || decoded>=expectedPts.size) return null
                            val image = decoder.getOutputImage(id) ?: return null
                            val sig = image.use { signature(it) } ?: return null
                            if (!tracker.push(Signature(info.presentationTimeUs,sig))) return null
                            decoded++
                        }
                        outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    } finally { decoder.releaseOutputBuffer(id,false) }
                    progress = true
                } else if (id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progress = true
                }
                if (progress) lastProgress = android.os.SystemClock.elapsedRealtime()
                else if (android.os.SystemClock.elapsedRealtime()-lastProgress > 15_000) return null
            }
            if (decoded != expectedPts.size || decoded < 2) return null
            return tracker.finish()
        } finally {
            if (started) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
