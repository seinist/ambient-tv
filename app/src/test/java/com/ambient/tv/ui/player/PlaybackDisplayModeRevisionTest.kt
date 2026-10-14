package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackDisplayModeRevisionTest {
    @Test fun outgoingChannelCannotClearTheNextChannelsManualDisplayChoice() {
        try {
            PlaybackDisplayModeRevision.bump("1920x1080@50.000", "tvh/channel2")
            PlaybackDisplayModeRevision.clearSessionFixedFor("tvh/channel1")
            assertEquals("1920x1080@50.000", PlaybackDisplayModeRevision.sessionFixedFor("tvh/channel2"))
            assertNull(PlaybackDisplayModeRevision.sessionFixedFor("tvh/channel1"))
            PlaybackDisplayModeRevision.clearSessionFixedFor("tvh/channel2")
            assertNull(PlaybackDisplayModeRevision.sessionFixedFor("tvh/channel2"))
        } finally { PlaybackDisplayModeRevision.clearSessionFixed() }
    }
}
