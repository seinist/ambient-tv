package com.ambient.tv.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTvVisibilityTest {
    @Test fun programmeClaimsNeedAConfirmedTvheadendConnection() {
        assertFalse(homeTvSourceReachable("tvheadend:living-room", emptySet(), tvheadendOnline = false))
        assertTrue(homeTvSourceReachable("tvheadend:living-room", emptySet(), tvheadendOnline = true))
    }

    @Test fun offlineStatusSuppressesProgrammeClaimsForTheAffectedSource() {
        assertFalse(homeTvSourceReachable("tvheadend:living-room", setOf("tvheadend"), tvheadendOnline = true))
        assertFalse(homeTvSourceReachable("plex:server-a", setOf("plex:server-a"), tvheadendOnline = true))
        assertTrue(homeTvSourceReachable("plex:server-b", setOf("plex:server-a", "plex"), tvheadendOnline = true))
    }

    @Test fun launcherClearsOnlyWhenEveryEnabledSourceIsConfirmedOffline() {
        assertTrue(allHomeTvSourcesOffline(listOf("tvheadend"), emptySet(), tvheadendFailed = true))
        assertFalse(allHomeTvSourcesOffline(listOf("tvheadend"), emptySet(), tvheadendFailed = false))
        assertFalse(allHomeTvSourcesOffline(listOf("tvheadend", "iptv"), setOf("tvheadend"), tvheadendFailed = true))
        assertTrue(allHomeTvSourcesOffline(listOf("tvheadend", "iptv"), setOf("iptv"), tvheadendFailed = true))
        assertFalse(allHomeTvSourcesOffline(emptyList(), emptySet(), tvheadendFailed = true))
    }

    @Test fun unrelatedLiveTvSourcesKeepIndependentProgrammeClaims() {
        assertTrue(homeTvSourceReachable("iptv:playlist", setOf("tvheadend"), tvheadendOnline = false))
        assertTrue(homeTvSourceReachable("jellyfin:server-b", setOf("tvheadend"), tvheadendOnline = false))
    }
}
