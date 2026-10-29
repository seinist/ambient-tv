package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import com.ambient.tv.youtube.youtubeChannelKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeVideoFiltersTest {
    private val channel = YouTubeItem(youtubeChannelKey("UCchannel"), "Zulu channel", channelId = "UCchannel")
    private val otherChannel = YouTubeItem(youtubeChannelKey("UCother"), "Alpha channel", channelId = "UCother")
    private val watched = YouTubeItem("watched", "Watched", percentWatched = 100, durationMs = 30_000)
    private val progress = YouTubeItem("progress", "Progress", percentWatched = 40, durationMs = 20_000)
    private val live = YouTubeItem("live", "Live", isLive = true)
    private val upcoming = YouTubeItem("upcoming", "Upcoming", isUpcoming = true)
    private val shorts = YouTubeItem("shorts", "Shorts", isShorts = true, durationMs = 10_000)

    @Test fun channelDirectoryOffersNoVideoFilterOrDurationSort() {
        val rows = listOf(YouTubeRow("Channels", listOf(channel, otherChannel)))

        assertFalse(youtubeVideoFiltersAvailable(rows))
        assertEquals(listOf(YouTubeSort.DEFAULT, YouTubeSort.TITLE), youtubeAvailableSorts(rows))
        assertEquals(YouTubeSort.DEFAULT, youtubeEffectiveSort(rows, YouTubeSort.DURATION))
        for (filter in YouTubeFilter.entries) {
            assertEquals(rows, applyYouTubeViewOptions(rows, YouTubeSort.DEFAULT, filter))
        }
    }

    @Test fun savedVideoSortAndFilterLeaveChannelDirectoryInProviderOrder() {
        val rows = listOf(YouTubeRow("Channels", listOf(channel, otherChannel)))

        assertEquals(rows, applyYouTubeViewOptions(rows, YouTubeSort.DURATION, YouTubeFilter.IN_PROGRESS))
        assertEquals(
            listOf(otherChannel, channel),
            applyYouTubeViewOptions(rows, YouTubeSort.TITLE, YouTubeFilter.ALL).single().items,
        )
    }

    @Test fun mixedResultsKeepChannelsWhileFilteringVideos() {
        val rows = listOf(YouTubeRow("Search", listOf(watched, channel, progress, live, upcoming, shorts)))

        assertTrue(youtubeVideoFiltersAvailable(rows))
        assertEquals(YouTubeSort.entries, youtubeAvailableSorts(rows))
        val matches = mapOf(
            YouTubeFilter.ALL to listOf(watched, channel, progress, live, upcoming, shorts),
            YouTubeFilter.UNWATCHED to listOf(channel, live, upcoming, shorts),
            YouTubeFilter.IN_PROGRESS to listOf(channel, progress),
            YouTubeFilter.LIVE to listOf(channel, live, upcoming),
            YouTubeFilter.SHORTS to listOf(channel, shorts),
        )
        for ((filter, expected) in matches) {
            assertEquals(expected, applyYouTubeViewOptions(rows, YouTubeSort.DEFAULT, filter).single().items)
        }
    }

    @Test fun channelRowsSurviveWhenAllVideoRowsAreFilteredOut() {
        val channelRow = YouTubeRow("Channels", listOf(channel), id = "channels")
        val rows = listOf(channelRow, YouTubeRow("Uploads", listOf(watched), id = "uploads"))

        assertEquals(listOf(channelRow), applyYouTubeViewOptions(rows, YouTubeSort.DEFAULT, YouTubeFilter.LIVE))
        // Availability uses the source rows, so an empty video result still offers its reset.
        assertTrue(youtubeVideoFiltersAvailable(rows))
    }

    @Test fun channelIdentityTakesPrecedenceOverStrayVideoMetadata() {
        val channelWithProgress = channel.copy(percentWatched = 75)
        val rows = listOf(YouTubeRow("Search", listOf(channelWithProgress, watched)))

        assertEquals(
            listOf(channelWithProgress),
            applyYouTubeViewOptions(rows, YouTubeSort.DEFAULT, YouTubeFilter.UNWATCHED).single().items,
        )
    }

    @Test fun uploadsInsideAChannelKeepTheirVideoFilters() {
        val channelVideos = listOf(watched, progress, live, shorts).map { it.copy(channelId = "UCchannel") }
        val rows = listOf(YouTubeRow("Uploads", channelVideos))

        assertTrue(youtubeVideoFiltersAvailable(rows))
        assertEquals(YouTubeSort.DURATION, youtubeEffectiveSort(rows, YouTubeSort.DURATION))
        assertEquals(
            listOf("progress"),
            applyYouTubeViewOptions(rows, YouTubeSort.DEFAULT, YouTubeFilter.IN_PROGRESS).single().items.map { it.videoId },
        )
        assertEquals(
            listOf("live"),
            applyYouTubeViewOptions(rows, YouTubeSort.DEFAULT, YouTubeFilter.LIVE).single().items.map { it.videoId },
        )
    }

    @Test fun videoDurationSortStillHonorsBothDirections() {
        val rows = listOf(YouTubeRow("Videos", listOf(progress, watched, shorts)))

        assertEquals(
            listOf(shorts, progress, watched),
            applyYouTubeViewOptions(rows, YouTubeSort.DURATION, YouTubeFilter.ALL, descending = false).single().items,
        )
        assertEquals(
            listOf(watched, progress, shorts),
            applyYouTubeViewOptions(rows, YouTubeSort.DURATION, YouTubeFilter.ALL, descending = true).single().items,
        )
    }

    @Test fun emptySourceHasNoVideoFilters() {
        assertFalse(youtubeVideoFiltersAvailable(emptyList()))
        assertFalse(youtubeVideoFiltersAvailable(listOf(YouTubeRow("Empty", emptyList()))))
    }
}
