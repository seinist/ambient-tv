package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaTag
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Audit 25.10. M1: collections lookup off the playback path. */
class VideoScopeCollectionsTest {
    private fun item(type: String, key: String = "1", season: String? = null, series: String? = null) = MediaItem(
        ratingKey = key, title = key, type = type, index = null, thumbUrl = null, artUrl = null, year = null, summary = null,
        tagline = null, durationMs = null, viewOffsetMs = null, parentTitle = season?.let { "Staffel $it" },
        parentRatingKey = season, grandparentTitle = series?.let { "Serie $it" }, grandparentRatingKey = series,
        contentRating = null, rating = null, studio = null)

    private val tags = listOf(MediaTag("c1", "Saga"), MediaTag(null, "No id"), MediaTag("", "Blank"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test fun ownerIsTheMovieOrTheEpisodesSeries() {
        assertEquals("1", videoScopeOwner(item("movie")))
        assertEquals("show", videoScopeOwner(item("episode", season = "s1", series = "show")))
        assertNull(videoScopeOwner(item("episode")))
        assertNull(videoScopeOwner(item("track")))
    }

    @Test fun scopeCarriesSeasonSeriesAndOnlyCollectionsWithId() {
        val episode = playbackVideoScope(item("episode", season = "s1", series = "show"), "srv", tags)!!
        assertEquals(PlaybackVideoScope("srv", "s1", "Staffel s1", "show", "Serie show", listOf(MediaTag("c1", "Saga")), true), episode)
        val movie = playbackVideoScope(item("movie", season = "x"), null, tags)!!
        assertNull(movie.seasonId)
        assertFalse(movie.episode)
        assertNull(playbackVideoScope(item("clip"), "srv", tags))
    }

    @Test fun slowLookupIsNotWaitedForButCachedForTheNextPlay() = runBlocking {
        val cache = CollectionsLookupCache(scope)
        val gate = CompletableDeferred<Unit>()
        assertNull(cache.get("k", 20) { gate.await(); tags })
        assertNull(cache.cached("k"))
        gate.complete(Unit)
        withTimeout(5_000) { while (cache.cached("k") == null) delay(5) }
        assertEquals(tags, cache.get("k", 20) { error("cached") })
    }

    @Test fun failuresAreRetriedOnlyAfterTheInterval() = runBlocking {
        var now = 0L
        val cache = CollectionsLookupCache(scope, retryAfterMs = 1_000, clock = { now })
        val calls = AtomicInteger()
        assertNull(cache.get("k", 1_000) { calls.incrementAndGet(); null })
        assertNull(cache.get("k", 1_000) { calls.incrementAndGet(); error("boom") })
        assertEquals(1, calls.get())
        now = 1_000
        assertNull(cache.get("k", 1_000) { calls.incrementAndGet(); error("boom") })
        assertEquals(2, calls.get())
        now = 2_000
        assertEquals(tags, cache.get("k", 1_000) { calls.incrementAndGet(); tags })
        assertEquals(1, cache.size())
    }

    @Test fun concurrentRequestsShareOneLookupAndTheCacheIsBounded() = runBlocking {
        val cache = CollectionsLookupCache(scope, maxEntries = 3)
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val waiting = (1..5).map { async { cache.get("k", 5_000) { calls.incrementAndGet(); gate.await(); tags } } }
        delay(50)
        gate.complete(Unit)
        assertTrue(waiting.awaitAll().all { it == tags })
        assertEquals(1, calls.get())
        for (i in 1..5) cache.get("n$i", 1_000) { listOf(MediaTag("$i", "$i")) }
        assertEquals(3, cache.size())
        assertNull(cache.cached("k"))
        assertNotNull(cache.cached("n5"))
    }
}
