package com.ambient.tv.ui.games

import org.junit.Assert.*
import org.junit.Test

class GameCheatUpdatesTest {
    private val queued = mutableListOf<() -> Unit>()
    private val native = mutableListOf<String>()
    private val updates = GameCheatUpdates(
        enqueue = queued::add,
        reset = { native += "reset" },
        enable = { index, code -> native += "$index:$code" },
    )

    @Test fun callerDoesNotRunNativeOperationsAndOneBatchAppliesAllCodesInOrder() {
        updates.apply(listOf("first", "second", "third"))
        assertTrue(native.isEmpty())
        assertEquals(1, queued.size)
        queued.single().invoke()
        assertEquals(listOf("reset", "0:first", "1:second", "2:third"), native)
    }

    @Test fun aPolicyChangeSupersedesPreviouslyQueuedEnabledCheats() {
        updates.apply(listOf("old"))
        updates.apply(emptyList())
        queued.forEach { it() }
        assertEquals(listOf("reset"), native)
    }

    @Test fun duplicateRequestsDoNotQueueOrReapplyTheSameNativeState() {
        repeat(4) { updates.apply(listOf("same")) }
        assertEquals(1, queued.size)
        queued.single().invoke()
        updates.apply(listOf("same"))
        assertEquals(1, queued.size)
    }

    @Test fun requestedCodesAreAnImmutableSnapshot() {
        val codes = mutableListOf("original")
        updates.apply(codes)
        codes[0] = "mutated"
        queued.single().invoke()
        assertEquals(listOf("reset", "0:original"), native)
    }

    @Test fun disposalRejectsPendingAndFutureWork() {
        updates.apply(listOf("old"))
        updates.close()
        queued.single().invoke()
        updates.apply(listOf("new"))
        assertTrue(native.isEmpty())
        assertEquals(1, queued.size)
    }

    @Test fun aPolicyUpdateDuringNativeWorkStopsTheOldBatchBeforeArmingMoreCodes() {
        lateinit var changing: GameCheatUpdates
        changing = GameCheatUpdates(queued::add,
            reset = { native += "reset"; if (native.size == 1) changing.apply(emptyList()) },
            enable = { _, code -> native += code })
        changing.apply(listOf("must-not-be-armed"))
        queued[0]()
        queued[1]()
        assertEquals(listOf("reset", "reset"), native)
    }
}
