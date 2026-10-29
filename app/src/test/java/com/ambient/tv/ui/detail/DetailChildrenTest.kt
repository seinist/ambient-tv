package com.ambient.tv.ui.detail

import com.ambient.tv.domain.model.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailChildrenTest {
    @Test fun aCachedSingleSeasonNeverAppearsAsATemporaryShowRow() {
        val show = media("show", "show")
        val season = media("season", "season", "show")
        val lookedUp = mutableListOf<String>()
        val children = cachedDetailChildren(show, "show") { key ->
            lookedUp += key
            if (key == "show") listOf(season) else null
        }
        assertTrue(children.isEmpty())
        assertEquals(listOf("show", "season"), lookedUp)
    }

    @Test fun cachedEpisodesAreShownImmediatelyForASingleSeason() {
        val season = media("season", "season", "show")
        val episodes = listOf(media("episode", "episode-1", "season"), media("episode", "episode-2", "season"))
        val cache = mapOf("show" to listOf(season), "season" to episodes)
        assertSame(episodes, cachedDetailChildren(media("show", "show"), "show", cache::get))
    }

    @Test fun multipleSeasonsAndCollectionMembersAreNotFlattened() {
        val seasons = listOf(media("season", "s1", "show"), media("season", "s2", "show"))
        val showLookups = mutableListOf<String>()
        assertSame(seasons, cachedDetailChildren(media("show", "show"), "show") { key ->
            showLookups += key
            seasons
        })
        assertEquals(listOf("show"), showLookups)

        val collectionLookups = mutableListOf<String>()
        val members = listOf(seasons.first())
        assertSame(members, cachedDetailChildren(media("collection", "collection"), "collection") { key ->
            collectionLookups += key
            members
        })
        assertEquals(listOf("collection"), collectionLookups)
    }

    @Test fun episodeRowsUseTheirParentAndMissingParentsDoNotStartAnUnfinishableLoad() {
        val siblings = listOf(media("episode", "episode", "season"))
        val lookedUp = mutableListOf<String>()
        assertSame(siblings, cachedDetailChildren(siblings.first(), "episode-route") { key ->
            lookedUp += key
            siblings
        })
        assertEquals(listOf("season"), lookedUp)
        assertNull(detailChildrenKey(media("episode", "episode"), "episode"))
        assertNull(detailChildrenKey(media("episode", "episode", " "), "episode"))
        assertNull(detailChildrenKey(media("movie", "movie"), "movie"))
        assertEquals("route-key", detailChildrenKey(media("show", "canonical-key"), "route-key"))
    }

    @Test fun loadingASingleSeasonWaitsForEpisodesBeforeReturningAnyRow() = runBlocking {
        val episodeResponse = CompletableDeferred<Result<List<MediaItem>>>()
        val lookedUp = mutableListOf<String>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            loadDetailChildren(media("show", "show"), "show") { key ->
                lookedUp += key
                if (key == "show") Result.success(listOf(media("season", "season", "show")))
                else episodeResponse.await()
            }
        }
        assertEquals(listOf("show", "season"), lookedUp)
        assertFalse(pending.isCompleted)
        val episodes = listOf(media("episode", "episode", "season"))
        episodeResponse.complete(Result.success(episodes))
        assertSame(episodes, pending.await().getOrThrow())
    }

    @Test fun directChildrenAreLoadedOnceForCollectionsAndMultipleSeasons() = runBlocking {
        listOf("collection" to listOf(media("season", "s1")),
            "show" to listOf(media("season", "s1"), media("season", "s2"))).forEach { (type, children) ->
            val lookedUp = mutableListOf<String>()
            val result = loadDetailChildren(media(type, "root"), "root") { key ->
                lookedUp += key
                Result.success(children)
            }
            assertSame(children, result.getOrThrow())
            assertEquals(listOf("root"), lookedUp)
        }
    }

    @Test fun failedSeasonOrEpisodeLoadsStayFailuresInsteadOfEmptyRows() = runBlocking {
        val failure = IllegalStateException("offline")
        var requests = 0
        val seasonsFailed = loadDetailChildren(media("show", "show"), "show") {
            requests++
            Result.failure(failure)
        }
        assertSame(failure, seasonsFailed.exceptionOrNull())
        assertEquals(1, requests)
        val episodesFailed = loadDetailChildren(media("show", "show"), "show") { key ->
            if (key == "show") Result.success(listOf(media("season", "season", "show")))
            else Result.failure(failure)
        }
        assertSame(failure, episodesFailed.exceptionOrNull())
    }

    @Test fun cancellingAnEpisodeLoadDoesNotReturnAnIntermediateSeason() = runBlocking {
        val episodeResponse = CompletableDeferred<Result<List<MediaItem>>>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            loadDetailChildren(media("show", "show"), "show") { key ->
                if (key == "show") Result.success(listOf(media("season", "season", "show")))
                else episodeResponse.await()
            }
        }
        pending.cancel()
        pending.join()
        assertTrue(pending.isCancelled)
        assertFalse(episodeResponse.isCompleted)
    }

    private fun media(type: String, key: String, parent: String? = null) = MediaItem(
        ratingKey = key, title = key, type = type, index = null,
        thumbUrl = null, artUrl = null, year = null, summary = null, tagline = null,
        durationMs = null, viewOffsetMs = null, parentTitle = null, parentRatingKey = parent,
        grandparentTitle = null, grandparentRatingKey = null, contentRating = null, rating = null, studio = null,
    )
}
