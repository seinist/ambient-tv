package com.ambient.tv.games.metadata

import org.junit.Assert.*
import org.junit.Test

class GameMetadataBatchQueueTest {
    private class Catalog(size: Int = 100, pageSize: Int = 64, maxAhead: Int = 128) {
        val ids = MutableList(size) { it.toString().padStart(3, '0') }
        var priorities = emptyList<String>()
        val lookups = mutableListOf<String>()
        val pageLimits = mutableListOf<Int>()
        val queue = GameMetadataBatchQueue(
            fileId = { id: String -> id },
            prioritizedFileIds = { priorities },
            page = { after, limit ->
                pageLimits += limit
                ids.filter { after == null || it > after }.take(limit)
            },
            find = { id, after ->
                lookups += id
                ids.firstOrNull { it == id && (after == null || it > after) }
            },
            pageSize = pageSize,
            maxAhead = maxAhead,
        )

        fun drain(): List<String> = generateSequence { queue.next() }.toList()
    }

    @Test fun focusedGameBeyondTheFirstPageRunsBeforeSequentialWork() {
        val catalog = Catalog()
        catalog.priorities = listOf("099", "098")
        assertEquals("099", catalog.queue.next())
        assertEquals("Only one indexed lookup is needed for a distant focus", listOf("099"), catalog.lookups)
        assertTrue("Promotion does not materialize earlier pages", catalog.pageLimits.isEmpty())
    }

    @Test fun nextSelectionUsesTheNewFocusAndBackgroundStillProgresses() {
        val catalog = Catalog()
        catalog.priorities = listOf("099", "098", "097", "096")
        val attempted = mutableListOf(catalog.queue.next()!!)
        catalog.priorities = listOf("095", "098", "097", "096")
        attempted += List(4) { catalog.queue.next()!! }
        assertEquals(listOf("099", "095", "098", "000", "097"), attempted)
        attempted += catalog.drain()
        assertEquals(catalog.ids.toSet(), attempted.toSet())
        assertEquals("Every attempt counts once even when its normal page arrives", catalog.ids.size, attempted.size)
        assertTrue(catalog.pageLimits.all { it == 64 })
    }

    @Test fun currentPagePromotionsAndRepeatedInvalidAttemptsAreReturnedOnlyOnce() {
        val catalog = Catalog(6)
        assertEquals("000", catalog.queue.next())
        catalog.priorities = listOf("004", "004", "002")
        // Callers record success or a recoverable failure after receiving a game. Both outcomes
        // consume the same attempt, so a bad ROM cannot be retried by its still-visible card.
        assertEquals("004", catalog.queue.next())
        assertEquals("002", catalog.queue.next())
        assertEquals(listOf("001", "003", "005"), catalog.drain())
        assertEquals(listOf("004", "002"), catalog.lookups)
    }

    @Test fun fullAheadLedgerDrainsNormallyInsteadOfGrowingWithTheCatalog() {
        val catalog = Catalog(maxAhead = 2)
        catalog.priorities = listOf("099", "098")
        val attempted = mutableListOf(catalog.queue.next()!!, catalog.queue.next()!!)
        catalog.priorities = listOf("097")
        attempted += List(10) { catalog.queue.next()!! }
        assertEquals((0 until 10).map { it.toString().padStart(3, '0') }, attempted.drop(2))
        assertEquals("The full ledger prevents more out-of-order requests", listOf("099", "098"), catalog.lookups)
        attempted += catalog.drain()
        assertEquals(catalog.ids.toSet(), attempted.toSet())
        assertEquals(catalog.ids.size, attempted.size)
    }

    @Test fun removingAnAheadRomCannotPermanentlyFillTheLedger() {
        val catalog = Catalog(pageSize = 1, maxAhead = 2)
        catalog.priorities = listOf("030", "040")
        assertEquals("030", catalog.queue.next())
        assertEquals("040", catalog.queue.next())
        catalog.ids.remove("030")
        catalog.priorities = listOf("099")
        assertEquals((0..29).map { it.toString().padStart(3, '0') }, List(30) { catalog.queue.next()!! })
        assertEquals("031", catalog.queue.next())
        assertEquals("A skipped removed ID frees its bounded slot", "099", catalog.queue.next())
    }

    @Test fun foreignOrRemovedPrioritiesDoNotCauseRepeatedPointQueries() {
        val catalog = Catalog(5)
        catalog.priorities = listOf("outside", "removed")
        assertEquals(catalog.ids, catalog.drain())
        assertEquals(listOf("outside", "removed"), catalog.lookups)
    }
}
