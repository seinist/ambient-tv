package com.ambient.tv.ui.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class RenewingMedia3DataSourceTest {
    @Test fun okhttpRenewsForbiddenRange() {
        expiredRangeIsRenewedWithoutLosingOffsetOrHeaders(OkHttpDataSource.Factory(OkHttpClient()), 403)
    }

    @Test fun platformHttpRenewsGoneRange() {
        expiredRangeIsRenewedWithoutLosingOffsetOrHeaders(DefaultHttpDataSource.Factory(), 410)
    }

    private fun expiredRangeIsRenewedWithoutLosingOffsetOrHeaders(upstream: DataSource.Factory, status: Int) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var expiredRequests = 0
        val ranges = mutableListOf<String?>()
        val headers = mutableListOf<String?>()
        server.createContext("/old") { e -> expiredRequests++; e.sendResponseHeaders(status, -1); e.close() }
        server.createContext("/new") { e ->
            ranges += e.requestHeaders.getFirst("Range")
            headers += e.requestHeaders.getFirst("X-Test")
            e.responseHeaders.add("Content-Range", "bytes 3-5/6")
            e.sendResponseHeaders(206, 3)
            e.responseBody.use { it.write(byteArrayOf(4, 5, 6)) }
        }
        server.start()
        try {
            val root = "http://127.0.0.1:${server.address.port}"
            val renewed = mutableListOf<String>()
            val factory = RenewingMedia3DataSourceFactory(upstream) {
                renewed += it
                "$root/new"
            }
            val spec = DataSpec.Builder().setUri("$root/old").setPosition(3).setLength(3)
                .setHttpRequestHeaders(mapOf("X-Test" to "retained")).build()
            repeat(2) {
                val source = factory.createDataSource()
                try {
                    assertEquals(3L, source.open(spec))
                    val bytes = ByteArray(3)
                    var read = 0
                    while (read < bytes.size) {
                        val count = source.read(bytes, read, bytes.size - read)
                        assertTrue(count > 0)
                        read += count
                    }
                    assertArrayEquals(byteArrayOf(4, 5, 6), bytes)
                } finally {
                    source.close()
                }
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
            val factory = RenewingMedia3DataSourceFactory(DefaultHttpDataSource.Factory()) { renewals++; it }
            val spec = DataSpec.Builder().setUri("http://127.0.0.1:${server.address.port}/").build()
            repeat(2) {
                val source = factory.createDataSource()
                try {
                    assertEquals(403, assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { source.open(spec) }.responseCode)
                } finally {
                    source.close()
                }
            }
            assertEquals(2, requests)
            assertEquals(1, renewals)
        } finally { server.stop(0) }
    }
}
