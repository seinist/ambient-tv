package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPollIntervalTest {
    @Test
    fun `hidden app polls once per second, visible app keeps its pace`() {
        assertEquals(250L, playbackPollInterval(250, appVisible = true))
        assertEquals(1_000L, playbackPollInterval(250, appVisible = false))
        assertEquals(2_000L, playbackPollInterval(2_000, appVisible = false))
    }
}
