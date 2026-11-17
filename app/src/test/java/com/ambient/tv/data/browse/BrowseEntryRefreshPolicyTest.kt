package com.ambient.tv.data.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseEntryRefreshPolicyTest {
    private var now = 1_000_000L
    private val policy = BrowseEntryRefreshPolicy(minIntervalMs = 60_000L) { now }

    @Test fun firstEntryTrustsDiskCacheAndImmediateReentryDoesNotExpire() {
        assertEquals(emptySet<String>(), policy.sourcesToExpire(setOf("plex", "youtube")))
        now += 15_000L
        assertEquals(emptySet<String>(), policy.sourcesToExpire(setOf("plex", "youtube")))
        now += 45_000L
        assertEquals(setOf("plex", "youtube"), policy.sourcesToExpire(setOf("plex", "youtube")))
        assertEquals(emptySet<String>(), policy.sourcesToExpire(setOf("plex")))
    }

    @Test fun eachSystemTracksItsOwnFirstEntry() {
        assertEquals(emptySet<String>(), policy.sourcesToExpire(setOf("plex")))
        now += 70_000L
        assertEquals(setOf("plex"), policy.sourcesToExpire(setOf("plex", "emby")))
        assertEquals(emptySet<String>(), policy.sourcesToExpire(setOf("emby")))
        now += 60_000L
        assertEquals(setOf("plex", "emby"), policy.sourcesToExpire(setOf("plex", "emby")))
    }

    @Test fun clockRollbackDoesNotKeepAnEntryFreshForever() {
        policy.sourcesToExpire(setOf("tvheadend"))
        now -= 1_000L
        assertEquals(setOf("tvheadend"), policy.sourcesToExpire(setOf("tvheadend")))
    }
}
