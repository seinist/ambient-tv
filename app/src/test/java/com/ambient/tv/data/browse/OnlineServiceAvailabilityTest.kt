package com.ambient.tv.data.browse

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
