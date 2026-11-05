package com.ambient.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeLocalProgressTest {
    @Test fun localPositionWinsUntilYouTubeReportsSomethingNew() {
        val id = "local-progress-a"
        YouTubeLocalProgress.observeRemote(id, 10)
        YouTubeLocalProgress.record(id, positionMs = 60_000, durationMs = 100_000)
        // YouTube has not applied the update yet: still the old value.
        assertEquals(60, YouTubeLocalProgress.percent(id, 10))
        YouTubeLocalProgress.observeRemote(id, 10)
        assertEquals(60, YouTubeLocalProgress.percent(id, 10))
        // Another device (or the synced update) changed YouTube's value: YouTube wins.
        YouTubeLocalProgress.observeRemote(id, 80)
        assertEquals(80, YouTubeLocalProgress.percent(id, 80))
    }

    @Test fun unknownRemoteAdoptsTheFirstReportedValue() {
        val id = "local-progress-b"
        YouTubeLocalProgress.record(id, positionMs = 30_000, durationMs = 100_000)
        assertEquals(30, YouTubeLocalProgress.percent(id, 0))
        YouTubeLocalProgress.observeRemote(id, 0)
        assertEquals(30, YouTubeLocalProgress.percent(id, 0))
    }
}
