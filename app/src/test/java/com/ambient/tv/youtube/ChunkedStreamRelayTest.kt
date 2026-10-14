package com.ambient.tv.youtube

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedStreamRelayTest {
    private val payload = ByteArray(25_000) { (it * 31 % 251).toByte() }
    /** Upstream drops the connection once when a response would cover this offset. */
    @Volatile private var dropOnceAt = -1L
    private val seenRanges = Collections.synchronizedList(mutableListOf<String?>())
    private val seenHeaders = Collections.synchronizedList(mutableListOf<String?>())
    private val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/videoplayback") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            seenRanges += range
            seenHeaders += exchange.requestHeaders.getFirst("X-Test")
            val spec = ChunkedStreamRelay.parseRange(range)
            if (spec == null) {
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } else {
                val end = minOf(spec.second ?: (payload.size - 1L), payload.size - 1L)
                val slice = payload.copyOfRange(spec.first.toInt(), end.toInt() + 1)
                exchange.responseHeaders.add("Content-Range", "bytes ${spec.first}-$end/${payload.size}")
                exchange.responseHeaders.add("Content-Type", "video/mp4")
                exchange.sendResponseHeaders(206, slice.size.toLong())
                val drop = dropOnceAt
                if (drop in spec.first..end) {
                    dropOnceAt = -1
                    // Half the bytes, then the connection is gone: a network drop mid-chunk.
                    runCatching { exchange.responseBody.write(slice, 0, (drop - spec.first).toInt()); exchange.responseBody.flush() }
                    exchange.close()
                } else {
                    exchange.responseBody.use { it.write(slice) }
                }
            }
        }
        val wrongRangeServed = java.util.concurrent.atomic.AtomicInteger()
        createContext("/wrong-range-once") { exchange ->
            val requested = ChunkedStreamRelay.parseRange(exchange.requestHeaders.getFirst("Range"))!!
            val start = if (wrongRangeServed.getAndIncrement() == 0) 0L else requested.first
            val end = minOf(requested.second ?: payload.lastIndex.toLong(), payload.lastIndex.toLong())
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
            exchange.sendResponseHeaders(206, end - start + 1)
            exchange.responseBody.use { it.write(payload, start.toInt(), (end - start + 1).toInt()) }
        }
        val transientRejects = java.util.concurrent.atomic.AtomicInteger()
        createContext("/transient-forbidden") { exchange ->
            if (transientRejects.getAndIncrement() == 1) {
                exchange.sendResponseHeaders(403, -1); exchange.close(); return@createContext
            }
            val spec = ChunkedStreamRelay.parseRange(exchange.requestHeaders.getFirst("Range"))!!
            val end = minOf(spec.second ?: payload.lastIndex.toLong(), payload.lastIndex.toLong())
            exchange.responseHeaders.add("Content-Range", "bytes ${spec.first}-$end/${payload.size}")
            exchange.sendResponseHeaders(206, end - spec.first + 1)
            exchange.responseBody.use { it.write(payload, spec.first.toInt(), (end - spec.first + 1).toInt()) }
        }
        // An expired link: always 403. An expiring one: the first range works, then 403.
        createContext("/expired") { exchange -> exchange.sendResponseHeaders(403, -1); exchange.close() }
        val expiringServed = java.util.concurrent.atomic.AtomicInteger()
        createContext("/expiring") { exchange ->
            if (expiringServed.getAndIncrement() > 0) { exchange.sendResponseHeaders(403, -1); exchange.close(); return@createContext }
            val spec = ChunkedStreamRelay.parseRange(exchange.requestHeaders.getFirst("Range"))!!
            val end = minOf(spec.second ?: (payload.size - 1L), payload.size - 1L)
            exchange.responseHeaders.add("Content-Range", "bytes ${spec.first}-$end/${payload.size}")
            exchange.sendResponseHeaders(206, end - spec.first + 1)
            exchange.responseBody.use { it.write(payload, spec.first.toInt(), (end - spec.first + 1).toInt()) }
        }
        executor = java.util.concurrent.Executors.newCachedThreadPool()
        start()
    }
    private val client = OkHttpClient()
    private val relay = ChunkedStreamRelay(client, chunkBytes = 4_000)
    private val source = "http://127.0.0.1:${upstream.address.port}/videoplayback?sig=x&n=y"

    @After fun stop() {
        upstream.stop(0)
        (upstream.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun get(url: String, range: String? = null, method: String = "GET") = client.newCall(
        Request.Builder().url(url).method(method, null).apply { range?.let { header("Range", it) } }.build(),
    ).execute()

    /**
     * The server withholds the rest until the player has received headers/a small first packet.
     * Before the relay flush fix this times out: its output buffer waits for the gated bytes.
     */
    private fun verifyEarlyDelivery(status: Int, prefix: Boolean) {
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val opened = AtomicReference<okhttp3.Response?>()
        val bytes = if (status == 206) payload.copyOf(4_000) else payload
        val path = "/gated-$status-$prefix"
        upstream.createContext(path) { exchange ->
            if (status == 206) exchange.responseHeaders.add("Content-Range", "bytes 0-3999/${payload.size}")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            try {
                exchange.responseBody.use { output ->
                    val initial = if (prefix) 96 else 0
                    if (initial > 0) output.write(bytes, 0, initial)
                    output.flush()
                    if (!release.await(8, TimeUnit.SECONDS)) return@use
                    output.write(bytes, initial, bytes.size - initial)
                }
            } finally { exchange.close() }
        }
        val url = relay.register("http://127.0.0.1:${upstream.address.port}$path", emptyMap())
        val pending = executor.submit(Callable {
            val response = get(url, "bytes=0-3999").also(opened::set)
            response to if (prefix) response.body!!.byteStream().read() else -1
        })
        try {
            // A liveness assertion, not a throughput benchmark: upstream cannot finish yet.
            val (response, first) = pending.get(3, TimeUnit.SECONDS)
            response.use {
                assertEquals(status, response.code)
                if (prefix) assertEquals(bytes[0].toInt() and 255, first)
                release.countDown()
                val remaining = response.body!!.byteStream().readBytes()
                assertArrayEquals(if (prefix) bytes.copyOfRange(1, bytes.size) else bytes, remaining)
            }
        } finally {
            release.countDown()
            pending.cancel(true)
            opened.get()?.close()
            executor.shutdownNow()
        }
    }

    @Test fun `206 headers arrive while upstream withholds its body`() = verifyEarlyDelivery(206, false)
    @Test fun `206 first small packet arrives before the rest`() = verifyEarlyDelivery(206, true)
    @Test fun `range ignoring 200 headers arrive without waiting for the body`() = verifyEarlyDelivery(200, false)
    @Test fun `range ignoring 200 first small packet arrives before the rest`() = verifyEarlyDelivery(200, true)

    @Test fun `numeric timing observer cannot interrupt playback`() {
        val observed = Collections.synchronizedList(mutableListOf<RelayTransferTiming>())
        val firstPacket = CountDownLatch(1)
        val watched = ChunkedStreamRelay(client, chunkBytes = 4_000, onTransferTiming = {
            observed += it
            if (it.stage == "first_body") firstPacket.countDown()
            throw IllegalStateException("observer unavailable")
        })
        val url = watched.register(source, emptyMap())
        get(url, "bytes=500-899").use { response ->
            assertArrayEquals(payload.copyOfRange(500, 900), response.body!!.bytes())
        }
        assertTrue(firstPacket.await(3, TimeUnit.SECONDS))
        assertTrue(observed.any { it.stage == "headers" && it.rangeStart == 500L && it.headersMs != null })
        assertTrue(observed.any { it.stage == "first_body" && it.sentBytes > 0 && it.firstBodyMs != null })
    }

    @Test fun `whole stream arrives in bounded upstream chunks with the original headers`() {
        val url = relay.register(source, mapOf("X-Test" to "yes"))
        get(url).use { response ->
            assertEquals(200, response.code)
            assertEquals("25000", response.header("Content-Length"))
            assertArrayEquals(payload, response.body!!.bytes())
        }
        assertEquals(7, seenRanges.size)
        assertTrue(seenRanges.all { it != null && !it.endsWith("-") })
        assertTrue(seenHeaders.all { it == "yes" })
    }

    @Test fun `open and closed ranges answer with exact content range`() {
        val url = relay.register(source, emptyMap())
        get(url, "bytes=10000-").use { response ->
            assertEquals(206, response.code)
            assertEquals("bytes 10000-24999/25000", response.header("Content-Range"))
            assertArrayEquals(payload.copyOfRange(10_000, 25_000), response.body!!.bytes())
        }
        get(url, "bytes=100-199").use { response ->
            assertEquals(206, response.code)
            assertEquals("bytes 100-199/25000", response.header("Content-Range"))
            assertArrayEquals(payload.copyOfRange(100, 200), response.body!!.bytes())
        }
    }

    @Test fun `same upstream maps to the same relay url and unknown paths are refused`() {
        val first = relay.register(source, mapOf("A" to "1"))
        assertEquals(first, relay.register(source, mapOf("A" to "1")))
        assertTrue(first != relay.register(source, mapOf("A" to "2")))
        get(first.substringBeforeLast('/') + "/nope").use { assertEquals(404, it.code) }
        get(first, method = "HEAD").use { assertEquals("25000", it.header("Content-Length")) }
    }

    @Test fun `wrong first range after seek is retried before any bytes are exposed`() {
        val url = relay.register(source.replace("/videoplayback", "/wrong-range-once"), emptyMap())
        get(url, "bytes=10000-14999").use {
            assertEquals(206, it.code)
            assertEquals("bytes 10000-14999/25000", it.header("Content-Range"))
            assertArrayEquals(payload.copyOfRange(10_000, 15_000), it.body!!.bytes())
        }
    }

    @Test fun `range parsing`() {
        assertEquals(5L to null, ChunkedStreamRelay.parseRange("bytes=5-"))
        assertEquals(5L to 9L, ChunkedStreamRelay.parseRange("bytes=5-9"))
        assertNull(ChunkedStreamRelay.parseRange("bytes=-500"))
        assertNull(ChunkedStreamRelay.parseRange("bytes=1-2,4-5"))
        assertNull(ChunkedStreamRelay.parseRange("bytes=9-5"))
    }

    @Test fun `a drop mid-chunk is resumed at the exact byte`() {
        dropOnceAt = 9_000
        val url = relay.register(source, emptyMap())
        get(url).use { response -> assertArrayEquals(payload, response.body!!.bytes()) }
        // The failed range was requested again from where the bytes stopped.
        assertTrue(seenRanges.any { it == "bytes=9000-11999" || it?.startsWith("bytes=9000-") == true })
    }

    @Test fun `probes do not fetch ahead, streaming connections do`() {
        assertEquals(false, relay.shouldPrefetch(20_000_000L))
        assertEquals(true, relay.shouldPrefetch(400_000_000L))
    }

    @Test fun `transient forbidden midstream retries identical bytes without a new url`() {
        val url = relay.register(source.replace("/videoplayback", "/transient-forbidden"), emptyMap()) { null }
        get(url).use { assertArrayEquals(payload, it.body!!.bytes()) }
    }

    @Test fun `an expired link is renewed before the first byte`() {
        var renewals = 0
        val url = relay.register(source.replace("/videoplayback", "/expired"), emptyMap()) { renewals++; source }
        get(url).use { response -> assertArrayEquals(payload, response.body!!.bytes()) }
        assertEquals(1, renewals)
    }

    @Test fun `a link expiring mid-stream is renewed and resumed at the same byte`() {
        val url = relay.register(source.replace("/videoplayback", "/expiring"), emptyMap()) { source }
        get(url).use { response -> assertArrayEquals(payload, response.body!!.bytes()) }
    }

    @Test fun `a replacement rejected by upstream renews the current url`() {
        val first = source.replace("/videoplayback", "/expired-first")
        val second = source.replace("/videoplayback", "/expired-second")
        val rejected = mutableListOf<String>()
        val url = relay.register(first, emptyMap()) { failed ->
            rejected += failed
            if (failed == first) second else source
        }
        get(url).use { assertArrayEquals(payload, it.body!!.bytes()) }
        assertEquals(listOf(first, second), rejected)
    }

    @Test fun `without a renewal the player gets the 403`() {
        val url = relay.register(source.replace("/videoplayback", "/expired"), emptyMap()) { null }
        get(url).use { response -> assertEquals(403, response.code) }
    }

    @Test fun `renewed url keeps format and audio track`() {
        val expired = "https://a.googlevideo.com/videoplayback?expire=1&itag=140&xtags=acont%3Ddubbed%3Alang%3Dde&sig=old"
        val candidates = listOf(
            "https://b.googlevideo.com/videoplayback?expire=2&itag=140&xtags=acont%3Doriginal%3Alang%3Den&sig=n1",
            "https://b.googlevideo.com/videoplayback?expire=2&itag=137&sig=n2",
            "https://b.googlevideo.com/videoplayback?expire=2&itag=140&xtags=acont%3Ddubbed%3Alang%3Dde&sig=n3",
        )
        assertEquals(candidates[2], youtubeRenewedUrl(expired, candidates))
        assertEquals(candidates[1], youtubeRenewedUrl("https://a.googlevideo.com/videoplayback?itag=137&sig=old", candidates))
        assertNull(youtubeRenewedUrl("https://a.googlevideo.com/videoplayback?itag=999", candidates))
    }

    @Test fun renewalFindsItagInThePathAndAcceptsTheSameLink() {
        val pathStyle = "https://r1.googlevideo.com/videoplayback/id/abc/itag/251/source/youtube/sig/old"
        val fresh = "https://r2.googlevideo.com/videoplayback/id/abc/itag/251/source/youtube/sig/new"
        assertEquals(fresh, youtubeRenewedUrl(pathStyle, listOf("https://r2.googlevideo.com/videoplayback/id/abc/itag/140/sig/x", fresh)))
        val same = "https://a.googlevideo.com/videoplayback?itag=140&sig=s"
        assertEquals(same, youtubeRenewedUrl(same, listOf(same)))
    }
}
