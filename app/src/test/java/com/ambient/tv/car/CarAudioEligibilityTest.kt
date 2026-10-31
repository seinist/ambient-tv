package com.ambient.tv.car

import com.ambient.tv.data.dlna.DlnaNode
import com.ambient.tv.data.dlna.DlnaResource
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvChannelKind
import org.junit.Assert.*
import org.junit.Test

class CarAudioEligibilityTest {
    private fun dlna(kind: String, mime: String) = DlnaNode(
        serverId = "srv", objectId = "id", parentObjectId = "0", title = "Media",
        upnpClass = kind, container = false, artworkUrl = null, summary = null,
        date = null, resources = listOf(DlnaResource("http://nas.test/item", "http-get:*:$mime:*")),
    )

    @Test fun dlnaAudioOnlyNeverAdvertisesVideoOrImages() {
        assertTrue(carDlnaAudioEligible(dlna("object.item.audioItem.musicTrack", "audio/mpeg")))
        assertTrue(carDlnaAudioEligible(dlna("object.item", "audio/flac")))
        assertFalse(carDlnaAudioEligible(dlna("object.item.videoItem", "video/mp4")))
        assertFalse(carDlnaAudioEligible(dlna("object.item.audioItem", "video/mp4")))
        assertFalse(carDlnaAudioEligible(dlna("object.item.imageItem", "image/png")))
        assertFalse(carDlnaAudioEligible(dlna("object.item", "application/octet-stream")))
    }

    @Test fun fileAudioFiltersOutDirectoriesAndVideos() {
        val song = FileEntry("source", "Music/track.flac", "track.flac", false, mime = "audio/flac")
        assertTrue(carAudioFileEligible(song))
        assertFalse(carAudioFileEligible(song.copy(directory = true)))
        assertFalse(carAudioFileEligible(song.copy(path = "Movie.mp4", name = "Movie.mp4", mime = "video/mp4")))
    }

    @Test fun tvheadendAndIptvRequireExplicitRadioKind() {
        fun channel(kind: LiveTvChannelKind) =
            LiveTvChannel(uuid = "radio-1", name = "News", number = null, iconUrl = null, kind = kind)
        assertTrue(carLiveAudioEligible(channel(LiveTvChannelKind.RADIO)))
        assertFalse(carLiveAudioEligible(channel(LiveTvChannelKind.TV)))
        assertFalse(carLiveAudioEligible(channel(LiveTvChannelKind.UNKNOWN)))
    }
}
