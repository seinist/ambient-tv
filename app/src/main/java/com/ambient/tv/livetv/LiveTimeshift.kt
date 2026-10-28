package com.ambient.tv.livetv

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Player-independent timeshift for live TV. The app reads the live MPEG-TS stream into a ring
 * file on the box and hands the player a loopback URL that streams from any buffered moment and
 * then keeps following the recording. Pausing therefore works in Media3, VLC and MPV alike (the
 * buffer keeps filling while the player waits), and "back"/"forward"/"live" reopen the URL at
 * another moment. Only continuous MPEG-TS streams qualify (TVHeadend, most IPTV `.ts` links);
 * HLS playlists and anything else play directly as before.
 */
class TimeshiftBuffer private constructor(
    private val file: File,
    private val capacity: Long,
) : SeekableLiveBuffer {
    private val token = UUID.randomUUID().toString().replace("-", "")
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // wait/notify need a real monitor object
    private val lock = java.lang.Object()
    @Volatile private var written = 0L
    /** End of the range the recorder is writing right now; ring bytes below `reserved - capacity` are gone. */
    @Volatile private var reserved = 0L
    private val aligner = TsPacketAligner()
    /** Upstream reconnects spliced into this buffer (diagnostics and tests). */
    @Volatile internal var splices = 0
        private set
    @Volatile private var stopped = false
    @Volatile private var failure: Throwable? = null
    private val ring = RandomAccessFile(file, "rw").apply { setLength(0) }
    /** (wall clock ms, absolute byte offset), one entry per ~0.5 s of input, oldest first. */
    private val index = ArrayDeque<Pair<Long, Long>>()
    private var server: ServerSocket? = null
    private val upstream = UpstreamOwner()
    private val connections = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())

    override val liveWallMs: Long get() = synchronized(lock) { index.lastOrNull()?.first ?: System.currentTimeMillis() }
    override val oldestWallMs: Long get() = synchronized(lock) { index.firstOrNull { it.second >= written - capacity }?.first ?: liveWallMs }

    /** URL that starts at [wallMs] (clamped to the buffer) and the moment it really starts at. */
    override fun urlAt(wallMs: Long): Pair<String, Long> {
        val (time, offset) = synchronized(lock) {
            val floor = written - capacity
            val candidates = index.filter { it.second >= floor }
            (candidates.lastOrNull { it.first <= wallMs } ?: candidates.firstOrNull() ?: (System.currentTimeMillis() to 0L))
        }
        val aligned = offset - offset % TS_PACKET
        return "http://127.0.0.1:${server!!.localPort}/$token/$aligned.ts" to time
    }

    /** Only URLs belonging to this buffer may be rebased during a renderer restart. */
    private fun ownsUrl(url: String): Boolean =
        url.startsWith("http://127.0.0.1:${server?.localPort}/$token/") &&
            url.removePrefix("http://127.0.0.1:${server?.localPort}/$token/").removeSuffix(".ts").toLongOrNull() != null

    override fun reopenedUrl(url: String): Pair<String, Long>? = if (ownsUrl(url)) liveUrl() else null

    /** A little behind the edge, so the player starts with data instead of waiting for it. */
    override fun liveUrl(): Pair<String, Long> = urlAt(liveWallMs - LIVE_PREROLL_MS)

    override fun stop() {
        stopped = true
        upstream.close()
        synchronized(lock) { lock.notifyAll() }
        runCatching { server?.close() }
        connections.forEach { runCatching { it.close() } }
        runCatching { ring.close() }
        synchronized(activeFiles) { file.delete(); activeFiles.remove(file) }
    }

    private fun record(client: OkHttpClient, url: String, headers: Map<String, String>, firstBytes: ByteArray, firstStream: java.io.InputStream?) {
        var stream = firstStream
        var pending: ByteArray? = firstBytes
        var reconnects = 0
        val buffer = ByteArray(64 * 1024)
        var lastIndex = 0L
        var lostAt = 0L
        val sink: (ByteArray, Int, Int) -> Unit = { bytes, offset, length -> append(bytes, offset, length) }
        try {
            while (!stopped) {
                val input = stream ?: run {
                    if (reconnects++ >= MAX_RECONNECTS) throw IOException("Live stream ended")
                    Thread.sleep(1000)
                    if (stopped) return
                    openUpstream(client, url, headers, upstream).second.also {
                        val dropped = aligner.droppedBytes
                        aligner.startSplice()
                        splices++
                        logTimeshiftLine("Timeshift upstream reconnected attempt=$reconnects/$MAX_RECONNECTS " +
                            "gap_ms=${System.currentTimeMillis() - lostAt} byte=$written " +
                            "dropped_partial=${aligner.droppedBytes - dropped} splice=$splices")
                    }
                }
                stream = null
                try {
                    input.use {
                        pending?.let { bytes -> aligner.feed(bytes, bytes.size, sink); pending = null }
                        while (!stopped) {
                            val n = it.read(buffer)
                            if (n < 0) {
                                lostAt = System.currentTimeMillis()
                                logTimeshiftLine("Timeshift upstream ended (EOF) byte=$written, reconnecting")
                                break
                            }
                            aligner.feed(buffer, n, sink)
                            reconnects = 0
                            val now = System.currentTimeMillis()
                            if (now - lastIndex >= INDEX_INTERVAL_MS) {
                                lastIndex = now
                                synchronized(lock) {
                                    index.addLast(now to written)
                                    val floor = written - capacity
                                    while (index.size > 2 && index[1].second < floor) index.removeFirst()
                                }
                            }
                        }
                    }
                } catch (error: IOException) {
                    if (stopped) return
                    lostAt = System.currentTimeMillis()
                    logTimeshiftLine("Timeshift upstream error byte=$written: ${error.javaClass.simpleName}: ${error.message}, reconnecting")
                }
            }
        } catch (error: Throwable) {
            failure = error
            if (!stopped) logTimeshiftLine("Timeshift recording stopped byte=$written: ${error.message}")
        } finally {
            synchronized(lock) { stopped = stopped || failure != null; lock.notifyAll() }
        }
    }

    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        // Announce the range first: a reader that copies ring bytes meanwhile must know they may
        // already belong to this newer data (see [ringReadStillValid]).
        synchronized(lock) { reserved = written + length }
        var done = 0
        while (done < length) {
            val position = (written + done) % capacity
            val chunk = minOf((length - done).toLong(), capacity - position).toInt()
            synchronized(ring) { ring.seek(position); ring.write(bytes, offset + done, chunk) }
            done += chunk
        }
        synchronized(lock) { written += length; lock.notifyAll() }
    }

    private fun serve() {
        val listener = server ?: return
        while (!stopped) {
            val socket = runCatching { listener.accept() }.getOrNull() ?: return
            connections += socket
            Thread({ handle(socket) }, "timeshift-serve").apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = input.readLine()?.split(' ') ?: return
            while (true) { val header = input.readLine() ?: return; if (header.isEmpty()) break }
            val path = request.getOrNull(1).orEmpty().removePrefix("/")
            val requested = path.substringAfter('/').removeSuffix(".ts").toLongOrNull()
            val out = socket.getOutputStream()
            if (request.firstOrNull() !in setOf("GET", "HEAD") || path.substringBefore('/') != token || requested == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            // No length and no ranges: players treat it as the live stream it is.
            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            if (request[0] == "HEAD") return
            // Teletext reads what the player gets, so its subtitles follow a time-shifted picture.
            val teletextStream = com.ambient.tv.livetv.teletext.LiveTeletext.openTapStream()
            var position: Long = requested
            val chunk = ByteArray(64 * 1024)
            while (!stopped) {
                val available = synchronized(lock) {
                    while (!stopped && written <= position) lock.wait(500)
                    written
                }
                if (stopped) return
                // Fell out of the ring (paused longer than the buffer holds): continue at the oldest data.
                val oldest = ringOldestReadable(reserved, capacity)
                if (position < oldest) {
                    logTimeshiftLine("Timeshift reader fell out of the ring byte=$position -> $oldest (skipped ${oldest - position} bytes)")
                    position = oldest
                }
                val length = minOf(chunk.size.toLong(), available - position, capacity - position % capacity).toInt()
                synchronized(ring) { ring.seek(position % capacity); ring.readFully(chunk, 0, length) }
                // The recorder may have overwritten part of what was just copied: drop it and retry.
                if (!ringReadStillValid(position, reserved, capacity)) continue
                out.write(chunk, 0, length)
                com.ambient.tv.livetv.teletext.LiveTeletext.tap(teletextStream, chunk, length)
                position += length
            }
        } catch (_: Exception) {
            // Player closed the connection (seek, stop, channel change).
        } finally {
            connections -= socket
            runCatching { socket.close() }
        }
    }

    companion object {
        const val TS_PACKET = 188
        private const val INDEX_INTERVAL_MS = 500L
        /** Lead behind the live edge: players start with a few seconds of data instead of ~1.5 s. */
        const val LIVE_PREROLL_MS = LIVE_TIMESHIFT_PREROLL_MS
        private const val MAX_RECONNECTS = 5
        private val activeFiles = mutableSetOf<File>()
        private const val MIN_CAPACITY = 64L * 1024 * 1024

        private fun openUpstream(client: OkHttpClient, url: String, headers: Map<String, String>, owner: UpstreamOwner): Pair<String?, java.io.InputStream> {
            val call = client.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build())
            owner.register(call)
            val response = call.execute()
            if (!response.isSuccessful) { response.close(); throw IOException("HTTP ${response.code}") }
            return response.header("Content-Type") to response.body.byteStream()
        }

        /**
         * Starts recording [url] into [directory] and returns the buffer, or null when the stream is
         * not MPEG-TS (HLS, MP4, …) or there is not enough free space. Blocking; call off the main thread.
         */
        fun start(client: OkHttpClient, url: String, headers: Map<String, String>, directory: File, requestedBytes: Long, owner: UpstreamOwner = UpstreamOwner()): TimeshiftBuffer? {
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
            if (url.substringBefore('?').endsWith(".m3u8", true)) return null
            directory.mkdirs()
            synchronized(activeFiles) {
                directory.listFiles()?.filter { it.name.startsWith("live-") && it.extension == "ts" && it !in activeFiles }
                    ?.forEach { it.delete() }
            }
            val capacity = minOf(requestedBytes, directory.usableSpace - 512L * 1024 * 1024).let { it - it % TS_PACKET }
            if (capacity < MIN_CAPACITY) return null
            try {
                val (type, stream) = openUpstream(client, url, headers, owner)
                if (type?.contains("mpegurl", true) == true) { stream.close(); return null }
                val head = ByteArray(TS_PACKET * 4)
                var filled = 0
                while (filled < head.size) {
                    val n = stream.read(head, filled, head.size - filled)
                    if (n < 0) break
                    filled += n
                }
                // Sync bytes every 188 bytes: MPEG-TS. Anything else is left to the player.
                val sync = (0 until TS_PACKET).firstOrNull { start ->
                    (0..2).all { k -> start + k * TS_PACKET < filled && head[start + k * TS_PACKET] == 0x47.toByte() }
                }
                if (filled < head.size || sync == null) { stream.close(); return null }
                val buffer = synchronized(activeFiles) {
                    val file = File(directory, "live-${System.nanoTime()}.ts")
                    TimeshiftBuffer(file, capacity).also { activeFiles.add(file) }
                }
                owner.register(buffer)
                buffer.upstream.register(owner)
                buffer.server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
                owner.register(buffer) // Stop may have raced with socket creation.
                // The first index entry marks the start of the buffer.
                synchronized(buffer.lock) { buffer.index.addLast(System.currentTimeMillis() to 0L) }
                val recorderClient = client.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
                Thread({ buffer.record(recorderClient, url, headers, head.copyOfRange(sync, filled), stream) }, "timeshift-record").apply { isDaemon = true }.start()
                Thread({ buffer.serve() }, "timeshift-accept").apply { isDaemon = true }.start()
                return buffer
            } catch (error: Throwable) {
                owner.close()
                throw error
            }
        }
    }
}

