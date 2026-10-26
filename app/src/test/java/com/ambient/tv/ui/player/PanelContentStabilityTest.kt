package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelContentStabilityTest {
    @Test fun emptyTrackListsKeepTheVideoPanel() {
        assertTrue(playbackPanelHasVideo(videoTracks = 0, audioTracks = 0, audioOnly = false))
    }

    @Test fun knownAudioOnlyCollapsesEvenBeforeTracks() {
        assertFalse(playbackPanelHasVideo(videoTracks = 0, audioTracks = 0, audioOnly = true))
    }

    @Test fun parsedAudioWithoutVideoCollapses() {
        assertFalse(playbackPanelHasVideo(videoTracks = 0, audioTracks = 2, audioOnly = false))
    }

    @Test fun videoTrackAlwaysWins() {
        assertTrue(playbackPanelHasVideo(videoTracks = 1, audioTracks = 1, audioOnly = true))
    }

    @Test fun selectedSectionStaysWhileAvailable() {
        assertEquals("filters", resolveVideoSection("filters", listOf("renderer", "filters"), confirmed = true))
    }

    @Test fun missingSectionStaysWhileEngineUnconfirmed() {
        assertEquals("filters", resolveVideoSection("filters", listOf("renderer"), confirmed = false))
    }

    @Test fun missingSectionIsGoneOnceConfirmed() {
        assertNull(resolveVideoSection("filters", listOf("renderer", "output"), confirmed = true))
    }

    @Test fun restartKeepsLastValueAsPending() {
        assertEquals(StickyValue("1080p", pending = true), stickyDuringRestart(null, "1080p"))
    }

    @Test fun runningEngineValueReplacesLastOne() {
        assertEquals(StickyValue("2160p", pending = false), stickyDuringRestart("2160p", "1080p"))
    }

    @Test fun expiredWaitDropsLastValue() {
        assertEquals(StickyValue<String>(null, pending = false), stickyDuringRestart(null, "1080p", expired = true))
    }

    @Test fun nothingKnownIsNotPending() {
        assertEquals(StickyValue<String>(null, pending = false), stickyDuringRestart<String>(null, null))
    }
}
