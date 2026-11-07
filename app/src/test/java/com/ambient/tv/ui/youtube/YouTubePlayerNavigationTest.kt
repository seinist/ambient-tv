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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class YouTubePlayerNavigationTest {
    private val scope = "trailer-title:movie:123"
    private fun video(id: String) = YouTubeItem(videoId = id, title = id)
    private fun row() = YouTubeRow("TMDB videos", listOf(video("a"), video("b"), video("c")), id = "tmdb-videos")
    private fun context() = YouTubeBrowsePlaybackContext(
        SmartTubeEngine(ApplicationProvider.getApplicationContext(), AppPreferences(memoryPreferences())),
        YouTubeContentCache(),
    )

    @Test fun trailerPreviousCanReachAnUnplayedItemAndNextReturnsToTheSameRow() {
        val source = context()
        val token = source.start(scope, row(), "b", isTrailer = true)!!
        val navigation = YouTubePlayerNavigation(source, token, isTrailer = true)
        assertTrue(navigation.loaded(video("b")))
        assertEquals("a", navigation.previousVideoId("b"))
        assertEquals("c", navigation.nextVideo("b")?.videoId)

        assertTrue(navigation.requestPrevious("b", "a"))
        assertTrue(navigation.startsAtBeginning("a"))
        assertTrue(navigation.loaded(video("a")))
        assertNull(navigation.previousVideoId("a"))
        assertEquals("b", navigation.nextVideo("a")?.videoId)
        assertTrue(navigation.requestNext("a", "b"))
        assertTrue(navigation.loaded(video("b")))
        assertTrue(navigation.requestNext("b", "c"))
        assertTrue(navigation.loaded(video("c")))
        assertNull(navigation.nextVideo("c"))
        // TMDB's finite row must also reject an unsolicited YouTube continuation result.
        source.appendContinuation(token, "tmdb-videos", YouTubeRowPage(listOf(video("recommendation")), hasMore = false))
        assertNull(navigation.nextVideo("c"))

        assertNull(source.consumeReturn("trailer-title:movie:other"))
        assertEquals("c", source.consumeReturn(scope)?.videoId)
        assertNull(source.consumeReturn(scope))
        // The final item never wraps or becomes a YouTube recommendation.
        assertFalse(navigation.requestNext("c", "a"))
        assertFalse(navigation.requestNext("c", "recommendation"))
    }

    @Test fun failedNextAndLateLoadsKeepTheReturnTargetOnTheLastSuccessfulVideo() {
        val source = context()
        val token = source.start(scope, row(), "b", isTrailer = true)!!
        val navigation = YouTubePlayerNavigation(source, token, isTrailer = true)
        assertTrue(navigation.loaded(video("b")))
        assertTrue(navigation.requestNext("b", "c"))
        assertFalse(navigation.requestNext("b", "c"))
        assertFalse(navigation.loaded(video("a")))
        assertEquals("b", source.consumeReturn(scope)?.videoId)

        // A successful retry advances once; stepping back makes B the return target again.
        assertTrue(navigation.loaded(video("c")))
        assertEquals("c", source.consumeReturn(scope)?.videoId)
        assertTrue(navigation.requestPrevious("c", "b"))
        assertTrue(navigation.loaded(video("b")))
        assertEquals("b", source.consumeReturn(scope)?.videoId)
    }

    @Test fun replacedTrailerQueueCannotBeUsedThroughOldCallbacksOrHistory() {
        val source = context()
        val oldToken = source.start(scope, row(), "a", isTrailer = true)!!
        val navigation = YouTubePlayerNavigation(source, oldToken, isTrailer = true)
        assertTrue(navigation.loaded(video("a")))
        assertTrue(navigation.requestNext("a", "b"))
        assertTrue(navigation.loaded(video("b")))
        val newToken = source.start("another-title", row(), "c", isTrailer = true)!!
        source.played(newToken, "c")

        assertFalse(navigation.fromBrowse("b"))
        assertNull(navigation.previousVideoId("b"))
        assertNull(navigation.nextVideo("b"))
        assertFalse(navigation.requestPrevious("b", "a"))
        assertFalse(navigation.requestNext("b", "c"))
        assertTrue(navigation.loaded(video("b"))) // Renderer reload is still allowed.
        assertNull(source.consumeReturn(scope))
        assertEquals("c", source.consumeReturn("another-title")?.videoId)
    }

    @Test fun standaloneTrailerAndMismatchedYouTubeTokenDoNotBorrowSourceNavigation() {
        val source = context()
        val youtubeToken = source.start("feed:home", row(), "b")!!
        for (token in listOf(null, youtubeToken)) {
            val navigation = YouTubePlayerNavigation(source, token, isTrailer = true)
            assertTrue(navigation.loaded(video("b")))
            assertFalse(navigation.fromBrowse("b"))
            assertNull(navigation.previousVideoId("b"))
            assertNull(navigation.nextVideo("b"))
            assertFalse(navigation.requestNext("b", "c"))
            assertFalse(navigation.requestPrevious("b", "a"))
        }
        assertNull(source.consumeReturn("feed:home"))
    }

    @Test fun normalYouTubeKeepsSourceNavigationAndStandaloneHistory() {
        val source = context()
        val token = source.start("feed:home", row(), "b")!!
        val fromBrowse = YouTubePlayerNavigation(source, token, isTrailer = false)
        assertTrue(fromBrowse.loaded(video("b")))
        assertTrue(fromBrowse.requestPrevious("b", "a"))
        assertTrue(fromBrowse.loaded(video("a")))
        assertEquals("a", source.consumeReturn("feed:home")?.videoId)

        val standalone = YouTubePlayerNavigation(source, null, isTrailer = false)
        assertTrue(standalone.loaded(video("a")))
        assertTrue(standalone.requestNext("a", "b"))
        assertTrue(standalone.loaded(video("b")))
        assertEquals("a", standalone.previousVideoId("b"))
        assertTrue(standalone.requestPrevious("b", "a"))
        assertTrue(standalone.loaded(video("a")))
        assertEquals("b", standalone.nextVideo("a")?.videoId)
    }
}
