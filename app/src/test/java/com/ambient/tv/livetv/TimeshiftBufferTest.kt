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

    @Test fun rendererReopenUsesLiveEdgeAndRejectsUnrelatedStreams() {
        val (url, server) = upstream("video/mp2t", tsPackets(2000))
        val dir = kotlin.io.path.createTempDirectory("timeshift-reopen").toFile()
        val buffer = requireNotNull(TimeshiftBuffer.start(OkHttpClient(), url, emptyMap(), dir, 128L * 1024 * 1024))
        try {
            val (original, start) = buffer.urlAt(0)
            // A deterministic recording index: the edge has advanced since the original open.
            val indexField = TimeshiftBuffer::class.java.getDeclaredField("index").apply { isAccessible = true }
            val lockField = TimeshiftBuffer::class.java.getDeclaredField("lock").apply { isAccessible = true }
            synchronized(lockField.get(buffer)) {
                @Suppress("UNCHECKED_CAST")
                val index = indexField.get(buffer) as ArrayDeque<Pair<Long, Long>>
                index.clear()
                index.addLast(start to 0L)
                index.addLast((start + 8_000L) to 1880L)
                index.addLast((start + 12_000L) to 3760L)
                // Opens LIVE_TIMESHIFT_PREROLL_MS (4 s) behind the newest index entry.
                val reopened = requireNotNull(buffer.reopenedUrl(original))
                assertEquals(start + 8_000L, reopened.second)
                assertTrue(reopened.first.endsWith("/1880.ts"))
                assertNotEquals(original, reopened.first)
                assertEquals(original, buffer.urlAt(start).first) // Explicit rewind stays available.
                index.addLast((start + 16_000L) to 5640L)
                assertEquals(start + 12_000L, buffer.reopenedUrl(original)?.second)
                assertEquals(start + 12_000L, buffer.reopenedUrl(reopened.first)?.second)
                assertNull(buffer.reopenedUrl(url))
                assertNull(buffer.reopenedUrl(original.replace("127.0.0.1", "localhost")))
                assertNull(buffer.reopenedUrl(original.replace("/0.ts", "/other/0.ts")))
            }
        } finally { buffer.stop(); server.close(); dir.deleteRecursively() }
    }

    /** Upstream that ends every connection mid-packet: the recorder must reconnect and splice cleanly. */
    private fun brokenUpstream(body: ByteArray): Pair<String, ServerSocket> {
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
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body); out.write(body.copyOf(100)); out.flush()
                            socket.close()
                        }
                    }.start()
                }
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${server.localPort}/stream" to server
    }

    @Test fun upstreamReconnectIsLoggedAndKeepsThePacketGrid() {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        timeshiftLogSink = { lines += it }
        val data = tsPackets(200)
        val (url, server) = brokenUpstream(data)
        val dir = kotlin.io.path.createTempDirectory("timeshift-splice").toFile()
        val buffer = requireNotNull(TimeshiftBuffer.start(OkHttpClient(), url, emptyMap(), dir, 128L * 1024 * 1024))
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while (buffer.splices < 1 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            Thread.sleep(300)
            assertTrue("reconnected", buffer.splices >= 1)
            val (start, _) = buffer.urlAt(0)
            val connection = URL(start).openConnection().apply { readTimeout = 1000 }
            val received = java.io.ByteArrayOutputStream()
            runCatching { connection.getInputStream().use { input ->
                val chunk = ByteArray(8192)
                while (received.size() < 2 * data.size) { val n = input.read(chunk); if (n < 0) break; received.write(chunk, 0, n) }
            } }
            val bytes = received.toByteArray()
            assertTrue(bytes.size >= 2 * data.size - 188)
            for (p in 0 until bytes.size - bytes.size % 188 step 188) assertEquals("packet at $p", 0x47.toByte(), bytes[p])
            assertTrue(lines.any { it.startsWith("Timeshift upstream ended (EOF)") })
            assertTrue(lines.any { it.startsWith("Timeshift upstream reconnected") && "gap_ms=" in it && "dropped_partial=100" in it })
        } finally { timeshiftLogSink = null; buffer.stop(); server.close(); dir.deleteRecursively() }
    }

    @Test fun ringReadsAreRejectedOnceTheWriterLappedThem() {
        val capacity = 188L * 10
        assertEquals(0L, ringOldestReadable(500L, capacity))
        assertEquals(188L * 3, ringOldestReadable(188L * 12 + 50, capacity)) // 426 rounded up to the grid
        assertTrue(ringReadStillValid(188L * 5, 188L * 15, capacity))
        assertFalse(ringReadStillValid(188L * 4, 188L * 15, capacity))
        assertTrue(ringReadStillValid(0L, capacity, capacity))
    }

    @Test fun restartKeepsTheWatchedMomentUnlessItIsAtTheLiveEdgeOrUnknown() {
        val live = 1_000_000L
        assertEquals(live - 60_000L, timeshiftResumeWallMs(live - 90_000L, 30_000L, live))
        assertNull(timeshiftResumeWallMs(live - 10_000L, 8_000L, live)) // within the 4 s lead
        assertNull(timeshiftResumeWallMs(live - 90_000L, 0L, live)) // clock unknown: never replay the stream start
        assertEquals(4_000L, LIVE_TIMESHIFT_PREROLL_MS)
    }

    @Test fun onlyBufferLoopbackUrlsCountAsTimeshift() {
        assertTrue(isLiveTimeshiftUrl("http://127.0.0.1:40123/0123456789abcdef0123456789abcdef/1880.ts"))
        assertFalse(isLiveTimeshiftUrl("http://127.0.0.1:40123/stream"))
        assertFalse(isLiveTimeshiftUrl("http://192.168.1.2:9981/stream/channel/x"))
        assertFalse(isLiveTimeshiftUrl("http://localhost:40123/0123456789abcdef0123456789abcdef/0.ts"))
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
