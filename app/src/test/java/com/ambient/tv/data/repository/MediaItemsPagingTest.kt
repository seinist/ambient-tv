package com.ambient.tv.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaItemsPagingTest {
    @Test fun loadsMoreThanFiveHundredItemsWithoutTruncation() = runBlocking {
        val source = (0 until 753).map(Int::toString)
        val offsets = mutableListOf<Int>()
        val result = fetchAllMediaPages(100, key = { it }) { start, size ->
            offsets += start
            MediaItemsPage(source.drop(start).take(size), source.size)
        }
        assertEquals(source, result)
        assertEquals((0..7).map { it * 100 }, offsets)
    }

    @Test fun advancesByRawCountBeforeDeduplicatingOverlappingItems() = runBlocking {
        val offsets = mutableListOf<Int>()
        val result = fetchAllMediaPages(3, key = { it }) { start, _ ->
            offsets += start
            MediaItemsPage(if (start == 0) listOf("a", "b", "b") else listOf("b", "c"), 5)
        }
        assertEquals(listOf("a", "b", "c"), result)
        assertEquals(listOf(0, 3), offsets)
    }

    @Test fun keepsReadingCappedShortPagesWhenTheServerOmitsItsTotal() = runBlocking {
        val source = listOf("a", "b", "c", "d", "e")
        val offsets = mutableListOf<Int>()
        val result = fetchAllMediaPages(100, key = { it }) { start, _ ->
            offsets += start
            MediaItemsPage(source.drop(start).take(2))
        }
        assertEquals(source, result)
        assertEquals(listOf(0, 2, 4, 5), offsets)
    }

    @Test fun rejectsIgnoredOffsetsRatherThanCachingPartialSuccess() = runBlocking {
        var calls = 0
        val error = runCatching {
            fetchAllMediaPages(2, key = { it }) { _, _ ->
                calls++
                MediaItemsPage(listOf("a", "b"), 6)
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(2, calls)
    }

    @Test fun rejectsAnEmptyPageBeforeTheAdvertisedEnd() = runBlocking {
        val error = runCatching {
            fetchAllMediaPages(2, key = { it }) { start, _ ->
                MediaItemsPage(if (start == 0) listOf("a", "b") else emptyList(), 5)
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test fun laterRequestFailuresAndCancellationPropagate() = runBlocking {
        for (failure in listOf(IllegalStateException("Disconnected"), CancellationException("Left collection"))) {
            val error = runCatching {
                fetchAllMediaPages(2, key = { it }) { start, _ ->
                    if (start > 0) throw failure
                    MediaItemsPage(listOf("a", "b"), 4)
                }
            }.exceptionOrNull()
            assertSame(failure, error)
        }
    }
    @Test fun changedTotalsAndSafetyLimitAreNotSuccessfulSnapshots() = runBlocking {
        val changed = runCatching {
            fetchAllMediaPages(2, key = { it }) { start, _ ->
                MediaItemsPage(listOf("$start", "${start + 1}"), if (start == 0) 4 else 3)
            }
        }
        assertTrue(changed.isFailure)
        val capped = runCatching {
            fetchAllMediaPages(2, key = { it }, maxItems = 2) { start, _ ->
                MediaItemsPage(listOf("$start", "${start + 1}"), 10)
            }
        }
        assertTrue(capped.isFailure)
    }

    @Test fun truncatedPagingReturnsTheFirstItemsInsteadOfFailing() = runBlocking {
        val items = fetchAllMediaPages(2, key = { it }, maxItems = 4, truncateAtLimit = true) { start, _ ->
            MediaItemsPage(listOf("$start", "${start + 1}"), 10)
        }
        assertEquals(listOf("0", "1", "2", "3"), items)
    }

    @Test fun partialLibraryFailuresKeepTheOtherLibraries() {
        val ok = listOf(Result.success(listOf("a")), Result.failure<List<String>>(IllegalStateException("down")), Result.success(listOf("b")))
        assertEquals(listOf("a", "b"), ok.successfulOrThrow())
        val failure = IllegalStateException("all down")
        val all = listOf(Result.failure<List<String>>(failure), Result.failure(IllegalStateException("x")))
        assertSame(failure, runCatching { all.successfulOrThrow() }.exceptionOrNull())
        assertEquals(emptyList<String>(), emptyList<Result<List<String>>>().successfulOrThrow())
    }
}
