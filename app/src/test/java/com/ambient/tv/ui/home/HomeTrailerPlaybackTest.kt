package com.ambient.tv.ui.home

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.ContentRowData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HomeTrailerPlaybackTest {
    @Test fun queueUsesTheClickedRowsOrderAndRejectsUnrelatedOrMissingItems() {
        val row = row("trailers.CINEMA", "third", "first", "second", "third")
        assertEquals(listOf("third", "first", "second"), homeTrailerPlaybackRow(row, "first")?.items?.map { it.videoId })
        assertEquals(row.id, homeTrailerPlaybackRow(row, "first")?.id)
        assertNull(homeTrailerPlaybackRow(row, "missing"))
        assertNull(homeTrailerPlaybackRow(row.copy(id = "youtube.home"), "first"))
        assertNull(homeTrailerVideoId("youtube:first"))
        assertNull(homeTrailerVideoId("trailer-video:"))
    }

    @Test fun returnFollowsLastPlayedIdentityInTheExactSourceRow() {
        val memory = HomeTrailerPlaybackReturn()
        memory.start(row("trailers.CINEMA", "a", "b", "c"), "a")
        assertNull(memory.restore("trailers.SERIES", "b"))
        assertNull(memory.restore("trailers.CINEMA", "missing"))
        assertEquals(0, memory.revision)
        assertEquals(HomeFocusSnapshot("trailer-video:c", "trailers.CINEMA"), memory.restore("trailers.CINEMA", "c"))
        assertEquals(HomeFocusSnapshot("trailer-video:b", "trailers.CINEMA"), memory.restore("trailers.CINEMA", "b"))
        assertEquals(2, memory.revision)
    }

    @Test fun backgroundRefreshCannotRemovePlayedCardBeforeOrImmediatelyAfterReturn() {
        val source = row("trailers.CINEMA", "a", "b", "c")
        val fresh = row("trailers.CINEMA", "new", "a")
        val memory = HomeTrailerPlaybackReturn()
        memory.start(source, "a")
        assertSame(source, memory.row(source.id, fresh))
        val target = memory.restore(source.id, "c")
        // The old Compose child can receive focus briefly before the lazy return finishes.
        memory.focused(source.id, "trailer-video:a")
        assertEquals(target, memory.pendingTarget)
        assertSame(source, memory.row(source.id, fresh))
        memory.focused(source.id, "trailer-video:c")
        assertNull(memory.pendingTarget)
        assertSame(source, memory.row(source.id, fresh))
        // The user's next actual move releases the pin; ordinary browsing stays live.
        memory.focused(source.id, "trailer-video:b")
        assertSame(fresh, memory.row(source.id, fresh))
        assertNull(memory.target)
    }

    @Test fun companionLaunchAndCancelledPlaybackDoNotKeepAFeedFrozen() {
        val source = row("trailers.CINEMA", "a", "b")
        val fresh = row("trailers.CINEMA", "new")
        val memory = HomeTrailerPlaybackReturn()
        memory.start(source, "a")
        memory.focused(source.id, "trailer-video:b")
        assertSame(fresh, memory.row(source.id, fresh))
        memory.start(source, "a")
        memory.clear()
        assertNull(memory.restore(source.id, "a"))
        assertSame(fresh, memory.row(source.id, fresh))
    }

    private fun row(id: String, vararg videos: String) = ContentRowData(
        id = id, title = id, items = videos.map { homeTrailerTestItem(it) },
    )
}

internal fun homeTrailerTestItem(id: String, title: String = id) = MediaItem(
    ratingKey = "trailer-video:$id", title = title, type = "trailer", index = null,
    thumbUrl = null, artUrl = null, year = null, summary = null, tagline = null,
    durationMs = null, viewOffsetMs = null, parentTitle = null, parentRatingKey = null,
    grandparentTitle = null, grandparentRatingKey = null, contentRating = null, rating = null, studio = "TMDB",
)
