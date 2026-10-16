package com.ambient.tv.livetv

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads a growing TVHeadend MPEG-TS recording by HTTP range, without a second recording on disk.
 * Players receive a continuous loopback stream; EOF of a range is not EOF of the recording.
 * PCR timestamps, rather than file size/average bitrate, map seek positions to byte offsets.
 */
class RecordingPlaybackBuffer private constructor(
    private val client: OkHttpClient,
    private val source: LiveTvPlaybackSource,
    override val recordingId: String,
    override val oldestWallMs: Long,
    private val isRecording: () -> Boolean,
) : SeekableLiveBuffer {
    private val token = UUID.randomUUID().toString()
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val prefix = "http://127.0.0.1:${server.localPort}/$token/"
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val index = TreeMap<Long, Long>()
    private var pcrOrigin = 0L
    private var pcrPid = -1
    @Volatile private var size = 0L
    @Volatile private var closed = false
    @Volatile private var failure: Exception? = null
    @Volatile override var liveWallMs = oldestWallMs
        private set
    @Volatile override var finished = false
        private set

    private fun <T> request(method: String = "GET", range: LongRange? = null, read: (okhttp3.Response) -> T): T {
        check(!closed) { "Recording playback closed" }
        val call = client.newCall(Request.Builder().url(source.url).apply {
            source.headers.forEach { (key, value) -> header(key, value) }
            header("Accept-Encoding", "identity")
            if (method == "HEAD") head()
            range?.let { header("Range", "bytes=${it.first}-${it.last}") }
        }.build())
        calls += call
        try {
            if (closed) throw IOException("Recording playback closed")
            return call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Recording HTTP ${response.code}")
                if (range != null) {
                    val actual = response.header("Content-Range").orEmpty()
                    if (response.code != 206 || !actual.startsWith("bytes ${range.first}-${range.last}/")) {
                        throw IOException("Recording server does not support byte ranges")
                    }
                }
                read(response)
            }
        } finally { calls -= call }
    }

    private fun readRange(offset: Long, length: Int): ByteArray = request(range = offset..(offset + length - 1)) {
        val bytes = ByteArray(length)
        val input = it.body.byteStream()
        var received = 0
        while (received < length) {
            val count = input.read(bytes, received, length - received)
            if (count < 0) throw IOException("Incomplete recording range")
            received += count
        }
        bytes
    }

    @Synchronized private fun refresh() {
        val length = request(method = "HEAD") { it.header("Content-Length")?.toLongOrNull() }
            ?: throw IOException("Recording has no file length")
        if (length < size) throw IOException("Recording file was replaced")
        size = length - length % PACKET
        if (size < PACKET * 4) throw IOException("Recording has no media yet")
        val start = (size - SAMPLE).coerceAtLeast(0L).let { it - it % PACKET }
        val points = sample(start)
        val last = points.lastOrNull() ?: throw IOException("Recording has no MPEG-TS clock")
        liveWallMs = maxOf(liveWallMs, last.first)
    }

    /** Also records sparse index points. All offsets and reads stay on packet boundaries. */
    private fun sample(offset: Long): List<Pair<Long, Long>> {
        val data = readRange(offset, minOf(SAMPLE.toLong(), size - offset).toInt())
        val points = recordingPcrSamples(data, offset).filter { it.pid == pcrPid }.map {
            oldestWallMs + ((it.ticks - pcrOrigin + PCR_WRAP) % PCR_WRAP) / 90L to it.offset
        }
        for ((wall, byte) in points) {
            val previous = index.floorKey(wall)
            if (previous == null || wall - previous >= 1_000L) index[wall] = byte
        }
        points.lastOrNull()?.let { index[it.first] = it.second }
        return points
    }

    override fun urlAt(wallMs: Long): Pair<String, Long> {
        val target = wallMs.coerceIn(oldestWallMs, liveWallMs)
        return "${prefix}at/$target.ts" to target
    }

    @Synchronized private fun resolve(wallMs: Long): Pair<Long, Long> {
        check(!closed) { "Recording playback closed" }
        failure?.let { throw it }
        val target = wallMs.coerceIn(oldestWallMs, liveWallMs)
        if (target == oldestWallMs) return 0L to oldestWallMs
        var best = index.floorEntry(target)?.let { it.key to it.value } ?: (oldestWallMs to 0L)
        // Refine a sparse section using the actual TS clock (also handles variable bitrate).
        repeat(12) {
            if (target - best.first <= 250L) return best.second to best.first
            val low = index.floorEntry(target) ?: return@repeat
            val high = index.higherEntry(target) ?: return best.second to best.first
            if (high.value - low.value < PACKET * 2) return best.second to best.first
            val fraction = (target - low.key).toDouble() / (high.key - low.key).coerceAtLeast(1L)
            val guess = (low.value + ((high.value - low.value) * fraction).toLong() - SAMPLE / 2)
                .coerceIn(low.value + PACKET, high.value - PACKET).let { it - it % PACKET }
            val points = sample(guess)
            // Retain bounding clocks even when they are closer than the sparse index interval.
            points.lastOrNull { it.first <= target }?.let { index[it.first] = it.second; best = it }
            points.firstOrNull { it.first > target }?.let { index[it.first] = it.second }
        }
        return best.second to best.first
    }

    // Renderer recovery must retain a delayed/paused recording's position, not jump to live.
    override fun reopenedUrl(url: String): Pair<String, Long>? {
        if (!url.startsWith(prefix)) return null
        val parts = url.removePrefix(prefix).removeSuffix(".ts").split('/')
        if (parts.size != 2 || parts[0] != "at") return null
        return parts[1].toLongOrNull()?.let { url to it }
    }

    override fun stop() {
        closed = true
        calls.forEach { it.cancel() }
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = input.readLine()?.split(' ') ?: return
            var headers = 0
            while (!input.readLine().isNullOrEmpty()) {
                if (++headers > 64) return
            }
            val path = request.getOrNull(1).orEmpty().removePrefix("/$token/").split('/')
            val wall = path.getOrNull(1)?.removeSuffix(".ts")?.toLongOrNull()
            val out = socket.getOutputStream()
            if (request.firstOrNull() !in setOf("GET", "HEAD") ||
                !request.getOrNull(1).orEmpty().startsWith("/$token/") || path.size != 2 ||
                path.firstOrNull() != "at" || wall == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            if (request[0] == "HEAD") return
            var position = resolve(wall).first
            while (!closed && failure == null) {
                val available = size - position
                if (available <= 0) {
                    if (finished) break
                    Thread.sleep(200)
                    continue
                }
                val data = readRange(position, minOf(SAMPLE.toLong(), available).toInt())
                out.write(data)
                out.flush()
                position += data.size
            }
        } catch (_: Exception) {
            // Player disconnect / source replacement. The server recording is never stopped here.
        } finally { sockets -= socket; runCatching { socket.close() } }
    }

    private fun begin() {
        Thread({
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                if (sockets.size >= 8) { socket.close(); continue }
                sockets += socket
                Thread({ serve(socket) }, "recording-playback-client").apply { isDaemon = true }.start()
            }
        }, "recording-playback-http").apply { isDaemon = true }.start()
        Thread({
            var errors = 0
            var ticks = 0
            while (!closed && !finished) {
                try {
                    Thread.sleep(1_000)
                    // Fetch the last size AFTER checking completion, so the final bytes are included.
                    val active = if (ticks++ % 3 == 0) isRecording() else true
                    refresh()
                    if (!active) finished = true
                    errors = 0
                } catch (error: Exception) {
                    if (++errors >= 5) { failure = error; break }
                }
            }
        }, "recording-playback-index").apply { isDaemon = true }.start()
    }

    companion object {
        private const val PACKET = 188L
        private const val SAMPLE = 188 * 2048
        private const val PCR_WRAP = 1L shl 33

        /** Blocking. Only publish after the recording has real, seekable MPEG-TS media. */
        fun open(client: OkHttpClient, source: LiveTvPlaybackSource, recording: LiveTvRecording,
                 isRecording: () -> Boolean): RecordingPlaybackBuffer {
            val buffer = RecordingPlaybackBuffer(client, source, recording.uuid, recording.startEpochSeconds * 1000, isRecording)
            try {
                val length = buffer.request(method = "HEAD") { it.header("Content-Length")?.toLongOrNull() }
                    ?: throw IOException("Recording has no file length")
                buffer.size = length - length % PACKET
                if (buffer.size < PACKET * 4) throw IOException("Recording has no media yet")
                val head = buffer.readRange(0, minOf(SAMPLE.toLong(), buffer.size).toInt())
                if ((0..3).any { head[it * PACKET.toInt()] != 0x47.toByte() }) {
                    throw UnsupportedOperationException("Growing recording playback requires an MPEG-TS recording profile")
                }
                val first = recordingPcrSamples(head, 0).firstOrNull() ?: throw IOException("Recording has no MPEG-TS clock yet")
                buffer.pcrOrigin = first.ticks
                buffer.pcrPid = first.pid
                buffer.index[buffer.oldestWallMs] = 0L
                buffer.sample(0)
                buffer.refresh()
                buffer.begin()
                return buffer
            } catch (error: Exception) { buffer.stop(); throw error }
        }
    }
}

internal data class RecordingPcr(val pid: Int, val ticks: Long, val offset: Long)

/** PCR base is a 33-bit, 90 kHz clock; the extension is not needed for millisecond seeking. */
internal fun recordingPcrSamples(data: ByteArray, offset: Long): List<RecordingPcr> = buildList {
    fun byte(i: Int) = data[i].toInt() and 0xff
    for (i in 0..(data.size - 188) step 188) {
        if (byte(i) != 0x47 || byte(i + 1) and 0x80 != 0) continue
        val adaptation = (byte(i + 3) shr 4) and 3
        if (adaptation !in 2..3 || byte(i + 4) !in 7..183 || byte(i + 5) and 0x10 == 0) continue
        val ticks = (byte(i + 6).toLong() shl 25) or (byte(i + 7).toLong() shl 17) or
            (byte(i + 8).toLong() shl 9) or (byte(i + 9).toLong() shl 1) or (byte(i + 10).toLong() shr 7)
        add(RecordingPcr(((byte(i + 1) and 0x1f) shl 8) or byte(i + 2), ticks, offset + i))
    }
}
