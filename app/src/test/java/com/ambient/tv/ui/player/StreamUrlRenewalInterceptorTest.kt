package com.ambient.tv.ui.player

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class StreamUrlRenewalInterceptorTest {
    @Test fun expiredRangeIsRenewedWithoutLosingOffsetOrHeaders() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var expiredRequests = 0
        val ranges = mutableListOf<String?>()
        val headers = mutableListOf<String?>()
        server.createContext("/old") { e -> expiredRequests++; e.sendResponseHeaders(403, -1); e.close() }
        server.createContext("/new") { e ->
            ranges += e.requestHeaders.getFirst("Range")
            headers += e.requestHeaders.getFirst("X-Test")
            e.sendResponseHeaders(206, 3)
            e.responseBody.use { it.write(byteArrayOf(4, 5, 6)) }
        }
        server.start()
        try {
            val root = "http://127.0.0.1:${server.address.port}"
            val renewed = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor(StreamUrlRenewalInterceptor {
                renewed += it; "$root/new"
            }).build()
            repeat(2) {
                client.newCall(Request.Builder().url("$root/old").header("Range", "bytes=3-5").header("X-Test", "retained").build())
                    .execute().use { r -> assertEquals(206, r.code); assertArrayEquals(byteArrayOf(4, 5, 6), r.body!!.bytes()) }
            }
            assertEquals(1, expiredRequests)
            assertEquals(listOf("$root/old"), renewed)
            assertEquals(listOf("bytes=3-5", "bytes=3-5"), ranges)
            assertEquals(listOf("retained", "retained"), headers)
        } finally { server.stop(0) }
    }

    @Test fun unchangedUrlIsNotRetriedOrRenewedContinuously() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        var renewals = 0
        server.createContext("/") { e -> requests++; e.sendResponseHeaders(403, -1); e.close() }
        server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor(StreamUrlRenewalInterceptor { renewals++; it }).build()
            repeat(2) {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/").build())
                    .execute().use { assertEquals(403, it.code) }
            }
            assertEquals(2, requests)
            assertEquals(1, renewals)
        } finally { server.stop(0) }
    }
}
