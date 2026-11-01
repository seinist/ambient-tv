package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.player.playerOsdIdentityMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioOsdStationPresentationTest {
    private fun station(id: String) = RadioStation(
        id = id,
        name = "Station $id",
        streamUrl = "https://example.test/$id.mp3",
        homepage = null,
        artworkUrl = "https://example.test/$id.png",
        country = null,
        countryCode = null,
        state = null,
        languages = emptyList(),
        tags = emptyList(),
        codec = "MP3",
        bitrateKbps = 128,
        isHls = false,
        votes = 0,
        clickCount = 0,
    )

    private fun metadata(id: String, source: String = "radio") = PlaybackMetadata(
        route = "radio_player",
        mediaId = id,
        source = source,
        title = "Station $id",
        subtitle = "Old ICY metadata",
        artworkUrl = "https://example.test/$id.png",
        hasVideo = false,
        isLive = true,
    )

    @Test fun pendingStationReplacesAllOutgoingOsdContentWithoutUnmountingChrome() {
        val old = metadata("old")
        val target = radioOsdStationPresentation(old, station("old"), station("new"), true, "Loading…")!!
        assertEquals("new", target.stationId)
        assertEquals("Station new", target.stationName)
        assertEquals("https://example.test/new.png", target.artworkUrl)
        assertEquals("new", target.station?.id)
        assertNull(target.nowPlaying)
        assertTrue(target.switching)
        assertFalse(playerOsdIdentityMatches(old.mediaId, target.stationId))
        assertTrue(playerOsdIdentityMatches(target.stationId, target.stationId))
    }

    @Test fun unknownNextPageShowsNeutralStatusNotPreviousStation() {
        val target = radioOsdStationPresentation(metadata("old"), station("old"), null, true, "Loading…")!!
        assertEquals(RADIO_OSD_LOADING_ID, target.stationId)
        assertEquals("Loading…", target.stationName)
        assertNull(target.station)
        assertNull(target.artworkUrl)
        assertNull(target.nowPlaying)
        assertTrue(target.switching)
    }

    @Test fun commitOfRequestedStationRestoresFreshLiveMetadata() {
        val result = radioOsdStationPresentation(metadata("new"), station("new"), null, false, "Loading…")!!
        assertEquals("new", result.stationId)
        assertEquals("Old ICY metadata", result.nowPlaying)
        assertEquals("https://example.test/new.png", result.artworkUrl)
        assertFalse(result.switching)
    }

    @Test fun outOfSyncQueueNeverDisplaysPreviousStationBadges() {
        val result = radioOsdStationPresentation(metadata("new"), station("old"), null, false, "Loading…")!!
        assertEquals("new", result.stationId)
        assertNull(result.station)
    }

    @Test fun nonRadioPlaybackDoesNotRetainRadioOsd() {
        assertNull(radioOsdStationPresentation(null, null, null, false, "Loading…"))
        assertNull(radioOsdStationPresentation(metadata("other", "spotify"), station("old"), null, false, "Loading…"))
    }
}
