package com.ambient.tv.ui.player.backend

import java.io.Closeable
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
    private val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    private val listener = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    val url: String = "http://127.0.0.1:${listener.localPort}/$token"

    init {
        thread(name = "vlc-auth-listener", isDaemon = true) {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                thread(name = "vlc-auth-stream", isDaemon = true) {
                    try { socket.use(::serve) } catch (_: Exception) {
                        // A failed relay just closes the connection; VLC surfaces the error.
                    } finally { sockets.remove(socket) }
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
        if (request[1].removePrefix("/") != token) { status(output, 404, "Not Found"); return }
        val headOnly = request[0] == "HEAD"

        val upstream = Request.Builder().url(upstreamUrl).apply {
            upstreamHeaders.forEach { (name, value) -> header(name, value) }
            // Keep Content-Length/Content-Range byte-exact for seekable recordings.
            header("Accept-Encoding", "identity")
            range?.let { header("Range", it) }
            if (headOnly) head()
        }.build()
        val call = client.newCall(upstream)
        calls.add(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful && response.code != 416) { status(output, response.code, response.message.ifBlank { "Error" }); return }
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

    private fun status(output: OutputStream, code: Int, message: String) {
        output.write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    override fun close() {
        runCatching { listener.close() }
        calls.forEach { it.cancel() }
        sockets.forEach { runCatching { it.close() } }
    }

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
                .build()
        }
    }
}
