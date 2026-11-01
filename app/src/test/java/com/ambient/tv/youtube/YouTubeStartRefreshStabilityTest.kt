package com.ambient.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

/** Start recommendations change on every request; an automatic refresh must not swap tiles. */
class YouTubeStartRefreshStabilityTest {
    private fun item(id: String) = YouTubeItem(videoId = id, title = id)
    private fun row(vararg ids: String) = YouTubeRow("Empfohlen", ids.map(::item), id = "rec")

    @Test fun automaticRefreshKeepsVisibleTilesAndAppendsNewOnes() {
        val cache = YouTubeContentCache()
        cache.replace("feed:home", listOf(row("a", "b", "c")))
        val shown = cache.replace("feed:home", listOf(row("x", "b", "y")), keepVisible = true)
        assertEquals(listOf("a", "b", "c", "x", "y"), shown.single().items.map { it.videoId })
    }

    @Test fun explicitRefreshShowsTheNewRecommendations() {
        val cache = YouTubeContentCache()
        cache.replace("feed:home", listOf(row("a", "b", "c")))
        val shown = cache.replace("feed:home", listOf(row("x", "b", "y")))
        assertEquals(listOf("b", "x", "y"), shown.single().items.map { it.videoId })
    }

    @Test fun firstLoadWithoutVisibleTilesIsTheServerResult() {
        val cache = YouTubeContentCache()
        val shown = cache.replace("feed:home", listOf(row("x", "y")), keepVisible = true)
        assertEquals(listOf("x", "y"), shown.single().items.map { it.videoId })
    }
}
