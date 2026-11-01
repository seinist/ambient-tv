package com.ambient.tv.ui.player

import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.ui.radio.RadioOsdStationPresentation
import com.ambient.tv.ui.radio.radioOsdHasDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Details button: one switch for all players, and stable while zapping. */
class PlayerDetailsButtonAvailabilityTest {
    private val detailPlayers = OsdPlayer.entries.filter { OsdButton.DETAILS in it.buttons }

    @Test fun everyPlayerWithADetailsDialogOffersTheButton() {
        // Medien (Plex/Jellyfin/Emby, Musik, DLNA/Dateien, Telegram), YouTube/SmartTube/Trailer, Live-TV/IPTV/TVHeadend, Radio.
        assertEquals(listOf(OsdPlayer.MEDIA, OsdPlayer.YOUTUBE, OsdPlayer.LIVE_TV, OsdPlayer.RADIO), detailPlayers)
    }

    @Test fun staysOffUntilSwitchedOn() {
        detailPlayers.forEach { assertFalse(osdButtonVisible(OsdButton.DETAILS, it, emptySet())) }
    }

    @Test fun switchedOnInOnePlayerShowsInEveryPlayer() {
        // An older per-player entry (switched on for Medien only) now counts for YouTube as well.
        val hidden = setOf("+MEDIA/DETAILS")
        detailPlayers.forEach { assertTrue(it.name, osdButtonVisible(OsdButton.DETAILS, it, hidden)) }
    }

    @Test fun switchingInAnyPlayerChangesAllOfThem() {
        val on = osdHiddenAfterToggle(emptySet(), OsdPlayer.YOUTUBE, OsdButton.DETAILS, visible = true)
        detailPlayers.forEach { assertTrue(osdButtonVisible(OsdButton.DETAILS, it, on)) }
        val off = osdHiddenAfterToggle(on + "+MEDIA/STATS", OsdPlayer.RADIO, OsdButton.DETAILS, visible = false)
        detailPlayers.forEach { assertFalse(osdButtonVisible(OsdButton.DETAILS, it, off)) }
        assertEquals(setOf("+MEDIA/STATS"), off)
    }

    @Test fun otherButtonsStayPerPlayer() {
        val hidden = osdHiddenAfterToggle(emptySet(), OsdPlayer.MEDIA, OsdButton.STATS, visible = true)
        assertTrue(osdButtonVisible(OsdButton.STATS, OsdPlayer.MEDIA, hidden))
        assertFalse(osdButtonVisible(OsdButton.STATS, OsdPlayer.YOUTUBE, hidden))
        val stopHidden = osdHiddenAfterToggle(emptySet(), OsdPlayer.YOUTUBE, OsdButton.STOP, visible = false)
        assertFalse(osdButtonVisible(OsdButton.STOP, OsdPlayer.YOUTUBE, stopHidden))
        assertTrue(osdButtonVisible(OsdButton.STOP, OsdPlayer.MEDIA, stopHidden))
    }

    @Test fun liveTvKeepsDetailsOfTheNewChannelWhileTuning() {
        val old = LiveTvChannel("one", "Das Erste", 1, "logo-1")
        val target = LiveTvChannel("two", "ZDF", 2, "logo-2")
        val programme = LiveTvProgramme(
            eventId = "e1", channelUuid = "one", channelName = "Das Erste", title = "Tagesschau",
            startEpochSeconds = 0L, stopEpochSeconds = 900L,
        )
        val tuning = playerDetailForLiveTune(true, target, old, programme, null, null)
        assertNotNull(tuning)
        assertEquals("ZDF", tuning!!.title)
        assertNull(tuning.overview)
        assertTrue(tuning.lines.isEmpty())
        val tuned = playerDetailForLiveTune(false, target, old, programme, null, null)
        assertEquals("Tagesschau", tuned!!.title)
        assertNull(playerDetailForLiveTune(true, null, old, programme, null, null))
    }

    @Test fun radioKeepsDetailsOfTheRequestedStationWhileSwitching() {
        val station = RadioStation(
            id = "s2", name = "Radio Zwei", streamUrl = "http://s2", homepage = null, artworkUrl = null,
            country = null, countryCode = null, state = null, languages = emptyList(), tags = emptyList(),
            codec = "MP3", bitrateKbps = 128, isHls = false, votes = 0, clickCount = 0,
        )
        fun shown(station: RadioStation?, switching: Boolean) = RadioOsdStationPresentation(
            stationId = station?.id ?: "loading", station = station, stationName = station?.name ?: "Lädt",
            nowPlaying = null, artworkUrl = null, switching = switching,
        )
        assertTrue(radioOsdHasDetail(shown(station, switching = false)))
        assertTrue(radioOsdHasDetail(shown(station, switching = true)))
        assertFalse(radioOsdHasDetail(shown(null, switching = true)))
    }
}
