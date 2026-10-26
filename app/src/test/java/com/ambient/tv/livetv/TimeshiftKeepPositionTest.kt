package com.ambient.tv.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2/M3 of the 26.10. audit: restarts keep the timeshift position, recordings count as timeshift. */
class TimeshiftKeepPositionTest {
    private class FakeBuffer(override val liveWallMs: Long, private val reopenable: Boolean = true) : SeekableLiveBuffer {
        override val oldestWallMs = 0L
        override fun urlAt(wallMs: Long) = "at:$wallMs" to wallMs
        override fun reopenedUrl(url: String) = if (reopenable) "reopened:$url" to 1_000L else null
        override fun stop() = Unit
    }

    @Test fun keepsTheWatchedMoment() {
        val buffer = FakeBuffer(liveWallMs = 100_000L)
        val (url, kept) = timeshiftUrlKeepingPosition(buffer, "u", anchorWallMs = 10_000L, positionMs = 30_000L)!!
        assertEquals("at:40000" to 40_000L, url)
        assertTrue(kept)
    }

    @Test fun withoutAnchorUsesTheReopenedStart() {
        val (url, kept) = timeshiftUrlKeepingPosition(FakeBuffer(100_000L), "u", anchorWallMs = null, positionMs = 5_000L)!!
        assertEquals("at:6000" to 6_000L, url)
        assertTrue(kept)
    }

    @Test fun nearTheLiveEdgeGoesLiveNotBackToTheStart() {
        val buffer = FakeBuffer(liveWallMs = 100_000L)
        val (url, kept) = timeshiftUrlKeepingPosition(buffer, "u", anchorWallMs = 10_000L, positionMs = 89_000L)!!
        assertEquals(buffer.liveUrl(), url)
        assertFalse(kept)
    }

    @Test fun stillOpeningReusesTheReopenedUrl() {
        val (url, kept) = timeshiftUrlKeepingPosition(FakeBuffer(100_000L), "u", anchorWallMs = 10_000L, positionMs = 0L)!!
        assertEquals("reopened:u" to 1_000L, url)
        assertFalse(kept)
    }

    @Test fun unknownUrlIsNotRestartedHere() {
        assertNull(timeshiftUrlKeepingPosition(FakeBuffer(100_000L, reopenable = false), "u", 10_000L, 30_000L))
    }

    @Test fun serverRecordingUrlsCountAsTimeshift() {
        assertTrue(isLiveTimeshiftUrl("http://127.0.0.1:41234/0123456789abcdef0123456789abcdef/0.ts"))
        assertTrue(isLiveTimeshiftUrl("http://127.0.0.1:41234/123e4567-e89b-12d3-a456-426614174000/at/1729900000000.ts"))
        assertFalse(isLiveTimeshiftUrl("http://127.0.0.1:41234/123e4567-e89b-12d3-a456-426614174000/stream"))
        assertFalse(isLiveTimeshiftUrl("http://192.168.1.2:9981/123e4567-e89b-12d3-a456-426614174000/at/1.ts"))
    }
}
