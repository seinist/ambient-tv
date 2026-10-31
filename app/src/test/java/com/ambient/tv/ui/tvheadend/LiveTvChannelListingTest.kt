package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.GuideOptions
import com.ambient.tv.livetv.GuideSort
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.guideChannels
import com.ambient.tv.livetv.guideEvents
import com.ambient.tv.livetv.guideChannelsPrepared
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The expensive off-main preparation must match the existing channel filtering contract. */
class LiveTvChannelListingTest {
    private fun channel(id: String, name: String, number: Int) =
        LiveTvChannel(uuid = id, name = name, number = number, iconUrl = null, sourceId = "server")

    private fun event(id: String, channelId: String, title: String = "News", start: Long = 4_000L) =
        LiveTvProgramme(
            eventId = id, channelUuid = channelId, channelName = "Station", title = title,
            startEpochSeconds = start, stopEpochSeconds = start + 3_600L, sourceId = "server",
        )

    @Test fun workerUsesSameFilteringAndDeduplicationAsOriginalGuide() {
        val channels = listOf(
            channel("a", "ZDF HD", 2).copy(tags = listOf("German", "News")),
            channel("b", " zdf  hd ", 102).copy(tags = listOf("German")),
            channel("c", "ARD", 1).copy(tags = listOf("Local"), hbbtvUrl = "https://hbbtv.example/start"),
        )
        val events = listOf(
            event("1", "a"), event("2", "b"), event("3", "c", "Regional"),
            event("4", "a", "Later", 8_000L), event("5", "b", "Later", 8_000L),
        )
        val now = 5_000L
        val options = GuideOptions(sort = GuideSort.NAME)
        val prepared = buildLiveTvChannelListing(channels, events, options, now)
        val direct = guideChannels(channels, events.groupBy { it.channelUuid }, now, options)
        assertEquals(direct, prepared.visibleChannels)
        assertEquals(listOf("ARD", "ZDF HD"), prepared.visibleChannels.map { it.name })
        assertEquals(prepared.visibleChannels.map { it.uuid }, prepared.visibleChannelIds)
        assertEquals(listOf("German", "Local", "News"), prepared.tags)
        assertTrue(prepared.hasHbbtvCategory)
        assertFalse(prepared.building)
        assertEquals(guideEvents(events.filter { it.channelUuid == "a" }, now), prepared.eventsByChannel["a"])
        assertEquals(
            listOf(
                "c" to listOf("channel:c", "event:3"),
                "a" to listOf("channel:a", "event:1", "event:4"),
            ),
            prepared.guideFocusRows,
        )
        assertEquals(prepared.visibleChannels, guideChannelsPrepared(channels, prepared.eventsByChannel, options))
        assertEquals(
            prepared.guideFocusRows.sumOf { it.second.size },
            prepared.guideFocusSlots.size,
        )
        assertEquals(listOf(0, 0), prepared.guideFocusSlots.take(2).map { it.index })
        assertEquals(listOf(1, 1, 1), prepared.guideFocusSlots.drop(2).map { it.index })
        assertEquals("c", prepared.guideFocusSlots.first().key.row)
        assertEquals("channel:c", prepared.guideFocusSlots.first().key.item)
    }

    @Test fun preparedGuideRespectsEpgFilterAndDescendingSort() {
        val channels = listOf(channel("a", "A", 1), channel("b", "B", 2), channel("c", "C", 3))
        val events = listOf(event("1", "a"), event("2", "c"))
        val now = 5_000L
        val options = GuideOptions(onlyWithEpg = true, sort = GuideSort.NUMBER, descending = true)
        val prepared = buildLiveTvChannelListing(channels, events, options, now)
        assertEquals(listOf("c", "a"), prepared.visibleChannelIds)
        assertEquals(guideChannels(channels, events.groupBy { it.channelUuid }, now, options), prepared.visibleChannels)
        assertEquals(listOf("channel:c", "event:2"), prepared.guideFocusRows.first().second)
    }

    @Test fun emptyInputHasNoFalseChannelRowsOrFocusTargets() {
        val empty = buildLiveTvChannelListing(emptyList(), emptyList(), GuideOptions(), 5_000L)
        assertTrue(empty.visibleChannels.isEmpty())
        assertTrue(empty.visibleChannelIds.isEmpty())
        assertTrue(empty.guideFocusRows.isEmpty())
        assertTrue(empty.tags.isEmpty())
    }
}