/** Lead behind the live edge for every seekable live buffer (local ring and server recordings). */
const val LIVE_TIMESHIFT_PREROLL_MS = 4_000L

/** First absolute byte offset that is still intact in a ring of [capacity] while [reserved] is being written; packet-aligned. */
internal fun ringOldestReadable(reserved: Long, capacity: Long): Long {
    val floor = (reserved - capacity).coerceAtLeast(0L)
    return floor + (TimeshiftBuffer.TS_PACKET - floor % TimeshiftBuffer.TS_PACKET) % TimeshiftBuffer.TS_PACKET
}

/** Bytes copied from absolute [position] are intact when the writer (up to [reservedAfterRead]) has not lapped them. */
internal fun ringReadStillValid(position: Long, reservedAfterRead: Long, capacity: Long): Boolean =
    position >= reservedAfterRead - capacity

/**
 * Wall-clock moment to resume at after a restart: [anchorWallMs] + [positionMs], or null when that
 * would be closer to the live edge than [LIVE_TIMESHIFT_PREROLL_MS] or the position is unknown
 * (then the live edge is right).
 */
internal fun timeshiftResumeWallMs(anchorWallMs: Long, positionMs: Long, liveWallMs: Long): Long? {
    // No usable clock (still opening): the live edge, as before, never the start of the stream.
    if (positionMs <= 0L) return null
    val target = anchorWallMs + positionMs
    return target.takeIf { it <= liveWallMs - LIVE_TIMESHIFT_PREROLL_MS }
}

