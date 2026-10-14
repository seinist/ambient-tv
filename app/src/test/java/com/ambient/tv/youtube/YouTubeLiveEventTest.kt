package com.ambient.tv.youtube

import org.junit.Assert.*
import org.junit.Test

class YouTubeLiveEventTest {
    @Test fun `scheduled slate takes precedence over broadcast timestamp`() {
        assertEquals(1600977300000L, youtubeScheduledStartMs("1600977300", "2021-10-06T13:36:25Z"))
    }
    @Test fun `timestamp fallback supports UTC and timezone offsets`() {
        assertEquals(1633527385000L, youtubeScheduledStartMs(null, "2021-10-06T13:36:25Z"))
        assertEquals(1633527385000L, youtubeScheduledStartMs("invalid", "2021-10-06T15:36:25+02:00"))
    }
    @Test fun `missing invalid and overflowing dates remain unknown`() {
        assertNull(youtubeScheduledStartMs(null, null))
        assertNull(youtubeScheduledStartMs("-1", "invalid"))
        assertNull(youtubeScheduledStartMs(Long.MAX_VALUE.toString(), null))
        assertNull(youtubeScheduledStartMs("0", "1970-01-01T00:00:00Z"))
    }
    @Test fun `countdown never becomes negative or wraps at a day`() {
        assertEquals("25:00:01", youtubeLiveCountdown(90_001_000, 0))
        assertEquals("00:00:01", youtubeLiveCountdown(1001, 1000))
        assertNull(youtubeLiveCountdown(1000, 1000))
        assertNull(youtubeLiveCountdown(1000, 2000))
    }
    @Test fun `offline is terminal without client switches or format work`() {
        val event = YouTubeLiveEvent("Scheduled", 1600977300000)
        assertEquals(event, resolveYouTubeFormatInfo(
            load = { event }, switchClient = { error("Must not switch clients") },
            score = { error("No stream formats before broadcast") }, isTerminal = { true },
        ))
    }
    @Test fun `offline upgrade cannot discard an already playable response`() {
        var calls = 0
        assertEquals(2, resolveYouTubeFormatInfo<Int>(load = { if (calls++ == 0) 2 else 0 },
            switchClient = { true }, score = { it }, isTerminal = { it == 0 }))
        assertEquals(2, calls)
    }
}
