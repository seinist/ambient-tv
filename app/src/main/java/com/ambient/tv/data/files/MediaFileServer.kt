package com.ambient.tv.data.files

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface SeekableMedia : Closeable {
    val size: Long
    fun read(position: Long, buffer: ByteArray, count: Int): Int
    /** True when [read] may run on several threads at once (network sources): enables read-ahead. */
    val parallelReads: Boolean get() = false
}

/** A private loopback bridge gives all three renderers the same seekable, read-only transport.
 * Credentials and file paths are never part of the URL. No socket or worker exists until playback.
 *
 * Network sources are slow to open (SMB: connect, login, tree connect, create; NFS: portmap,
 * mount, lookups), and every seek is a new player request: an opened handle is therefore kept
 * for a short while and reused. Sequential reads on network sources run several chunks ahead in
 * parallel, so throughput is no longer one read size per round trip (which matters through the
 * WireGuard tunnel). A read that fails mid-stream is resumed on a freshly opened handle. */
@Singleton
class MediaFileServer @Inject constructor() : Closeable {
    private class Idle(val media: SeekableMedia, val since: Long)
    private class Resource(val sourceId: String, val mime: String, val open: () -> SeekableMedia) {
        val idle = ArrayDeque<Idle>()
        /** Handles serving a request right now. */
        var inUse = 0
        /** How long the last open took: waiting for a busy handle is only worth up to that. */
        var openMs = 0L
        /** Largest piece the server returns per read (SMB MaxReadSize, NFS rtmax): the read-ahead unit. */
        @Volatile var readSize = CHUNK
    }
    private val resources = LinkedHashMap<String, Resource>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private var listener: ServerSocket? = null
    private var workers: ThreadPoolExecutor? = null
    private var reaper: ScheduledExecutorService? = null
    private val readers = Executors.newCachedThreadPool { task -> Thread(task, "file-read-ahead").apply { isDaemon = true } }