/**
 * Where a restart continues for [url] in [buffer]: the moment the viewer was watching
 * ([positionMs] after [anchorWallMs]) and whether that position was kept. Close to the live edge
 * the buffer's live URL (with preroll); without a usable position (still opening) the URL the
 * buffer reopens anyway. Null when [url] does not belong to [buffer].
 */
internal fun timeshiftUrlKeepingPosition(
    buffer: SeekableLiveBuffer,
    url: String,
    anchorWallMs: Long?,
    positionMs: Long,
): Pair<Pair<String, Long>, Boolean>? {
    val reopened = buffer.reopenedUrl(url) ?: return null
    val start = anchorWallMs ?: reopened.second
    val target = timeshiftResumeWallMs(start, positionMs, buffer.liveWallMs)
    return when {
        target != null -> buffer.urlAt(target) to true
        // Near the edge: a server recording would otherwise reopen at its original start point.
        positionMs > 0L -> buffer.liveUrl() to false
        else -> reopened to false
    }
}

private val localTimeshiftUrl = Regex("""^http://127\.0\.0\.1:\d+/[0-9a-f]{32}/\d+\.ts$""")
private val recordingTimeshiftUrl =
    Regex("""^http://127\.0\.0\.1:\d+/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/at/\d+\.ts$""")

