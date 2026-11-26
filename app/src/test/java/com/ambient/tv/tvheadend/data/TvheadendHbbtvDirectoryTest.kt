package com.ambient.tv.tvheadend.data

import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvChannelKind
import com.ambient.tv.livetv.LiveTvHbbtvApp
import org.junit.Assert.*
import org.junit.Test

class TvheadendHbbtvDirectoryTest {
    private val raw = LiveTvHbbtvApp("https://one.example/app", "One", "all")
    private val htsp = LiveTvHbbtvApp("https://two.example/app", "Two", "apps")
    private val channel = LiveTvChannel("channel", "TV", 1, "logo", tags = listOf("News"), kind = LiveTvChannelKind.TV)
    private val topology = mapOf("channel" to listOf("service"))
    private fun directory() = TvheadendHbbtvDirectory(9982, topology)

    @Test fun independentTransportsMergeAndDeduplicateWithoutChangingBaseMetadata() {
        val dir = directory().withHttp(mapOf("service" to listOf(raw)), 100)
            .withHtsp(mapOf("channel" to listOf(raw.copy(name = "Duplicate"), htsp)), 200)
        val result = dir.enrich(channel)
        assertEquals(listOf(raw, htsp), result.hbbtvApps)
        assertEquals(raw.url, result.hbbtvUrl)
        assertEquals(channel, result.copy(hbbtvApps = emptyList(), hbbtvUrl = null, hbbtvName = null))
        val list = listOf(result)
        assertSame(list, dir.enrich(list))
        assertTrue(dir.hasMenuApps())
    }

    @Test fun oneTransportRefreshCannotEraseTheOtherAndAuthoritativeEmptyRemovesApps() {
        val before = directory().withHttp(mapOf("service" to listOf(raw)), 100)
            .withHtsp(mapOf("channel" to listOf(htsp)), 200)
        val afterHttp = before.withHttp(mapOf("service" to emptyList()), 300)
        assertEquals(listOf(htsp), afterHttp.apps("channel"))
        assertEquals(200L, afterHttp.htspLoadedAt)
        val empty = afterHttp.withHtsp(mapOf("channel" to emptyList()), 400)
        assertTrue(empty.apps("channel").isEmpty())
        assertFalse(empty.hasMenuApps())
        assertNull(empty.enrich(before.enrich(channel)).hbbtvUrl)
    }

    @Test fun remappedOrRemovedChannelsCannotInheritOldServiceApplications() {
        val before = directory().withHttp(mapOf("service" to listOf(raw)), 100)
            .withHtsp(mapOf("channel" to listOf(htsp)), 200)
        val remapped = before.withTopology(mapOf("channel" to listOf("new-service"), "other" to listOf("other-service")))
        assertTrue(remapped.apps("channel").isEmpty())
        assertEquals(0L, remapped.httpLoadedAt)
        assertEquals(0L, remapped.htspLoadedAt)
        val removed = before.withTopology(emptyMap())
        assertFalse(removed.hasMenuApps())
    }

    @Test fun legacyCacheIsVisibleUntilARealTransportCanReplaceItsChannel() {
        val old = channel.copy(hbbtvApps = listOf(raw), hbbtvUrl = raw.url, hbbtvName = raw.name)
        val migrated = TvheadendHbbtvDirectory.fromLegacy(9982, listOf(old)).withTopology(topology)
        assertEquals(listOf(raw), migrated.apps("channel"))
        assertEquals(listOf(htsp), migrated.withHtsp(mapOf("channel" to listOf(htsp)), 200).apps("channel"))
    }

    @Test fun sixHourFreshnessAndSeparateFailureBackoff() {
        val now = 100_000_000L
        assertFalse(hbbtvRefreshDue(now - 1000, null, now, 1000, false))
        assertTrue(hbbtvRefreshDue(now - 6 * 3600_000L, null, now, 1000, false))
        assertFalse(hbbtvRefreshDue(0, 1000L, now, 2000, false))
        assertTrue(hbbtvRefreshDue(0, 1000L, now, 901_000L, false))
        assertTrue(hbbtvRefreshDue(now, 1000L, now, 31_000L, true))
        assertFalse(hbbtvRefreshDue(now, 1000L, now, 2000L, true))
        assertTrue(hbbtvRefreshDue(now + 1000, null, now, 1000, false))
    }

    @Test fun missingRowsInOptionalResponsesRetainPreviouslyKnownApplications() {
        val before = directory().withHttp(mapOf("service" to listOf(raw)), 100)
            .withHtsp(mapOf("channel" to listOf(htsp)), 200)
        assertEquals(listOf(raw, htsp), before.withHttp(emptyMap(), 300).withHtsp(emptyMap(), 400).apps("channel"))
    }

    @Test fun legacyAppsSurviveOneEmptyTransportUntilBothConfirmAbsence() {
        val old = channel.copy(hbbtvApps = listOf(raw), hbbtvUrl = raw.url)
        val migrated = TvheadendHbbtvDirectory.fromLegacy(9982, listOf(old)).withTopology(topology)
        val onlyHttp = migrated.withHttp(mapOf("service" to emptyList()), 100)
        assertEquals(listOf(raw), onlyHttp.apps("channel"))
        assertTrue(onlyHttp.withHtsp(mapOf("channel" to emptyList()), 200).apps("channel").isEmpty())
    }

    @Test fun changingHtspPortKeepsIndependentHttpMetadataAndInvalidatesHtsp() {
        val before = directory().withHttp(mapOf("service" to listOf(raw)), 100)
            .withHtsp(mapOf("channel" to listOf(htsp)), 200)
        val changed = before.withHtspPort(9983)
        assertEquals(listOf(raw), changed.apps("channel"))
        assertEquals(100L, changed.httpLoadedAt)
        assertEquals(0L, changed.htspLoadedAt)
        assertEquals(topology, changed.servicesByChannel)
    }
}
