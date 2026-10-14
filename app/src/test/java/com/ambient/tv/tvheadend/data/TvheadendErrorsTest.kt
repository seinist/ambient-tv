package com.ambient.tv.tvheadend.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

class TvheadendErrorsTest {
    @Test
    fun `http statuses split into login and other`() {
        assertEquals(TvheadendTestFailure.AUTH, classifyTvheadendTestFailure(TvheadendHttpException(401, "x")))
        assertEquals(TvheadendTestFailure.AUTH, classifyTvheadendTestFailure(TvheadendHttpException(403, "x")))
        assertEquals(TvheadendTestFailure.HTTP, classifyTvheadendTestFailure(TvheadendHttpException(404, "x")))
        assertEquals(401, tvheadendFailureHttpCode(RuntimeException(TvheadendHttpException(401, "x"))))
    }

    @Test
    fun `network causes are named`() {
        assertEquals(TvheadendTestFailure.HOST, classifyTvheadendTestFailure(UnknownHostException("tvh")))
        assertEquals(TvheadendTestFailure.TLS, classifyTvheadendTestFailure(SSLHandshakeException("plain http")))
        assertEquals(TvheadendTestFailure.UNREACHABLE, classifyTvheadendTestFailure(ConnectException("refused")))
        assertEquals(TvheadendTestFailure.UNREACHABLE, classifyTvheadendTestFailure(SocketTimeoutException("timeout")))
        assertEquals(TvheadendTestFailure.UNREACHABLE, classifyTvheadendTestFailure(IOException("wrapped", ConnectException("refused"))))
    }

    @Test
    fun `app policies are named`() {
        assertEquals(TvheadendTestFailure.NOT_LOCAL, classifyTvheadendTestFailure(TvheadendNotLocalException("x")))
        assertEquals(TvheadendTestFailure.VPN, classifyTvheadendTestFailure(TvheadendVpnException("x", null)))
        assertEquals(TvheadendTestFailure.WAKE, classifyTvheadendTestFailure(TvheadendWakeTimeoutException("x")))
        assertEquals(TvheadendTestFailure.OTHER, classifyTvheadendTestFailure(IllegalStateException("x")))
    }
}