/**
 * A loopback URL served by [TimeshiftBuffer] (`http://127.0.0.1:<port>/<token>/<byte>.ts`) or by
 * [RecordingPlaybackBuffer] (`http://127.0.0.1:<port>/<uuid>/at/<wall-ms>.ts`). Both stream near
 * the live edge, so the players use the same live buffer settings for them.
 */
fun isLiveTimeshiftUrl(url: String): Boolean = localTimeshiftUrl.matches(url) || recordingTimeshiftUrl.matches(url)

/** Only the local [TimeshiftBuffer] loopback, whose served bytes teletext reads along. */
fun isLocalTimeshiftUrl(url: String): Boolean = localTimeshiftUrl.matches(url)

/** Owns blocking network work before and after the recording buffer is published. */
class UpstreamOwner : java.io.Closeable {
    private var closed = false
    private var call: okhttp3.Call? = null
    private var buffer: TimeshiftBuffer? = null
    private var parent: UpstreamOwner? = null
    @Synchronized fun register(value: okhttp3.Call) {
        if (closed) { value.cancel(); throw IOException("Timeshift stopped") }
        call = value
    }
    @Synchronized fun register(value: TimeshiftBuffer) {
        if (closed) { value.stop(); throw IOException("Timeshift stopped") }
        buffer = value
    }
    @Synchronized fun register(value: UpstreamOwner) {
        if (closed) { value.close(); throw IOException("Timeshift stopped") }
        parent = value
    }
    override fun close() {
        val resources = synchronized(this) {
            if (closed) return
            closed = true
            Triple(call, buffer, parent).also { call = null; buffer = null; parent = null }
        }
        resources.first?.cancel()
        resources.third?.close()
        resources.second?.stop()
    }
}

/** The one timeshift buffer of the current live playback; it ends with that playback. */
object LiveTimeshift {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .also { com.ambient.tv.network.CredentialRedirectGuard.install(it) }.build()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var pending: UpstreamOwner? = null
    private var watcher: kotlinx.coroutines.Job? = null
    @Volatile var current: SeekableLiveBuffer? = null
        private set

    private val mutableAnchor = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    /** Wall-clock moment at which the player's current stream starts; null without timeshift. */
    val anchor = mutableAnchor.asStateFlow()

    /** Set by the live player: reopens the stream at a wall-clock moment, or at the live edge for null. */
    @Volatile var seekHandler: ((Long?) -> Unit)? = null

