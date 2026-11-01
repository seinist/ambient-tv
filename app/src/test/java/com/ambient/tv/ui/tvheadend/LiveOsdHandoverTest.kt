package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvPlaybackSource
import com.ambient.tv.livetv.LiveTvProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOsdHandoverTest {
    private val one = LiveTvChannel("one", "Das Erste", 1, "logo-1")
    private val two = LiveTvChannel("two", "ZDF", 2, "logo-2")

    private fun model(channel: LiveTvChannel, now: LiveTvProgramme? = null) = LiveOsdModel(
        source = LiveTvPlaybackSource("http://stream/${channel.uuid}", emptyMap(), "Tagesschau", isLive = true),
        channel = channel,
        imageHeaders = emptyMap(),
        artworkUrl = "programme-art",
        artworkFallbackUrls = listOf("fallback"),
        nowEvent = now,
        nextEvent = now,
        positionMs = 10_000L,
        durationMs = 60_000L,
        playing = true,
        loading = false,
        backendLabel = "Media3",
        mediaBadges = listOf("1920×1080"),
        seekJumpMs = 10_000L,
        audioBadge = "AC3",
        onTogglePlayback = {}, onSeekBy = {}, onZap = {}, onStop = {}, onBack = {},
        onOpenAudio = {}, onOpenSubtitles = {}, onOpenRenderer = {}, onOpenSleep = {}, onOpenSettings = {},
    )

    @Test fun aZapKeepsTheOsdWithOnlyTheNewChannel() {
        val handover = liveOsdHandover(model(one), two)!!
        assertEquals("two", handover.sourceIdentity)
        assertEquals("ZDF", handover.source.title)
        assertNull(handover.nowEvent)
        assertNull(handover.nextEvent)
        assertNull(handover.artworkUrl)
        assertTrue(handover.mediaBadges.isEmpty())
        assertNull(handover.audioBadge)
        assertTrue(handover.loading)
    }

    @Test fun nothingToHandOverWhenTheModelAlreadyMatches() {
        assertNull(liveOsdHandover(model(two), two))
        assertNull(liveOsdHandover(model(one), null))
        assertNull(liveOsdHandover(null, two))
    }
}
