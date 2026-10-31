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

    @Test fun allSmartTubePresentationModesObserveTouchViewport() {
        val file = File("src/main/java/com/ambient/tv/ui/youtube/YouTubeScreen.kt").readText()
        assertEquals(2, Regex("ObserveYouTubeListViewport\\(rowId, rowState\\)").findAll(file).count())
        assertEquals(1, Regex("ObserveYouTubeGridViewport\\(null, gridState\\)").findAll(
            file.substringAfter("private fun LandscapeGrid(").substringBefore("private fun ListMode(")).count())
        assertEquals(2, Regex("youtubeViewportItemKeys\\(rows,").findAll(file).count())
        assertEquals(1, Regex("ObserveYouTubeListViewport\\(null, rowState\\)").findAll(file).count())
    }
}