    suspend fun start(context: android.content.Context, route: String, url: String, headers: Map<String, String>, bytes: Long): TimeshiftBuffer? {
        stop()
        val directory = File(context.externalCacheDir ?: context.cacheDir, "timeshift")
        val owner = UpstreamOwner()
        pending = owner
        val buffer = try {
            kotlinx.coroutines.suspendCancellableCoroutine<TimeshiftBuffer?> { continuation ->
                continuation.invokeOnCancellation { owner.close() }
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val result = runCatching { TimeshiftBuffer.start(client, url, headers, directory, bytes, owner) }.getOrNull()
                    continuation.resume(result, onCancellation = { _, _, _ -> owner.close() })
                }
            }
        } catch (error: Throwable) {
            if (pending === owner) pending = null
            owner.close()
            throw error
        }
        if (pending !== owner || buffer == null) {
            if (pending === owner) pending = null
            owner.close()
            return null
        }
        pending = null
        current = buffer
        watch(route, buffer)
        return buffer
    }

    /** A renderer reload must not replay the fixed byte offset of its original URL. */
    fun playbackUrl(url: String, reopening: Boolean): String {
        if (!reopening) return url
        val buffer = current ?: return url
        val reopened = buffer.reopenedUrl(url) ?: return url
        val (liveUrl, wallMs) = if (buffer.recordingId != null) {
            // MPV also opens streams on its worker thread; read the published snapshot here.
            val position = com.ambient.tv.ui.player.BackgroundPlaybackSession.snapshot.value.status.positionMs
            buffer.urlAt((anchor.value ?: reopened.second) + position.coerceAtLeast(0L))
        } else reopened
        setAnchor(wallMs)
        logTimeshiftLine("Timeshift renderer reopen behind_live_ms=${buffer.liveWallMs - wallMs}")
        return liveUrl
    }

    /**
     * Reopen for an audio/route restart that must not jump: the stream continues at the moment the
     * viewer was watching ([positionMs] into the current stream), clamped to the live lead. Returns
     * null when [url] is not a timeshift stream.
     */
    fun playbackUrlKeepingPosition(url: String, positionMs: Long, reason: String): Pair<String, Long>? {
        val buffer = current ?: return null
        val (result, kept) = timeshiftUrlKeepingPosition(buffer, url, anchor.value, positionMs) ?: return null
        setAnchor(result.second)
        logTimeshiftLine("Timeshift restart reason=$reason keep_position=$kept " +
            "behind_live_ms=${buffer.liveWallMs - result.second}")
        return result
    }

    /** Install only after the server recording has become readable. Does not change preferences. */
    fun useRecording(route: String, buffer: SeekableLiveBuffer, wallMs: Long) {
        stop()
        current = buffer
        setAnchor(wallMs)
        watch(route, buffer)
    }

    fun setAnchor(wallMs: Long?) { mutableAnchor.value = wallMs }

    /**
     * Buffers left by an earlier process that ended without cleaning up (closed by the system,
     * force-stopped). Only files last written before [olderThanWallMs]; a buffer started since
     * this process began keeps writing and stays newer. Blocking; call off the main thread.
     */
    fun deleteLeftovers(context: android.content.Context, olderThanWallMs: Long) {
        listOfNotNull(context.externalCacheDir, context.cacheDir).map { File(it, "timeshift") }.distinct().forEach { directory ->
            directory.listFiles()?.filter { isLeftoverBuffer(it.name, it.lastModified(), olderThanWallMs) }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    fun stop() {
        pending?.close(); pending = null
        watcher?.cancel(); watcher = null
        current?.let { buffer -> current = null; Thread { buffer.stop() }.start() }
        mutableAnchor.value = null
    }

    /** Stops the buffer once its playback is gone: closed, or replaced by another title/channel. */
    private fun watch(route: String, buffer: SeekableLiveBuffer) {
        watcher = scope.launch {
            var seen = false
            com.ambient.tv.ui.player.BackgroundPlaybackSession.snapshot.collect { snapshot ->
                val playing = snapshot.metadata?.route
                if (playing == route) seen = true
                val gone = seen && ((!snapshot.active) || (playing != null && playing != route))
                if (gone && current === buffer) stop()
            }
        }
    }
}

/** A timeshift buffer file ("live-….ts") written before [olderThanWallMs]. */
internal fun isLeftoverBuffer(name: String, lastModifiedWallMs: Long, olderThanWallMs: Long): Boolean =
    name.startsWith("live-") && name.endsWith(".ts") && lastModifiedWallMs in 1 until olderThanWallMs
