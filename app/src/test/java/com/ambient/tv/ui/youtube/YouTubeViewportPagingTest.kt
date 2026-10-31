package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class YouTubeViewportPagingTest {
    @Test fun mixedHeaderViewsIdentifyEverySourceRowByItsVisibleItemKey() {
        val rows = listOf(
            YouTubeRow("First row", listOf(YouTubeItem("a", "A"), YouTubeItem("b", "B")), "first"),
            YouTubeRow("Second row", listOf(YouTubeItem("c", "C")), "second"),
        )
        for (prefix in listOf("youtube-list", "youtube-wall")) {
            val indexed = youtubeViewportItemKeys(rows, prefix)
            assertEquals("first" to 0, indexed["$prefix:first:a"])
            assertEquals("first" to 1, indexed["$prefix:first:b"])
            assertEquals("second" to 0, indexed["$prefix:second:c"])
            assertNull(indexed["$prefix-first-header"])
        }
    }

    @Test fun pageContinuationSkipsNonContentHeadersAtTheEndOfTheViewport() {
        val targets = mapOf(
            "youtube-list:a:a1" to ("a" to 0),
            "youtube-list:a:a2" to ("a" to 1),
            "youtube-list:b:b1" to ("b" to 0),
        )
        assertEquals(
            "a" to 1,
            youtubeLastVisibleSourceItem(
                listOf("youtube-list:a:a1", "youtube-list:a:a2", "youtube-list-header:b"), targets,
            ),
        )
        assertEquals("b" to 0, youtubeLastVisibleSourceItem(listOf("youtube-list:b:b1"), targets))
        assertNull(youtubeLastVisibleSourceItem(listOf("youtube-list-header:b"), targets))
    }

    @Test fun flatGridsRememberTheCorrectSourceRowEvenWhenVideoIdsOverlap() {
        val rows = listOf(
            YouTubeRow("First", listOf(YouTubeItem("a", "A"), YouTubeItem("shared", "S")), "source-a"),
            YouTubeRow("Second", listOf(YouTubeItem("shared", "S"), YouTubeItem("b", "B")), "source-b"),
        )
        val keys = youtubeFlatViewportItemKeys(rows)
        assertEquals("source-a" to 0, keys["a"])
        assertEquals("source-a" to 1, keys["shared"])
        assertEquals("source-b" to 1, keys["b"])
        assertEquals("source-b" to 1, youtubeLastVisibleSourceItem(listOf("a", "b"), keys))
    }

    @Test fun bothTvAndHandheldObserveTheVisibleTail() {
        val source = File("src/main/java/com/ambient/tv/ui/youtube/YouTubeViewportPaging.kt").readText()
        assertEquals(false, source.contains("LocalHandheldUi"))
        assertEquals(3, Regex("collectLatest").findAll(source).count()) // import and two collectors
    }

    @Test fun allSmartTubePresentationModesObserveTouchViewport() {
        val file = File("src/main/java/com/ambient/tv/ui/youtube/YouTubeScreen.kt").readText()
        assertEquals(2, Regex("ObserveYouTubeListViewport\\(rowId, rowState\\)").findAll(file).count())
        assertEquals(1, Regex("ObserveYouTubeGridViewport\\(null, gridState\\)").findAll(
            file.substringAfter("private fun LandscapeGrid(").substringBefore("private fun ListMode(")).count())
        assertEquals(2, Regex("youtubeViewportItemKeys\\(rows,").findAll(file).count())
        assertEquals(1, Regex("ObserveYouTubeListViewport\\(null, rowState\\)").findAll(file).count())
    }
}
