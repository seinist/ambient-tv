package com.ambient.tv.ui.tvheadend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpToLiveTest {
    @Test fun `requires live playback and a seekable buffer`() {
        assertFalse(canJumpToLive(false, 1000L, 0L, 61000L, true))
        assertFalse(canJumpToLive(true, null, 0L, 61000L, true))
        assertFalse(canJumpToLive(true, 1000L, 0L, null, true))
        assertFalse(canJumpToLive(true, 1000L, 0L, 61000L, false))
    }

    @Test fun `only shows beyond live tolerance`() {
        assertFalse(canJumpToLive(true, 1000L, 60000L, 61000L, true))
        assertFalse(canJumpToLive(true, 1000L, 50000L, 61000L, true))
        assertTrue(canJumpToLive(true, 1000L, 49999L, 61000L, true))
        assertFalse(canJumpToLive(true, 1000L, Long.MAX_VALUE, 61000L, true))
    }

    @Test fun `paused playback becomes delayed as buffer advances`() {
        assertFalse(canJumpToLive(true, 1000L, 5000L, 6000L, true))
        assertTrue(canJumpToLive(true, 1000L, 5000L, 17000L, true))
    }
    @org.junit.Test fun completedRecordingCanReturnToBroadcastEvenAtItsEnd() {
        org.junit.Assert.assertTrue(canJumpToLive(true, 1000L, 9000L, 10000L, true, recordingFinished = true))
        org.junit.Assert.assertFalse(canJumpToLive(false, 1000L, 9000L, 10000L, true, recordingFinished = true))
    }

    @Test fun `live button is dimmed near live and active when clearly behind`() {
        assertFalse(liveJumpEnabled(shiftable = false, behindMs = 60_000L))
        assertFalse(liveJumpEnabled(shiftable = true, behindMs = 0L))
        assertFalse(liveJumpEnabled(shiftable = true, behindMs = 4_500L))
        assertFalse(liveJumpEnabled(shiftable = true, behindMs = TIMESHIFT_LIVE_TOLERANCE_MS))
        assertTrue(liveJumpEnabled(shiftable = true, behindMs = TIMESHIFT_LIVE_TOLERANCE_MS + 1))
        assertTrue(liveJumpEnabled(shiftable = true, behindMs = 0L, recordingFinished = true))
        assertFalse(liveJumpEnabled(shiftable = false, behindMs = 0L, recordingFinished = true))
    }

    @Test fun `forward seek closer to live than the preroll opens the live edge`() {
        // anchor 0, edge 100 s; watching at 88 s (12 s behind). +10 s would leave only 2 s preroll.
        assertNull(timeshiftSeekTarget(0L, 88_000L, 10_000L, 0L, 100_000L))
        // Exactly the preroll in front of live is the live URL itself.
        assertNull(timeshiftSeekTarget(0L, 86_000L, 10_000L, 0L, 100_000L))
        assertEquals(95_999L, timeshiftSeekTarget(0L, 85_999L, 10_000L, 0L, 100_000L))
        assertEquals(60_000L, timeshiftSeekTarget(0L, 50_000L, 10_000L, 0L, 100_000L))
    }

    @Test fun `seek target stays inside the buffer`() {
        assertEquals(20_000L, timeshiftSeekTarget(0L, 25_000L, -10_000L, 20_000L, 100_000L))
        assertEquals(98_000L, timeshiftSeekTarget(0L, 99_000L, -1_000L, 0L, 100_000L))
        // Rewinding near live never jumps to live.
        assertEquals(99_000L, timeshiftSeekTarget(0L, 100_000L, -1_000L, 0L, 100_000L))
        // Position from a player reporting stream time is bounded by the buffer.
        assertNull(timeshiftSeekTarget(0L, Long.MAX_VALUE, 10_000L, 0L, 100_000L))
    }
}
