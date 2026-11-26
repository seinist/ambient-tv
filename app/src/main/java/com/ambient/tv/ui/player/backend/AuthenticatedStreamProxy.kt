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

        var opened = openUpstream(resource, range, headOnly)
        // Extensionless manifests can first be discovered through a ranged probe.
        // Repeat once without Range rather than trying to rewrite an incomplete XML/M3U.
        if (opened.response.code == 206 && !resource.playlist &&
            (vlcDashContentType(opened.response.header("Content-Type")) ||
                vlcHlsContentType(opened.response.header("Content-Type")) ||
                vlcDashPath(opened.response.request.url) || vlcHlsPath(opened.response.request.url))) {
            opened.response.close()
            calls.remove(opened.call)
            opened = openUpstream(resource.copy(playlist = true), null, headOnly)
        }
        val call = opened.call
        try {
            opened.response.use { response ->
                if (!response.isSuccessful && response.code != 416) { status(output, response.code, response.message.ifBlank { "Error" }); return }
                val contentType = response.header("Content-Type")
                val dash = vlcDashPath(response.request.url) || vlcDashContentType(contentType)
                val playlist = resource.playlist || vlcHlsPath(response.request.url) || vlcHlsContentType(contentType)
                if (dash || playlist) {
                    val manifestType = if (dash) "application/dash+xml" else "application/vnd.apple.mpegurl"
                    if (response.code != 200) { status(output, 502, "Partial playlist unsupported"); return }
                    if (request[1].substringBefore('?') == "/$token") routes.markRootPlaylist()
                    if (headOnly) {
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: $manifestType\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                        output.flush()
                        return
                    }
                    val rewritten = try {
                        val manifest = readManifest(response.body.byteStream())
                        val localUri: (okhttp3.HttpUrl, Boolean) -> String = { child, isPlaylist ->
                            localOrigin + routes.register(child, isPlaylist, opened.credentialed)
                        }
                        val rewrittenText = if (dash) rewriteVlcDashManifest(manifest, response.request.url,
                            localUri, { localOrigin + routes.registerTemplate(it, opened.credentialed) })
                        else rewriteVlcHlsManifest(manifest, response.request.url, resource.variables) { child, isPlaylist, vars ->
                            localOrigin + routes.register(child, isPlaylist, opened.credentialed, vars)
                        }
                        rewrittenText.toByteArray(Charsets.UTF_8)
                    } catch (_: IOException) {
                        status(output, 502, "Unsupported playlist")
                        return
                    }
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: $manifestType\r\nContent-Length: ${rewritten.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
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

    private data class Opened(val call: Call, val response: okhttp3.Response, val credentialed: Boolean)

    /**
     * Redirects are explicit, bounded and never restore credentials after leaving the origin.
     * Foreign children and redirect targets use a separate anonymous client with public DNS.
     */
    private fun openUpstream(resource: HlsRelayRoutes.Resource, range: String?, headOnly: Boolean): Opened {
        var url = resource.url
        var sendCredentials = resource.credentialed && vlcRelayOriginAllowed(origin, url)
        repeat(6) {
            if (closed.get()) throw IOException("Media relay closed")
            if (!vlcRelayResourceAllowed(origin, url) || !sendCredentials && !vlcRelayCdnUrlAllowed(url))
                throw IOException("Media relay origin refused")
            val upstream = Request.Builder().url(url).apply {
                if (sendCredentials) upstreamHeaders.forEach { (name, value) ->
                    // Connection and authority are controlled by the actual destination.
                    if (name.lowercase(java.util.Locale.ROOT) !in HOP_HEADERS) header(name, value)
                }
                header("Accept-Encoding", "identity")
                if (resource.playlist) removeHeader("Range") else range?.let { header("Range", it) }
                if (headOnly) head()
            }.build()
            val call = (if (sendCredentials) client else cdnClient).newCall(upstream)
            calls.add(call)
            if (closed.get()) call.cancel()
            val response = try { call.execute() } catch (error: Throwable) {
                calls.remove(call)
                throw error
            }
            if (response.code !in REDIRECTS) return Opened(call, response, sendCredentials)
            val target = response.header("Location")?.let(url::resolve)
            response.close()
            calls.remove(call)
            if (target == null) throw IOException("Invalid media redirect")
            sendCredentials = sendCredentials && vlcRelayOriginAllowed(origin, target)
            url = target
        }
        throw IOException("Too many media redirects")
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

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val HOP_HEADERS = setOf("host", "connection", "proxy-connection", "proxy-authorization", "transfer-encoding",
            "content-length", "upgrade", "keep-alive", "te", "trailer")
        val RELAYED_HEADERS = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges")

        // Deliberately without any logging interceptor. The read timeout matches the Media3
        // streaming client so a dead live stream ends instead of buffering forever.
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                // The relay handles each redirect itself before creating the next request.
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
        val cdnClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .proxy(java.net.Proxy.NO_PROXY)
                .dns(VlcRelayPublicDns())
                .build()
        }
    }
}

internal fun vlcHttpRelaySupported(url: String): Boolean {
    return url.toHttpUrlOrNull() != null
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
