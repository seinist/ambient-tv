package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvDashboard
import com.ambient.tv.livetv.LiveTvHbbtvApp
import com.ambient.tv.livetv.LiveTvServerInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvInitialLoadPolicyTest {
    private val empty = LiveTvDashboard(
        serverInfo = LiveTvServerInfo("TV", null, null),
        channels = emptyList(),
        events = emptyList(),
        upcoming = emptyList(),
        recordings = emptyList(),
    )
    private val channel = LiveTvChannel("one", "One", 1, null)

    @Test fun emptyCachedDashboardDoesNotProveRemoteTabsAreEmptyBeforeRefresh() {
        for (tab in TvheadendTab.entries) {
            val initial = TvheadendUiState(dashboard = empty, tab = tab)
            assertTrue("tab: $tab", initial.awaitingInitialContent())
            assertFalse("tab: $tab", initial.copy(initialDashboardSettled = true).awaitingInitialContent())
            assertTrue("tab: $tab", initial.copy(initialDashboardSettled = true, refreshing = true).awaitingInitialContent())
        }
    }

    @Test fun progressiveChannelsKeepLiveAndGuideVisibleButOptionalTabsLoading() {
        val dashboard = empty.copy(channels = listOf(channel))
        val live = TvheadendUiState(dashboard = dashboard, tab = TvheadendTab.LIVE, refreshing = true)
        assertTrue(live.tabHasContent())
        assertFalse(live.awaitingInitialContent())
        assertFalse(live.copy(tab = TvheadendTab.GUIDE).awaitingInitialContent())
        assertTrue(live.copy(tab = TvheadendTab.RECORDINGS).awaitingInitialContent())
        assertTrue(live.copy(tab = TvheadendTab.TIMERS).awaitingInitialContent())
    }

    @Test fun hbbtvIsOnlyRenderedWhenMenuApplicationsWereActuallyDiscovered() {
        val before = TvheadendUiState(dashboard = empty.copy(channels = listOf(channel)), tab = TvheadendTab.HBBTV)
        assertTrue(before.awaitingInitialContent())
        assertFalse(before.copy(initialDashboardSettled = true).awaitingInitialContent())
        val withApps = before.copy(dashboard = empty.copy(channels = listOf(
            channel.copy(hbbtvApps = listOf(LiveTvHbbtvApp("https://example.test/hbbtv", visibility = "apps")))))
        )
        assertTrue(withApps.tabHasContent())
        assertFalse(withApps.awaitingInitialContent())
    }
}
