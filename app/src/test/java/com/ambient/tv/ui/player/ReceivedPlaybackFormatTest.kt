package com.ambient.tv.ui.player

import com.ambient.tv.data.shared.SharedMediaLink
import com.ambient.tv.domain.playback.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceivedPlaybackFormatTest {
    /** The receiver supplies these values from SetAVTransportURI and its DIDL metadata. */
    private fun receivedLink(url: String, mime: String): SharedMediaLink =
        requireNotNull(SharedMediaLink.parseRendered(url, mime, "object.item.videoItem", "Cast video"))

    @Test fun extensionlessHlsKeepsItsTypeAndSignedUrlThroughTheCastRequest() {
        val url = "http://192.168.1.5:8192/stream/123?signature=a%2Fb%3D&profile=tv"
        val link = receivedLink(url, "application/vnd.apple.mpegurl")
        assertEquals(url, link.url)
        assertEquals("application/x-mpegURL", directPlaybackMimeType(link.extension, PlaybackMode.DIRECT_PLAY))
    }

    @Test fun extensionlessDashUsesTheManifestSourceInsteadOfProgressiveDetection() {
        val link = receivedLink("http://192.168.1.5:8192/stream/456", "application/dash+xml")
        assertEquals("application/dash+xml", directPlaybackMimeType(link.extension, PlaybackMode.DIRECT_PLAY))
    }

    @Test fun nonstandardProxySuffixAndMimeParametersDoNotHideHls() {
        val link = receivedLink("http://192.168.1.5/play.php?id=7", "Application/X-MPEGURL; charset=utf-8")
        assertEquals("application/x-mpegURL", directPlaybackMimeType(link.extension, PlaybackMode.DIRECT_PLAY))
    }

    @Test fun directManifestHintsAcceptProviderNamesAndAudioPlaylistAliases() {
        for (container in listOf("hls", " M3U8 ", "audio/mpegurl", "audio/x-mpegurl")) {
            assertEquals("application/x-mpegURL", directPlaybackMimeType(container, PlaybackMode.DIRECT_PLAY))
        }
        assertEquals("application/dash+xml", directPlaybackMimeType("DASH", PlaybackMode.DIRECT_PLAY))
    }

    @Test fun originalManifestTypeDoesNotOverrideAServerFallbackResponse() {
        for (mode in listOf(PlaybackMode.DIRECT_STREAM, PlaybackMode.TRANSCODE)) {
            assertNull(directPlaybackMimeType("mpd", mode))
            assertNull(directPlaybackMimeType("m3u8", mode))
        }
    }

    @Test fun progressiveAndUnknownSourcesKeepContentDetection() {
        for (container in listOf(null, "", "mp4", "mkv", "mp3", "ts", "unknown")) {
            assertNull(directPlaybackMimeType(container, PlaybackMode.DIRECT_PLAY))
        }
    }
}
