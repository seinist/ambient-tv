package com.ambient.tv.livetv

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class RecordingPlaybackBufferTest {
    private val entry = LiveTvRecording("dvr", "channel", "Channel", "Show", startEpochSeconds = 100,
        stopEpochSeconds = 200, status = null, scheduleStatus = "recording", url = null,
        imageUrl = null, fanartUrl = null, fileSizeBytes = null)

    private fun packet(ms: Long, origin: Long = 0): ByteArray = ByteArray(188).apply {
        val ticks = (origin + ms * 90) % (1L shl 33)
        this[0] = 0x47; this[1] = 0; this[2] = 100; this[3] = 0x30
        this[4] = 7; this[5] = 0x10
        this[6] = (ticks shr 25).toByte(); this[7] = (ticks shr 17).toByte()
        this[8] = (ticks shr 9).toByte(); this[9] = (ticks shr 1).toByte()
        this[10] = ((ticks and 1) shl 7).toByte()
    }
    private fun media(from: Int, until: Int, origin: Long = 0): ByteArray =
        java.io.ByteArrayOutputStream().apply { for (i in from until until) write(packet(i * 40L, origin)) }.toByteArray()

    private class RecordingServer(initial: ByteArray, private val ranges: Boolean = true) : AutoCloseable {
        @Volatile var bytes = initial
        val active = AtomicBoolean(true)
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/dvrfile/dvr"
        init {
            Thread {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    Thread { socket.use { runCatching { serve(it) } } }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }
        private fun serve(socket: Socket) {
            val input = socket.getInputStream().bufferedReader()
            val method = input.readLine().substringBefore(' ')
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val out = socket.getOutputStream()
            if (headers["authorization"] != "Basic fixture") {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray()); return
            }
            val data = bytes
            val range = headers["range"]?.removePrefix("bytes=")?.split('-')?.takeIf { ranges }
            val start = range?.get(0)?.toInt() ?: 0
            val end = range?.get(1)?.toInt() ?: data.lastIndex
            val body = data.copyOfRange(start, end + 1)
            out.write(("HTTP/1.1 ${if (range != null) "206 Partial Content" else "200 OK"}\r\n" +
                "Content-Length: ${body.size}\r\nContent-Type: video/mp2t\r\nConnection: close\r\n" +
                (if (range != null) "Content-Range: bytes $start-$end/${data.size}\r\n" else "") + "\r\n").toByteArray())
            if (method != "HEAD") out.write(body)
        }
        override fun close() { server.close() }
    }

    private fun open(server: RecordingServer) = RecordingPlaybackBuffer.open(OkHttpClient(),
        LiveTvPlaybackSource(server.url, mapOf("Authorization" to "Basic fixture"), "Show"), entry) { server.active.get() }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 6_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Timed out waiting for recording update", predicate())
    }

    @Test fun followsGrowthWhilePausedAndEndsOnlyAfterServerRecordingCompletes() {
        val first = media(0, 200)
        RecordingServer(first).use { server ->
            val buffer = open(server)
            try {
                val (url, anchor) = buffer.urlAt(0)
                assertEquals(100_000L, anchor)
                val connection = URL(url).openConnection().apply { readTimeout = 6_000 }
                connection.getInputStream().use { input ->
                    assertArrayEquals(first, input.readNBytes(first.size))
                    // Keep the player's stream open but stop reading while recording grows.
                    val extra = media(200, 400)
                    server.bytes = first + extra
                    await { buffer.liveWallMs >= 115_960L }
                    assertArrayEquals(extra, input.readNBytes(extra.size))
                    server.active.set(false)
                    await { buffer.finished }
                    assertEquals(-1, input.read())
                }
                // Finished recordings remain rewindable until the playback session is closed.
                URL(buffer.urlAt(0).first).openStream().use { assertArrayEquals(first, it.readNBytes(first.size)) }
            } finally { buffer.stop() }
            assertFalse(server.active.get())
        }
    }

    @Test fun seeksUsingPcrAcrossVariableBitrateAndClockRollover() {
        val origin = (1L shl 33) - 90_000L
        val data = java.io.ByteArrayOutputStream().apply {
            for (i in 0 until 12_000) {
                write(packet(i * 40L, origin))
                // Vary the bytes per second sharply in the middle of the file.
                if (i in 3_000..8_000) repeat(3) { write(ByteArray(188).apply { this[0] = 0x47; this[1] = 0x1f; this[2] = -1 }) }
            }
        }.toByteArray()
        RecordingServer(data).use { server ->
            val buffer = open(server)
            try {
                val target = 100_000L + 237_120L
                val (url, anchor) = buffer.urlAt(target)
                assertEquals(target, anchor)
                val bytes = URL(url).openConnection().apply { readTimeout = 6_000 }.getInputStream().use { it.readNBytes(188) }
                val clock = recordingPcrSamples(bytes, 0).single().ticks
                val actualMs = ((clock - origin + (1L shl 33)) % (1L shl 33)) / 90
                assertTrue("Seek was $actualMs", actualMs in 236_870L..237_120L)
                assertEquals(url to anchor, buffer.reopenedUrl(url))
                assertNull(buffer.reopenedUrl(server.url))
                assertNull(buffer.reopenedUrl(url.replace("127.0.0.1", "localhost")))
                assertEquals(buffer.liveWallMs, buffer.urlAt(Long.MAX_VALUE).second)
            } finally { buffer.stop() }
            assertTrue("Closing playback must not cancel the DVR", server.active.get())
        }
    }

    @Test fun unsupportedFormatsAndServersFailBeforeReplacingLivePlayback() {
        RecordingServer(ByteArray(188 * 10) { 0x1a }).use { server ->
            assertTrue(runCatching { open(server) }.exceptionOrNull() is UnsupportedOperationException)
        }
        RecordingServer(media(0, 200), ranges = false).use { server ->
            assertTrue(runCatching { open(server) }.exceptionOrNull() is java.io.IOException)
        }
    }

    @Test fun closingPlaybackClosesClientsAndDoesNotCancelRecording() {
        RecordingServer(media(0, 200)).use { server ->
            val buffer = open(server)
            val connection = URL(buffer.urlAt(0).first).openConnection().apply { readTimeout = 2_000 }
            connection.getInputStream().use { input ->
                input.readNBytes(200 * 188)
                buffer.stop()
                assertEquals(-1, input.read())
            }
            assertTrue(server.active.get())
        }
    }
}
