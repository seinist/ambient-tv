package com.ambient.tv.car

import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvChannelAccess
import com.ambient.tv.livetv.LiveTvChannelKind
import com.ambient.tv.livetv.LiveTvProgramme
import org.junit.Assert.*
import org.junit.Test

class CarLiveRadioChannelsTest {
    private val now = 10_000L

    private fun radio(uuid: String, name: String = "Radio $uuid", number: Int? = 1) =
        LiveTvChannel(uuid, name, number, null, sourceId = "tvheadend:test", kind = LiveTvChannelKind.RADIO)

    private fun programme(uuid: String, title: String = "Music", start: Long = 9_000L, stop: Long = 11_000L) =
        LiveTvProgramme("$uuid:$start", uuid, "Station", title,
            startEpochSeconds = start, stopEpochSeconds = stop, sourceId = "tvheadend:test")

    private fun snapshot(channels: List<LiveTvChannel>, programmes: List<LiveTvProgramme> = emptyList()) =
        CarLiveRadioChannels(channels, programmes, now)

    @Test fun exactPlaylistDuplicatesAreRemovedBeforeMorePagesAndQueueAreBuilt() {
        val channels = (0 until 45).map { radio("$it", number = it + 1) }
        // The duplicate falls into a different raw page from the original station.
        val raw = channels.take(40) + channels.first() + channels.drop(40)
        val radios = snapshot(raw)
        val firstPage = radios.all.take(40)
        val secondPage = radios.all.drop(40).take(40)

        assertEquals(40, firstPage.size)
        assertEquals(5, secondPage.size)
        assertEquals(channels.map { it.uuid }, (firstPage + secondPage).map { it.uuid })
        assertEquals(channels.map { it.uuid }, radios.selection("0")!!.queue.map { it.uuid })
        assertFalse(radios.needsProgrammeEvidence)
    }

    @Test fun repeatedPlaylistIdentityRetainsAllGroupsAndAnAvailableLogo() {
        val first = radio("same").copy(tags = listOf(" Jazz "))
        val repeated = first.copy(tags = listOf("Favourites", "Jazz", " "), iconUrl = "http://tvh/imagecache/7")
        val radios = snapshot(listOf(first, repeated))

        assertEquals(1, radios.all.size)
        assertEquals(listOf("Favourites", "Jazz"), radios.tags)
        assertEquals("same", radios.inGroup("Jazz").single().uuid)
        assertEquals("same", radios.inGroup("Favourites").single().uuid)
        assertEquals(repeated.iconUrl, radios.all.single().iconUrl)
    }

    @Test fun matchingStationVariantsUseTheSameProgrammeEvidenceAsNormalLiveTv() {
        val first = radio("first", "Station One", 1)
        val variant = radio("variant", " station   ONE ", 101)
        val radios = snapshot(listOf(variant, first), listOf(programme("first"), programme("variant")))

        assertTrue(radios.needsProgrammeEvidence)
        assertEquals(listOf("first"), radios.all.map { it.uuid })
    }

    @Test fun sameNamesWithoutProgrammeEvidenceStayAvailable() {
        val radios = snapshot(listOf(radio("a", "Station"), radio("b", "Station")))
        assertTrue(radios.needsProgrammeEvidence)
        assertEquals(listOf("a", "b"), radios.all.map { it.uuid })
    }

    @Test fun differentLaterRegionalProgrammesKeepSameNameStationsSeparate() {
        val radios = snapshot(listOf(radio("a", "Station"), radio("b", "Station")), listOf(
            programme("a"), programme("b"),
            programme("a", "Local North", 11_000, 12_000),
            programme("b", "Local South", 11_000, 12_000),
        ))
        assertEquals(listOf("a", "b"), radios.all.map { it.uuid })
    }

    @Test fun expiredCachedGuideCannotContinueHidingStationVariants() {
        val radios = snapshot(listOf(radio("a", "Station"), radio("b", "Station")), listOf(
            programme("a", start = 1_000, stop = 2_000),
            programme("b", start = 1_000, stop = 2_000),
        ))
        assertEquals(listOf("a", "b"), radios.all.map { it.uuid })
    }

    @Test fun groupFiltersRunBeforeVariantGrouping() {
        val first = radio("first", "Station", 1).copy(tags = listOf("Local"))
        val favourite = radio("favourite", "Station", 101).copy(tags = listOf("Favourites"))
        val radios = snapshot(listOf(first, favourite), listOf(programme("first"), programme("favourite")))

        assertEquals(listOf("first"), radios.all.map { it.uuid })
        assertEquals(listOf("favourite"), radios.inGroup("Favourites").map { it.uuid })
        assertEquals(listOf("Local", "Favourites").sorted(), radios.tags.sorted())
    }

