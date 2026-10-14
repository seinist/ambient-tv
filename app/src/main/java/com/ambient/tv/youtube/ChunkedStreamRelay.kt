package com.ambient.tv.youtube

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Per-connection timings; no URL, headers, title or account data. */
data class RelayTransferTiming(
    val stage: String,
    val openedAtNs: Long,
    val rangeStart: Long,
    val headOnly: Boolean,
    val upstreamRequests: Int,
    val sentBytes: Long,
    val headersMs: Long?,
    val firstBodyMs: Long?,
)

/**
 * Loopback HTTP relay for players with their own network stack (VLC, MPV).
 *
 * Media3 plays YouTube on every device because it fetches googlevideo through OkHttp with the
 * engine's headers and Android's TLS. VLC and MPV use their own HTTP/TLS code (ffmpeg/mbedTLS,
 * VLC's gnutls) instead, which on phones fails in ways the emulator does not show: certificate
 * stores, header handling, and googlevideo throttling or dropping one open-ended `Range: bytes=0-`
 * request. Through this relay they open a plain `http://127.0.0.1` URL; the relay performs the
 * upstream request with OkHttp and the original headers, in bounded byte ranges like ExoPlayer and
 * yt-dlp, and answers the player with one continuous response including correct Content-Range.
 *
 * Only loopback is bound and every stream has an unguessable path. Optional diagnostics contain
 * numeric transfer timings only; stream URLs and request headers are never passed to them.
 */