    /**
     * [fileName] is appended as a last path segment ("…/<token>/subs.srt"): some players pick a
     * subtitle parser from the URL's extension. It carries no meaning for the server.
     */
    @Synchronized fun register(sourceId: String, mime: String, fileName: String? = null, open: () -> SeekableMedia): String {
        start()
        val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        resources[token] = Resource(sourceId, mime.takeIf { it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) } ?: "application/octet-stream", open)
        // Room for a video plus its subtitles for a few recent titles.
        while (resources.size > 64) resources.remove(resources.keys.first())?.let(::closeIdle)
        val suffix = fileName?.let { name -> "/" + name.filter { it.isLetterOrDigit() || it in "._-" }.take(80).ifEmpty { "file" } }.orEmpty()
        return "http://127.0.0.1:${listener!!.localPort}/$token$suffix"
    }
    @Synchronized fun revoke(sourceId: String) {
        resources.entries.filter { it.value.sourceId == sourceId }.forEach { closeIdle(it.value); resources.remove(it.key) }
    }
    @Synchronized private fun start() {
        if (listener != null) return
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue(), { task -> Thread(task, "file-stream").apply { isDaemon = true } })
        listener = server; workers = pool
        reaper = Executors.newSingleThreadScheduledExecutor { Thread(it, "file-handle-reaper").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ reapIdle() }, IDLE_MS, IDLE_MS / 2, TimeUnit.MILLISECONDS) }
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                try { pool.execute { try { socket.use { serve(it) } } finally { sockets.remove(socket) } } }
                catch (_: java.util.concurrent.RejectedExecutionException) { sockets.remove(socket); socket.close() }
            }
        }, "file-listener").apply { isDaemon = true; start() }
    }

    /** An idle handle of [resource], or a new one. */
    private fun acquire(resource: Resource): SeekableMedia {
        synchronized(this) {
            // A seek: the player hangs up and asks again at once, while the previous request's
            // handle still finishes its reads. Waiting for it is cheaper than opening a new one
            // (many round trips through a tunnel), so wait up to what an open costs.
            val deadline = System.nanoTime() + minOf(resource.openMs, MAX_HANDLE_WAIT_MS) * 1_000_000
            while (resource.idle.isEmpty() && resource.inUse > 0) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                monitorWait(left)
            }
            resource.inUse++
            resource.idle.removeFirstOrNull()?.let { return it.media }
        }
        val started = System.nanoTime()
        try {
            return resource.open().also { synchronized(this) { resource.openMs = (System.nanoTime() - started) / 1_000_000 } }
        } catch (error: Exception) {
            synchronized(this) { resource.inUse--; monitorNotifyAll() }
            throw error
        }
    }

    /** Keeps a healthy handle for the next request (seek) of the same file; closes it otherwise. */
    private fun release(resource: Resource, media: SeekableMedia?, healthy: Boolean) {
        val kept = synchronized(this) {
            resource.inUse--
            val current = media != null && healthy && listener != null && resources.values.any { it === resource } && resource.idle.size < MAX_IDLE_PER_FILE
            if (current) resource.idle.addLast(Idle(media, System.nanoTime()))
            monitorNotifyAll()
            current
        }
        if (!kept) runCatching { media?.close() }
    }


    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun monitorWait(timeoutMs: Long) {
        (this as java.lang.Object).wait(timeoutMs)
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun monitorNotifyAll() {
        (this as java.lang.Object).notifyAll()
    }

    /** Caller holds the monitor; closing (network round trips) happens off it. */
    private fun closeIdle(resource: Resource) {
        val handles = resource.idle.map { it.media }
        resource.idle.clear()
        if (handles.isNotEmpty()) readers.execute { handles.forEach { runCatching { it.close() } } }
    }

    private fun reapIdle() {
        val expired = mutableListOf<SeekableMedia>()
        synchronized(this) {
            val limit = System.nanoTime() - IDLE_MS * 1_000_000
            resources.values.forEach { resource ->
                while (resource.idle.firstOrNull()?.let { it.since < limit } == true) expired += resource.idle.removeFirst().media
            }
        }
        expired.forEach { runCatching { it.close() } }
    }

    private fun line(input: InputStream): String {
        val result = StringBuilder()
        while (result.length < 4096) {
            val ch = input.read(); if (ch < 0) throw java.io.EOFException()
            if (ch == 10) return result.toString().trimEnd('\r')
            result.append(ch.toChar())
        }
        throw java.io.IOException("Header too large")
    }
    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            val input = socket.getInputStream().buffered()
            val request = line(input).split(' ')
            if (request.size != 3 || request[0] !in setOf("GET", "HEAD")) { response(socket, "405 Method Not Allowed", 0); return }
            var range: String? = null
            var done = false
            repeat(40) {
                if (!done) {
                    val h = line(input)
                    if (h.isEmpty()) done = true
                    else if (h.substringBefore(':').equals("Range", true)) {
                        if (range != null) throw IllegalArgumentException("Duplicate range")
                        range = h.substringAfter(':').trim()
                    }
                }
            }
            if (!done) { response(socket, "431 Request Header Fields Too Large", 0); return }
            val token = request[1].removePrefix("/").substringBefore('/').substringBefore('?')
            val resource = synchronized(this) { resources[token] }
            if (resource == null) { response(socket, "404 Not Found", 0); return }
            val handle = Handle(resource)
            try {
                val file = try { handle.media } catch (_: Exception) { response(socket, "503 Service Unavailable", 0); return }
                val selected = try { ByteRange.parse(range, file.size) } catch (_: IllegalArgumentException) {
                    response(socket, "416 Range Not Satisfiable", 0, listOf("Content-Range: bytes */${file.size}")); return
                }
                val headers = mutableListOf("Accept-Ranges: bytes", "Content-Type: ${resource.mime}", "Cache-Control: no-store")
                if (range != null) headers += "Content-Range: bytes ${selected.first}-${selected.last}/${file.size}"
                val count = if (file.size == 0L) 0 else selected.last - selected.first + 1
                response(socket, if (range == null) "200 OK" else "206 Partial Content", count, headers)
                if (request[0] == "HEAD") return
                val output = socket.getOutputStream()
                if (file.parallelReads && count > CHUNK) streamAhead(handle, selected.first, count, output)
                else streamSequential(handle, selected.first, count, output)
            } finally { handle.release() }
        } catch (_: Exception) {
            // A failed/open-ended response closes the connection; native players surface the error.
            // Never log SMB credentials, opaque document IDs or the capability URL.
        }
    }

    private fun streamSequential(handle: Handle, from: Long, count: Long, output: java.io.OutputStream) {
        var position = from; var remaining = count
        val buffer = ByteArray(256 * 1024)
        while (remaining > 0 && !Thread.currentThread().isInterrupted) {
            val read = handle.read(position, buffer, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            output.write(buffer, 0, read); position += read; remaining -= read
        }
    }

    /** Keeps about [IN_FLIGHT] bytes in flight, one server read per chunk; the player receives them in order. */
    private fun streamAhead(handle: Handle, from: Long, count: Long, output: java.io.OutputStream) {
        val end = from + count
        fun ahead() = (IN_FLIGHT / handle.readSize).coerceIn(MIN_AHEAD, MAX_AHEAD)
        val pending = ArrayDeque<Future<ByteArray>>()
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        var next = from
        fun submit() {
            val start = next; val length = minOf(handle.readSize.toLong(), end - start).toInt()
            next += length
            pending.addLast(readers.submit(Callable { if (stopped.get()) ByteArray(0) else handle.readFully(start, length) }))
        }
        try {
            while (pending.size < ahead() && next < end) submit()
            while (pending.isNotEmpty() && !Thread.currentThread().isInterrupted) {
                val chunk = try { pending.removeFirst().get() } catch (error: ExecutionException) { throw error.cause ?: error }
                while (pending.size < ahead() && next < end) submit()
                output.write(chunk)
                if (chunk.isEmpty()) break
            }
        } finally {
            // Reads still in flight must finish before the handle is reused or closed (a cancelled
            // Future would report done while its read still runs, so they are awaited instead).
            stopped.set(true)
            pending.forEach { runCatching { it.get(READ_DRAIN_SECONDS, TimeUnit.SECONDS) } }
            if (pending.any { !it.isDone }) handle.broken = true
        }
    }

    /** One request's handle: taken from the idle pool or opened, reopened once on a read failure. */
    private inner class Handle(private val resource: Resource) {
        val readSize: Int get() = resource.readSize
        private var current: SeekableMedia? = null
        @Volatile var broken = false
        private var reopens = 0
        private var acquired = false
        private val lock = Any()

        private var released = false

        val media: SeekableMedia get() = synchronized(lock) {
            if (released) throw java.io.IOException("Request finished")
            current ?: (if (acquired) throw java.io.IOException("Source handle lost") else acquire(resource))
                .also { current = it; acquired = true }
        }

        fun read(position: Long, buffer: ByteArray, count: Int): Int {
            while (true) {
                val file = media
                try { return file.read(position, buffer, count) } catch (error: Exception) { recover(file, error) }
            }
        }

        /** One read-ahead chunk; a source may return less per call than asked (server read size). */
        fun readFully(position: Long, length: Int): ByteArray {
            val chunk = ByteArray(length)
            var read = read(position, chunk, length)
            if (read <= 0) return ByteArray(0)
            if (read == length) return chunk
            // Short of the end of the file: the server's read size is smaller; later chunks use it.
            if (position + read < media.size) resource.readSize = read.coerceIn(MIN_CHUNK, CHUNK)
            val out = java.io.ByteArrayOutputStream(length).apply { write(chunk, 0, read) }
            while (out.size() < length) {
                read = read(position + out.size(), chunk, length - out.size())
                if (read <= 0) break
                out.write(chunk, 0, read)
            }
            return out.toByteArray()
        }

        /** Network blip, a server that dropped an idle session: continue on a fresh handle (same file size). */
        private fun recover(failed: SeekableMedia, error: Exception) {
            if (error is InterruptedException || Thread.currentThread().isInterrupted) throw error
            synchronized(lock) {
                if (current !== failed) return // Another read-ahead worker already reopened.
                if (++reopens > MAX_REOPENS) { broken = true; throw error }
                runCatching { failed.close() }
                current = null
                Thread.sleep(REOPEN_BACKOFF_MS * reopens)
                val fresh = try { resource.open() } catch (second: Exception) { broken = true; second.addSuppressed(error); throw second }
                if (fresh.size != failed.size) { runCatching { fresh.close() }; broken = true; throw error }
                current = fresh
            }
        }

        fun release() {
            val file = synchronized(lock) { released = true; if (!acquired) return; acquired = false; current.also { current = null } }
            release(resource, file, !broken)
        }
    }

    private fun response(socket: Socket, status: String, count: Long, extra: List<String> = emptyList()) {
        socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Length: $count\r\nConnection: close\r\n" + extra.joinToString("") { "$it\r\n" } + "\r\n").toByteArray(Charsets.US_ASCII))
    }
    @Synchronized override fun close() {
        listener?.close(); listener = null
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
        workers?.shutdownNow(); workers = null
        reaper?.shutdownNow(); reaper = null
        resources.values.forEach(::closeIdle); resources.clear()
    }

    private companion object {
        const val CHUNK = 1024 * 1024
        const val MIN_CHUNK = 32 * 1024
        const val IN_FLIGHT = 4 * 1024 * 1024
        const val MIN_AHEAD = 4
        const val MAX_AHEAD = 16
        const val MAX_IDLE_PER_FILE = 2
        const val IDLE_MS = 30_000L
        const val MAX_REOPENS = 2
        const val REOPEN_BACKOFF_MS = 500L
        const val READ_DRAIN_SECONDS = 25L
        const val MAX_HANDLE_WAIT_MS = 2_000L
    }
}

internal object ByteRange {
    fun parse(header: String?, size: Long): LongRange {
        require(size >= 0)
        if (header == null) return 0L..(size - 1)
        require(size > 0 && header.startsWith("bytes=") && ',' !in header)
        val bounds = header.removePrefix("bytes=").split('-', limit = 2)
        require(bounds.size == 2)
        if (bounds[0].isEmpty()) {
            val suffix = bounds[1].toLongOrNull(); require(suffix != null && suffix > 0)
            return (size - minOf(size, suffix))..(size - 1)
        }
        val start = bounds[0].toLongOrNull(); require(start != null && start in 0 until size)
        val end = if (bounds[1].isEmpty()) size - 1 else bounds[1].toLongOrNull()
        require(end != null && end >= start)
        return start..minOf(end, size - 1)
    }
}
