package com.ambient.tv.ui.player.backend

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Conservative automatic plan for seekable local HEVC VOD only. A change in the
 * authored HDR10+ T.35 payload is a candidate scene boundary, not proof of an
 * editorial cut. Refuse ambiguous frame-varying/missing metadata rather than
 * advertise normative scene detection. No URL/credential-derived cache identity.
 */
internal object HdrScenePlanner {
    private const val MAX_SAMPLE = 16 * 1024 * 1024
    private const val MAX_FRAMES = 1_000_000
    private const val MAX_SCENES = 100_000

    internal data class Sample(val ptsUs: Long, val metadata: String)
    internal data class Plan(val ends: List<Long>, val source: Double, val target: Double)

    fun localFile(url: String): File? = runCatching {
        val uri = Uri.parse(url)
        val path = when {
            url.startsWith("/") -> url
            uri.scheme == "file" && uri.authority.isNullOrEmpty() -> uri.path
            else -> null
        } ?: return null
        File(path).canonicalFile.takeIf { it.isFile && it.canRead() }
    }.getOrNull()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    // Extract the registered user-data SEI message from HEVC NAL units. Accept
    // Annex B or four-byte length prefixes only; reject inconsistent payloads.
    internal fun metadata(sample: ByteArray): String? {
        fun start(i: Int): Int = when {
            i + 3 < sample.size && sample[i] == 0.toByte() && sample[i+1] == 0.toByte() &&
                sample[i+2] == 0.toByte() && sample[i+3] == 1.toByte() -> 4
            i + 2 < sample.size && sample[i] == 0.toByte() && sample[i+1] == 0.toByte() && sample[i+2] == 1.toByte() -> 3
            else -> 0
        }
        var result: String? = null
        fun nal(lo: Int, hi: Int): Boolean {
            if (hi-lo < 2) return false
            val type = (sample[lo].toInt() ushr 1) and 63
            if (type != 39 && type != 40) return true
            val rbsp = ByteArray(hi-lo-2)
            var n = 0
            var zeros = 0
            for (i in lo+2 until hi) {
                val v = sample[i].toInt() and 255
                if (zeros >= 2 && v == 3) { zeros = 0; continue }
                rbsp[n++] = sample[i]
                zeros = if (v == 0) zeros+1 else 0
            }
            var p = 0
            fun field(): Int? {
                var value = 0
                while (p < n) {
                    val v = rbsp[p++].toInt() and 255
                    if (value > MAX_SAMPLE-v) return null
                    value += v
                    if (v != 255) return value
                }
                return null
            }
            while (p < n && !(rbsp[p] == 0x80.toByte() && p == n-1)) {
                val kind = field() ?: return false
                val size = field() ?: return false
                if (size > n-p) return false
                if (kind == 4 && size >= 7 &&
                    (rbsp[p].toInt() and 255) == 0xb5 && rbsp[p+1] == 0.toByte() &&
                    rbsp[p+2] == 0x3c.toByte() && rbsp[p+3] == 0.toByte() &&
                    rbsp[p+4] == 1.toByte() && rbsp[p+5] == 4.toByte() && rbsp[p+6] == 1.toByte()) {
                    val hash = hex(MessageDigest.getInstance("SHA-256").digest(rbsp.copyOfRange(p,p+size)))
                    if (result != null && result != hash) return false
                    result = hash
                }
                p += size
            }
            return true
        }
        var i = 0
        if (start(0) != 0) {
            while (i < sample.size) {
                val prefix = start(i)
                if (prefix == 0) return null
                val lo = i+prefix
                var end = lo+2
                while (end < sample.size && start(end) == 0) end++
                if (!nal(lo,end.coerceAtMost(sample.size))) return null
                i = end
            }
        } else {
            while (i < sample.size) {
                if (sample.size-i < 4) return null
                var len = 0L
                repeat(4) { len = (len shl 8) or (sample[i++].toLong() and 255L) }
                if (len < 2 || len > sample.size-i) return null
                val end = i+len.toInt()
                if (!nal(i,end)) return null
                i = end
            }
        }
        return result
    }

