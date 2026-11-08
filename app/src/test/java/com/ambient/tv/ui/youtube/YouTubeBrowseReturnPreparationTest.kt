package com.ambient.tv.ui.youtube

import com.ambient.tv.ui.common.ContentFocusTarget
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class YouTubeBrowseReturnPreparationTest {
    private fun video(id: String, shorts: Boolean = false) = YouTubeItem(id, id, isShorts = shorts)
    private val rows = listOf(
        YouTubeRow("First", listOf(video("first")), id = "first-row"),
        YouTubeRow("Videos", listOf(video("a", shorts = true), video("b"), video("last")), id = "videos"),
    )
    private val pending = YouTubeBrowsePlaybackContext.ReturnSnapshot(
        scope = "channel:UC1", revision = 7, cacheRevision = 3,
        target = YouTubeBrowsePlaybackContext.ReturnTarget("videos", "last"), rows = rows,
    )

    @Test fun firstLayoutGetsTheReturnRowsTargetAndExactEpochWithoutConsumingAnything() {
        val prepared = prepareYouTubeBrowseReturn("channel:UC1", 3, pending,
            YouTubeSort.DEFAULT, YouTubeFilter.ALL, descending = false)!!
        assertSame(rows, prepared.rows)
        assertEquals(ContentFocusTarget("last", "videos"), prepared.target)
        assertEquals(pending.revision, prepared.revision)
        // Reading the same immutable preview again produces the same first layout.
        assertEquals(prepared, prepareYouTubeBrowseReturn("channel:UC1", 3, pending,
            YouTubeSort.DEFAULT, YouTubeFilter.ALL, descending = false))
    }

    @Test fun anotherDestinationOrAccountCannotReuseThePreparedReturn() {
        assertNull(prepareYouTubeBrowseReturn("feed:home", 3, pending,
            YouTubeSort.DEFAULT, YouTubeFilter.ALL, descending = false))
        assertNull(prepareYouTubeBrowseReturn("channel:UC1", 4, pending,
            YouTubeSort.DEFAULT, YouTubeFilter.ALL, descending = false))
        assertNull(prepareYouTubeBrowseReturn("channel:UC1", 3, null,
            YouTubeSort.DEFAULT, YouTubeFilter.ALL, descending = false))
    }

    @Test fun aFilteredOutReturnUsesTheFirstVisibleCardWithoutBypassingTheFilter() {
        val prepared = prepareYouTubeBrowseReturn("channel:UC1", 3, pending,
            YouTubeSort.TITLE, YouTubeFilter.SHORTS, descending = true)!!
        assertEquals(ContentFocusTarget("a", "videos"), prepared.target)
        // Keep raw rows for the normal display pipeline; no synthetic or unfiltered card.
        assertSame(rows, prepared.rows)
        val visible = applyYouTubeViewOptions(prepared.rows, YouTubeSort.TITLE, YouTubeFilter.SHORTS, true)
        assertEquals(listOf("a"), visible.flatMap { it.items }.map { it.videoId })
    }

    @Test fun anEmptyFilteredReturnHasNoFocusableTarget() {
        val prepared = prepareYouTubeBrowseReturn("channel:UC1", 3, pending,
            YouTubeSort.DEFAULT, YouTubeFilter.LIVE, descending = false)!!
        assertEquals(ContentFocusTarget(null, null), prepared.target)
    }

    @Test fun scopeMatchesTheActualFeedChannelAndNormalizedSearchRequest() {
        assertEquals("feed:subscriptions", youtubeBrowseReturnScope("subscriptions", null, ""))
        assertEquals("feed:home", youtubeBrowseReturnScope("home", null, "x"))
        assertEquals("channel:UC1", youtubeBrowseReturnScope("home", "UC1", ""))
        assertEquals("search:tv news", youtubeBrowseReturnScope("home", null, "  TV News  "))
        assertEquals("channel:UC1:search:tv news", youtubeBrowseReturnScope("home", "UC1", " TV News "))
    }
}
