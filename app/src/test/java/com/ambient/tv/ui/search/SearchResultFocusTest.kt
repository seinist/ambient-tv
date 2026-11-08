package com.ambient.tv.ui.search

import com.ambient.tv.ui.common.BrowseFocusKey
import com.ambient.tv.ui.common.BrowseFocusSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultFocusTest {
    @Test fun aPlaybackLayoutPreparedBeforeCompositionNeverSchedulesALateCorrection() = runTest {
        val restore = SearchFocusRequest("last-played", initialLayoutPrepared = true)
        assertTrue(restore.pending)
        restore.restore(listOf(slot("last-played", 40))) { error("Prepared return must not scroll after its first layout") }
        restore.restore(listOf(slot("last-played", 43))) { error("A provider update must not rearm return scrolling") }
        restore.contentFocused()
        assertFalse(restore.pending)
    }

    @Test fun navigationOnlyChangesTheNextEntryRequestAndANewQueryForgetsIt() = runTest {
        val focus = SearchResultFocus("saved")
        val entry = focus.request()
        entry.contentFocused()
        repeat(200) { focus.focused("movie-$it") }
        assertEquals("saved", entry.itemKey)
        assertFalse("Navigation must not rearm the active screen's restore", entry.pending)
        entry.restore(listOf(slot("saved", 1))) { error("Consumed entry scrolled again") }

        val switchedView = focus.request()
        assertEquals("movie-199", switchedView.itemKey)
        assertTrue(switchedView.pending)
        val scrolled = mutableListOf<Int>()
        switchedView.restore(listOf(slot("movie-199", 7))) { scrolled += it }
        assertEquals(listOf(7), scrolled)

        focus.clear()
        val newQuery = focus.request()
        assertNull(newQuery.itemKey)
        newQuery.restore(listOf(slot("movie-199", 3))) { error("New query restored the old target") }
        assertTrue("A new query still allows initial focus on its first result", newQuery.pending)
    }

    @Test fun aLateProviderCanRestoreOnceButLaterStatusAndResultChangesCannotScrollAgain() = runTest {
        val restore = SearchResultFocus("saved").request()
        val scrolled = mutableListOf<Int>()
        restore.restore(emptyList()) { scrolled += it }
        restore.restore(listOf(slot("other-provider", 1))) { scrolled += it }
        assertTrue(scrolled.isEmpty())
        val returned = listOf(slot("other-provider", 1), slot("saved", 9))
        restore.restore(returned) { scrolled += it }
        restore.restore(returned) { scrolled += it }
        restore.restore(listOf(slot("saved", 7), slot("new-provider", 10))) { scrolled += it }
        restore.contentFocused()
        assertFalse(restore.pending)
        restore.restore(listOf(slot("saved", 3))) { scrolled += it }
        assertEquals("Status rows and new provider responses cannot correct the scroll again", listOf(9), scrolled)
    }

    @Test fun enteringAvailableContentCancelsAStillMissingReturnTarget() = runTest {
        val restore = SearchResultFocus("late").request()
        val scrolled = mutableListOf<Int>()
        restore.restore(listOf(slot("available", 1))) { scrolled += it }
        restore.contentFocused()
        restore.restore(listOf(slot("available", 1), slot("late", 6))) { scrolled += it }
        assertTrue("A late response must not steal focus/scroll after the user navigates", scrolled.isEmpty())
        assertFalse(restore.pending)
    }

    @Test fun cancellationBeforeTheScrollCompletesLetsTheNewSnapshotRetry() = runTest {
        val restore = SearchResultFocus("saved").request()
        try {
            restore.restore(listOf(slot("saved", 9))) { throw CancellationException("New provider snapshot") }
            error("Cancellation must propagate")
        } catch (_: CancellationException) { }
        val scrolled = mutableListOf<Int>()
        restore.restore(listOf(slot("saved", 7))) { scrolled += it }
        restore.restore(listOf(slot("saved", 3))) { scrolled += it }
        assertEquals(listOf(7), scrolled)
    }

    private fun slot(key: String, index: Int) = BrowseFocusSlot(BrowseFocusKey("provider-row", key, true), index)
}
