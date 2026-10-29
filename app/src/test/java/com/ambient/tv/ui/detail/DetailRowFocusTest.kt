package com.ambient.tv.ui.detail

import com.ambient.tv.domain.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailRowFocusTest {
    @Test fun serverNextEpisodeWinsOverAnEarlierUnwatchedEpisode() {
        val earlier = media("episode:1")
        val resume = media("episode:2").copy(viewOffsetMs = 30_000)
        assertEquals("children:episode:2", detailPreferredChildKey(media("season", "season"), listOf(earlier, resume), resume))
    }

    @Test fun anExplicitRewatchTargetIsNotDiscardedByItsOldViewCount() {
        val first = media("episode:1")
        val rewatch = media("episode:2").copy(viewCount = 1, viewOffsetMs = 20_000)
        assertTrue(rewatch.isWatched)
        assertEquals("children:episode:2", detailPreferredChildKey(media("show", "show"), listOf(first, rewatch), rewatch))
    }

    @Test fun nextEpisodeSelectsItsSeasonRatherThanTheFirstSeason() {
        val seasonOne = media("season:1", "season")
        val seasonTwo = media("season:2", "season").copy(linkedRatingKeys = listOf("other-server:season:2"))
        val next = media("episode:2").copy(parentRatingKey = "other-server:season:2")
        assertEquals("children:season:2", detailPreferredChildKey(media("show", "show"), listOf(seasonOne, seasonTwo), next))
    }

    @Test fun collectionStartsAtAnInProgressFilmThenFirstUnwatchedThenFirstItem() {
        val watched = media("movie:1", "movie").copy(viewCount = 1)
        val unseen = media("movie:2", "movie")
        val progress = media("movie:3", "movie").copy(viewOffsetMs = 25_000)
        val collection = media("collection", "collection")
        assertEquals("children:movie:3", detailPreferredChildKey(collection, listOf(watched, unseen, progress), null))
        assertEquals("children:movie:2", detailPreferredChildKey(collection, listOf(watched, unseen), null))
        assertEquals("children:movie:1", detailPreferredChildKey(collection, listOf(watched), null))
        assertNull(detailPreferredChildKey(collection, emptyList(), null))
    }

    @Test fun seasonWatchProgressUsesLeafCountsAndEpisodeDetailsKeepTheirCurrentEpisode() {
        val complete = media("season:1", "season").copy(leafCount = 5, viewedLeafCount = 5)
        val unfinished = media("season:2", "season").copy(leafCount = 5, viewedLeafCount = 2, viewCount = 1)
        assertEquals("children:season:2", detailPreferredChildKey(media("show", "show"), listOf(complete, unfinished), null))
        val first = media("ep1")
        val current = media("ep2").copy(viewCount = 1)
        assertEquals("children:ep2", detailPreferredChildKey(current, listOf(first, current), first))
    }

    @Test fun rememberingOneRowDoesNotOverwriteOtherRowsOrClearThemAtTheHero() {
        val memory = DetailFocusMemory()
        memory.record("children:plex:episode:4")
        memory.record("cast:actor:7")
        memory.record("similar:movie:2")
        memory.record(null)
        assertNull(memory.key)
        assertEquals(mapOf("children" to "children:plex:episode:4", "cast" to "cast:actor:7", "similar" to "similar:movie:2"), memory.rowItems())
    }

    @Test fun poppingAndReopeningAViewKeepsSessionPositionsButSourcesAndProcessesAreIndependent() {
        val session = DetailFocusSessionStore()
        val page = session.page("server-a:user-a", "42")
        page.record("children:episode:9")
        assertSame(page, session.page("server-a:user-a", "42"))
        assertNotSame(page, session.page("server-b:user-a", "42"))
        assertNotSame(page, session.page("server-a:user-b", "42"))
        assertNotSame(page, session.page("server-a:user-a", "43"))
        assertEquals(emptyMap<String, String>(), DetailFocusSessionStore().page("server-a:user-a", "42").rowItems())
    }

    @Test fun providerQualifiedIdsKeepTheirWholeRowAndItemIdentity() {
        assertEquals("actor:jellyfin:person:17", detailRowForFocusKey("actor:jellyfin:person:17\u0000jellyfin:movie:22"))
        assertEquals("collections", detailRowForFocusKey("collection:emby:collection:8"))
        assertEquals("season-strip", detailRowForFocusKey("season-strip:jellyfin:season:2"))
        assertNull(detailRowForFocusKey(null))
    }

    @Test fun navigationContainersDoNotReportMissingOwnPlaybackMedia() {
        listOf("show", "season", "collection", "artist", "album").forEach { type ->
            assertFalse(type, detailShowsMissingPlaybackSource(type, hasMediaVersions = false, hasActivePlayback = false))
        }
        listOf("movie", "episode", "video", "clip", "track", "audio").forEach { type ->
            assertTrue(type, detailShowsMissingPlaybackSource(type, hasMediaVersions = false, hasActivePlayback = false))
            assertFalse(type, detailShowsMissingPlaybackSource(type, hasMediaVersions = true, hasActivePlayback = false))
            assertFalse(type, detailShowsMissingPlaybackSource(type, hasMediaVersions = false, hasActivePlayback = true))
        }
    }

    private fun media(key: String, type: String = "episode") = MediaItem(
        ratingKey = key, title = key, type = type, index = null,
        thumbUrl = null, artUrl = null, year = null, summary = null, tagline = null,
        durationMs = 100_000, viewOffsetMs = null, parentTitle = null, parentRatingKey = null,
        grandparentTitle = null, grandparentRatingKey = null, contentRating = null, rating = null, studio = null,
    )
}
