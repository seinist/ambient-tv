package com.ambient.tv.livetv

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class GuideOptionsTest {
    private fun channel(id: String, name: String = "Station", number: Int = 1) = LiveTvChannel(id, name, number, null, sourceId = "server")
    private fun event(id: String, channel: String, title: String = "Programme", start: Long = 4000) =
        LiveTvProgramme(id, channel, "Station", title, startEpochSeconds = start, stopEpochSeconds = start + 3600, sourceId = "server")
    @Test fun duplicateStationsCollapseButRegionalProgrammesRemain() {
        val channels = listOf(channel("a"), channel("b"), channel("c"))
        val events = listOf(event("1", "a"), event("2", "b"), event("3", "c", "Regional news")).groupBy { it.channelUuid }
        assertEquals(listOf("a", "c"), guideChannels(channels, events, 5000).map { it.uuid })
    }
    @Test fun sameNameWithDifferentLaterProgrammeRemainsSeparate() {
        val events = listOf(event("1", "a"), event("2", "b"), event("3", "a", "Local A", 7600), event("4", "b", "Local B", 7600)).groupBy { it.channelUuid }
        assertEquals(2, guideChannels(listOf(channel("a"), channel("b")), events, 5000).size)
    }
    @Test fun radioWithoutEpgRemainsVisibleAndFiltersCombine() {
        val radio = channel("radio").copy(kind = LiveTvChannelKind.RADIO, access = LiveTvChannelAccess.FTA, tags = listOf("Favourites"))
        val encrypted = radio.copy(uuid = "encrypted", access = LiveTvChannelAccess.ENCRYPTED)
        val rows = listOf(channel("tv").copy(kind = LiveTvChannelKind.TV), radio, encrypted, channel("unknown"))
        val options = GuideOptions(kind = LiveTvChannelKind.RADIO, access = LiveTvChannelAccess.FTA, tag = "Favourites")
        assertEquals(listOf(radio), guideChannels(rows, emptyMap(), 5000, options))
        assertTrue(guideChannels(rows, emptyMap(), 5000, options.copy(onlyWithEpg = true)).isEmpty())
        assertEquals(4, guideChannels(rows, emptyMap(), 5000).size)
    }
    @Test fun filteringPrecedesDedupe() {
        val channels = listOf(channel("a"), channel("b").copy(tags = listOf("Favourite")))
        val events = listOf(event("1", "a"), event("2", "b")).groupBy { it.channelUuid }
        assertEquals(listOf("b"), guideChannels(channels, events, 5000, GuideOptions(tag = "Favourite")).map { it.uuid })
    }
    @Test fun unknownNeverMatchesFta() {
        assertTrue(guideChannels(listOf(channel("a")), emptyMap(), 5000, GuideOptions(access = LiveTvChannelAccess.FTA)).isEmpty())
    }
    @Test fun hbbtvCategoryUsesOnlyPreloadedChannelMetadata() {
        val ordinary = channel("ordinary")
        val hbbtv = channel("hbbtv").copy(hbbtvUrl = "https://hbbtv.example/start", hbbtvName = "Start")
        assertEquals(
            listOf("hbbtv"),
            guideChannels(listOf(ordinary, hbbtv), emptyMap(), 5000, GuideOptions(onlyHbbtv = true)).map { it.uuid },
        )
    }

    @Test fun staleHbbtvFilterIsInertWhenProviderCannotExposeCategoryWithoutStreaming() {
        val rows = listOf(channel("a"), channel("b"))
        assertEquals(
            listOf("a", "b"),
            guideChannels(rows, emptyMap(), 5000, GuideOptions(onlyHbbtv = true)).map { it.uuid },
        )
    }
    @Test fun duplicateEventsKeepTimerAndPreserveDifferentBroadcastTimes() {
        val first = event("1", "a")
        val timer = first.copy(eventId = "2", dvrUuid = "timer")
        assertEquals(listOf(timer, event("3", "a", start = 7600)), guideEvents(listOf(first, first, timer, event("3", "a", start = 7600)), 5000))
    }
    @Test fun descendingReversesOnlyTheChosenField() {
        val channels = listOf(channel("z", "ZDF", 2), channel("a", "Ärzte TV", 9), channel("3", "3sat", 1), channel("b", "BBC", 4), channel("n", "Nowhere").copy(number = null))
        assertEquals(listOf("z", "n", "b", "a", "3"), guideChannels(channels, emptyMap(), 5000, GuideOptions(sort = GuideSort.NAME, descending = true)).map { it.uuid })
        assertEquals(listOf("a", "b", "z", "3", "n"), guideChannels(channels, emptyMap(), 5000, GuideOptions(sort = GuideSort.NUMBER, descending = true)).map { it.uuid })
        assertEquals(listOf("3", "z", "b", "a", "n"), guideChannels(channels, emptyMap(), 5000, GuideOptions(sort = GuideSort.NUMBER)).map { it.uuid })
    }
    @Test fun alphabetSortAndBucketsAgree() {
        val channels = listOf(channel("z", "ZDF"), channel("a", "Ärzte TV"), channel("3", "3sat"), channel("b", "BBC"))
        assertEquals(listOf("3", "a", "b", "z"), guideChannels(channels, emptyMap(), 5000, GuideOptions(sort = GuideSort.NAME)).map { it.uuid })
        assertEquals('A', guideIndexLetter(" Äpfel"))
        assertEquals('#', guideIndexLetter("3sat"))
    }
    @Test fun sourceIdentitySurvivesGrouping() {
        val rows = listOf(channel("a"), channel("b").copy(sourceId = "other"))
        val events = listOf(event("1", "a"), event("2", "b")).groupBy { it.channelUuid }
        assertEquals(2, guideChannels(rows, events, 5000).size)
    }
    @Test fun optionsAndOldChannelSnapshotsDeserialize() {
        val options = GuideOptions(GuideSort.NAME, LiveTvChannelKind.RADIO, LiveTvChannelAccess.FTA, "Favourite", true)
        assertEquals(options, Json.decodeFromString<GuideOptions>(Json.encodeToString(options)))
        val oldChannel = Json.decodeFromString<LiveTvChannel>("""{"uuid":"1","name":"Radio","number":1,"iconUrl":null}""")
        assertEquals(LiveTvChannelAccess.UNKNOWN, oldChannel.access)
        assertNull(oldChannel.hbbtvUrl)
        assertFalse(Json.decodeFromString<GuideOptions>("""{"sort":"NUMBER"}""").onlyHbbtv)
    }
}
