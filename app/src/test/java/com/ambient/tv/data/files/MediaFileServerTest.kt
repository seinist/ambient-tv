package com.ambient.tv.data.files

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MediaFileServerTest {
    @Test fun rangeParsingSupportsOpenAndSuffixRequests() {
        assertEquals(0L..99L, ByteRange.parse(null, 100))
        assertEquals(20L..39L, ByteRange.parse("bytes=20-39", 100))
        assertEquals(20L..99L, ByteRange.parse("bytes=20-", 100))
        assertEquals(90L..99L, ByteRange.parse("bytes=-10", 100))
        assertEquals(0L..99L, ByteRange.parse("bytes=-1000", 100))
        assertEquals(90L..99L, ByteRange.parse("bytes=90-1000", 100))
    }
    @Test fun invalidRangesCannotOverflowOrProduceNegativeReads() {
        for (header in listOf("bytes=100-", "bytes=3-2", "bytes=-0", "bytes=0-1,3-4", "bytes=999999999999999999999-", "items=0-5", "bytes=", "bytes=0--1"))
            assertThrows(IllegalArgumentException::class.java) { ByteRange.parse(header, 100) }
        assertThrows(IllegalArgumentException::class.java) { ByteRange.parse("bytes=0-", 0) }
        assertTrue(ByteRange.parse(null, 0).isEmpty())
    }
    private fun data(bytes: ByteArray, closed: AtomicInteger, released: CountDownLatch) = object : SeekableMedia {
        override val size = bytes.size.toLong()
        override fun read(position: Long, buffer: ByteArray, count: Int): Int {
            val n = minOf(count, bytes.size - position.toInt()); if (n <= 0) return -1
            bytes.copyInto(buffer, 0, position.toInt(), position.toInt() + n); return n
        }
        override fun close() { closed.incrementAndGet(); released.countDown() }
    }
    private fun connection(url: String, range: String? = null, head: Boolean = false): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 3000; readTimeout = 3000; if (range != null) setRequestProperty("Range", range); if (head) requestMethod = "HEAD"
    }
    @Test fun fullGetHeadAndSeekReturnExactBytesAndReleaseHandles() {
        val bytes = ByteArray(512 * 1024 + 19) { (it % 251).toByte() }; val closed = AtomicInteger(); val released = CountDownLatch(1)
        MediaFileServer().use { server ->
            val url = server.register("nas", "video/mp4") { data(bytes, closed, released) }
            assertEquals("127.0.0.1", URL(url).host)
            val full = connection(url); assertEquals(200, full.responseCode); assertArrayEquals(bytes, full.inputStream.use { it.readBytes() }); full.disconnect()
            val seek = connection(url, "bytes=8193-20000"); assertEquals(206, seek.responseCode)
            assertEquals("bytes 8193-20000/${bytes.size}", seek.getHeaderField("Content-Range"))
            assertArrayEquals(bytes.copyOfRange(8193, 20001), seek.inputStream.use { it.readBytes() }); seek.disconnect()
            val head = connection(url, head = true); assertEquals(200, head.responseCode); assertEquals(bytes.size.toLong(), head.contentLengthLong); assertEquals(0, head.inputStream.readBytes().size); head.disconnect()
        }
        // Requests reuse one kept handle; closing the server closes it.
        assertTrue("Server must release its media handle", released.await(3, TimeUnit.SECONDS))
        assertEquals(1, closed.get())
    }
    @Test fun suffixUnsatisfiableRevokedAndUnknownUrlsAreHandled() {
        val closed = AtomicInteger(); val released = CountDownLatch(1)
        MediaFileServer().use { server ->
            val url = server.register("usb", "audio/flac") { data(byteArrayOf(1, 2, 3, 4), closed, released) }
            val suffix = connection(url, "bytes=-2"); assertEquals(206, suffix.responseCode); assertArrayEquals(byteArrayOf(3, 4), suffix.inputStream.readBytes()); suffix.disconnect()
            val invalid = connection(url, "bytes=9-"); assertEquals(416, invalid.responseCode); assertEquals("bytes */4", invalid.getHeaderField("Content-Range")); invalid.disconnect()
            server.revoke("usb")
            val revoked = connection(url); assertEquals(404, revoked.responseCode); revoked.disconnect()
            val unknown = connection(url + "guess"); assertEquals(404, unknown.responseCode); unknown.disconnect()
        }
        assertTrue("Revoking must release the kept handle", released.await(3, TimeUnit.SECONDS))
        assertEquals(1, closed.get())
    }
    @Test fun emptyFileReturnsZeroLengthAndCloses() {
        val closed = AtomicInteger(); val released = CountDownLatch(1)
        MediaFileServer().use { server ->
            val c = connection(server.register("usb", "video/mp4") { data(byteArrayOf(), closed, released) })
            assertEquals(200, c.responseCode); assertEquals(0, c.contentLength); assertEquals(0, c.inputStream.readBytes().size); c.disconnect()
        }
        assertTrue("Server worker must release its media handle", released.await(3, TimeUnit.SECONDS))
        assertEquals(1, closed.get())
    }

    @Test fun missingOrDisconnectedSourceReturnsHttpErrorInsteadOfTruncatedResponse() {
        MediaFileServer().use { server ->
            val url = server.register("nas", "video/mp4") { throw java.io.IOException("offline") }
            val c = connection(url)
            assertEquals(503, c.responseCode); assertEquals(0L, c.contentLengthLong); c.disconnect()
        }
    }

    /** A network-like source: short reads, optional parallel reads, and a scripted failure. */
    private class Remote(val bytes: ByteArray, val parallel: Boolean, val maxRead: Int, val failAt: Long = -1, val opens: AtomicInteger, val closed: AtomicInteger, val failures: AtomicInteger) : SeekableMedia {
        override val size = bytes.size.toLong()
        override val parallelReads = parallel
        @Volatile private var open = true
        override fun read(position: Long, buffer: ByteArray, count: Int): Int {
            if (!open) throw java.io.IOException("closed")
            if (failAt >= 0 && position <= failAt && failAt < position + count && failures.getAndIncrement() == 0) throw java.io.IOException("network blip")
            val n = minOf(count, maxRead, bytes.size - position.toInt()); if (n <= 0) return -1
            Thread.sleep(1)
            bytes.copyInto(buffer, 0, position.toInt(), position.toInt() + n); return n
        }
        override fun close() { open = false; closed.incrementAndGet() }
    }

    @Test fun readAheadReturnsExactBytesWithShortReads() {
        val bytes = ByteArray(7 * 1024 * 1024 + 4321) { (it * 31 % 253).toByte() }
        val opens = AtomicInteger(); val closed = AtomicInteger(); val failures = AtomicInteger()
        MediaFileServer().use { server ->
            val url = server.register("nas", "video/mp4") { opens.incrementAndGet(); Remote(bytes, true, 64 * 1024 + 7, -1, opens, closed, failures) }
            val full = connection(url); assertArrayEquals(bytes, full.inputStream.use { it.readBytes() }); full.disconnect()
            val part = connection(url, "bytes=1234567-6543210")
            assertArrayEquals(bytes.copyOfRange(1234567, 6543211), part.inputStream.use { it.readBytes() }); part.disconnect()
        }
        assertEquals(1, opens.get())
    }

    @Test fun failedReadContinuesOnFreshHandleAtSameByte() {
        val bytes = ByteArray(5 * 1024 * 1024 + 99) { (it % 241).toByte() }
        for (parallel in listOf(false, true)) {
            val opens = AtomicInteger(); val closed = AtomicInteger(); val failures = AtomicInteger()
            MediaFileServer().use { server ->
                val url = server.register("nas", "video/mp4") { opens.incrementAndGet(); Remote(bytes, parallel, 256 * 1024, 3_000_000, opens, closed, failures) }
                val full = connection(url); assertArrayEquals(bytes, full.inputStream.use { it.readBytes() }); full.disconnect()
            }
            assertEquals("parallel=$parallel", 2, opens.get())
        }
    }

    @Test fun seekWhileStreamingReusesTheHandle() {
        val bytes = ByteArray(20 * 1024 * 1024) { (it % 239).toByte() }
        val opens = AtomicInteger(); val closed = AtomicInteger(); val failures = AtomicInteger()
        MediaFileServer().use { server ->
            val url = server.register("nas", "video/x-matroska") {
                Thread.sleep(150); opens.incrementAndGet(); Remote(bytes, true, 1024 * 1024, -1, opens, closed, failures)
            }
            repeat(4) { index ->
                val c = connection(url, "bytes=${index * 3_000_000}-")
                val head = ByteArray(1000); var got = 0
                c.inputStream.use { input -> while (got < head.size) { val n = input.read(head, got, head.size - got); if (n < 0) break; got += n } }
                assertArrayEquals(bytes.copyOfRange(index * 3_000_000, index * 3_000_000 + 1000), head)
                c.disconnect()
            }
        }
        assertEquals("A seek must wait for the previous handle instead of opening a new one", 1, opens.get())
    }
}
