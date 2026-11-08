package com.ambient.tv.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackReturnEntryTest {
    @Test fun playerPreviewCannotClaimFocusButTheActualReturnCan() {
        val entry = PlaybackReturnEntry()
        assertFalse(entry.accepts(active = false, revision = 12))
        assertFalse(entry.accepts(active = false, revision = 13))
        assertTrue(entry.accepts(active = true, revision = 13))
    }

    @Test fun nextInTheMiniPlayerDoesNotReplaceAnActiveBrowseSelection() {
        val entry = PlaybackReturnEntry()
        assertTrue(entry.accepts(active = true, revision = 12))
        assertFalse(entry.accepts(active = true, revision = null))
        assertFalse(entry.accepts(active = true, revision = 13))
        assertFalse(entry.accepts(active = false, revision = 13))
        assertTrue(entry.accepts(active = true, revision = 13))
    }

    @Test fun anOrdinaryEntryDoesNotTurnIntoAReturnWhenPlaybackChanges() {
        val entry = PlaybackReturnEntry()
        assertFalse(entry.accepts(active = true, revision = null))
        assertFalse(entry.accepts(active = true, revision = 1))
        assertFalse(entry.accepts(active = false, revision = 1))
        assertTrue(entry.accepts(active = true, revision = 1))
    }

    @Test fun navigationReturnStaysStableAfterAcknowledgementButNotAfterAnotherVisit() {
        val entry = NavigationPlaybackReturnEntry()
        val returned = NavigationPlaybackReturn("folder-entry", "next-song", 5)
        assertNull(entry.select(active = false, pending = returned))
        assertEquals(returned, entry.select(active = true, pending = returned))
        assertEquals(returned, entry.select(active = true, pending = null))
        assertNull(entry.select(active = false, pending = null))
        assertNull(entry.select(active = true, pending = null))
    }

    @Test fun aCommittedLayoutRevisionDoesNotGrantFocusOnALaterOrdinaryVisit() {
        val visit = PlaybackReturnVisit<Int>()
        assertEquals(7, visit.token(active = true, accepted = 7))
        assertEquals(7, visit.token(active = true, accepted = null))
        assertNull(visit.token(active = false, accepted = null))
        assertNull(visit.token(active = true, accepted = null))
    }

    @Test fun navigationMailboxSeparatesOriginsAndRejectsStaleAcknowledgements() {
        NavigationPlaybackReturns.clear()
        NavigationPlaybackReturns.prepare("folder", "one")
        val earlier = NavigationPlaybackReturns.pending.value.getValue("folder")
        NavigationPlaybackReturns.prepare("folder", "two")
        NavigationPlaybackReturns.prepare("search", "different")
        assertFalse(NavigationPlaybackReturns.consume(earlier))
        assertEquals("two", NavigationPlaybackReturns.pending.value["folder"]?.itemId)
        assertEquals("different", NavigationPlaybackReturns.pending.value["search"]?.itemId)
        assertTrue(NavigationPlaybackReturns.consume(NavigationPlaybackReturns.pending.value.getValue("folder")))
        assertNull(NavigationPlaybackReturns.pending.value["folder"])
        NavigationPlaybackReturns.clear()
    }
}
