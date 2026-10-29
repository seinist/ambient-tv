package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.YouTubeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaybackHistoryTest {
    private fun video(id: String, title: String = id) = YouTubeItem(id, title)

    private fun playedHistory(vararg ids: String): YouTubePlaybackHistory {
        val history = YouTubePlaybackHistory()
        ids.forEachIndexed { index, id ->
            if (index > 0) assertTrue(history.requestNext(ids[index - 1], id))
            assertTrue(history.loaded(video(id)))
        }
        return history
    }

    @Test fun firstVideoAndRendererReloadsDoNotInventAPreviousVideo() {
        val history = playedHistory("a")
        repeat(3) { assertTrue(history.loaded(video("a", "Enriched title"))) }
        assertEquals("a", history.currentVideoId)
        assertNull(history.previousVideoId)
        assertNull(history.nextVideo)
        assertFalse(history.requestNext("a", "a"))
        assertFalse(history.requestNext("a", ""))
        assertFalse(history.requestPrevious("a", "missing"))
    }

    @Test fun previousThenNextTraversesTheSameVideosWithoutAppendingThemAgain() {
        val history = playedHistory("a", "b", "c")
        assertTrue(history.requestPrevious("c", "b"))
        assertTrue(history.loaded(video("b")))
        assertEquals("a", history.previousVideoId)
        assertEquals("c", history.nextVideo?.videoId)
        assertTrue(history.requestPrevious("b", "a"))
        assertTrue(history.loaded(video("a")))
        assertNull(history.previousVideoId)
        assertEquals("b", history.nextVideo?.videoId)

        assertTrue(history.requestNext("a", "b"))
        assertTrue(history.loaded(video("b")))
        assertTrue(history.requestNext("b", "c"))
        assertTrue(history.loaded(video("c")))
        assertEquals("b", history.previousVideoId)
        assertNull(history.nextVideo)
        // Back returns to the original beginning, rather than a new A/B/C copy.
        assertTrue(history.requestPrevious("c", "b"))
        assertTrue(history.loaded(video("b")))
        assertTrue(history.requestPrevious("b", "a"))
        assertTrue(history.loaded(video("a")))
        assertNull(history.previousVideoId)
    }

    @Test fun pendingNavigationKeepsTheCursorUntilSuccessfulLoadAndRejectsDoublePresses() {
        val history = playedHistory("a", "b")
        assertTrue(history.requestPrevious("b", "a"))
        assertEquals("b", history.currentVideoId)
        assertEquals("a", history.previousVideoId)
        assertFalse(history.requestPrevious("b", "a"))
        assertFalse(history.requestNext("b", "c"))
        // A failed load commits nothing; a late completion for another item cannot move it either.
        assertFalse(history.loaded(video("b")))
        assertFalse(history.loaded(video("stale")))
        assertEquals("b", history.currentVideoId)
        assertTrue(history.startsAtBeginning("a"))
        // Retrying the requested item uses the same destination and keeps the forward path.
        assertTrue(history.loaded(video("a")))
        assertNull(history.previousVideoId)
        assertEquals("b", history.nextVideo?.videoId)
    }

    @Test fun nextAfterPreviousKeepsTheForwardVideoInsteadOfAFreshRecommendation() {
        val history = playedHistory("a", "b", "c")
        assertTrue(history.requestPrevious("c", "b"))
        assertTrue(history.loaded(video("b")))
        assertEquals("c", history.nextVideo?.videoId)
        assertFalse(history.requestNext("b", "new-recommendation"))
        assertTrue(history.requestNext("b", "c"))
        assertTrue(history.loaded(video("c")))
        assertTrue(history.requestNext("c", "new-recommendation"))
        assertFalse(history.startsAtBeginning("new-recommendation"))
        assertTrue(history.loaded(video("new-recommendation")))
        assertEquals("c", history.previousVideoId)
    }

    @Test fun historyStepsRestartVisitedVideosOnlyForThatNavigationRequest() {
        val history = playedHistory("a", "b")
        assertFalse(history.startsAtBeginning("b"))
        assertTrue(history.requestPrevious("b", "a"))
        assertTrue(history.startsAtBeginning("a"))
        assertFalse(history.startsAtBeginning("b"))
        assertTrue(history.loaded(video("a")))
        assertFalse(history.startsAtBeginning("a"))
        assertTrue(history.loaded(video("a", "Renderer reload")))
        assertFalse(history.startsAtBeginning("a"))
        assertTrue(history.requestNext("a", "b"))
        assertTrue(history.startsAtBeginning("b"))
        assertTrue(history.loaded(video("b")))
        assertFalse(history.startsAtBeginning("b"))
    }

    @Test fun staleActionsAndUnrequestedLoadsCannotChangeTheCurrentNavigation() {
        val history = playedHistory("a", "b", "c")
        assertFalse(history.requestPrevious("b", "a"))
        assertFalse(history.requestPrevious("c", "a"))
        assertFalse(history.requestNext("b", "c"))
        assertFalse(history.loaded(video("a")))
        assertEquals("c", history.currentVideoId)
        assertEquals("b", history.previousVideoId)
        assertNull(history.nextVideo)
        // A separate destination does not inherit this player's history or an account history.
        val fresh = playedHistory("c")
        assertNull(fresh.previousVideoId)
        assertNull(fresh.nextVideo)
    }
}
