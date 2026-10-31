package com.ambient.tv.car

import org.junit.Assert.*
import org.junit.Test

class CarAudioIdsTest {
    @Test fun sourceIdentifiersAreOpaqueAndRoundTrip() {
        val jellyfinId = "jellyfin:server/album?token=secret"
        val stationId = "a-b/c"
        assertEquals(jellyfinId, CarAudioIds.musicItemId(CarAudioIds.musicItem(jellyfinId)))
        assertEquals(jellyfinId, CarAudioIds.musicSectionId(CarAudioIds.musicSection(jellyfinId)))
        assertEquals(stationId, CarAudioIds.stationId(CarAudioIds.station(stationId)))
        assertFalse(CarAudioIds.musicItem(jellyfinId).contains("token"))
        assertFalse(CarAudioIds.station(stationId).contains("/"))
    }

    @Test fun spotifyKindsAreNotInterchangeable() {
        val trackId = CarAudioIds.spotifyTrack("track-42")
        assertEquals("track-42", CarAudioIds.spotifyTrackId(trackId))
        val playlistId = CarAudioIds.spotifyCollection("playlist", "list-42")
        assertEquals("playlist" to "list-42", CarAudioIds.spotifyCollectionId(playlistId))
        assertNull(CarAudioIds.spotifyTrackId(playlistId))
        assertNull(CarAudioIds.spotifyCollectionId(CarAudioIds.spotifyCollection("video", "abc")))
    }

    @Test fun malformedOrEmptyIdsNeverBecomePlayable() {
        assertNull(CarAudioIds.stationId("car:station:###"))
        assertNull(CarAudioIds.musicItemId("car:music:item:"))
        assertNull(CarAudioIds.spotifyTrackId("car:spotify:track:"))
        assertFalse(CarAudioIds.isFolder("car:music:section:###"))
        assertFalse(CarAudioIds.isFolder("car:video:test"))
    }

    @Test fun carBrowsePolicyNeverOffersVideoItemsAsAudio() {
        assertTrue(carAudioPlayableType("track"))
        assertTrue(carAudioPlayableType("song"))
        assertTrue(carAudioBrowseType("album"))
        assertTrue(carAudioBrowseType("artist"))
        for (type in listOf("movie", "episode", "video", "clip", "season", "show", "photo")) {
            assertFalse(type, carAudioPlayableType(type))
            assertFalse(type, carAudioBrowseType(type))
        }
    }
}
