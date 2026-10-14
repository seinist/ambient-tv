package com.ambient.tv.ui.youtube

import com.ambient.tv.data.local.MediaSelectionPreferences
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeViewOptionsTest {
    private val rows = listOf(
        YouTubeRow(
            title = "Feed",
            items = listOf(
                YouTubeItem(videoId = "watched", title = "Zulu", durationMs = 20_000, percentWatched = 100),
                YouTubeItem(videoId = "short", title = "Alpha", durationMs = 10_000, isShorts = true),
                YouTubeItem(videoId = "live", title = "Live", isLive = true),
                YouTubeItem(videoId = "progress", title = "Bravo", durationMs = 30_000, percentWatched = 40),
            ),
        ),
    )

    @Test fun filterKeepsOnlyMatchingYouTubeMetadata() {
        assertEquals(
            listOf("progress"),
            applyYouTubeViewOptions(rows, YouTubeSort.DEFAULT, YouTubeFilter.IN_PROGRESS)
                .flatMap { it.items }.map { it.videoId },
        )
        assertEquals(
            listOf("live"),
            applyYouTubeViewOptions(rows, YouTubeSort.DEFAULT, YouTubeFilter.LIVE)
                .flatMap { it.items }.map { it.videoId },
        )
        assertEquals(
            listOf("short"),
            applyYouTubeViewOptions(rows, YouTubeSort.DEFAULT, YouTubeFilter.SHORTS)
                .flatMap { it.items }.map { it.videoId },
        )
    }

    @Test fun sortIsAppliedInsideRowsWithoutLosingRowTitles() {
        val sorted = applyYouTubeViewOptions(rows, YouTubeSort.TITLE, YouTubeFilter.ALL)

        assertEquals("Feed", sorted.single().title)
        assertEquals(listOf("Alpha", "Bravo", "Live", "Zulu"), sorted.single().items.map { it.title })
    }

    @Test fun emptyRowsAreRemovedAfterFiltering() {
        val noLiveRows = listOf(YouTubeRow("No live", listOf(YouTubeItem("video", "Video"))))

        assertEquals(emptyList<YouTubeRow>(), applyYouTubeViewOptions(noLiveRows, YouTubeSort.DEFAULT, YouTubeFilter.LIVE))
    }

    @Test fun selectionDefaultsToPlayAndIgnoresLibraryDetailsDefault() {
        assertEquals(YouTubeSelectionRoute.Play("abc"), youtubeSelectionRoute(null, "abc"))
        assertEquals(YouTubeSelectionRoute.Play("abc"), youtubeSelectionRoute(MediaSelectionPreferences.PLAY, "abc"))
        assertEquals(YouTubeSelectionRoute.Details("abc"), youtubeSelectionRoute(MediaSelectionPreferences.DETAILS, "abc"))
        assertEquals(YouTubeSelectionRoute.Ask("abc"), youtubeSelectionRoute(MediaSelectionPreferences.ASK, "abc"))
        assertEquals(YouTubeSelectionRoute.Play("abc"), youtubeSelectionRoute("details-from-library", "abc"))
    }

    @Test fun infoListDoesNotRepeatChannelUnderTheTitle() {
        val channelOnly = YouTubeItem(videoId = "a", title = "Video", subtitle = "Channel", author = "Channel")
        val subtitleAsAuthor = YouTubeItem(videoId = "b", title = "Video", subtitle = "Channel")
        val distinct = YouTubeItem(videoId = "c", title = "Video", subtitle = "1.2M views", author = "Channel")
        assertEquals(null, youtubeInfoSubtitle(channelOnly))
        assertEquals(null, youtubeInfoSubtitle(subtitleAsAuthor))
        assertEquals("1.2M views", youtubeInfoSubtitle(distinct))
    }
}
