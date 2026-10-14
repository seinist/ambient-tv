package com.ambient.tv.livetv

import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class TimeshiftBufferTest {
    /** Minimal HTTP upstream that sends [header] and then [body] once. */
    private fun upstream(contentType: String, body: ByteArray): Pair<String, ServerSocket> {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        Thread {
            runCatching {
                while (true) {
                    val socket = server.accept()
                    Thread {
                        runCatching {
                            val input = socket.getInputStream().bufferedReader()
                            while (input.readLine()?.isNotEmpty() == true) Unit
                            val out = socket.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body); out.flush()
                            Thread.sleep(3000) // Keep the "live" connection open for a moment.
                            socket.close()
                        }
                    }.start()
                }
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${server.localPort}/stream" to server
    }

    private fun tsPackets(count: Int) = ByteArray(count * 188) { i -> if (i % 188 == 0) 0x47 else (i / 188 % 250).toByte() }

    @Test fun bufferedStreamIsServedFromTheStart() {
        val data = tsPackets(2000)
        val (url, server) = upstream("video/mp2t", data)
        val dir = kotlin.io.path.createTempDirectory("timeshift").toFile()
        val buffer = requireNotNull(TimeshiftBuffer.start(OkHttpClient(), url, emptyMap(), dir, 128L * 1024 * 1024))
        try {
            Thread.sleep(500)
            val (start, _) = buffer.urlAt(0)
            val connection = URL(start).openConnection().apply { readTimeout = 2000 }
            val received = java.io.ByteArrayOutputStream()
            runCatching { connection.getInputStream().use { input ->
                val chunk = ByteArray(8192)
                while (received.size() < data.size) { val n = input.read(chunk); if (n < 0) break; received.write(chunk, 0, n) }
            } }
            assertArrayEquals(data, received.toByteArray().copyOf(data.size))
            assertTrue(buffer.liveWallMs >= buffer.oldestWallMs)
        } finally { buffer.stop(); server.close(); dir.deleteRecursively() }
    }

    @Test fun hlsAndOtherStreamsAreLeftToThePlayer() {
        val (playlist, server) = upstream("application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-VERSION:3\n".repeat(100).toByteArray())
        val dir = kotlin.io.path.createTempDirectory("timeshift").toFile()
        try {
            assertNull(TimeshiftBuffer.start(OkHttpClient(), playlist, emptyMap(), dir, 128L * 1024 * 1024))
            assertNull(TimeshiftBuffer.start(OkHttpClient(), "http://example.invalid/live.m3u8", emptyMap(), dir, 128L * 1024 * 1024))
        } finally { server.close(); dir.deleteRecursively() }
    }
}