    /** Same constant output grid as both adapters. Use the actual PTS of every
     * source sample, not the container duration or rounded movie FPS. */
    internal fun plan(samples: List<Sample>, source: Double, target: Double, vlc: Boolean): Plan? {
        if (samples.size < 2 || samples.size > MAX_FRAMES || !source.isFinite() || !target.isFinite() ||
            source !in 5.0..120.0 || target <= source || target > 120 || target/source > 5) return null
        val sorted = samples.sortedBy { it.ptsUs }
        val origin = sorted.first().ptsUs
        if (origin < 0 || sorted.last().ptsUs-origin > 86_400_000_000L) return null
        val expected = 1_000_000.0/source
        val ends = ArrayList<Long>()
        var tick = 0L
        var previous = 0L
        for (i in sorted.indices) {
            val current = sorted[i].ptsUs-origin
            if (i > 0 && (current <= previous || abs(current-previous-expected) > maxOf(3000.0,expected*.25))) return null
            previous = current
            val end = if (i+1 < sorted.size) sorted[i+1].ptsUs-origin else current+expected.roundToLong()
            val first = tick
            while (if (vlc) (tick*1_000_000.0/target).roundToLong() < end else tick*1_000_000.0/target < end-1.0) {
                if (++tick-first > 8 || tick >= 1_000_000_000L) return null
            }
            if (tick == first) return null
            if (i+1 == sorted.size || sorted[i].metadata != sorted[i+1].metadata) {
                if (ends.size >= MAX_SCENES) return null
                ends += tick
            }
        }
        // Per-frame dynamic metadata cannot identify a stable scene plan.
        if (ends.size > maxOf(1, sorted.size/4)) return null
        return Plan(ends,source,target)
    }

    suspend fun scan(url: String, target: Double, vlc: Boolean, directory: File): HdrSceneSession? = withContext(Dispatchers.IO) {
        val file = localFile(url) ?: return@withContext null
        val length = file.length()
        val modified = file.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024*1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer,0,count)
            }
        }
        val key = hex(digest.digest())
        val extractor = MediaExtractor()
        val plan = try {
            extractor.setDataSource(file.path)
            val video = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            if (video.size != 1) return@withContext null
            val format = extractor.getTrackFormat(video.single())
            if (format.getString(MediaFormat.KEY_MIME) != "video/hevc" ||
                format.getInteger(MediaFormat.KEY_WIDTH).toLong()*format.getInteger(MediaFormat.KEY_HEIGHT) > 1920L*1080 ||
                !format.containsKey(MediaFormat.KEY_FRAME_RATE)) return@withContext null
            val source = runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
                .getOrElse { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
            extractor.selectTrack(video.single())
            val buffer = ByteBuffer.allocate(MAX_SAMPLE)
            val samples = ArrayList<Sample>()
            while (extractor.sampleTrackIndex >= 0) {
                currentCoroutineContext().ensureActive()
                if (samples.size >= MAX_FRAMES) return@withContext null
                buffer.clear()
                val count = extractor.readSampleData(buffer,0)
                if (count <= 0 || count >= MAX_SAMPLE || extractor.sampleTrackIndex != video.single()) return@withContext null
                val bytes = ByteArray(count)
                buffer.position(0);buffer.get(bytes)
                val hdr = metadata(bytes) ?: return@withContext null
                samples += Sample(extractor.sampleTime,hdr)
                if (!extractor.advance()) break
            }
            plan(samples,source,target,vlc) ?: return@withContext null
        } finally { extractor.release() }
        currentCoroutineContext().ensureActive()
        if (file.length() != length || file.lastModified() != modified) return@withContext null
        if (!directory.exists() && !directory.mkdirs()) return@withContext null
        val run = java.nio.file.Files.createTempDirectory(directory.toPath(),"hdr-").toFile()
        try {
            val manifest = File(run,"plan")
            manifest.writeText(buildString {
                appendLine("AMBIENTSVP_SCENE_PLAN_1");appendLine(key)
                plan.ends.forEach { appendLine(it) }
            })
            HdrSceneSession(file,length,modified,key,plan,manifest,File(run,"cache"))
        } catch (e: Exception) { run.deleteRecursively();throw e }
    }
}

internal class HdrSceneSession(
    private val sourceFile: File,
    private val sourceLength: Long,
    private val sourceModified: Long,
    val key: String,
    val plan: HdrScenePlanner.Plan,
    private val manifest: File,
    private val cache: File,
) {
    var mode: Int = 1
        private set
    fun valid() = mode != 0 && sourceFile.length() == sourceLength && sourceFile.lastModified() == sourceModified
    fun advance(): Boolean {
        if (mode != 1 || !valid() || !cache.isFile || cache.length() !in 1L..32_000_000L) return false
        mode = 2
        return true
    }
    fun mpvOptions(): String = ":hdr-pass=$mode:hdr-plan=${manifest.path}:hdr-cache=${cache.path}:hdr-key=$key"
    fun vlcOptions(): List<String> = listOf("hdr-pass=$mode","hdr-plan=${manifest.path}","hdr-cache=${cache.path}","hdr-key=$key")
    fun abort() { mode = 0 }
    fun cleanup() { abort(); manifest.parentFile?.deleteRecursively() }
}
