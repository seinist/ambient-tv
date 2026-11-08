package com.ambient.tv.ui.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeInsertViewportTest {
    @Test fun prependingAtTheStartRevealsTheInsertedCards() {
        assertTrue(youtubeRevealLeadingInsert("a", listOf("new", "a", "b"), 0, 0, false))
    }

    @Test fun deeperOrActivelyScrolledViewsKeepTheirExistingAnchor() {
        assertFalse(youtubeRevealLeadingInsert("a", listOf("new", "a", "b"), 4, 0, false))
        assertFalse(youtubeRevealLeadingInsert("a", listOf("new", "a", "b"), 0, 12, false))
        assertFalse(youtubeRevealLeadingInsert("a", listOf("new", "a", "b"), 0, 0, true))
    }

    @Test fun initialLoadRemovalAndPaginationDoNotResetTheViewport() {
        assertFalse(youtubeRevealLeadingInsert(null, listOf("a", "b"), 0, 0, false))
        assertFalse(youtubeRevealLeadingInsert("a", listOf("b", "c"), 0, 0, false))
        assertFalse(youtubeRevealLeadingInsert("a", listOf("a", "b", "next"), 0, 0, false))
        assertFalse(youtubeRevealLeadingInsert("a", emptyList(), 0, 0, false))
    }

    @Test fun aLargeBatchMustNotPushTheFocusedCardOutsideTheViewport() {
        val keys = listOf("new1", "new2", "new3", "a", "b")
        assertFalse(youtubeRevealLeadingInsert("a", keys, 0, 0, false,
            focusedIndex = 3, lastFullyVisibleIndex = 2))
        assertTrue(youtubeRevealLeadingInsert("a", keys, 0, 0, false,
            focusedIndex = 3, lastFullyVisibleIndex = 4))
        assertFalse(youtubeRevealLeadingInsert("a", keys, 0, 0, false,
            focusedIndex = -1, lastFullyVisibleIndex = 4))
    }
}
