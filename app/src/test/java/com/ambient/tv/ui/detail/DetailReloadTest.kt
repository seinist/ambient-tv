package com.ambient.tv.ui.detail

import com.ambient.tv.data.browse.SourceHealth
import com.ambient.tv.domain.model.PlexServerIdentity
import com.ambient.tv.server.ServerConnectionState
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

    @Test
    fun onlyARealRecoveryReloads() {
        val online = ServerConnectionState.Online(PlexServerIdentity(machineIdentifier = null, version = null, claimed = false))
        fun run(vararg states: ServerConnectionState): List<Boolean> {
            var tracker = DetailConnectionTracker()
            return states.map { state -> tracker.next(state).also { tracker = it.first }.second }
        }
        // First connect of the page: the initial load covers it.
        assertEquals(listOf(false, false), run(ServerConnectionState.Checking, online))
        // Passive re-check (resume, background probe): no reload.
        assertEquals(listOf(false, false, false, false), run(online, ServerConnectionState.Checking, ServerConnectionState.Connecting, online))
        // Real recovery after Offline, Error or a timed-out wake.
        assertEquals(listOf(false, false, false, true), run(online, ServerConnectionState.Offline, ServerConnectionState.Checking, online))
        assertEquals(listOf(false, true), run(ServerConnectionState.Error("x"), online))
        assertEquals(listOf(false, false, true), run(ServerConnectionState.WakeTimedOut, ServerConnectionState.Connecting, online))
        // A wake finished: the server was asleep, the page data is from before.
        assertEquals(listOf(false, false, false, true),
            run(online, ServerConnectionState.SendingWakePacket, ServerConnectionState.Waking(10, 60), online))
        assertFalse(connectionCameOnline(online, false, online))
        assertFalse(connectionCameOnline(null, false, online))
        assertFalse(connectionCameOnline(ServerConnectionState.Offline, true, ServerConnectionState.Offline))
    }
}
