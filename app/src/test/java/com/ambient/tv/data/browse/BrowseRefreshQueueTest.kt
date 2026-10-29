package com.ambient.tv.data.browse

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class BrowseRefreshQueueTest {
    @Test fun recoveryDuringAnEntryWaitForMutationJoinsTheSameActualExpiry() = runBlocking {
        val queue = BrowseRefreshQueue()
        val locks = BrowseSourceLocks()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val write = launch { locks.withSources(setOf("plex")) { entered.complete(Unit); finish.await() } }
        entered.await()
        val entry = queue.enter(setOf("plex"))
        val expired = mutableListOf<Set<String>>()
        val clock = BrowseReconnectRevisions()
        val work = async { queue.preparePending(locks, { expired += it }, clock::publish) }
        yield()
        queue.recover(setOf("plex"))
        finish.complete(Unit)
        write.join()
        work.await()
        assertEquals(listOf(setOf("plex")), expired)
        assertEquals(1L, entry.await()["plex"])
        assertTrue(queue.drain().sources.isEmpty())
    }

    @Test fun aBroaderRecoveryDuringTheWriteReacquiresLocksBeforeTheSingleExpiry() = runBlocking {
        val queue = BrowseRefreshQueue()
        val locks = BrowseSourceLocks()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val write = launch { locks.withSources(setOf("plex")) { entered.complete(Unit); finish.await() } }
        entered.await()
        val entry = queue.enter(setOf("plex"))
        val expired = mutableListOf<Set<String>>()
        val clock = BrowseReconnectRevisions()
        val work = async { queue.preparePending(locks, { expired += it }, clock::publish) }
        yield()
        queue.recover(setOf("*"))
        val other = queue.enter(setOf("jellyfin"))
        finish.complete(Unit)
        write.join()
        work.await()
        assertEquals(listOf(setOf("plex", "jellyfin", "*")), expired)
        assertEquals(1L, entry.await()["*"])
        assertEquals(entry.await(), other.await())
    }

    @Test fun changingFolderInterestsDoesNotRestartTheFoldersOwnLoad() {
        val observation = BrowseRefreshObservation(setOf("files"), mapOf("files:nas-a" to 4L))
        assertFalse(observation.changed(setOf("files:nas-a"), mapOf("files:nas-a" to 4L)))
        assertFalse(observation.changed(setOf("files:nas-a"), mapOf("files:nas-a" to 4L, "files:nas-b" to 5L)))
        assertTrue(observation.changed(setOf("files:nas-a"), mapOf("files:nas-a" to 6L, "files:nas-b" to 5L)))
        assertFalse(observation.changed(setOf("files:nas-a"), mapOf("files:nas-a" to 6L)))
    }

    @Test fun localFoldersIgnoreRecoveryAndReturningRemoteFoldersAdoptTheirOwnBaseline() {
        val observation = BrowseRefreshObservation(setOf("files:nas-a"), mapOf("files:nas-a" to 9L))
        assertFalse(observation.changed(emptySet(), mapOf("*" to 10L)))
        assertFalse(observation.changed(setOf("files:nas-b"), mapOf("files:nas-a" to 9L, "files:nas-b" to 3L)))
        assertTrue(observation.changed(setOf("files:nas-b"), mapOf("files:nas-b" to 11L)))
    }

    @Test fun simultaneousEntriesExpireEachSourceOnceWithoutAnnouncingRecovery() = runBlocking {
        val queue = BrowseRefreshQueue()
        val home = queue.enter(setOf("plex", "tvheadend"))
        val detail = queue.enter(setOf("plex"))
        val batch = queue.drain()
        assertEquals(setOf("plex", "tvheadend"), batch.sources)
        assertTrue(batch.recoveredSources.isEmpty())
        assertFalse(home.isCompleted)
        assertFalse(detail.isCompleted)
        batch.complete(mapOf("plex" to 7L))
        assertEquals(mapOf("plex" to 7L), home.await())
        assertEquals(home.await(), detail.await())
    }

    @Test fun entryAndReconnectShareExpiryAndUseThePublishedRecoveryAsTheirBaseline() = runBlocking {
        val queue = BrowseRefreshQueue()
        val revisions = BrowseReconnectRevisions()
        revisions.publish(setOf("plex"))
        val entry = queue.enter(setOf("plex"))
        queue.recover(setOf("plex", "jellyfin"))
        val batch = queue.drain()
        assertEquals(setOf("plex", "jellyfin"), batch.sources)
        assertEquals(batch.sources, batch.recoveredSources)
        val published = revisions.publish(batch.recoveredSources)
        batch.complete(published)
        assertEquals(2L, browseReconnectVersion(entry.await(), setOf("plex")))
        assertEquals(2L, browseReconnectVersion(published, setOf("plex")))
    }

    @Test fun leavingBeforePreparationDoesNotExpireAnAbandonedDestination() {
        val queue = BrowseRefreshQueue()
        queue.enter(setOf("plex")).cancel()
        assertTrue(queue.drain().sources.isEmpty())
    }

    @Test fun abandoningAnEntryDoesNotDiscardAnIndependentRecovery() {
        val queue = BrowseRefreshQueue()
        queue.enter(setOf("plex")).cancel()
        queue.recover(setOf("plex"))
        val batch = queue.drain()
        assertEquals(setOf("plex"), batch.sources)
        assertEquals(setOf("plex"), batch.recoveredSources)
    }

    @Test fun requestsArrivingDuringExpiryStayPendingForTheNextBatch() = runBlocking {
        val queue = BrowseRefreshQueue()
        val first = queue.enter(setOf("plex"))
        val batch = queue.drain()
        val second = queue.enter(setOf("tvheadend"))
        queue.recover(setOf("spotify"))
        batch.complete(emptyMap())
        assertTrue(first.isCompleted)
        assertFalse(second.isCompleted)
        val next = queue.drain()
        assertEquals(setOf("tvheadend", "spotify"), next.sources)
        assertEquals(setOf("spotify"), next.recoveredSources)
        next.complete(mapOf("spotify" to 3L))
        assertEquals(mapOf("spotify" to 3L), second.await())
    }

    @Test fun returningToTheSameSourcePreparesItAgainWithoutInventingAReconnect() = runBlocking {
        val queue = BrowseRefreshQueue()
        repeat(2) {
            val entry = queue.enter(setOf("plex"))
            val batch = queue.drain()
            assertEquals(setOf("plex"), batch.sources)
            assertTrue(batch.recoveredSources.isEmpty())
            batch.complete(mapOf("plex" to 1L))
            assertEquals(mapOf("plex" to 1L), entry.await())
        }
    }
}
