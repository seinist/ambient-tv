package com.ambient.tv.data.repository

import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class PlexDuplicateResolverTest {
    private val section = LibrarySection("movies", "Movies", "movie", null, null)

    @Test fun restoredLinkedKeysLoadEveryPhysicalVersionBeyondTheOpenedPair() = runBlocking {
        val copies = (1..5).map { movie("$it", versions = if (it == 1) 2 else 1) }
        val snapshot = ContentDeduplicator.mergeGroup(copies).copy(mediaVersions = emptyList())
        val requested = mutableListOf<String>()
        val result = resolvePlexDuplicateVersions(
            copies.first(), snapshot, listOf(section),
            sectionItems = { listOf(copies.first()) },
            metadata = { key -> requested += key; copies.first { it.ratingKey == key } },
        )
        assertTrue(result.complete)
        assertEquals(setOf("2", "3", "4", "5"), requested.toSet())
        assertEquals((1..5).map { "$it" }.toSet(), result.item.allRatingKeys.toSet())
        assertEquals(6, result.item.mediaVersions.size)
        assertEquals(setOf(0, 1), result.item.mediaVersions.filter { it.sourceRatingKey == "1" }.map { it.sourceMediaIndex }.toSet())
    }

    @Test fun discoveredSiblingLoadsAllItsOwnVersionsAndKeepsOpenedRouteIdentity() = runBlocking {
        val opened = movie("hd", versions = 2)
        val sibling = movie("uhd", versions = 3).copy(
            mediaVersions = movie("uhd", versions = 3).mediaVersions.map { it.copy(width = 3840, height = 2160) },
        )
        val result = resolvePlexDuplicateVersions(
            opened, null, listOf(section),
            sectionItems = { listOf(opened, sibling.copy(mediaVersions = emptyList())) },
            metadata = { sibling },
        )
        assertTrue(result.complete)
        assertEquals("hd", result.item.ratingKey)
        assertEquals(5, result.item.mediaVersions.size)
        assertEquals(setOf(0, 1, 2), result.item.mediaVersions.filter { it.sourceRatingKey == "uhd" }.map { it.sourceMediaIndex }.toSet())
    }

    @Test fun sameTitleWithDifferentGuidIsNotConsideredAnotherVersion() = runBlocking {
        val opened = movie("1")
        val unrelated = movie("2").copy(guid = "imdb://other")
        val result = resolvePlexDuplicateVersions(
            opened, null, listOf(section), sectionItems = { listOf(opened, unrelated) },
            metadata = { error("Unrelated title must not be loaded") },
        )
        assertEquals(listOf("1"), result.item.allRatingKeys)
        assertEquals(1, result.item.mediaVersions.size)
    }

    @Test fun deletedAndRematchedSiblingsDoNotReappearWhenResolutionIsRegistered() = runBlocking {
        val copies = (1..5).map { movie("$it") }
        val cached = ContentDeduplicator.mergeGroup(copies)
        val cache = PlexMediaCache()
        cache.registerResolved(cached)
        val result = resolvePlexDuplicateVersions(
            copies.first(), cached, listOf(section), sectionItems = { listOf(copies.first()) },
            metadata = { key ->
                when (key) {
                    "2" -> movie(key).copy(guid = "imdb://rematched")
                    "3" -> null
                    "4" -> throw http(404)
                    else -> throw http(410)
                }
            },
        )
        assertTrue(result.complete)
        assertEquals(listOf("1"), cache.registerResolved(result.item).allRatingKeys)
        assertEquals(listOf("1"), cache.register(copies.first()).allRatingKeys)
        assertEquals(1, cache.mediaItem("1")!!.mediaVersions.size)
        (2..5).forEach { assertNull(cache.mediaItem("$it")) }
    }

    @Test fun failedSiblingKeepsKnownVersionAndCanBeRetried() = runBlocking {
        val opened = movie("1")
        val sibling = movie("2")
        val cached = ContentDeduplicator.mergeGroup(listOf(opened, sibling))
        var available = false
        suspend fun resolve() = resolvePlexDuplicateVersions(
            opened, cached, listOf(section), sectionItems = { listOf(opened, sibling) },
            metadata = { if (available) sibling.copy(mediaVersions = movie("2", 3).mediaVersions) else throw IOException("starting") },
        )
        val pending = resolve()
        assertFalse(pending.complete)
        assertEquals(2, pending.item.mediaVersions.size)
        available = true
        val completed = resolve()
        assertTrue(completed.complete)
        assertEquals(4, completed.item.mediaVersions.size)
    }

    @Test fun failedLibraryOrAuthorizationDoesNotMarkDiscoveryComplete() = runBlocking {
        val opened = movie("1")
        val sibling = movie("2")
        val cached = ContentDeduplicator.mergeGroup(listOf(opened, sibling))
        val failedLibrary = resolvePlexDuplicateVersions(
            opened, cached, listOf(section), sectionItems = { throw IOException("offline") }, metadata = { sibling },
        )
        assertFalse(failedLibrary.complete)
        assertEquals(2, failedLibrary.item.mediaVersions.size)
        val deniedMetadata = resolvePlexDuplicateVersions(
            opened, cached, listOf(section), sectionItems = { listOf(opened, sibling) }, metadata = { throw http(403) },
        )
        assertFalse(deniedMetadata.complete)
        assertEquals(2, deniedMetadata.item.mediaVersions.size)
    }

    @Test fun cancellationDoesNotBecomeACompletedOrPartialDiscovery() = runBlocking {
        try {
            resolvePlexDuplicateVersions(
                movie("1"), null, listOf(section),
                sectionItems = { listOf(movie("2")) }, metadata = { throw CancellationException() },
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

    private fun movie(key: String, versions: Int = 1) = MediaItem(
        ratingKey = key, title = "Example", type = "movie", index = null, thumbUrl = null, artUrl = null,
        year = 2024, summary = null, tagline = null, durationMs = 10_000L, viewOffsetMs = 0L,
        parentTitle = null, parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
        contentRating = null, rating = null, studio = null, guid = "imdb://same-film",
        mediaVersions = (0 until versions).map { index ->
            MediaVersion(
                "http://local/$key/$index", "1080p", "h264", "aac", "mkv",
                width = 1920, height = 1080, sourceRatingKey = key, sourceMediaIndex = index,
                fileName = "Movie-$key-$index.mkv",
            )
        },
    )
}
