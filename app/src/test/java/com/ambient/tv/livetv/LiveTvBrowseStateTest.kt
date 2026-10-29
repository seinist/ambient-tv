package com.ambient.tv.livetv

import org.junit.Assert.*
import org.junit.Test

class LiveTvBrowseStateTest {
    private fun dashboard(warnings: List<String> = emptyList()) = LiveTvDashboard(
        LiveTvServerInfo("Test", null, null), emptyList(), emptyList(), emptyList(), emptyList(),
        warnings = warnings,
    )

    @Test fun staleSnapshotRemainsVisibleWhileRefreshIsDue() {
        var clock = 0L
        val state = LiveTvBrowseState { clock }
        assertTrue(state.needsRefresh())
        val snapshot = dashboard()
        state.update(snapshot, LiveTvCapabilities(available = true, guide = true))
        clock = 44_999
        assertFalse(state.needsRefresh())
        clock = 45_000
        assertTrue(state.needsRefresh())
        assertSame(snapshot, state.dashboard)
        assertTrue(state.capabilities.guide)
    }

    @Test fun partialResultsAndMutationsRemainVisibleButRequireRefresh() {
        val state = LiveTvBrowseState { 0L }
        val partial = dashboard(listOf("EPG unavailable"))
        state.update(partial, LiveTvCapabilities())
        assertTrue(state.needsRefresh())
        assertSame(partial, state.dashboard)
        state.update(dashboard(), LiveTvCapabilities())
        val changed = dashboard()
        state.updateAfterMutation(changed)
        assertTrue(state.needsRefresh())
        assertSame(changed, state.dashboard)
    }

    @Test fun sourceStatesAndChannelScrollPositionsStayIndependent() {
        val first = LiveTvBrowseState()
        val second = LiveTvBrowseState()
        first.update(dashboard(), LiveTvCapabilities())
        first.savePosition("guide", "channel-a", 27)
        first.savePosition("guide:channel-a", "event-1", 12)
        first.savePosition("guide:channel-b", "event-2", 8)
        assertNull(second.dashboard)
        assertNull(second.position("guide"))
        assertEquals(LiveTvBrowseState.Position("channel-a", 27), first.position("guide"))
        assertEquals(LiveTvBrowseState.Position("event-1", 12), first.position("guide:channel-a"))
    }

    @Test fun aNewSessionClearsPositionsWhileKeepingDashboardCapabilitiesAndFreshness() {
        val state = LiveTvBrowseState { 10_000L }
        val snapshot = dashboard()
        val capabilities = LiveTvCapabilities(available = true, guide = true, recordings = true)
        state.update(snapshot, capabilities)
        state.savePosition("channels", "channel-a", 27)
        state.savePosition("guide", "channel-b", 13)
        state.savePosition("guide:channel-b", "event-2", 8)

        state.clearPositions()

        assertNull(state.position("channels"))
        assertNull(state.position("guide"))
        assertNull(state.position("guide:channel-b"))
        assertSame(snapshot, state.dashboard)
        assertSame(capabilities, state.capabilities)
        assertFalse(state.needsRefresh())
        state.savePosition("channels", "channel-first", 0)
        assertEquals(LiveTvBrowseState.Position("channel-first", 0), state.position("channels"))
    }
}
