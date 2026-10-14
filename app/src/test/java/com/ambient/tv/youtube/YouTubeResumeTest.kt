package com.ambient.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeResumeTest {
    @Test fun partlyWatchedResumes() {
        assertEquals(30_000L, youtubeResumeMs(120_000, 25, isLive = false))
        assertEquals(112_800L, youtubeResumeMs(120_000, 94, isLive = false))
    }

    @Test fun finishedVideosStartFromTheBeginning() {
        assertEquals(0L, youtubeResumeMs(120_000, 95, isLive = false))
        assertEquals(0L, youtubeResumeMs(120_000, 100, isLive = false))
    }

    @Test fun liveAndUnknownStartAtZero() {
        assertEquals(0L, youtubeResumeMs(120_000, 50, isLive = true))
        assertEquals(0L, youtubeResumeMs(120_000, null, isLive = false))
    }
}
