package com.ambient.tv.ui.detail

import com.ambient.tv.data.browse.SourceHealth
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class DetailReloadTest {
    private fun http(code: Int) = retrofit2.HttpException(Response.error<Any>(code, "".toResponseBody()))

    @Test
    fun aStartingServerKeepsPlayVisibleWhileRetrying() {
        assertTrue(detailFailureTransient(http(503)))
        assertTrue(detailFailureTransient(java.net.SocketTimeoutException()))
        assertTrue(detailFailureTransient(java.io.IOException("Media source changed during loading; please retry")))
        assertFalse(detailFailureTransient(http(401)))
        assertFalse(detailOfflineAfterFailure(removed = false, SourceHealth.UNKNOWN, retriesLeft = true))
        assertFalse(detailOfflineAfterFailure(removed = false, SourceHealth.ONLINE, retriesLeft = true))
    }

    @Test
    fun offlineComesFromAvailability() {
        assertTrue(detailOfflineAfterFailure(removed = false, SourceHealth.OFFLINE, retriesLeft = true))
        assertTrue(detailOfflineAfterFailure(removed = false, SourceHealth.AUTH_REQUIRED, retriesLeft = true))
        assertTrue(detailOfflineAfterFailure(removed = false, SourceHealth.UNKNOWN, retriesLeft = false))
        assertFalse(detailOfflineAfterFailure(removed = true, SourceHealth.OFFLINE, retriesLeft = false))
        assertTrue(detailItemRemoved(http(404)))
    }

    @Test
    fun backoffIsTwoFourEightSeconds() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 8_000L), (0..3).map(::detailReloadDelayMs))
    }

}
