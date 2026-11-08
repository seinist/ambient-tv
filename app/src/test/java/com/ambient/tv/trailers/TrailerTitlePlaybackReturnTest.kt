package com.ambient.tv.trailers

import com.ambient.tv.ui.youtube.YouTubeBrowsePlaybackContext
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TrailerTitlePlaybackReturnTest {
    private val videos = listOf(TrailerVideo("a", "First", official = true), TrailerVideo("last", "Last"))
    private val origin = TrailerTitlePlaybackOrigin(13, "movie:7:de:1", "Film", videos)
    private val pending = YouTubeBrowsePlaybackContext.ReturnSnapshot(
        scope = "trailer-title:movie:7", revision = 4, cacheRevision = 0,
        target = YouTubeBrowsePlaybackContext.ReturnTarget("videos", "last"),
        rows = listOf(YouTubeRow("Film", videos.map { YouTubeItem(it.key, it.name) }, "videos")),
        sessionToken = 13,
    )

    @Test fun nextAndPreviousAreResolvedFromTheFrozenTitleBeforeLayout() {
        val prepared = prepareTrailerTitleReturn(pending.scope, origin.requestKey, origin, pending, true)!!
        assertSame(videos, prepared.videos)
        assertEquals("Film", prepared.title)
        assertEquals("trailer-video:last", prepared.focusKey)
        assertEquals("youtube:4", prepared.token)
        val previous = pending.copy(revision = 5,
            target = pending.target.copy(videoId = "a"))
        val returned = prepareTrailerTitleReturn(previous.scope, origin.requestKey, origin, previous, true)!!
        assertEquals("trailer-video:a", returned.focusKey)
        assertEquals("youtube:5", returned.token)
    }

    @Test fun anUnrelatedScopeSessionOrConfigurationCannotBorrowTheReturn() {
        assertNull(prepareTrailerTitleReturn("other-title", origin.requestKey, origin, pending, true))
        assertNull(prepareTrailerTitleReturn(pending.scope, "movie:7:en:2", origin, pending, true))
        assertNull(prepareTrailerTitleReturn(pending.scope, origin.requestKey, origin,
            pending.copy(sessionToken = 14), true))
        assertNull(prepareTrailerTitleReturn(pending.scope, origin.requestKey, origin, pending, false))
        assertNull(prepareTrailerTitleReturn(pending.scope, origin.requestKey, origin, null, true))
    }

    @Test fun aVanishedVideoDoesNotCreateASyntheticReturnTile() {
        assertNull(prepareTrailerTitleReturn(pending.scope, origin.requestKey, origin,
            pending.copy(target = pending.target.copy(videoId = "unknown")), true))
    }
}
