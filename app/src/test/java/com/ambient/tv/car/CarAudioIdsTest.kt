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

    @Test fun liveRadioIdsRoundTripAndKeepServiceTypesSeparate() {
        val channelId = CarAudioIds.liveAudio("tvheadend:localhost", "radio-uuid:7")
        assertEquals("tvheadend:localhost" to "radio-uuid:7", CarAudioIds.liveAudioSpec(channelId))
        assertEquals("iptv:source-a", CarAudioIds.liveSourceId(CarAudioIds.liveSource("iptv:source-a")))
        assertEquals("iptv:source-a", CarAudioIds.liveGroupsId(CarAudioIds.liveGroups("iptv:source-a")))
        assertEquals("iptv:source-a" to "Jazz",
            CarAudioIds.liveGroupSpec(CarAudioIds.liveGroup("iptv:source-a", "Jazz")))
        assertNull(CarAudioIds.liveAudioSpec(CarAudioIds.liveSource("iptv:source-a")))
    }

    @Test fun dlnaAndSmbFoldersAreBrowseableButTheirSourceIdsAreNotPlayable() {
        val dlna = CarAudioIds.dlnaFolder("dlna:server:YXZl")
        val smb = CarAudioIds.fileFolder("files:server:TXVzaWM")
        assertEquals("dlna:server:YXZl", CarAudioIds.dlnaFolderId(dlna))
        assertEquals("files:server:TXVzaWM", CarAudioIds.fileFolderId(smb))
        assertTrue(CarAudioIds.isFolder(dlna))
        assertTrue(CarAudioIds.isFolder(smb))
        assertTrue(CarAudioIds.isFolder(CarAudioIds.dlnaServer("server")))
        assertTrue(CarAudioIds.isFolder(CarAudioIds.fileSource("server")))
        assertNull(CarAudioIds.musicItemId(dlna))
        assertNull(CarAudioIds.musicItemId(smb))
    }

    @Test fun paginatedRadioAndNasFoldersDoNotAcceptRecursiveOrMalformedCursors() {
        val parent = CarAudioIds.radioFacet("country", "DE")
        val page = CarAudioIds.more(parent, 1200)
        assertTrue(CarAudioIds.isFolder(page))
        assertEquals(parent to 1200, CarAudioIds.moreSpec(page))
        assertNull(CarAudioIds.moreSpec(CarAudioIds.more(parent, 0)))
        assertNull(CarAudioIds.moreSpec(CarAudioIds.more(parent, 1_000_001)))
        assertNull(CarAudioIds.moreSpec(CarAudioIds.more(page, 80)))
        assertTrue(CarAudioIds.isFolder(CarAudioIds.RADIO_GENRES))
        assertTrue(CarAudioIds.isFolder(CarAudioIds.STORAGE_ROOT))
        assertFalse(CarAudioIds.isFolder(CarAudioIds.liveAudio("iptv:abc", "video-uuid")))
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
