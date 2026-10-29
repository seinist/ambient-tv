package com.ambient.tv.livetv

import com.ambient.tv.connectivity.HostHealth
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class LiveTvAvailabilityTest {
    @Test fun everySavedMediaServerKeepsItsOwnHostIdentity() {
        for (system in listOf("plex", "jellyfin", "emby", "iptv")) {
            assertEquals("$system:living-room:user", liveTvHostSource("$system:living-room:user", system))
            assertNotEquals(liveTvHostSource("$system:a", system), liveTvHostSource("$system:b", system))
        }
        assertEquals("tvheadend", liveTvHostSource("tvheadend:connection-scope", "tvheadend"))
    }

    @Test fun storedOrPartiallyFailedDataDoesNotReplaceActualHostEvidence() {
        assertFalse(liveTvSourceOffline(true, HostHealth.ONLINE))
        assertFalse(liveTvSourceOffline(true, HostHealth.UNKNOWN))
        assertTrue(liveTvSourceOffline(true, HostHealth.OFFLINE))
        assertTrue(liveTvSourceOffline(false, HostHealth.ONLINE))
    }

    @Test fun aSlowGuideCanRetryWhileAnOtherwiseReachableSourceRemainsPlayable() {
        assertTrue(liveTvRefreshRetryable(SocketTimeoutException("EPG timed out")))
        assertFalse(liveTvSourceOffline(true, HostHealth.ONLINE))
        assertTrue(liveTvRefreshRetryable(ConnectException("Refused")))
        assertTrue(liveTvRefreshRetryable(IOException("Live TV: HTTP 503")))
    }

    @Test fun rejectedAccessMissingEndpointsAndInvalidResponsesAreNotAutomaticOutageRetries() {
        assertFalse(liveTvRefreshRetryable(IOException("Live TV: HTTP 401")))
        assertFalse(liveTvRefreshRetryable(IOException("Live TV: HTTP 403")))
        assertFalse(liveTvRefreshRetryable(IOException("Live TV: HTTP 404")))
        assertFalse(liveTvRefreshRetryable(IllegalStateException("Malformed programme response")))
    }
}
