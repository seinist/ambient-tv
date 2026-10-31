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

    @Test fun unrelatedLiveTvSourcesStayVisible() {
        assertTrue(homeTvSourceVisible("iptv:playlist", setOf("tvheadend"), tvheadendOnline = false))
        assertTrue(homeTvSourceVisible("jellyfin:server-b", setOf("tvheadend"), tvheadendOnline = false))
    }
}
