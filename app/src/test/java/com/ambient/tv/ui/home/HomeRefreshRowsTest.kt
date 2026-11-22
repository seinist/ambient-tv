package com.ambient.tv.ui.home

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.ContentRowData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HomeRefreshRowsTest {
    private fun row(id: String, version: String) = ContentRowData(version, emptyList(), id = id)

    @Test
    fun partialLibraryAnswersKeepExistingAggregateRowsForEveryProvider() {
        for (system in listOf("plex", "jellyfin", "emby")) {
            val old = listOf("recentlyAddedMovies", "recentlyReleased", "collection.shared")
                .map { row("$system.$it", "complete old snapshot") }
            val partial = old.map { it.copy(title = "only first library") }
            assertEquals(old, mergePartialHomeRows(old, partial, system, false, false))
            assertEquals(partial, mergePartialHomeRows(old, partial, system, true, true))
        }
    }

    @Test
    fun independentRowsAndNewRowsStillAppearImmediately() {
        val old = listOf(row("plex.recentlyAddedMovies", "old"), row("plex.library.1", "old"))
        val incoming = old.map { it.copy(title = "new") } + row("plex.library.2", "new")
        assertEquals(listOf(old[0], incoming[1], incoming[2]),
            mergePartialHomeRows(old, incoming, "plex", false, false))
        assertEquals(incoming, mergePartialHomeRows(emptyList(), incoming, "plex", false, false))
    }

    @Test
    fun previewAndReleaseRequestsCompleteIndependently() {
        val old = listOf(row("plex.recentlyAddedMovies", "old"), row("plex.recentlyReleased", "old"))
        val incoming = old.map { it.copy(title = "new") }
        assertEquals(listOf(incoming[0], old[1]), mergePartialHomeRows(old, incoming, "plex", true, false))
        assertEquals(listOf(old[0], incoming[1]), mergePartialHomeRows(old, incoming, "plex", false, true))
    }

    @Test
    fun pendingAndOtherProviderRowsRemainVisible() {
        val old = listOf(row("plex.continue", "old"), row("emby.recentlyAddedMovies", "old"))
        assertEquals(old, mergePartialHomeRows(old, emptyList(), "plex", false, false))
    }

    @Test
    fun coldStartHydrationMergesEvenWhenIndependentRowWasFirst() {
        val cached = listOf(row("plex.recentlyAddedMovies", "cached"), row("jellyfin.continue", "cached"))
        val radio = row("radio.favorites", "already visible")
        assertEquals(cached + radio, mergeColdStartHomeRows(listOf(radio), cached))
    }

    @Test
    fun newerOnScreenDataWinsOverStaleDiskRowWithSameId() {
        val old = row("plex.recentlyAddedMovies", "cached")
        val fresh = row("plex.recentlyAddedMovies", "new from server")
        val independent = row("youtube.home", "already visible")
        assertEquals(listOf(independent, fresh),
            mergeColdStartHomeRows(listOf(independent, fresh), listOf(old)))
    }

    @Test
    fun onlineDuplicateWinsWhenTheMostRecentlyWatchedServerIsOffline() {
        val sleeping = item("123", "Shared movie", lastViewedAt = 30)
        val available = item("j:abc", "Shared movie", lastViewedAt = 20)
        val another = item("e:def", "Another movie", lastViewedAt = 10)
        val cached = linkedMapOf("plex" to listOf(sleeping), "jellyfin" to listOf(available), "emby" to listOf(another))
        assertEquals(listOf(available, another), homeContinueItems(cached, setOf("plex"), 10))
        assertEquals(listOf(sleeping, another), homeContinueItems(cached, emptySet(), 10))
        assertEquals(listOf(available), homeContinueItems(cached, setOf("plex"), 1))
        assertEquals(listOf(sleeping, another), homeContinueItems(cached, cached.keys, 10))
    }

    @Test
    fun offlineOnlyTitlesRemainAlongsideReachableTitlesWithTheirOriginalProgress() {
        val saved = item("123", "Only on Plex", lastViewedAt = 30).copy(viewOffsetMs = 9000L)
        val online = item("j:abc", "Jellyfin title", lastViewedAt = 20)
        val results = homeContinueItems(mapOf("plex" to listOf(saved), "jellyfin" to listOf(online)), setOf("plex"), 10)
        assertEquals(listOf(saved, online), results)
        assertEquals(9000L, results.first().viewOffsetMs)
    }

    private fun item(key: String, title: String, lastViewedAt: Long = 0) = MediaItem(
        ratingKey = key, title = title, type = "movie", index = null, thumbUrl = null, artUrl = null,
        year = 2026, summary = null, tagline = null, durationMs = null, viewOffsetMs = null,
        parentTitle = null, parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
        contentRating = null, rating = null, studio = null, lastViewedAt = lastViewedAt,
    )
}
