package com.ambient.tv.ui.tvheadend

import org.junit.Assert.assertFalse
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
}
