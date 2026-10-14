package com.ambient.tv.ui.player.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AuthenticatedStreamProxyTest {
    private val payload = ByteArray(5_000) { (it % 251).toByte() }
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/recording") { exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") != "Basic dGVzdDp0ZXN0" ||
                exchange.requestHeaders.getFirst("Accept-Encoding") != "identity") {
                exchange.sendResponseHeaders(401, -1); exchange.close(); return@createContext
            }
            val range = exchange.requestHeaders.getFirst("Range")
            val from = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            if (from >= payload.size) {
                exchange.responseHeaders.add("Content-Range", "bytes */${payload.size}")
                exchange.sendResponseHeaders(416, -1); exchange.close(); return@createContext
            }
            val to = minOf(range?.substringAfter('-')?.toIntOrNull() ?: payload.lastIndex, payload.lastIndex)
            val count = to - from + 1
            exchange.responseHeaders.add("Content-Type", "video/mp2t")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Length", count.toString())
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${payload.size}")
            val code = if (range == null) 200 else 206
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(code, -1); exchange.close()
            } else {
                exchange.sendResponseHeaders(code, count.toLong())
                exchange.responseBody.use { it.write(payload, from, count) }
            }
        }
        this.executor = this@AuthenticatedStreamProxyTest.executor
        start()
    }
    private val proxy = AuthenticatedStreamProxy("http://127.0.0.1:${server.address.port}/recording",
        mapOf("authorization" to "Basic dGVzdDp0ZXN0"))
    private val client = OkHttpClient()

    @After fun close() { proxy.close(); server.stop(0); executor.shutdownNow() }

    private fun get(range: String? = null, method: String = "GET", url: String = proxy.url) = client.newCall(
        Request.Builder().url(url).method(method, null).apply { range?.let { header("Range", it) } }.build()
    ).execute()

    @Test fun recordingHeadAndRepeatedSeeksKeepAuthAndExactOffsets() {
        get(method = "HEAD").use {
            assertEquals(200, it.code)
            assertEquals("5000", it.header("Content-Length"))
            assertEquals("bytes", it.header("Accept-Ranges"))
            assertEquals(0, it.body!!.bytes().size)
        }
        for (range in listOf("bytes=4000-4999", "bytes=0-199", "bytes=2000-")) {
            get(range).use {
                val from = range.substringAfter('=').substringBefore('-').toInt()
                val to = range.substringAfter('-').toIntOrNull() ?: payload.lastIndex
                assertEquals(206, it.code)
                assertEquals("bytes $from-$to/5000", it.header("Content-Range"))
                assertArrayEquals(payload.copyOfRange(from, to + 1), it.body!!.bytes())
            }
        }
    }

    @Test fun beyondEofPreservesSizeForDemuxerAndUnknownCapabilityIsRejected() {
        get("bytes=6000-").use {
            assertEquals(416, it.code)
            assertEquals("bytes */5000", it.header("Content-Range"))
        }
        get(url = proxy.url.substringBeforeLast('/') + "/unknown").use { assertEquals(404, it.code) }
    }
}
