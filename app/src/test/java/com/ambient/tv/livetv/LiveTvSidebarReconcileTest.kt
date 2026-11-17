package com.ambient.tv.livetv

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTvSidebarReconcileTest {
    private val offline = AvailableLiveTvSource(
        LiveTvSource("iptv:test", "iptv", "My Playlist"),
        LiveTvCapabilities(available = true),
    )
    private val discovered = AvailableLiveTvSource(
        offline.source,
        LiveTvCapabilities(available = true, guide = true),
    )

    @Test fun configuredIptvRemainsVisibleWhenDiscoveryFails() {
        assertEquals(listOf(offline), reconcileSidebarLiveTvSources(emptyList(), listOf(offline)))
    }

    @Test fun successfulDiscoveryReplacesProvisionalCapabilitiesOnce() {
        assertEquals(listOf(discovered), reconcileSidebarLiveTvSources(listOf(discovered), listOf(offline)))
    }

    @Test fun noPlaceholderForNonIptvSourcesThatWereRemoved() {
        val tvheadend = AvailableLiveTvSource(LiveTvSource("tvheadend:one", "tvheadend", "TVHeadend"), LiveTvCapabilities())
        assertEquals(emptyList<AvailableLiveTvSource>(), reconcileSidebarLiveTvSources(emptyList(), listOf(tvheadend)))
    }
}
