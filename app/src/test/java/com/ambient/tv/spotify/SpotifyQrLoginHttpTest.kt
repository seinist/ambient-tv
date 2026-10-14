package com.ambient.tv.spotify

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpotifyQrLoginHttpTest {
    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `routes require the exact login token`() {
        assertEquals(QrLoginRoute.Login, qrLoginRoute("GET /login/abc_-1 HTTP/1.1", "abc_-1"))
        assertEquals(QrLoginRoute.Login, qrLoginRoute("GET /login/abc_-1?x=1 HTTP/1.1", "abc_-1"))
        assertEquals(QrLoginRoute.NotFound, qrLoginRoute("GET /login/abc_-1x HTTP/1.1", "abc_-1"))
        assertEquals(QrLoginRoute.NotFound, qrLoginRoute("GET /login/ HTTP/1.1", "abc_-1"))
        assertEquals(QrLoginRoute.NotFound, qrLoginRoute("GET /login/%61bc_-1 HTTP/1.1", "abc_-1"))
        assertEquals(QrLoginRoute.Callback, qrLoginRoute("GET /callback?code=c&state=s HTTP/1.1", "t"))
        assertEquals(QrLoginRoute.MethodNotAllowed, qrLoginRoute("POST /callback HTTP/1.1", "t"))
        assertEquals(QrLoginRoute.MethodNotAllowed, qrLoginRoute("", "t"))
    }

    @Test
    fun `request head skips headers and returns the request line`() {
        val head = "GET /callback?code=1 HTTP/1.1\r\nHost: tv\r\nUser-Agent: phone\r\n\r\nbody"
        assertEquals("GET /callback?code=1 HTTP/1.1", readRequestHead(stream(head)))
        assertEquals("GET / HTTP/1.0", readRequestHead(stream("GET / HTTP/1.0\n")))
    }

    @Test
    fun `oversized lines, endless headers and slow clients are cut off`() {
        assertThrows(IOException::class.java) { readRequestHead(stream("GET /" + "a".repeat(9000))) }
        val many = "GET / HTTP/1.1\r\n" + "X: y\r\n".repeat(65) + "\r\n"
        assertThrows(IOException::class.java) { readRequestHead(stream(many)) }
        assertEquals("GET / HTTP/1.1", readRequestHead(stream("GET / HTTP/1.1\r\n" + "X: y\r\n".repeat(64) + "\r\n")))
        var now = 0L
        assertThrows(IOException::class.java) {
            readRequestHead(stream("GET / HTTP/1.1\r\n\r\n"), deadlineMs = 5, clock = { now++ })
        }
    }

    @Test
    fun `result page text is escaped`() {
        assertEquals("&lt;b&gt;&quot;A&amp;B&quot;&lt;/b&gt;", escapeHtml("<b>\"A&B\"</b>"))
    }
}
