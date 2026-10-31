package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.player.PlaybackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioCoverPresentationTest {
    private fun station(id: String, artwork: String? = "https://example.test/$id.png") = RadioStation(
        id = id,
        name = "Radio $id",
        streamUrl = "https://example.test/$id.mp3",
        homepage = null,
        artworkUrl = artwork,
        country = null,
        countryCode = null,
        state = null,
        languages = emptyList(),
        tags = emptyList(),
        codec = null,
        bitrateKbps = null,
        isHls = false,
        votes = 0,
        clickCount = 0,
    )

    private fun metadata(id: String, source: String = "radio") = PlaybackMetadata(
        route = "radio_player",
        title = "Radio $id",
        source = source,
        mediaId = id,
        artworkUrl = "https://example.test/$id.png",
        hasVideo = false,
        isLive = true,
    )

    @Test fun incomingStationReplacesOldArtworkBeforeTheNewStreamStarts() {
        val old = metadata("old")
        val pending = station("new")
        val target = radioCoverPresentation(old, pending)
        assertEquals("new", target?.stationId)
        assertEquals("Radio new", target?.stationName)
        assertEquals("https://example.test/new.png", target?.artworkUrl)
        assertTrue(target!!.hasArtwork)
    }

    @Test fun incomingMetadataDoesNotRestartTheSameArtworkTransition() {
        val old = metadata("old")
        val pending = station("new")
        val preview = radioCoverPresentation(old, pending)
        val committed = radioCoverPresentation(metadata("new"), null)
        assertEquals(preview, committed)
    }

    @Test fun unavailableArtworkNeverRequiresAnIntermediateSystemLogo() {
        val target = radioCoverPresentation(metadata("old"), station("new", artwork = null))
        assertEquals("new", target?.stationId)
        assertFalse(target!!.hasArtwork)
    }

    @Test fun onlyRadioPlaybackCanSupplyTheDefaultCover() {
        assertNull(radioCoverPresentation(null, null))
        assertNull(radioCoverPresentation(metadata("other", source = "spotify"), null))
        assertEquals("old", radioCoverPresentation(metadata("old"), null)?.stationId)
    }
}
