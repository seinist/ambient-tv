package com.ambient.tv.livetv

import org.junit.Assert.*
import org.junit.Test

class LiveTvArtworkTest {
    private val channels = listOf(LiveTvChannel(
        uuid = "channel", name = "Sender", number = 1, iconUrl = "https://tv.example/logo.png",
    ))
    private val programme = LiveTvProgramme(
        eventId = "event", channelUuid = "channel", channelName = "Sender",
        title = "Der Film", startEpochSeconds = 10L, stopEpochSeconds = 20L,
        imageUrl = "https://tv.example/epg.jpg",
    )

    @Test fun tmdbBackdropPrecedesEpgImageAndChannelLogo() {
        val resolved = liveTvProgrammeArt(programme, channels, mapOf("der film" to "https://image.tmdb.org/t/p/w780/wide.jpg"))!!
        assertEquals("https://image.tmdb.org/t/p/w780/wide.jpg", resolved.url)
        assertTrue(resolved.external)
        assertFalse(resolved.logo)
        assertTrue(resolved.headers(mapOf("Authorization" to "Basic secret")).isEmpty())
    }

    @Test fun guideImageAndThenChannelLogoRemainFallbacks() {
        assertEquals("https://tv.example/epg.jpg", liveTvProgrammeArt(programme, channels)!!.url)
        val logo = liveTvProgrammeArt(programme.copy(imageUrl = null), channels)!!
        assertEquals("https://tv.example/logo.png", logo.url)
        assertTrue(logo.logo)
        assertEquals(mapOf("Authorization" to "Basic secret"), logo.headers(mapOf("Authorization" to "Basic secret")))
    }

    @Test fun aRecordingUsesItsOwnFanartBeforeTheTmdbBackdrop() {
        val recording = LiveTvRecording(
            uuid = "rec", channelUuid = "channel", channelName = "Sender", title = "Der Film",
            startEpochSeconds = 10L, stopEpochSeconds = 20L,
            status = null, scheduleStatus = null, url = null, imageUrl = "https://tv.example/poster.jpg",
            fanartUrl = "https://tv.example/fanart.jpg", fileSizeBytes = null,
        )
        val art = mapOf("der film" to "https://image.tmdb.org/t/p/w780/wide.jpg")
        assertEquals("https://tv.example/fanart.jpg", liveTvRecordingArt(recording, listOf(programme), channels, art)!!.url)
        val withoutFanart = liveTvRecordingArt(recording.copy(fanartUrl = null), listOf(programme), channels, art)!!
        assertEquals("https://image.tmdb.org/t/p/w780/wide.jpg", withoutFanart.url)
        assertTrue(withoutFanart.external)
    }

    @Test fun artCacheKeepsMissesBrieflyAndHitsAcrossRestarts() {
        val now = 10_000_000_000L
        val positive = LiveTvArtworkRepository.encodeEntry(now, "https://image.tmdb.org/t/p/w780/bg.jpg")
        val negative = LiveTvArtworkRepository.encodeEntry(now, null)
        assertEquals("https://image.tmdb.org/t/p/w780/bg.jpg", LiveTvArtworkRepository.decodeEntry(positive, now)!!.url)
        assertNull(LiveTvArtworkRepository.decodeEntry(negative, now)!!.url)
        assertNull(LiveTvArtworkRepository.decodeEntry(negative, now + LiveTvArtworkRepository.NEGATIVE_TTL_MS + 1))
        assertNotNull(LiveTvArtworkRepository.decodeEntry(positive, now + LiveTvArtworkRepository.NEGATIVE_TTL_MS + 1))
        assertNull(LiveTvArtworkRepository.decodeEntry(positive, now + LiveTvArtworkRepository.POSITIVE_TTL_MS + 1))
        assertNull(LiveTvArtworkRepository.decodeEntry("garbage", now))
    }
}
