package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackOutputChangeWindowTest {
    private val channel = "tvheadend" to "channel1"

    @Test fun requestedTvOutputChangeIsBoundedAndBelongsToItsChannel() {
        val window = PlaybackOutputChangeWindow(1_000)
        window.begin(channel, 500, television = true, personalOutput = false)
        assertTrue(window.active(channel, 1_499))
        assertFalse(window.active(channel, 1_500))
        assertFalse(window.active("tvheadend" to "channel2", 600))
        assertFalse(window.active(null, 600))
    }

    @Test fun phonesAndHeadphonesNeverSuppressUnplugPause() {
        val window = PlaybackOutputChangeWindow()
        window.begin(channel, 0, television = false, personalOutput = false)
        assertFalse(window.active(channel, 1))
        window.begin(channel, 0, television = true, personalOutput = true)
        assertFalse(window.active(channel, 1))
        window.begin(channel, 0, television = true, personalOutput = false)
        window.begin(channel, 1, television = true, personalOutput = true)
        assertFalse(window.active(channel, 2))
    }

    @Test fun stopClearsTheWindowAndFurtherChangesRenewOnlyTheirOwnWindow() {
        val window = PlaybackOutputChangeWindow(1_000)
        window.begin(channel, 0, television = true, personalOutput = false)
        window.begin(channel, 900, television = true, personalOutput = false)
        assertTrue(window.active(channel, 1_800))
        window.clear()
        assertFalse(window.active(channel, 1_801))
    }
}
