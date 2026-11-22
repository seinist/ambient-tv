package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvDashboard
import com.ambient.tv.livetv.LiveTvHbbtvApp
import com.ambient.tv.livetv.LiveTvServerInfo
import com.ambient.tv.livetv.LiveTvRecording
import com.ambient.tv.notifications.OperationFeedback
import org.junit.Assert.assertSame
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

    @Test fun offlineEmptyTabsAreUnknownUntilAFreshReadConfirmsThem() {
        for (tab in TvheadendTab.entries) {
            val cached = TvheadendUiState(dashboard = empty, tab = tab, initialDashboardSettled = true, savedView = true)
            assertTrue(cached.emptyContentUnconfirmed())
            assertFalse(cached.copy(savedView = false).emptyContentUnconfirmed())
            assertTrue(cached.copy(savedView = false, offline = true).emptyContentUnconfirmed())
        }
    }

    @Test fun aSavedChannelListRemainsBrowsableWithoutAnEpgSnapshot() {
        val saved = TvheadendUiState(dashboard = empty.copy(channels = listOf(channel)),
            savedView = true, offline = true, initialDashboardSettled = true)
        assertTrue(saved.tabHasContent())
        assertFalse(saved.awaitingInitialContent())
        assertFalse(saved.emptyContentUnconfirmed())
        assertTrue(saved.connectionRequired)
    }

    @Test fun anOpenDashboardLocksAfterAServiceFailureEvenWhileItsHostStaysOnline() {
        val loaded = TvheadendUiState(dashboard = empty.copy(channels = listOf(channel)),
            savedView = false, offline = false, initialDashboardSettled = true)
        assertFalse(loaded.connectionRequired)
        val failed = loaded.copy(serviceUnconfirmed = true)
        assertTrue(failed.connectionRequired)
        assertTrue(failed.tabHasContent())
        assertSame(loaded.dashboard, failed.dashboard)
        assertTrue(failed.copy(tab = TvheadendTab.RECORDINGS).emptyContentUnconfirmed())
    }

    @Test fun anUnconfirmedWriteKeepsItsRecordingAndEditorUntilTheServerIsReadAgain() {
        val entry = LiveTvRecording("recording", "one", "One", "Programme", startEpochSeconds = 10,
            stopEpochSeconds = 20, status = null, scheduleStatus = "scheduled", url = null,
            imageUrl = null, fanartUrl = null, fileSizeBytes = null)
        val dashboard = empty.copy(channels = listOf(channel), upcoming = listOf(entry))
        val editor = LiveTvEditTarget.Timer(entry)
        val loaded = TvheadendUiState(dashboard = dashboard, savedView = false,
            actionInProgress = true, selectedRecording = entry, editing = editor, tab = TvheadendTab.TIMERS)
        val feedback = OperationFeedback("write", "edit", 1)
        val failed = loaded.afterUnconfirmedRecordingAction(feedback)
        assertFalse(failed.actionInProgress)
        assertTrue(failed.connectionRequired)
        assertSame(dashboard, failed.dashboard)
        assertSame(entry, failed.selectedRecording)
        assertSame(editor, failed.editing)
        assertSame(feedback, failed.actionFeedback)
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
