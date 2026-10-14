package com.ambient.tv.notifications

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureReasonTest {
    /** Shape of retrofit2.HttpException: the status comes from code(). */
    class FakeHttpException(private val status: Int) : RuntimeException("HTTP $status Response.error()") {
        fun code(): Int = status
    }

    /** Shape of an exception with a Kotlin `code` property (getCode()). */
    class FakeCodeException(val code: Int) : IllegalStateException("failed")

    @Test
    fun `http status from typed exceptions and messages`() {
        assertEquals(401, failureHttpCode(FakeHttpException(401)))
        assertEquals(404, failureHttpCode(RuntimeException(FakeCodeException(404))))
        assertEquals(503, failureHttpCode(IllegalStateException("TVHeadend HTTP 503 Service Unavailable")))
        assertNull(failureHttpCode(IllegalStateException("no status here")))
    }

    @Test
    fun `kinds are named`() {
        assertEquals(FailureKind.AUTH, classifyFailure(FakeHttpException(401)).kind)
        assertEquals(FailureKind.AUTH, classifyFailure(FakeHttpException(403)).kind)
        assertEquals(FailureKind.HTTP, classifyFailure(FakeHttpException(500)).kind)
        assertEquals(FailureKind.HOST, classifyFailure(UnknownHostException("nas")).kind)
        assertEquals(FailureKind.TLS, classifyFailure(SSLHandshakeException("bad")).kind)
        assertEquals(FailureKind.UNREACHABLE, classifyFailure(IOException("x", ConnectException("refused"))).kind)
        assertEquals(FailureKind.TIMEOUT, classifyFailure(SocketTimeoutException("timeout")).kind)
        assertEquals(FailureKind.OTHER, classifyFailure(IllegalStateException("odd")).kind)
    }

    @Test
    fun `details never carry tokens or query strings`() {
        val raw = "Failed http://192.168.1.5:32400/library/sections?X-Plex-Token=SECRET123&x=1 password=hunter2\n next"
        val clean = sanitizeFailureDetail(raw)
        assertFalse(clean.contains("SECRET123"))
        assertFalse(clean.contains("hunter2"))
        assertFalse(clean.contains("\n"))
        assertTrue(clean.contains("http://192.168.1.5:32400/library/sections"))
        assertTrue(sanitizeFailureDetail("a".repeat(500)).length <= 160)
    }
}
