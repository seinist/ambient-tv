package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePlaybackSourceTest {
    private fun item(id: String) = YouTubeItem(id, "Video $id")
    private val a = YouTubeRow("A", listOf(item("a1"), item("shared"), item("a3")))
    private val b = YouTubeRow("B", listOf(item("b1"), item("shared"), item("b3")))

    @Test fun sectionedPlaybackUsesClickedRowEvenWhenVideoExistsInTwoRows() {
        val chosen = youtubePlaybackSourceRow(listOf(a, b), "shared", b.id, sectioned = true)
        assertEquals("B", chosen?.id)
        assertEquals("b1", previousPlayableBrowseVideo(chosen!!.items, "shared")?.videoId)
        assertEquals("b3", nextPlayableBrowseVideo(chosen.items, "shared")?.videoId)
    }

    @Test fun flatGridUsesExactlyTheVisibleCombinedSequence() {
        val chosen = youtubePlaybackSourceRow(listOf(a, b), "b1", preferredRowId = null, sectioned = false)!!
        assertEquals(listOf("a1", "shared", "a3", "b1", "b3"), chosen.items.map { it.videoId })
        assertEquals("a3", previousPlayableBrowseVideo(chosen.items, "b1")?.videoId)
        assertEquals("b3", nextPlayableBrowseVideo(chosen.items, "b1")?.videoId)
    }

    @Test fun singleFlatRowPreservesItsRealContinuationIdentity() {
        assertEquals(a.id, youtubePlaybackSourceRow(listOf(a), "shared", null, sectioned = false)?.id)
    }

    @Test fun videoMissingFromTheClickedDatasetCannotBorrowAnotherQueue() {
        assertNull(youtubePlaybackSourceRow(listOf(a, b), "not-in-source", a.id, sectioned = true))
        assertNull(youtubePlaybackSourceRow(listOf(a, b), "not-in-source", a.id, sectioned = false))
    }
}
