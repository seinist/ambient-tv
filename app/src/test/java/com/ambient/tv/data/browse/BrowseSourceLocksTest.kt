package com.ambient.tv.data.browse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class BrowseSourceLocksTest {
    @Test fun refreshWaitsForTheWholeRemoteWriteBeforeInvalidatingItsGeneration() = runBlocking {
        val locks = BrowseSourceLocks()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val write = launch {
            locks.withSources(setOf("plex")) {
                order += "first edition"
                entered.complete(Unit)
                finish.await()
                order += "second edition"
            }
        }
        entered.await()
        val refresh = async { locks.withSources(setOf("plex")) { order += "expire" } }
        yield()
        assertFalse(refresh.isCompleted)
        finish.complete(Unit)
        write.join()
        refresh.await()
        assertEquals(listOf("first edition", "second edition", "expire"), order)
    }

    @Test fun unrelatedSourceCanRefreshWhileAnotherServerHasAnActiveWrite() = runBlocking {
        val locks = BrowseSourceLocks()
        val entered = CompletableDeferred<Unit>()
        val write = launch { locks.withSources(setOf("plex")) { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        try {
            assertEquals("fresh", locks.withSources(setOf("jellyfin")) { "fresh" })
        } finally { write.cancelAndJoin() }
    }

    @Test fun wholeNetworkRefreshAlsoWaitsForAConcreteProfileWrite() = runBlocking {
        val locks = BrowseSourceLocks()
        val entered = CompletableDeferred<Unit>()
        val write = launch { locks.withSources(setOf("emby:bedroom:user")) { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        val refresh = async { locks.withSources(setOf("*")) { true } }
        yield()
        assertFalse(refresh.isCompleted)
        write.cancelAndJoin()
        assertTrue(refresh.await())
    }

    @Test fun cancellationReleasesEveryAcquiredSourceLock() = runBlocking {
        val locks = BrowseSourceLocks()
        val entered = CompletableDeferred<Unit>()
        val write = launch {
            locks.withSources(setOf("plex", "jellyfin")) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        write.cancelAndJoin()
        assertEquals(7, locks.withSources(setOf("jellyfin", "plex")) { 7 })
    }

    @Test fun failureDoesNotLeaveSubsequentEntriesWaitingForever() = runBlocking {
        val locks = BrowseSourceLocks()
        try { locks.withSources(setOf("plex")) { error("Write failed") } }
        catch (_: IllegalStateException) { }
        assertTrue(locks.withSources(setOf("plex")) { true })
    }
}
