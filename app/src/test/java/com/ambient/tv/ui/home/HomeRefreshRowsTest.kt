package com.ambient.tv.ui.home

import com.ambient.tv.ui.common.ContentRowData
import org.junit.Assert.assertEquals
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
    fun offlineServersRetainCachedPostersButNotLiveTvHomeRows() {
        for (provider in listOf("plex", "jellyfin", "emby")) {
            assertEquals(false, homeRowHiddenByOffline(provider, setOf(provider)))
        }
        assertEquals(true, homeRowHiddenByOffline("tvheadend", setOf("tvheadend")))
        assertEquals(false, homeRowHiddenByOffline("tvheadend", emptySet()))
    }
}
