package com.ambient.tv.data.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DlnaArtworkTest {
    private val video = DlnaNode(
        serverId = "server-1", objectId = "video", parentObjectId = "0", title = "Example",
        upnpClass = "object.item.videoItem", container = false, artworkUrl = null,
        summary = null, date = "2024-01-01",
        resources = listOf(DlnaResource("http://server/video.mp4", "http-get:*:video/mp4:*")),
    )

    @Test fun videoWithoutArtworkDoesNotDownloadTheVideoAsAnImage() {
        assertNull(video.toMediaItem().thumbUrl)
        assertNull(video.toMediaItem().artUrl)
        assertEquals("Example", video.toMediaItem().title)
        assertEquals(2024, video.toMediaItem().year)
    }

    @Test fun suppliedArtworkTakesPrecedenceOverPhotoResource() {
        val photo = video.copy(upnpClass = "object.item.imageItem", artworkUrl = "http://server/thumb.jpg")
        assertEquals("http://server/thumb.jpg", photo.toMediaItem().thumbUrl)
    }

    @Test fun photoWithoutArtworkUsesImageResource() {
        val photo = video.copy(upnpClass = "object.item.imageItem",
            resources = listOf(DlnaResource("http://server/photo.jpg", "http-get:*:image/jpeg:*")))
        assertEquals("http://server/photo.jpg", photo.toMediaItem().thumbUrl)
        assertEquals("photo", photo.toMediaItem().type)
    }
}
