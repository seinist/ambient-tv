package com.ambient.tv.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTvVisibilityTest {
    @Test fun cachedTvheadendChannelsAreNotHomeRowsUntilServiceIsOnline() {
        assertFalse(homeTvSourceVisible("tvheadend:living-room", emptySet(), tvheadendOnline = false))
        assertTrue(homeTvSourceVisible("tvheadend:living-room", emptySet(), tvheadendOnline = true))
    }

    @Test fun confirmedOfflineAlwaysHidesTheAffectedSource() {
        assertFalse(homeTvSourceVisible("tvheadend:living-room", setOf("tvheadend"), tvheadendOnline = true))
        assertFalse(homeTvSourceVisible("plex:server-a", setOf("plex"), tvheadendOnline = true))
    }

    @Test fun launcherClearsOnlyWhenEveryEnabledSourceIsConfirmedOffline() {
        assertTrue(allHomeTvSourcesOffline(listOf("tvheadend"), emptySet(), tvheadendFailed = true))
        assertFalse(allHomeTvSourcesOffline(listOf("tvheadend"), emptySet(), tvheadendFailed = false))
        assertFalse(allHomeTvSourcesOffline(listOf("tvheadend", "iptv"), setOf("tvheadend"), tvheadendFailed = true))
        assertTrue(allHomeTvSourcesOffline(listOf("tvheadend", "iptv"), setOf("iptv"), tvheadendFailed = true))
        assertFalse(allHomeTvSourcesOffline(emptyList(), emptySet(), tvheadendFailed = true))
    }

    @Test fun unrelatedLiveTvSourcesStayVisible() {
        assertTrue(homeTvSourceVisible("iptv:playlist", setOf("tvheadend"), tvheadendOnline = false))
        assertTrue(homeTvSourceVisible("jellyfin:server-b", setOf("tvheadend"), tvheadendOnline = false))
    }
}