    @Test fun savedOrGroupSpecificAliasRetainsItsPlayableQueueSlot() {
        val first = radio("first", "Station", 1)
        val alias = radio("alias", "Station", 101)
        val next = radio("next", "Other station", 2)
        val radios = snapshot(listOf(first, alias, next), listOf(programme("first"), programme("alias")))
        val selection = radios.selection("alias")!!

        assertEquals(alias, selection.channel)
        assertEquals(listOf("alias", "next"), selection.queue.map { it.uuid })
        assertEquals(listOf("first", "next"), radios.selection("first")!!.queue.map { it.uuid })
        assertNull(radios.selection("removed"))
    }

    @Test fun selectedGroupQueueContainsAllItsRadiosAcrossMorePages() {
        val favourites = (0 until 45).map { radio("fav$it", number = it + 1).copy(tags = listOf("Favourites")) }
        val unrelated = radio("unrelated", number = 100)
        val radios = snapshot(favourites + unrelated)

        assertEquals(favourites.map { it.uuid }, radios.selection("fav39", "Favourites")!!.queue.map { it.uuid })
        assertEquals(46, radios.selection("unrelated", "Favourites")!!.queue.size)
        assertEquals(46, radios.selection("fav0", "removed-group")!!.queue.size)
    }

    @Test fun alternateGroupRepresentativeIsRetainedWhenNavigatingThatGroup() {
        val other = radio("other", "Station", 1).copy(tags = listOf("Other"))
        val favourite = radio("favourite", "Station", 101).copy(tags = listOf("Favourites"))
        val next = radio("next", "Next station", 102).copy(tags = listOf("Favourites"))
        val radios = snapshot(listOf(other, favourite, next), listOf(programme("other"), programme("favourite")))

        assertEquals(listOf("favourite", "next"), radios.selection("favourite", "Favourites")!!.queue.map { it.uuid })
        assertEquals(listOf("favourite", "next"), radios.selection("next", "Favourites")!!.queue.map { it.uuid })
    }

    @Test fun searchLimitsCountUniqueStations() {
        val first = radio("a", "Jazz One", 1)
        val second = radio("b", "Jazz Two", 2)
        val raw = List(25) { first } + second
        val matches = snapshot(raw).all.filter { it.name.contains("Jazz") }.take(2)
        assertEquals(listOf("a", "b"), matches.map { it.uuid })
    }

    @Test fun differentSourcesAndAccessRemainDistinct() {
        val first = radio("a", "Station").copy(access = LiveTvChannelAccess.FTA)
        val otherSource = radio("b", "Station").copy(sourceId = "iptv:other", access = LiveTvChannelAccess.FTA)
        val encrypted = radio("c", "Station").copy(access = LiveTvChannelAccess.ENCRYPTED)
        val radios = snapshot(listOf(first, otherSource, encrypted), listOf(
            programme("a"), programme("b").copy(sourceId = "iptv:other"), programme("c"),
        ))

        assertEquals(listOf("a", "b", "c"), radios.all.map { it.uuid })
        assertFalse(radios.needsProgrammeEvidence)
    }

    @Test fun televisionAndUnknownChannelsNeverEnterRadioQueues() {
        val audio = radio("radio")
        val television = radio("tv").copy(kind = LiveTvChannelKind.TV)
        val unknown = radio("unknown").copy(kind = LiveTvChannelKind.UNKNOWN)
        val radios = snapshot(listOf(television, unknown, audio))

        assertEquals(listOf(audio), radios.all)
        assertEquals(listOf(audio), radios.selection("radio")!!.queue)
        assertNull(radios.selection("tv"))
        assertNull(radios.selection("unknown"))
    }

    @Test fun nowPlayingProgrammeListIncludesCurrentAndNextFromOnlyTheSelectedStation() {
        val current = programme("a")
        val next = programme("a", "Up next", 11_000, 12_000)
        val expired = programme("a", "Old show", 1_000, 2_000)
        val otherSource = current.copy(eventId = "other-source", sourceId = "iptv:other")
        val events = carLiveRadioProgrammes(
            listOf(next, programme("b"), expired, current, current, otherSource),
            "tvheadend:test", "a", now,
        )

        assertEquals(listOf(current, next), events)
        assertEquals(current, events.single { it.isLive(now) })
        assertEquals(next, events.first { it.startEpochSeconds > now })
    }

    @Test fun legacyCachedProgrammeSourceIsRestoredWithoutRepeatingTheCurrentShow() {
        val current = programme("a")
        val legacy = current.copy(sourceId = "")
        assertEquals(listOf(current), carLiveRadioProgrammes(
            listOf(legacy, current), "tvheadend:test", "a", now,
        ))
    }
}
