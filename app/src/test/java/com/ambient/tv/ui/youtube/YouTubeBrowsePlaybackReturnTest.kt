package com.ambient.tv.ui.youtube

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubeContentCache
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import com.ambient.tv.youtube.YouTubeRowPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Back or the end of the queue returns to the channel with focus on the last played video. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class YouTubeBrowsePlaybackReturnTest {
    private fun video(id: String) = YouTubeItem(videoId = id, title = id)

    private fun context(): YouTubeBrowsePlaybackContext = YouTubeBrowsePlaybackContext(
        SmartTubeEngine(ApplicationProvider.getApplicationContext(), AppPreferences(memoryPreferences())),
        YouTubeContentCache(),
    )

    @Test fun returnAfterNextLandsOnALazilyLoadedChannelPage() {
        val playback = context()
        val row = YouTubeRow("Videos", listOf(video("a"), video("b")), id = "videos")
        val token = playback.start("channel:UC1", row, "b")!!
        playback.played(token, "b")
        // The player reached the end of the loaded page; the next page is appended to the queue.
        playback.appendContinuation(token, "videos", YouTubeRowPage(listOf(video("c"), video("d")), hasMore = true))
        assertEquals("c", playback.nextLoaded(token, "b")?.videoId)
        playback.played(token, "c")
        playback.played(token, "d")
        assertEquals("c", playback.previousLoaded(token, "d")?.videoId)

        // A different page must not claim the return address.
        assertNull(playback.consumeReturn("feed:home"))
        val target = playback.consumeReturn("channel:UC1")
        assertEquals("videos", target?.rowId)
        assertEquals("d", target?.videoId)
        // Claimed once: a later visit of the channel keeps the user's own focus.
        assertNull(playback.consumeReturn("channel:UC1"))
    }

    @Test fun continuationPagesKeepTheChannelsSortAndFilter() {
        val playback = context()
        val row = YouTubeRow("Videos", listOf(video("a")), id = "videos")
        val token = playback.start("channel:UC1", row, "a")!!
        playback.appendContinuation(token, "videos", YouTubeRowPage(listOf(
            YouTubeItem("live", "Live", isLive = true), video("a"), video("b"),
        ), hasMore = false))
        // Live tiles and duplicates are never queued; the real end stops instead of wrapping.
        assertEquals("b", playback.nextLoaded(token, "a")?.videoId)
        assertNull(playback.nextLoaded(token, "b"))
    }

    @Test fun aNewChannelSessionReplacesTheOldPlayersQueue() {
        val playback = context()
        val old = playback.start("feed:home", YouTubeRow("Home", listOf(video("h1")), id = "home"), "h1")!!
        playback.played(old, "h1")
        val channel = playback.start("channel:UC1", YouTubeRow("Videos", listOf(video("a")), id = "videos"), "a")!!
        playback.played(channel, "a")
        playback.played(old, "h1")
        assertNull(playback.consumeReturn("feed:home"))
        assertEquals("a", playback.consumeReturn("channel:UC1")?.videoId)
    }
}