class ChunkedStreamRelay(
    private val client: OkHttpClient,
    private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    /** Players probe (VLC walks every MP4 fragment): the first upstream range per request is small. */
    private val firstChunkBytes: Long = minOf(FIRST_CHUNK_BYTES, chunkBytes),
    /** Least recently used streams are forgotten past this; every request refreshes its entry. */
    private val maxEntries: Int = 128,
    /** Upstream answered with an error status (for diagnostics; no URL is passed on). */
    private val onUpstreamStatus: (Int) -> Unit = {},
    private val onTransferTiming: (RelayTransferTiming) -> Unit = {},
) {
    private data class Key(val url: String, val headers: Map<String, String>)

    /**
     * A registered stream. [url] starts as the registered URL and is replaced when [refresh]
     * supplies a new one for an expired link; the player keeps its relay URL throughout.
     */
    private class Entry(val key: Key, val refresh: ((String) -> String?)?) {
        @Volatile var url: String = key.url
        val headers: Map<String, String> get() = key.headers
        private var refreshedAtNs: Long? = null
        private var refreshedUrl: String? = null

        /**
         * After a 403/410: true when the next attempt should use a (possibly just) renewed URL.
         * Concurrent connections of one stream (video seek, prefetch) share one renewal.
         */
        @Synchronized
        fun renew(failedUrl: String): Boolean {
            if (url != failedUrl) return true // Another connection renewed it already.
            val refresh = refresh ?: return false
            refreshedAtNs?.let { if (refreshedUrl == failedUrl && System.nanoTime() - it < RENEW_INTERVAL_NS) return false }
            refreshedAtNs = System.nanoTime()
            refreshedUrl = failedUrl
            val fresh = runCatching { refresh(failedUrl) }.getOrNull()?.takeIf { it.isNotBlank() && it != url } ?: return false
            url = fresh
            return true
        }
    }

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val tokens = HashMap<Key, String>()
    private var listener: ServerSocket? = null

    /**
     * Relay URL for [url]; the same upstream URL and headers always map to the same relay URL.
     * [refresh] (blocking, called on a relay thread) returns a fresh URL for the same stream when
     * upstream rejects the current one as expired (403/410).
     */
    @Synchronized
    fun register(url: String, headers: Map<String, String>, refresh: ((String) -> String?)? = null): String {
        val server = listener?.takeUnless { it.isClosed } ?: start()
        val key = Key(url, headers)
        val token = tokens[key] ?: (UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")).also { token ->
            tokens[key] = token
            entries[token] = Entry(key, refresh)
            while (entries.size > maxEntries) {
                val eldest = entries.keys.first()
                entries.remove(eldest)?.let { tokens.remove(it.key) }
            }
        }
        entries[token] // touch: recently used entries survive longest
        return "http://127.0.0.1:${server.localPort}/$token"
    }

    @Synchronized
    private fun lookup(token: String): Entry? = entries[token]

    private fun start(): ServerSocket {
        val server = ServerSocket(0, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        listener = server
        thread(name = "stream-relay", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(name = "stream-relay-conn", isDaemon = true) {
                    try { socket.use(::serve) } catch (_: Exception) {
                        // A failed relay closes the connection; the player reports or retries.
                    }
                }
            }
        }
        return server
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 15_000
        // Loopback: no Nagle delay on short answers (seeks). The send buffer stays at the system
        // default: a small buffer stops the relay early when the player stops reading.
        socket.tcpNoDelay = true
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream().buffered(64 * 1024)
        val request = line(input).split(' ')
        var rangeHeader: String? = null
        var headersDone = false
        repeat(100) {
            if (!headersDone) {
                val header = line(input)
                if (header.isEmpty()) headersDone = true
                else if (header.substringBefore(':').trim().equals("Range", true)) rangeHeader = header.substringAfter(':').trim()
            }
        }
        if (!headersDone) return status(output, 431, "Request Header Fields Too Large")
        if (request.size != 3 || request[0] !in setOf("GET", "HEAD")) return status(output, 405, "Method Not Allowed")
        val entry = lookup(request[1].removePrefix("/").substringBefore('?')) ?: return status(output, 404, "Not Found")
        val range = parseRange(rangeHeader)
        if (rangeHeader != null && range == null) return status(output, 416, "Range Not Satisfiable")
        // No read timeout while streaming: a paused player stops reading and this thread waits in
        // write() until it reads again or closes the connection (players close it on seek/stop).
        socket.soTimeout = 0
        Transfer(entry, range, request[0] == "HEAD", output).run()
    }

    /**
     * One player request, served from consecutive upstream ranges. The next range is requested
     * while the current one streams (no idle round trip between chunks), and an upstream failure
     * (network drop, a connection googlevideo closed during a long pause, a 5xx) is resumed at
     * the exact byte, up to [MAX_RETRIES] times in a row, without the player noticing.
     */
    private inner class Transfer(
        private val entry: Entry,
        private val range: Pair<Long, Long?>?,
        private val headOnly: Boolean,
        private val output: OutputStream,
    ) {
        private val start = range?.first ?: 0L
        private var end: Long? = range?.second
        private var position = start
        private var answered = false
        private var bodyStarted = false
        private var headersMs: Long? = null
        private var firstBodyMs: Long? = null
        private var upstreamRequests = 0
        private var failures = 0
        private var prefetch: Prefetch? = null
        private val openedAt = System.nanoTime()

        fun run() {
            try {
                while (true) {
                    val last = end
                    if (last != null && position > last) break
                    val size = if (answered) chunkBytes else firstChunkBytes
                    val chunkEnd = minOf(last ?: Long.MAX_VALUE, position + size - 1)
                    val response = try {
                        takePrefetch(position) ?: client.newCall(upstream(position, chunkEnd)).execute()
                    } catch (_: java.io.IOException) {
                        if (retry()) continue else return
                    }
                    when (response.use { handle(it, chunkEnd) }) {
                        Step.CONTINUE -> Unit
                        Step.COMPLETE -> break
                        Step.STOP -> return
                    }
                }
                flush()
            } catch (_: DownstreamClosed) {
                // The player hung up (seek, stop): nothing left to do.
            } finally {
                prefetch?.cancel()
                reportTiming("finished")
            }
        }

        /** Streams one upstream response. */
        private fun handle(response: Response, chunkEnd: Long): Step {
            if (response.code == 206) {
                // Validate the first range too. Otherwise a stale/wrong range is relabelled
                // with the requested offset and corrupts the demuxer immediately after seek.
                val upstreamStart = response.header("Content-Range")
                    ?.takeIf { it.startsWith("bytes ", ignoreCase = true) }?.substringAfter(' ')
                    ?.substringBefore('-')?.trim()?.toLongOrNull()
                if (upstreamStart == null || upstreamStart != position) {
                    if (retry()) return Step.CONTINUE
                    if (!answered) status(output, 502, "Invalid Upstream Range") else flush()
                    return Step.STOP
                }
            }
            if (!answered) {
                if (response.code == 200) {
                    // Upstream ignores ranges: pass the whole body through unchanged.
                    val head = StringBuilder("HTTP/1.1 200 OK\r\nConnection: close\r\n")
                    response.header("Content-Type")?.let { head.append("Content-Type: ").append(it).append("\r\n") }
                    response.header("Content-Length")?.let { head.append("Content-Length: ").append(it).append("\r\n") }
                    write(head.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1))
                    flush()
                    headersMs = (System.nanoTime() - openedAt) / 1_000_000
                    reportTiming("headers")
                    answered = true
                    if (!headOnly) response.body.byteStream().let { body -> runCatching { copy(body) } }
                    flush()
                    return Step.STOP
                }
                if (response.code != 206) {
                    onUpstreamStatus(response.code)
                    if (recoverable(response) && retry()) return Step.CONTINUE
                    status(output, response.code, response.message.ifBlank { "Upstream Error" })
                    return Step.STOP
                }
                val total = response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                if (total != null) end = minOf(end ?: (total - 1), total - 1)
                val head = StringBuilder()
                if (range != null) {
                    head.append("HTTP/1.1 206 Partial Content\r\n")
                    end?.let { last ->
                        head.append("Content-Range: bytes $start-$last/${total ?: "*"}\r\n")
                        head.append("Content-Length: ").append(last - start + 1).append("\r\n")
                    }
                } else {
                    head.append("HTTP/1.1 200 OK\r\n")
                    total?.let { head.append("Content-Length: ").append(it).append("\r\n") }
                }
                head.append("Accept-Ranges: bytes\r\nConnection: close\r\n")
                response.header("Content-Type")?.let { head.append("Content-Type: ").append(it).append("\r\n") }
                write(head.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1))
                flush()
                headersMs = (System.nanoTime() - openedAt) / 1_000_000
                reportTiming("headers")
                answered = true
                if (headOnly) { flush(); return Step.STOP }
            } else {
                if (response.code != 206) {
                    onUpstreamStatus(response.code)
                    // A 5xx is retried; an expired link (403/410) is renewed, then resumed.
                    if (recoverable(response) && retry()) return Step.CONTINUE
                    flush()
                    return Step.STOP
                }
            }
            // Request the next range once half of this one is through and the player has stayed
            // connected for a moment: it is then under way while the rest streams, while short
            // probing reads (VLC walks every MP4 fragment and hangs up within milliseconds) never
            // fetch ahead. Bytes alone are no signal: socket buffers absorb them unread.
            val next = end?.let { last -> if (chunkEnd < last) (chunkEnd + 1) to minOf(last, chunkEnd + chunkBytes) else null }
            val body = response.body.byteStream()
            val before = position
            try {
                copy(body, prefetchAt = before + (chunkEnd - before + 1) / 2, next = next)
            } catch (_: UpstreamFailed) {
                return if (retry()) Step.CONTINUE else Step.STOP
            }
            failures = 0
            if (position == before) return Step.STOP
            // Unknown total length: a short chunk is the end of the stream.
            if (end == null) return if (position - before < chunkEnd - before + 1) Step.COMPLETE else Step.CONTINUE
            return Step.CONTINUE
        }

        /** 5xx: try again. 403/410: googlevideo links expire after hours; renew it via [Entry.refresh]. */
        private fun recoverable(response: Response): Boolean = when (response.code) {
            in 500..599 -> true
            // The URL this request was built from: googlevideo often redirects to another host.
            403 -> {
                // A transient CDN rejection does not necessarily produce a different signed
                // URL. Retry the same byte with bounded backoff even when renewal is unchanged.
                entry.renew(response.request.tag(String::class.java) ?: response.request.url.toString())
                true
            }
            410 -> entry.renew(response.request.tag(String::class.java) ?: response.request.url.toString())
            else -> false
        }

        private fun upstream(from: Long, to: Long): Request {
            val current = entry.url
            return upstreamRequest(current, from, to)
        }

        private fun upstreamRequest(current: String, from: Long, to: Long): Request {
            upstreamRequests++
            return Request.Builder().url(current).tag(String::class.java, current).apply {
                entry.headers.forEach { (name, value) -> header(name, value) }
                header("Accept-Encoding", "identity")
                header("Range", "bytes=$from-$to")
            }.build()
        }

        private fun prefetch(from: Long, to: Long) {
            prefetch?.cancel()
            prefetch = Prefetch(from, client.newCall(upstream(from, to)))
        }

        /** The prefetched response if it starts at [from]; otherwise it is dropped. */
        private fun takePrefetch(from: Long): Response? {
            val pending = prefetch ?: return null
            prefetch = null
            if (pending.from != from) { pending.cancel(); return null }
            return pending.await()
        }

        /** Counts a failure; true (after a short backoff) while another attempt is allowed. */
        private fun retry(): Boolean {
            prefetch?.cancel()
            prefetch = null
            failures++
            if (failures > MAX_RETRIES) return false
            Thread.sleep(RETRY_BACKOFF_MS shl (failures - 1))
            return true
        }

        /**
         * Copies the body, advancing [position]; upstream read and player write errors are told
         * apart. Past [prefetchAt] the [next] range is requested.
         */
        private fun copy(body: InputStream, prefetchAt: Long = Long.MAX_VALUE, next: Pair<Long, Long>? = null) {
            val buffer = ByteArray(64 * 1024)
            var prefetched = next == null
            while (true) {
                val read = try { body.read(buffer) } catch (error: java.io.IOException) { throw UpstreamFailed(error) }
                if (read < 0) return
                if (read == 0) continue
                write(buffer, read)
                // Do not hold a small init box/audio packet until the 64 KiB output fills.
                // Flush only the first packet; retain batching for steady-state throughput.
                position += read
                if (!bodyStarted) {
                    flush()
                    bodyStarted = true
                    firstBodyMs = (System.nanoTime() - openedAt) / 1_000_000
                    reportTiming("first_body")
                }
                if (!prefetched && position >= prefetchAt && shouldPrefetch(System.nanoTime() - openedAt)) {
                    prefetched = true
                    prefetch(next!!.first, next.second)
                }
            }
        }

        private fun write(bytes: ByteArray, length: Int = bytes.size) =
            try { output.write(bytes, 0, length) } catch (error: java.io.IOException) { throw DownstreamClosed(error) }

        private fun flush() = try { output.flush() } catch (error: java.io.IOException) { throw DownstreamClosed(error) }

        private fun reportTiming(stage: String) {
            // Observability must never interrupt delivery or replace the original exception.
            runCatching { onTransferTiming(RelayTransferTiming(stage, openedAt, start, headOnly,
                upstreamRequests, position - start, headersMs, firstBodyMs)) }
        }
    }

    private enum class Step { CONTINUE, COMPLETE, STOP }

    /** Fetching ahead is for streaming connections only, not for probes. */
    internal fun shouldPrefetch(connectedNanos: Long): Boolean = connectedNanos >= PREFETCH_AFTER_NS

    /** An upstream range requested ahead of time on [prefetcher]. */
    private inner class Prefetch(val from: Long, private val call: Call) {
        private val future: Future<Response> = prefetcher.submit(Callable { call.execute() })

        fun await(): Response = try {
            future.get()
        } catch (error: ExecutionException) {
            throw (error.cause as? java.io.IOException) ?: java.io.IOException(error.cause)
        }

        fun cancel() {
            call.cancel()
            future.cancel(false)
            // A response that already arrived must be closed to give its connection back.
            if (future.isDone && !future.isCancelled) runCatching { future.get().close() }
        }
    }

    private class UpstreamFailed(cause: java.io.IOException) : Exception(cause)
    private class DownstreamClosed(cause: java.io.IOException) : Exception(cause)

    private val prefetcher = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "stream-relay-prefetch").apply { isDaemon = true }
    }

    private fun line(input: InputStream): String {
        val result = StringBuilder()
        while (result.length < 8192) {
            val ch = input.read()
            if (ch < 0) throw java.io.EOFException()
            if (ch == 10) return result.toString().trimEnd('\r')
            result.append(ch.toChar())
        }
        throw java.io.IOException("Header too large")
    }

    private fun status(output: OutputStream, code: Int, message: String) {
        output.write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    companion object {
        /** yt-dlp's chunk size for googlevideo; larger single requests get throttled or dropped. */
        const val DEFAULT_CHUNK_BYTES = 10L * 1024 * 1024
        const val FIRST_CHUNK_BYTES = 1L * 1024 * 1024
        /** Resumes in a row before the player's connection is ended. */
        const val MAX_RETRIES = 3
        const val RETRY_BACKOFF_MS = 250L
        const val PREFETCH_AFTER_NS = 250_000_000L
        /** At most one link renewal per rejected URL in this window (a renewal that does not help is not repeated). */
        const val RENEW_INTERVAL_NS = 30_000_000_000L

        /** `bytes=S-` or `bytes=S-E` → (S, E?); suffix ranges and lists are not used by players. */
        internal fun parseRange(header: String?): Pair<Long, Long?>? {
            val spec = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) }?.substring(6)?.trim() ?: return null
            if (spec.contains(',')) return null
            val start = spec.substringBefore('-').trim().toLongOrNull() ?: return null
            val endText = spec.substringAfter('-', "").trim()
            val end = if (endText.isEmpty()) null else endText.toLongOrNull() ?: return null
            if (start < 0 || (end != null && end < start)) return null
            return start to end
        }
    }
}
