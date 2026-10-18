package com.ambient.tv.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvKnownSourcesTest {
    private var now = 1_000_000L
    private val sources = LiveTvKnownSources { now }
    private val tvh = AvailableLiveTvSource(LiveTvSource("tvheadend:x", "tvheadend", "TVHeadend"), LiveTvCapabilities(available = true, guide = true))

    @Test fun singleNoLiveTvAnswerKeepsSource() {
        sources.available(tvh)
        assertNotNull(sources.unavailable(tvh.source.id))
        now += 60_000L
        assertNotNull(sources.unavailable(tvh.source.id))
    }

    @Test fun twoAnswersTwoMinutesApartRemoveSource() {
        sources.available(tvh)
        sources.unavailable(tvh.source.id)
        now += LiveTvKnownSources.REMOVE_AFTER_MS
        assertNull(sources.unavailable(tvh.source.id))
        assertNull(sources.failed(tvh.source.id))
    }

    @Test fun successInBetweenResetsTheCount() {
        sources.available(tvh)
        sources.unavailable(tvh.source.id)
        now += 90_000L
        sources.available(tvh)
        now += 60_000L
        assertNotNull(sources.unavailable(tvh.source.id))
    }

    @Test fun failedProbeKeepsLastAnswer() {
        sources.available(tvh)
        assertEquals(tvh, sources.failed(tvh.source.id))
    }

    @Test fun snapshotRoundTripsAfterRestart() {
        sources.available(tvh)
        val restarted = LiveTvKnownSources { now }
        restarted.restore(sources.snapshot())
        assertEquals(tvh, restarted.failed(tvh.source.id))
    }
}
