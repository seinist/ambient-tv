package com.ambient.tv.car

import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvChannelKind
import com.ambient.tv.livetv.LiveTvPlaybackSource
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.tvheadend.TvheadendPlayerState
import org.junit.Assert.*
import org.junit.Test

class CarLiveNowPlayingTest {
    private val sourceId = "tvheadend:profile-scope"
    private val channel = LiveTvChannel("radio-1", "Station", 101, "http://server/logo.png",
        sourceId = sourceId, kind = LiveTvChannelKind.RADIO)
    private val playbackId = CarAudioIds.liveAudio(sourceId, channel.uuid)

    private fun playback() = PlaybackMetadata(
        route = "live-radio", title = channel.name, subtitle = "101", source = "tvheadend",
        mediaId = channel.uuid, hasVideo = false, isLive = true, liveTvSourceId = sourceId,
        artworkUrl = channel.iconUrl,
        resumeState = TvheadendPlayerState.Ready(
            source = LiveTvPlaybackSource("http://server/stream", mapOf("Authorization" to "private"),
                channel.name, isLive = true),
            channel = channel,
            channelOrder = listOf(channel.uuid, "radio-2"),
        ),
    )

    private fun event(id: String, start: Long, stop: Long) = LiveTvProgramme(
        eventId = id, channelUuid = channel.uuid, channelName = channel.name,
        title = "Programme $id", subtitle = "Episode $id", description = "Description $id",
        startEpochSeconds = start, stopEpochSeconds = stop, sourceId = sourceId,
    )

    @Test fun currentAndNextProgrammesAreSelectedInTimeOrder() {
        val original = playback()
        val current = event("current", 100, 200)
        val next = event("next", 200, 300)
        val updated = carLiveNowPlayingMetadata(original,
            listOf(event("later", 300, 400), next, event("past", 25, 50), current), 150, null)
        val ready = updated.resumeState as TvheadendPlayerState.Ready

        assertEquals("Station", updated.title)
        assertEquals("Programme current · Episode current", updated.subtitle)
        assertEquals(100_000L, updated.liveProgramStartMs)
        assertEquals(200_000L, updated.liveProgramEndMs)
        assertEquals(current, ready.nowEvent)
        assertEquals(next, ready.nextEvent)
        assertEquals(updated.subtitle, ready.source.subtitle)
        assertEquals(original.artworkUrl, updated.artworkUrl)
        assertEquals((original.resumeState as TvheadendPlayerState.Ready).source.headers, ready.source.headers)
        assertEquals(listOf("radio-1", "radio-2"), ready.channelOrder)
    }

    @Test fun programmeBoundariesAdvanceAndExpiredMetadataIsCleared() {
        val current = event("first", 100, 200)
        val next = event("second", 200, 300)
        val first = carLiveNowPlayingMetadata(playback(), listOf(current, next), 150, null)
        val boundary = carLiveNowPlayingMetadata(first, listOf(current, next), 200, null)
        assertEquals(next, (boundary.resumeState as TvheadendPlayerState.Ready).nowEvent)
        assertEquals("Programme second · Episode second", boundary.subtitle)
        assertEquals(200_000L, boundary.liveProgramStartMs)

        val expired = carLiveNowPlayingMetadata(boundary, listOf(current, next), 300, null)
        val ready = expired.resumeState as TvheadendPlayerState.Ready
        assertNull(ready.nowEvent)
        assertNull(ready.nextEvent)
        assertNull(expired.liveProgramStartMs)
        assertNull(expired.liveProgramEndMs)
        // The channel fallback is filtered by the session presentation helper; no old song/EPG remains.
        assertEquals("101", expired.subtitle)
        assertEquals("101", ready.source.subtitle)
    }

    @Test fun programmesFromAnotherChannelOrSourceCannotRelabelTheStation() {
        val wrongChannel = event("wrong-channel", 100, 200).copy(channelUuid = "radio-2")
        val wrongSource = event("wrong-source", 100, 200).copy(sourceId = "tvheadend:other-profile")
        val original = playback()
        val filtered = carLiveNowPlayingMetadata(original, listOf(wrongChannel, wrongSource), 150, null)
        assertEquals("101", filtered.subtitle)
        assertNull((filtered.resumeState as TvheadendPlayerState.Ready).nowEvent)

        // Some delegates omit sourceId; the selected repository and channel still provide its identity.
        val unscoped = event("current", 100, 200).copy(sourceId = "")
        val accepted = carLiveNowPlayingMetadata(original, listOf(wrongChannel, wrongSource, unscoped), 150, null)
        assertEquals(unscoped, (accepted.resumeState as TvheadendPlayerState.Ready).nowEvent)
    }

    @Test fun streamTitlesWinWithoutDiscardingThePhonesProgrammeDetails() {
        val current = event("current", 100, 200)
        val next = event("next", 200, 300)
        val updated = carLiveNowPlayingMetadata(playback(), listOf(current, next), 150, " Artist - Song ")
        val ready = updated.resumeState as TvheadendPlayerState.Ready
        assertEquals("Artist - Song", updated.subtitle)
        assertEquals("Artist - Song", ready.source.subtitle)
        assertEquals(current, ready.nowEvent)
        assertEquals("Description current", ready.nowEvent?.description)
        assertEquals(next, ready.nextEvent)
        assertEquals(100_000L, updated.liveProgramStartMs)
        assertEquals(200_000L, updated.liveProgramEndMs)

        val cleared = carLiveNowPlayingMetadata(updated, listOf(current, next), 150, " ")
        assertEquals("Programme current · Episode current", cleared.subtitle)
        val noProgramme = carLiveNowPlayingMetadata(cleared, emptyList(), 150, null)
        assertEquals("101", noProgramme.subtitle)
        assertNull((noProgramme.resumeState as TvheadendPlayerState.Ready).nowEvent)
    }

    @Test fun generationAndSourceIdentityRejectLateUpdatesAfterAnyStationSwitch() {
        val current = playback()
        assertTrue(carLiveMetadataMatches(current, 7, 7, playbackId))
        assertFalse(carLiveMetadataMatches(current, 8, 7, playbackId))
        assertFalse(carLiveMetadataMatches(current.copy(mediaId = "radio-2"), 7, 7, playbackId))
        assertFalse(carLiveMetadataMatches(current.copy(liveTvSourceId = "tvheadend:other"), 7, 7, playbackId))
        assertFalse(carLiveMetadataMatches(current.copy(hasVideo = true), 7, 7, playbackId))
        assertFalse(carLiveMetadataMatches(current.copy(isLive = false), 7, 7, playbackId))
        assertFalse(carLiveMetadataMatches(null, 7, 7, playbackId))
    }

    @Test fun absentLiveStateDoesNotInventProgrammeInformation() {
        val withoutState = playback().copy(resumeState = null)
        assertSame(withoutState, carLiveNowPlayingMetadata(withoutState,
            listOf(event("current", 100, 200)), 150, "Artist - Song"))
        val noChannel = playback().let { it.copy(
            resumeState = (it.resumeState as TvheadendPlayerState.Ready).copy(channel = null),
        ) }
        assertSame(noChannel, carLiveNowPlayingMetadata(noChannel,
            listOf(event("current", 100, 200)), 150, "Artist - Song"))
    }
}
