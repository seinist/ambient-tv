package com.ambient.tv.data.local

import org.junit.Assert.*
import org.junit.Test

class RecentTvChannelsTest {
    private fun preferences() = AppPreferences(memoryPreferences()).apply {
        tvheadendEnabled = true; tvheadendHost = "nas.local"; tvheadendUsername = "alice"
        // #117: history is only recorded for a verified TVHeadend login.
        tvheadendLoginVerified = true
    }

    @Test fun historyIsUniqueMostRecentFirstAndBounded() {
        var history = RecentTvChannels("server")
        (1..30).forEach { history = history.watched("ch$it") }
        history = history.watched("ch25").watched("")
        assertEquals(12, history.channelIds.size)
        assertEquals(listOf("ch25", "ch30", "ch29"), history.channelIds.take(3))
        assertEquals(history.channelIds.size, history.channelIds.distinct().size)
    }

    @Test fun equivalentEndpointSpellingsShareHistory() {
        assertEquals(RecentTvChannels.scope("HTTP://NAS.local/", 9981, "alice"),
            RecentTvChannels.scope("nas.local", 9981, "alice"))
    }

    @Test fun portPathSchemeAndUserPartitionHistory() {
        val base = RecentTvChannels.scope("nas.local", 9981, "alice")
        assertNotEquals(base, RecentTvChannels.scope("nas.local", 9982, "alice"))
        assertNotEquals(base, RecentTvChannels.scope("https://nas.local", 9981, "alice"))
        assertNotEquals(base, RecentTvChannels.scope("nas.local/tvh", 9981, "alice"))
        assertNotEquals(base, RecentTvChannels.scope("nas.local", 9981, "bob"))
    }

    @Test fun preferencesPersistAndIsolateServerHistory() {
        val prefs = preferences()
        val first = prefs.tvheadendHistoryScope
        prefs.recordWatchedTvChannel(first, "one")
        prefs.tvheadendHost = "second.local"
        prefs.tvheadendLoginVerified = true
        assertTrue(prefs.recentTvChannelIds.isEmpty())
        prefs.recordWatchedTvChannel(first, "stale")
        assertTrue(prefs.recentTvChannelIds.isEmpty())
        prefs.recordWatchedTvChannel(prefs.tvheadendHistoryScope, "two")
        prefs.tvheadendHost = "nas.local"
        prefs.tvheadendLoginVerified = true
        assertEquals(listOf("one"), prefs.recentTvChannelIds)
        prefs.tvheadendHost = "second.local"
        prefs.tvheadendLoginVerified = true
        assertEquals(listOf("two"), prefs.recentTvChannelIds)
    }

    @Test fun disabledProviderDoesNotRecordAndClearRemovesAllScopes() {
        val prefs = preferences()
        prefs.recordWatchedTvChannel(prefs.tvheadendHistoryScope, "one")
        prefs.tvheadendEnabled = false
        prefs.recordWatchedTvChannel(prefs.tvheadendHistoryScope, "two")
        assertEquals(listOf("one"), prefs.recentTvChannelIds)
        prefs.clearRecentTvChannels()
        assertTrue(prefs.recentTvChannelIds.isEmpty())
    }

    @Test fun historyRetainsThirtyTwoAccountsAcrossTvProviders() {
        val prefs = preferences()
        (1..33).forEach {
            prefs.tvheadendUsername = "user$it"
            prefs.tvheadendLoginVerified = true
            prefs.recordWatchedTvChannel(prefs.tvheadendHistoryScope, "channel")
        }
        prefs.tvheadendUsername = "user1"
        prefs.tvheadendLoginVerified = true
        assertTrue(prefs.recentTvChannelIds.isEmpty())
        prefs.tvheadendUsername = "user2"
        prefs.tvheadendLoginVerified = true
        assertEquals(listOf("channel"), prefs.recentTvChannelIds)
    }
}
