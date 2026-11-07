package com.ambient.tv.data.repository

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class PlexMediaCacheTest {
    @Test fun progressPatchUpdatesAllLinkedCachedEntries() {
        val cache = PlexMediaCache()
        val item = item("1", linked = listOf("2"))
        cache.setOnDeck(listOf(item))
        cache.setSectionItems("movies", listOf(item))
        cache.register(item)

        cache.patchProgress(cache.linkedKeys("1").toSet(), 5_000L)

        assertEquals(5_000L, cache.onDeck()!!.single().viewOffsetMs)
        assertEquals(5_000L, cache.sectionItems("movies")!!.single().viewOffsetMs)
        assertEquals(5_000L, cache.mediaItem("1")!!.viewOffsetMs)
        assertEquals(5_000L, cache.mediaItem("2")!!.viewOffsetMs)
    }

    @Test fun unwatchedPatchUpdatesRegisteredItemAndEveryLinkedKey() {
        val cache = PlexMediaCache()
        val watched = item("1", linked = listOf("2")).copy(viewOffsetMs = 10_000L, viewCount = 1)
        cache.register(watched)

        cache.patchUnwatched(setOf("1", "2"))

        assertEquals(0L, cache.mediaItem("1")!!.viewOffsetMs)
        assertEquals(0, cache.mediaItem("1")!!.viewCount)
        assertEquals(0, cache.mediaItem("2")!!.viewCount)
    }

    @Test fun watchedPatchWorksWithoutDuration() {
        val cache = PlexMediaCache()
        cache.register(item("1").copy(durationMs = null, viewOffsetMs = null))

        cache.patchWatched(setOf("1"))

        assertEquals(1, cache.mediaItem("1")!!.viewCount)
    }

    @Test fun watchedPatchesMoveAShowsEpisodeCounts() {
        val cache = PlexMediaCache()
        cache.register(item("1").copy(type = "show", leafCount = 8, viewedLeafCount = 3, viewCount = 3))

        cache.patchWatched(setOf("1"))
        assertTrue(cache.mediaItem("1")!!.isWatched)
        assertEquals(0, cache.mediaItem("1")!!.unwatchedLeafCount)

        cache.patchUnwatched(setOf("1"))
        assertEquals(false, cache.mediaItem("1")!!.isWatched)
        assertEquals(8, cache.mediaItem("1")!!.unwatchedLeafCount)
    }

    @Test fun clearRemovesCachedState() {
        val cache = PlexMediaCache()
        cache.setOnDeck(listOf(item("1")))
        cache.setSectionItems("movies", listOf(item("1")))
        cache.clear()
        assertNull(cache.onDeck())
        assertNull(cache.sectionItems("movies"))
    }

    @Test fun lateResponseCannotRepopulateAnInvalidatedCache() = runBlocking {
        val cache = PlexMediaCache()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async {
            cache.withGeneration {
                started.complete(Unit)
                release.await()
                cache.setOnDeck(listOf(item("old")))
                Result.success(Unit)
            }
        }
        started.await()
        cache.clear()
        cache.setOnDeck(listOf(item("new")))
        release.complete(Unit)
        assertTrue(old.await().isFailure)
        assertEquals("new", cache.onDeck()!!.single().ratingKey)
    }

    @Test fun nestedRequestKeepsTheOriginalCacheGeneration() = runBlocking {
        val cache = PlexMediaCache()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async {
            cache.withGeneration {
                started.complete(Unit)
                release.await()
                cache.withGeneration {
                    cache.register(item("old"))
                    Result.success(Unit)
                }
            }
        }
        started.await()
        cache.clear()
        release.complete(Unit)
        assertTrue(old.await().isFailure)
        assertNull(cache.mediaItem("old"))
    }

    @Test fun mergedRegistryIsBoundedButKeepsListedAndRecentItems() {
        val cache = PlexMediaCache()
        fun unique(key: String) = item(key).copy(guid = "plex://movie/$key")
        val listed = unique("listed")
        cache.setSectionItems("movies", listOf(listed))
        cache.mergeAndRegister(listOf(listed))
        cache.register(unique("old"))
        // First prune: everything registered so far is still protected as "recent".
        cache.mergeAndRegister((0 until 9_000).map { unique("a$it") })
        assertEquals("old", cache.mediaItem("old")?.ratingKey)
        // Second prune: unlisted items from before the first prune are dropped.
        cache.mergeAndRegister((0 until 20_000).map { unique("b$it") })
        assertNull(cache.mediaItem("old"))
        assertNull(cache.mediaItem("a1"))
        assertEquals("listed", cache.mediaItem("listed")?.ratingKey)
        assertEquals("b19999", cache.mediaItem("b19999")?.ratingKey)

        cache.patchProgress(setOf("listed"), 7_000L)
        assertEquals(7_000L, cache.sectionItems("movies")!!.single().viewOffsetMs)
    }

    @Test fun expiryPreservesOfflineRowsAndCompleteEmptyClearsOnlyMembership() {
        val cache = PlexMediaCache()
        val movie = item("1")
        cache.register(movie)
        cache.setSectionItems("movies", listOf(movie))
        cache.setChildren("show", listOf(movie))
        cache.expire()
        assertNull(cache.freshSectionItems("movies", 1_000))
        assertEquals("1", cache.sectionItems("movies")!!.single().ratingKey)
        assertNull(cache.children("show", 1_000))
        assertEquals("1", cache.children("show", Long.MAX_VALUE)!!.single().ratingKey)
        cache.setSectionItems("movies", emptyList())
        assertTrue(cache.sectionItems("movies")!!.isEmpty())
        assertEquals("1", cache.mediaItem("1")!!.ratingKey)
    }

    @Test fun detailRefreshDoesNotKeepStaleTitleOrWatchedState() {
        val cache = PlexMediaCache()
        cache.register(item("1").copy(title = "Old", guid = "imdb://1", viewCount = 1))
        cache.register(item("1").copy(title = "New", guid = "imdb://1", viewCount = 0))
        assertEquals("New", cache.mediaItem("1")!!.title)
        assertEquals(0, cache.mediaItem("1")!!.viewCount)
    }

    @Test fun aLaterPreviewPairKeepsEveryPreviouslyResolvedVersion() {
        val cache = PlexMediaCache()
        val copies = (1..5).map { versioned("$it") }
        cache.registerResolved(ContentDeduplicator.mergeGroup(copies))
        val preview = cache.mergeAndRegister(copies.take(2)).single()
        assertEquals((1..5).map { "$it" }.toSet(), preview.allRatingKeys.toSet())
        assertEquals(5, preview.mediaVersions.size)
        (1..5).forEach { assertEquals(5, cache.mediaItem("$it")!!.mediaVersions.size) }
    }

    @Test fun overlappingGroupKeepsSiblingsWhenANewKeyBecomesRepresentative() {
        val cache = PlexMediaCache()
        cache.registerResolved(ContentDeduplicator.mergeGroup(listOf(versioned("1"), versioned("2"))))
        val newCopy = versioned("3").copy(addedAt = Long.MAX_VALUE)
        val refreshed = cache.mergeAndRegister(listOf(newCopy, versioned("2"))).single()
        assertEquals(setOf("1", "2", "3"), refreshed.allRatingKeys.toSet())
        assertEquals(3, refreshed.mediaVersions.size)
    }

    @Test fun newerListUrlReplacesSamePhysicalVersionWithoutDroppingDetailsOrSiblings() {
        val cache = PlexMediaCache()
        val original = versioned("1")
        cache.registerResolved(ContentDeduplicator.mergeGroup(listOf(original, versioned("2"))))
        val updated = original.copy(mediaVersions = original.mediaVersions.map {
            it.copy(streamUrl = "http://local/new-url", fileName = null)
        })
        val listed = cache.mergeAndRegister(listOf(updated)).single()
        assertEquals(2, listed.mediaVersions.size)
        val current = listed.mediaVersions.single { it.sourceRatingKey == "1" }
        assertEquals("http://local/new-url", current.streamUrl)
        assertEquals("1.mkv", current.fileName)
        val sparse = updated.copy(mediaVersions = updated.mediaVersions.map { it.copy(streamUrl = null) })
        val retained = cache.mergeAndRegister(listOf(sparse)).single()
        assertEquals(2, retained.mediaVersions.size)
        assertEquals("http://local/new-url", retained.mediaVersions.single { it.sourceRatingKey == "1" }.streamUrl)
    }

    @Test fun displayOnlyListCannotErasePlayableMetadata() {
        val cache = PlexMediaCache()
        cache.register(versioned("1"))
        val listed = cache.mergeAndRegister(listOf(item("1"))).single()
        assertEquals("http://local/1", listed.mediaVersions.single().streamUrl)
    }

    @Test fun freshMetadataStillReplacesItsOwnRemovedMediaSources() {
        val cache = PlexMediaCache()
        val original = versioned("1")
        val secondSource = original.mediaVersions.single().copy(sourceMediaIndex = 1, streamUrl = "http://local/1/second")
        cache.registerResolved(ContentDeduplicator.mergeGroup(listOf(
            original.copy(mediaVersions = original.mediaVersions + secondSource), versioned("2"),
        )))
        val updated = cache.register(original)
        assertEquals(2, updated.mediaVersions.size)
        assertEquals(listOf(0), updated.mediaVersions.filter { it.sourceRatingKey == "1" }.map { it.sourceMediaIndex })
        assertEquals(setOf("1", "2"), updated.allRatingKeys.toSet())
    }

    @Test fun refreshAndSectionInvalidationRequireDuplicateDiscoveryAgain() {
        val cache = PlexMediaCache()
        val key = item("1").canonicalContentKey
        cache.markCanonicalResolved(key)
        assertTrue(cache.isCanonicalResolved(key, 60_000))
        cache.expire()
        assertEquals(false, cache.isCanonicalResolved(key, 60_000))
        cache.markCanonicalResolved(key)
        cache.clearSection("movies")
        assertEquals(false, cache.isCanonicalResolved(key, 60_000))
    }

    @Test fun failedSecondPageCannotReplaceLastCompleteSnapshot() = runBlocking {
        val cache = PlexMediaCache()
        cache.setSectionItems("movies", listOf(item("saved")))
        val result = cache.withGeneration {
            com.ambient.tv.util.suspendResult {
                fetchAllMediaPages(2, key = { it.ratingKey }) { start, _ ->
                    if (start > 0) throw java.io.IOException("offline")
                    MediaItemsPage(listOf(item("new"), item("other")), 4)
                }.also { cache.setSectionItems("movies", it) }
            }
        }
        assertTrue(result.isFailure)
        assertEquals("saved", cache.sectionItems("movies")!!.single().ratingKey)
    }

    private fun versioned(key: String) = item(key).copy(mediaVersions = listOf(
        MediaVersion("http://local/$key", "1080p", "h264", "aac", "mkv", sourceRatingKey = key, fileName = "$key.mkv"),
    ))

    private fun item(ratingKey: String, linked: List<String> = emptyList()) = MediaItem(
        ratingKey = ratingKey,
        title = "Example",
        type = "movie",
        index = null,
        thumbUrl = null,
        artUrl = null,
        year = 2024,
        summary = null,
        tagline = null,
        durationMs = 10_000L,
        viewOffsetMs = 0L,
        parentTitle = null,
        parentRatingKey = null,
        grandparentTitle = null,
        grandparentRatingKey = null,
        contentRating = null,
        rating = null,
        studio = null,
        linkedRatingKeys = linked,
    )
}
