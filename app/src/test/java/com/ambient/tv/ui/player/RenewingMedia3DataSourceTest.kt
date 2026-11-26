@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RenewingMedia3DataSourceTest {
    @Test fun expiredRangePreservedAcrossPlatformHttpSources() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val ranges = mutableListOf<String?>()
        val headers = mutableListOf<String?>()
        val renewed = mutableListOf<String>()
        server.createContext("/old") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        server.createContext("/new") { exchange ->
            ranges += exchange.requestHeaders.getFirst("Range")
            headers += exchange.requestHeaders.getFirst("X-Test")
            exchange.responseHeaders.add("Content-Range", "bytes 3-5/6")
            exchange.sendResponseHeaders(206, 3)
            exchange.responseBody.use { it.write(byteArrayOf(4, 5, 6)) }
        }
        server.start()
        try {
            val root = "http://127.0.0.1:${server.address.port}"
            val factory = RenewingMedia3DataSource.Factory(DefaultHttpDataSource.Factory()) { expired ->
                renewed += expired
                "$root/new"
            }
            val spec = DataSpec.Builder().setUri("$root/old").setPosition(3).setLength(3)
                .setHttpRequestHeaders(mapOf("X-Test" to "retained")).build()
            repeat(2) {
                val source = factory.createDataSource()
                try {
                    assertEquals(3L, source.open(spec))
                    val output = ByteArray(3)
                    var offset = 0
                    while (offset < output.size) {
                        val count = source.read(output, offset, output.size - offset)
                        assertTrue(count > 0)
                        offset += count
                    }
                    assertArrayEquals(byteArrayOf(4, 5, 6), output)
                } finally { source.close() }
            }
            assertEquals(listOf("$root/old"), renewed)
            assertEquals(listOf("bytes=3-5", "bytes=3-5"), ranges)
            assertEquals(listOf("retained", "retained"), headers)
        } finally { server.stop(0) }
    }

    @Test fun ordinaryServerErrorIsNotRenewed() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val factory = RenewingMedia3DataSource.Factory(DefaultHttpDataSource.Factory()) {
                fail("Only expired URLs may be renewed")
                null
            }
            val source = factory.createDataSource()
            try {
                val spec = DataSpec.Builder().setUri("http://127.0.0.1:${server.address.port}/").build()
                val failure = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { source.open(spec) }
                assertEquals(500, failure.responseCode)
            } finally { source.close() }
        } finally { server.stop(0) }
    }
}
