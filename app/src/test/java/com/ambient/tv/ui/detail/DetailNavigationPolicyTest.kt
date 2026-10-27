package com.ambient.tv.ui.detail

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.FULLSCREEN_ROUTES
import com.ambient.tv.ui.navigation.Destinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailNavigationPolicyTest {
    @Test fun seriesAndSeasonsExposeWatchedActionsWithoutPretendingToPlay() {
        listOf("show", "season").forEach { type ->
            val actions = detailActionCapabilities(type, playbackAvailable = true, hasMediaVersions = false, inProgress = false)
            assertTrue(type, actions.canSetWatched)
            assertFalse(type, actions.canRestart)
            assertTrue(type, actions.hasMenuActions)
        }
    }

    @Test fun collectionsAndMusicContainersDoNotExposeDeadMenuActions() {
        listOf("collection", "artist", "album").forEach { type ->
            assertFalse(type, detailActionCapabilities(type, true, false, false).hasMenuActions)
        }
    }

    @Test fun offlineDetailsDoNotOfferServerMutations() {
        val actions = detailActionCapabilities("show", playbackAvailable = false, hasMediaVersions = false, inProgress = true)
        assertFalse(actions.canSetWatched)
        assertFalse(actions.canRemoveFromContinue)
        assertFalse(actions.hasMenuActions)
    }

    @Test fun childrenStatusRemainsVisibleWhileAContainerIsEmpty() {
        listOf("show", "season", "collection").forEach { assertTrue(it, detailShowsChildrenSection(it)) }
        assertFalse(detailShowsChildrenSection("movie"))
    }

    @Test fun detailDrillDownIsFullscreenWhileLibraryFiltersKeepTheirSidebar() {
        assertTrue(FULLSCREEN_ROUTES.contains(Destinations.DETAIL_FILTERED_BROWSE))
        assertFalse(FULLSCREEN_ROUTES.contains(Destinations.FILTERED_BROWSE))
    }

    @Test fun episodeTilesOnADetailPagePlayWhileSeasonsStayNavigation() {
        assertTrue(detailChildPlaysDirectly("episode"))
        listOf("show", "season", "collection", "movie").forEach { assertFalse(it, detailChildPlaysDirectly(it)) }
    }

    @Test fun seasonStripBelongsToTheShowAndHidesASingleSeason() {
        assertEquals("show", seriesKeyForSeasonStrip("season", "show", null))
        assertEquals("show", seriesKeyForSeasonStrip("episode", "season", "show"))
        assertNull(seriesKeyForSeasonStrip("show", null, null))
        assertEquals("season-2", currentSeasonKey("season", "season-2", "show"))
        assertEquals("season-2", currentSeasonKey("episode", "episode", "season-2"))
        assertEquals(emptyList<String>(), seasonStripItems(listOf(season("only", 1))).map { it.ratingKey })
        assertEquals(
            listOf("s1", "s2", "specials"),
            seasonStripItems(listOf(season("specials", 0), season("s2", 2), season("s1", 1), episode("e"))).map { it.ratingKey },
        )
    }

    @Test fun seriesChipPopsOnlyWhenThatSeriesIsThePreviousPage() {
        assertTrue(seriesDetailIsDirectlyUnder(Destinations.DETAIL, "show", "show"))
        assertFalse(seriesDetailIsDirectlyUnder(Destinations.DETAIL, "season", "show"))
        assertFalse(seriesDetailIsDirectlyUnder(Destinations.HOME, "show", "show"))
    }

    @Test fun playbackReturnsToTheEpisodeOnlyWhenItStartedFromADetailPage() {
        assertEquals(
            "episode-4",
            episodeDetailAfterPlayback(true, "episode", "episode-4", Destinations.DETAIL, "season-2"),
        )
        assertNull(episodeDetailAfterPlayback(true, "Episode", "episode-4", Destinations.DETAIL, "episode-4"))
        assertNull(episodeDetailAfterPlayback(false, "episode", "episode-4", Destinations.HOME, null))
        assertNull(episodeDetailAfterPlayback(true, "movie", "movie", Destinations.DETAIL, "movie"))
        assertNull(episodeDetailAfterPlayback(true, null, "episode-4", Destinations.DETAIL, "season-2"))
    }

    private fun season(key: String, index: Int) = MediaItem(
        ratingKey = key, title = key, type = "season", index = index,
        thumbUrl = null, artUrl = null, year = null, summary = null, tagline = null,
        durationMs = null, viewOffsetMs = null, parentTitle = "Show", parentRatingKey = "show",
        grandparentTitle = null, grandparentRatingKey = null, contentRating = null, rating = null, studio = null,
    )

    private fun episode(key: String) = MediaItem(
        ratingKey = key, title = key, type = "episode", index = 1,
        thumbUrl = null, artUrl = null, year = null, summary = null, tagline = null,
        durationMs = null, viewOffsetMs = null, parentTitle = "Season", parentRatingKey = "season",
        grandparentTitle = "Show", grandparentRatingKey = "show", contentRating = null, rating = null, studio = null,
    )

}
