package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlayerTitleChangeOsdTest {
    private val first = playbackTitleIdentity("youtube", "abc", isLive = false)
    private val next = playbackTitleIdentity("youtube", "def", isLive = false)

    @Test fun nextReadyTitleShowsOsd() {
        assertTrue(titleChangeRevealsOsd(first, next, ready = true, blocked = false))
        val episode = playbackTitleIdentity("plex", "101", isLive = false)
        val nextEpisode = playbackTitleIdentity("plex", "102", isLive = false)
        assertTrue(titleChangeRevealsOsd(episode, nextEpisode, ready = true, blocked = false))
    }

    @Test fun firstStartDoesNotShowOsd() {
        assertFalse(titleChangeRevealsOsd(null, first, ready = true, blocked = false))
    }

    @Test fun rendererSwitchOfSameTitleDoesNotShowOsd() {
        val sameAgain = playbackTitleIdentity("youtube", "abc", isLive = false)
        assertFalse(titleChangeRevealsOsd(first, sameAgain, ready = true, blocked = false))
    }

    @Test fun waitsUntilTheNewTitleIsReady() {
        assertFalse(titleChangeRevealsOsd(first, next, ready = false, blocked = false))
    }

    @Test fun openPanelMenuOrDialogKeepsOsdHidden() {
        assertFalse(titleChangeRevealsOsd(first, next, ready = true, blocked = true))
    }

    @Test fun liveTvAndRadioKeepTheirOwnChannelInfo() {
        assertNull(playbackTitleIdentity("tvheadend", "ch1", isLive = true))
        assertNull(playbackTitleIdentity("tvheadend", "rec1", isLive = false))
        assertNull(playbackTitleIdentity("radio", "st1", isLive = false))
        assertNull(playbackTitleIdentity("youtube", "live1", isLive = true))
        assertNull(playbackTitleIdentity("plex", "", isLive = false))
    }

    @Test fun sameIdFromAnotherSourceIsANewTitle() {
        assertNotEquals(playbackTitleIdentity("plex", "1", false), playbackTitleIdentity("jellyfin", "1", false))
    }
}
