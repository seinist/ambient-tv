package com.ambient.tv.data.browse

import java.io.IOException
import java.net.ConnectException
import com.ambient.tv.youtube.IncompleteYouTubeFeedException
import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class OnlineServiceAvailabilityTest {
    @Test fun internetLossDoesNotMarkIntranetMediaOffline() {
        val configured = setOf("plex", "jellyfin", "emby", "tvheadend", "files", "dlna",
            "iptv", "youtube", "radio", "weather", "telegram", "trailers")
        val snapshot = OnlineServiceSnapshot(
            internet = InternetReachability.UNAVAILABLE,
            hasNetwork = true,
        )
        assertEquals(ONLINE_SERVICE_KEYS, snapshot.unavailableServices(configured))
    }

    @Test fun workingServiceOverridesUnvalidatedInternetAndFailuresRemainScoped() {
        val snapshot = OnlineServiceSnapshot(
            internet = InternetReachability.UNAVAILABLE,
            hasNetwork = true,
            reachable = setOf("radio"),
            failed = setOf("youtube"),
        )
        // Radio's real response disproves a *global* internet outage. Only YouTube failed.
        assertEquals(setOf("youtube"), snapshot.unavailableServices(setOf(
            "plex", "youtube", "weather", "radio")))
        assertEquals(setOf("youtube"), snapshot.copy(internet = InternetReachability.AVAILABLE)
            .unavailableServices(setOf("youtube", "radio", "plex")))
    }

    @Test fun liveReplyOverridesAMissingDefaultRouteForBothMenuAndInternetStatus() {
        val snapshot = OnlineServiceSnapshot(
            internet = InternetReachability.UNAVAILABLE,
            hasNetwork = false,
            reachable = setOf("radio"),
            failed = setOf("youtube"),
        )

        assertFalse(snapshot.internetUnavailable)
        assertEquals(setOf("youtube"), snapshot.unavailableServices(setOf(
            "radio", "youtube", "weather", "plex", "files:share",
        )))
        // Whether Android has announced a route must not change the meaning of a live reply.
        assertFalse(snapshot.copy(hasNetwork = true).internetUnavailable)
        assertEquals(snapshot.unavailableServices(ONLINE_SERVICE_KEYS),
            snapshot.copy(hasNetwork = true).unavailableServices(ONLINE_SERVICE_KEYS))
    }

    @Test fun aMissingRouteWithoutFreshEvidenceStillMeansInternetIsUnavailable() {
        val snapshot = OnlineServiceSnapshot(
            internet = InternetReachability.UNAVAILABLE,
            hasNetwork = false,
        )

        assertTrue(snapshot.internetUnavailable)
        assertEquals(ONLINE_SERVICE_KEYS, snapshot.unavailableServices(ONLINE_SERVICE_KEYS + "plex"))
    }

    @Test fun noProbeDoesNotMeanOfflineUntilInternetIsKnownUnavailable() {
        val snapshot = OnlineServiceSnapshot()
        assertTrue(snapshot.unavailableServices(ONLINE_SERVICE_KEYS).isEmpty())
    }

    @Test fun actualRequestFailuresAreNotConfusedWithCancellationOrLoginErrors() {
        assertTrue(onlineServiceRequestFailed(IOException("unreachable")))
        assertFalse(onlineServiceRequestFailed(CancellationException("navigation changed")))
        assertFalse(onlineServiceRequestFailed(IllegalStateException("login required")))
        assertFalse(onlineServiceRequestFailed(IllegalStateException("empty feed")))
    }

    @Test fun incompleteFeedWithoutTransportFailureDoesNotMeanSmartTubeIsOffline() {
        assertFalse(onlineServiceRequestFailed(IncompleteYouTubeFeedException()))
        assertFalse(onlineServiceRequestFailed(IncompleteYouTubeFeedException(
            IllegalStateException("A feed has no results"),
        )))
    }

    @Test fun incompleteFeedPreservesARealConnectionFailure() {
        assertTrue(onlineServiceRequestFailed(IncompleteYouTubeFeedException(
            ConnectException("Network is unreachable"),
        )))
    }

    @Test fun rejectedRequestsAndRateLimitsStayDistinctFromAnOfflineService() {
        listOf(401, 403, 404, 429).forEach { status ->
            val http = HttpException(Response.error<Unit>(status, "Rejected".toResponseBody()))
            assertFalse("HTTP $status", onlineServiceRequestFailed(http))
            assertFalse("Wrapped HTTP $status", onlineServiceRequestFailed(
                IOException("Feed fetch failed", http),
            ))
            assertFalse("Incomplete HTTP $status", onlineServiceRequestFailed(
                IncompleteYouTubeFeedException(http),
            ))
        }
    }

    @Test fun smartTubeStringHttpWrappersDoNotTurnAccessErrorsIntoOfflineStatus() {
        listOf(403, 429).forEach { status ->
            assertFalse(onlineServiceRequestFailed(
                IOException("HTTP $status", IllegalStateException("MediaServiceCore request rejected")),
            ))
        }
    }

    @Test fun serverOutagesAndRequestTimeoutsRemainReportedThroughFeedWrappers() {
        listOf(408, 500, 502, 503).forEach { status ->
            val http = HttpException(Response.error<Unit>(status, "Unavailable".toResponseBody()))
            assertTrue("HTTP $status", onlineServiceRequestFailed(http))
            assertTrue("Wrapped HTTP $status", onlineServiceRequestFailed(
                IOException("Feed fetch failed", http),
            ))
            assertTrue("Incomplete HTTP $status", onlineServiceRequestFailed(
                IncompleteYouTubeFeedException(http),
            ))
        }
    }

    @Test fun aWrappedCancellationDoesNotBecomeAnOfflineFailure() {
        val cancellation = CancellationException("Feed navigation changed")
        assertFalse(onlineServiceRequestFailed(IOException("Fetch interrupted", cancellation)))
        assertFalse(onlineServiceRequestFailed(IncompleteYouTubeFeedException(cancellation)))
    }

    @Test fun unvalidatedRouteIsNotAnInternetOutage() {
        // Blocked connectivity probe (DNS filter, Private DNS, VPN, Shield): services keep working.
        val unvalidated = internetReachability(hasNetwork = true, capabilitiesKnown = true,
            validated = false, captivePortal = false)
        assertEquals(InternetReachability.UNKNOWN, unvalidated)
        assertTrue(OnlineServiceSnapshot(internet = unvalidated, hasNetwork = true)
            .unavailableServices(ONLINE_SERVICE_KEYS).isEmpty())
        assertEquals(InternetReachability.AVAILABLE, internetReachability(true, true, validated = true, captivePortal = false))
        assertEquals(InternetReachability.UNAVAILABLE, internetReachability(true, true, validated = false, captivePortal = true))
        assertEquals(InternetReachability.UNAVAILABLE, internetReachability(false, false, validated = false, captivePortal = false))
    }
}
