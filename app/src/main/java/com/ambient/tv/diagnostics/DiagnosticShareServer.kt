package com.ambient.tv.diagnostics

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Offers the diagnostic log ZIP for download on the home network, for TVs without a reachable
 * download folder: a phone or PC opens the shown address (or scans its QR code). The URL carries
 * a random token, only the ZIP is served, and the server stops after [LIFETIME_MS] or [stop].
 */
class DiagnosticShareServer private constructor(
    private val server: ServerSocket,
    private val token: String,
    private val zip: ByteArray,
    val url: String,
) {
    @Volatile private var stopped = false

    private fun acceptLoop() {
        val deadline = System.currentTimeMillis() + LIFETIME_MS
        server.soTimeout = 1_000
        while (!stopped && System.currentTimeMillis() < deadline) {
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
            Thread({ socket.use { serve(it) } }, "diagnostic-share").apply { isDaemon = true; start() }
        }
        stop()
    }

    private fun serve(socket: java.net.Socket) = runCatching {
        socket.soTimeout = 5_000
        val requestLine = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine().orEmpty()
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        val out = socket.getOutputStream()
        if (path.removePrefix("/").substringBefore('/') == token) {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: ${zip.size}\r\n" +
                "Content-Disposition: attachment; filename=\"$FILE_NAME\"\r\nCache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            out.write(zip)
        } else {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        out.flush()
    }

    fun stop() {
        stopped = true
        runCatching { server.close() }
    }

    companion object {
        const val LIFETIME_MS = 10 * 60_000L
        private const val FILE_NAME = "ambient-tv-log.zip"

        /** Null without a Wi-Fi/Ethernet LAN address (the phone could not reach it). */
        fun start(context: Context): DiagnosticShareServer? {
            val address = com.ambient.tv.spotify.SpotifyQrLoginServer.lanAddress(context) ?: return null
            val zip = ByteArrayOutputStream().also { DiagnosticLog.export(it) }.toByteArray()
            val token = UUID.randomUUID().toString().replace("-", "")
            val socket = runCatching { ServerSocket(0, 4, address) }.getOrNull() ?: return null
            val url = "http://${address.hostAddress}:${socket.localPort}/$token/$FILE_NAME"
            return DiagnosticShareServer(socket, token, zip, url).also { share ->
                Thread({ share.acceptLoop() }, "diagnostic-share-accept").apply { isDaemon = true; start() }
            }
        }
    }
}
