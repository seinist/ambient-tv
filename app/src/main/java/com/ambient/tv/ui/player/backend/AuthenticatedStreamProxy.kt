package com.ambient.tv.ui.player.backend

import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * libVLC cannot send an Authorization header, and credentials placed in the MRL end up in its
 * native log. This loopback relay keeps them inside the app: VLC opens a credential-free
 * capability URL and the relay performs the authenticated upstream request on its behalf.
 * Nothing in here is ever logged - neither the upstream headers nor the capability URL.
 */
internal class AuthenticatedStreamProxy(
    private val upstreamUrl: String,
    private val upstreamHeaders: Map<String, String>,
) : Closeable {
    private val origin = requireNotNull(upstreamUrl.toHttpUrlOrNull()) { "Invalid media relay URI" }
    private val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    private val routes = HlsRelayRoutes(origin, "/$token")
    private val listener = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val capacity = java.util.concurrent.Semaphore(8)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    private val localOrigin = "http://127.0.0.1:${listener.localPort}"
    val url: String = "$localOrigin/$token"

    init {
        thread(name = "vlc-auth-listener", isDaemon = true) {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                if (!capacity.tryAcquire()) {
                    runCatching { socket.close() }
                    continue
                }
                sockets.add(socket)
                if (closed.get()) {
                    sockets.remove(socket)
                    capacity.release()
                    runCatching { socket.close() }
                    break
                }
                thread(name = "vlc-auth-stream", isDaemon = true) {
                    try { socket.use(::serve) } catch (_: Exception) {
                        // A failed relay just closes the connection; VLC surfaces the error.
                    } finally { sockets.remove(socket); capacity.release() }
                }
            }
        }
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
        socket.soTimeout = 10_000
        socket.tcpNoDelay = true
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream()
        val request = line(input).split(' ')
        var range: String? = null
        var done = false
        repeat(64) {
            if (!done) {
                val header = line(input)
                if (header.isEmpty()) done = true
                else if (header.substringBefore(':').equals("Range", true)) range = header.substringAfter(':').trim()
            }
        }
        if (!done) { status(output, 431, "Request Header Fields Too Large"); return }
        if (request.size != 3 || request[0] !in setOf("GET", "HEAD")) { status(output, 405, "Method Not Allowed"); return }
        val resource = routes.resolve(request[1]) ?: run { status(output, 404, "Not Found"); return }
        val headOnly = request[0] == "HEAD"

        val upstream = Request.Builder().url(resource.url).tag(RelayOrigin::class.java, RelayOrigin(origin)).apply {
            upstreamHeaders.forEach { (name, value) -> header(name, value) }
            // Keep Content-Length/Content-Range byte-exact for seekable recordings.
            header("Accept-Encoding", "identity")
            // Manifest lengths/offsets change when their URIs are rewritten.
            if (resource.playlist) removeHeader("Range") else range?.let { header("Range", it) }
            if (headOnly) head()
        }.build()
        val call = client.newCall(upstream)
        calls.add(call)
        if (closed.get()) call.cancel()
        try {
            call.execute().use { response ->
                if (!response.isSuccessful && response.code != 416) { status(output, response.code, response.message.ifBlank { "Error" }); return }
                val contentType = response.header("Content-Type")
                val dash = response.request.url.encodedPath.endsWith(".mpd", ignoreCase = true) ||
                    contentType?.substringBefore(';')?.trim()?.equals("application/dash+xml", true) == true
                if (dash) { status(output, 501, "DASH manifest relay unsupported"); return }
                val playlist = resource.playlist || vlcHlsPath(response.request.url) || vlcHlsContentType(contentType)
                if (playlist) {
                    if (response.code != 200) { status(output, 502, "Partial playlist unsupported"); return }
                    if (request[1].substringBefore('?') == "/$token") routes.markRootPlaylist()
                    if (headOnly) {
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                        output.flush()
                        return
                    }
                    val rewritten = try {
                        val manifest = readManifest(response.body.byteStream())
                        rewriteVlcHlsManifest(manifest, response.request.url) { child, isPlaylist ->
                            localOrigin + routes.register(child, isPlaylist)
                        }.toByteArray(Charsets.UTF_8)
                    } catch (_: IOException) {
                        status(output, 502, "Unsupported playlist")
                        return
                    }
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${rewritten.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.write(rewritten)
                    output.flush()
                    return
                }
                val head = StringBuilder("HTTP/1.1 ${response.code} ${response.message.ifBlank { "OK" }}\r\nConnection: close\r\n")
                RELAYED_HEADERS.forEach { name -> response.header(name)?.let { head.append(name).append(": ").append(it).append("\r\n") } }
                output.write(head.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1))
                output.flush()
                if (headOnly) return
                // VLC sends nothing after its request, so EOF or a reset on this side means it
                // hung up. Cancel upstream right away instead of waiting for the next write to
                // fail: a stalled live stream would otherwise keep its server subscription open.
                socket.soTimeout = 0
                thread(name = "vlc-auth-watch", isDaemon = true) {
                    runCatching { while (input.read() >= 0) { } }
                    call.cancel()
                }
                val body = response.body.byteStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = body.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        } finally {
            calls.remove(call)
        }
    }

    private fun readManifest(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > 2 * 1024 * 1024) throw IOException("Manifest too large")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun status(output: OutputStream, code: Int, message: String) {
        output.write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        calls.forEach { it.cancel() }
        sockets.forEach { runCatching { it.close() } }
    }

    private data class RelayOrigin(val url: okhttp3.HttpUrl)

    private companion object {
        val RELAYED_HEADERS = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges")

        // Deliberately without any logging interceptor. The read timeout matches the Media3
        // streaming client so a dead live stream ends instead of buffering forever.
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                // The upstream headers carry server credentials: never to a redirect elsewhere.
                .let(com.ambient.tv.network.CredentialRedirectGuard::install)
                // A custom caller header or cookie can also be a credential. Never pass any
                // supplied header across an origin boundary via a redirect.
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val original = request.tag(RelayOrigin::class.java)?.url
                    if (original != null && !com.ambient.tv.network.CredentialRedirectGuard.keeps(original, request.url))
                        throw java.io.IOException("Cross-origin media relay redirect refused")
                    chain.proceed(request)
                }
                .build()
        }
    }
}

internal fun vlcHttpRelaySupported(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return !parsed.encodedPath.endsWith(".mpd", ignoreCase = true)
}

internal fun vlcDirectHttpRelaySupported(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    val path = parsed.encodedPath.lowercase(java.util.Locale.ROOT)
    return !path.endsWith(".m3u8") && !path.endsWith(".mpd") && !path.endsWith(".m3u")
}

/** Do not forward arbitrary primary-stream headers to another host or port. */
internal fun vlcSameHttpOrigin(first: String, second: String): Boolean {
    val a = first.toHttpUrlOrNull() ?: return false
    val b = second.toHttpUrlOrNull() ?: return false
    return a.scheme == b.scheme && a.host == b.host && a.port == b.port
}
