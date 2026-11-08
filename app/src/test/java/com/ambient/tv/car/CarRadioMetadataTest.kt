@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.car

import android.app.Application
import android.net.Uri
import androidx.media3.common.MediaMetadata
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvChannelKind
import com.ambient.tv.livetv.LiveTvPlaybackSource
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.tvheadend.TvheadendPlayerState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class CarRadioMetadataTest {
    private fun radio(source: String = "radio", nowPlaying: String = "") = PlaybackMetadata(
        route = "radio", title = "Station", subtitle = nowPlaying,
        source = source, mediaId = "station-id", hasVideo = false, isLive = true,
    )

    @Test fun icyTextStaysIntactAlongsideStationIdentityForBothCarProtocols() {
        val supplied = "Artist - Live session – Part 2"
        val metadata = MediaMetadata.Builder()
            .applyCarRadioMetadata(radio(nowPlaying = " $supplied "), genre = " Jazz ")
            .build()

        assertEquals("Station", metadata.title)
        assertEquals("Station", metadata.displayTitle)
        assertEquals("Station", metadata.station)
        assertEquals(supplied, metadata.subtitle)
        assertEquals(supplied, metadata.artist)
        assertEquals("Jazz", metadata.genre)
        assertNull(metadata.albumTitle)
        assertNull(metadata.description)
        assertEquals(MediaMetadata.MEDIA_TYPE_RADIO_STATION, metadata.mediaType)
        assertEquals(true, metadata.isPlayable)
        assertEquals(false, metadata.isBrowsable)
    }

    @Test fun liveRadioExposesOnlySuppliedProgrammeDetails() {
        for (source in listOf("tvheadend", "iptv")) {
            val metadata = MediaMetadata.Builder().applyCarRadioMetadata(
                radio(source, "Morning news • Local headlines"),
                description = " Today's regional reports. ",
                genre = "News",
            ).build()
            assertEquals("Station", metadata.title)
            assertEquals("Morning news • Local headlines", metadata.subtitle)
            assertEquals(metadata.subtitle, metadata.artist)
            assertEquals("Today's regional reports.", metadata.description)
            assertEquals("News", metadata.genre)
        }
    }

    @Test fun missingOrStationOnlyUpdatesClearOldProgrammeFields() {
        val previous = MediaMetadata.Builder().applyCarRadioMetadata(
            radio(nowPlaying = "Old programme"), description = "Old details", genre = "Old genre",
        ).build()
        for (nowPlaying in listOf("", "   ", " station ")) {
            val updated = previous.buildUpon().applyCarRadioMetadata(
                radio(nowPlaying = nowPlaying), description = " ", genre = null,
            ).build()
            assertEquals("Station", updated.title)
            assertNull(updated.subtitle)
            assertNull(updated.artist)
            assertNull(updated.description)
            assertNull(updated.genre)
            assertNull(updated.albumTitle)
        }
    }

    @Test fun aChannelNumberIsNotMisrepresentedAsNowPlaying() {
        val channel = LiveTvChannel("station-id", "Station", 101, null,
            kind = LiveTvChannelKind.RADIO)
        val ready = TvheadendPlayerState.Ready(
            source = LiveTvPlaybackSource("http://server/stream", emptyMap(), "Station", isLive = true),
            channel = channel,
        )
        val playback = radio("tvheadend", "101").copy(resumeState = ready)
        val metadata = MediaMetadata.Builder().applyCarRadioMetadata(playback).build()
        assertEquals("Station", metadata.title)
        assertNull(metadata.subtitle)
        assertNull(metadata.artist)

        // A numeric title without that exact channel context remains real supplied metadata.
        val actualTitle = MediaMetadata.Builder().applyCarRadioMetadata(radio(nowPlaying = "101")).build()
        assertEquals("101", actualTitle.subtitle)
    }

    @Test fun artworkAndOtherPlaybackFieldsSurviveRadioEnrichment() {
        val uri = Uri.parse("content://com.ambient.tv.caraudioart/live/opaque-id")
        val bytes = byteArrayOf(1, 2, 3)
        val metadata = MediaMetadata.Builder()
            .setArtworkUri(uri)
            .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .applyCarRadioMetadata(radio(nowPlaying = "Current programme"))
            .build()
        assertEquals(uri, metadata.artworkUri)
        assertArrayEquals(bytes, metadata.artworkData)
    }

    @Test fun otherMediaAndNonLiveRadioRecordingsKeepTheirOwnMetadata() {
        val unchanged = MediaMetadata.Builder().setTitle("Original").setArtist("Original artist")
        for (playback in listOf(null,
            radio().copy(hasVideo = true),
            radio().copy(isLive = false),
            radio(source = "youtube"),
            radio(source = "spotify"),
        )) {
            assertSame(unchanged, unchanged.applyCarRadioMetadata(playback, "Radio details", "Radio genre"))
            val metadata = unchanged.build()
            assertEquals("Original", metadata.title)
            assertEquals("Original artist", metadata.artist)
            assertNull(metadata.station)
            assertNull(metadata.description)
            assertNull(metadata.genre)
        }
    }
}
