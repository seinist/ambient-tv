package com.ambient.tv.games.metadata

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real coroutine scheduling and cancellation, without a timer or Android test runtime. */
class GameArtworkRecoveryTest {
    private data class Cover(val fileId: String, val reference: String = "cover-v1")

    private class Fixture(
        private val scope: CoroutineScope,
        maxOwners: Int = 4,
        maxRemembered: Int = 8,
        retryAfterMillis: Long = 60_000L,
        realClock: Boolean = false,
    ) {
        val priority = GameArtworkPriority(maxOwners)
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val targets = mutableMapOf<String, Cover>()
        val present = mutableSetOf<String>()
        val lookedUp = mutableListOf<String>()
        val restored = mutableListOf<Cover>()
        val failures = mutableListOf<Exception>()
        val started = Channel<Cover>(Channel.UNLIMITED)
        val failed = Channel<Exception>(Channel.UNLIMITED)
        var now = 1_000L
        var lookup: suspend (String) -> Unit = {}
        var restore: suspend (Cover) -> Boolean = { true }
        lateinit var worker: Job
            private set

        private val recovery = GameArtworkRecovery(
            priority = priority,
            changes = changes,
            pending = { fileId: String ->
                lookedUp += fileId
                lookup(fileId)
                targets[fileId].takeUnless { fileId in present }
            },
            identity = { cover: Cover -> "${cover.fileId}:${cover.reference}" },
            restore = { cover: Cover ->
                restored += cover
                started.send(cover)
                restore(cover)
            },
            onFailure = { error -> failures += error; failed.trySend(error) },
            nowMillis = { if (realClock) System.nanoTime() / 1_000_000L else now },
            retryAfterMillis = retryAfterMillis,
            maxRemembered = maxRemembered,
        )

        fun show(fileId: String, focused: Boolean = false, owner: Any = fileId) {
            targets.putIfAbsent(fileId, Cover(fileId))
            priority.update(owner, fileId, visible = true, focused = focused)
        }

        fun hide(owner: Any) = priority.remove(owner)

        suspend fun start() {
            worker = scope.launch { recovery.run() }
            withTimeout(3_000) { changes.subscriptionCount.first { it > 0 } }
            settle()
        }

        suspend fun pulse() { changes.emit(Unit); settle() }

        suspend fun nextStarted(): Cover = withTimeout(3_000) { started.receive() }

        suspend fun nextFailure(): Exception = withTimeout(3_000) { failed.receive() }

        /** All work uses runBlocking's single event loop; drain queued continuations, not time. */
        suspend fun settle() { repeat(16) { yield() } }

        suspend fun stop() { worker.cancelAndJoin() }
    }

    @Test fun idleCatalogAndRevisionsDoNotQueryOrRestoreWithoutVisibleCards() = runBlocking {
        val fixture = Fixture(this)
        fixture.targets["offscreen"] = Cover("offscreen")
        fixture.start()
        try {
            repeat(4) { fixture.pulse() }
            assertTrue(fixture.lookedUp.isEmpty())
            assertTrue(fixture.restored.isEmpty())
            assertTrue(fixture.worker.isActive)
        } finally { fixture.stop() }
    }

    @Test fun presentAndUnidentifiedVisibleCoversNeverStartDownloads() = runBlocking {
        val fixture = Fixture(this)
        fixture.show("present")
        fixture.present += "present"
        fixture.priority.update("unknown", "unknown", visible = true, focused = true)
        fixture.start()
        try {
            fixture.pulse()
            assertEquals(setOf("present", "unknown"), fixture.lookedUp.toSet())
            assertTrue(fixture.restored.isEmpty())
        } finally { fixture.stop() }
    }

    @Test fun latestFocusOrdersTheNextRequestWithoutCancellingTheActiveDownload() = runBlocking {
        val fixture = Fixture(this)
        val releaseFirst = CompletableDeferred<Unit>()
        var firstCancelled = false
        fixture.restore = { cover ->
            if (cover.fileId == "a") try { releaseFirst.await() }
            finally { firstCancelled = !currentCoroutineContext().job.isActive }
            true
        }
        fixture.show("a", focused = true)
        fixture.show("b")
        fixture.show("c")
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.show("a", focused = false)
            fixture.show("c", focused = true)
            fixture.pulse()
            assertEquals(listOf("a"), fixture.restored.map { it.fileId })
            assertFalse(firstCancelled)

            releaseFirst.complete(Unit)
            assertEquals("c", fixture.nextStarted().fileId)
            assertEquals("b", fixture.nextStarted().fileId)
            fixture.settle()
            assertFalse(firstCancelled)
            assertEquals(listOf("a", "c", "b"), fixture.restored.map { it.fileId })
        } finally { fixture.stop() }
    }

    @Test fun cardsThatLeaveWhileAnotherDownloadRunsNeverAcquireWork() = runBlocking {
        val fixture = Fixture(this)
        val releaseFirst = CompletableDeferred<Unit>()
        fixture.restore = { if (it.fileId == "a") releaseFirst.await(); true }
        fixture.show("a", focused = true)
        fixture.show("b")
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.hide("b")
            fixture.show("c", focused = true)
            releaseFirst.complete(Unit)
            assertEquals("c", fixture.nextStarted().fileId)
            fixture.settle()
            assertFalse("Removed cards must not even acquire a local lookup", "b" in fixture.lookedUp)
            assertEquals(listOf("a", "c"), fixture.restored.map { it.fileId })
        } finally { fixture.stop() }
    }

    @Test fun losingDemandDuringTheLocalLookupPreventsTheNetworkRequest() = runBlocking {
        val fixture = Fixture(this)
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        fixture.lookup = {
            if (it == "a") { lookupStarted.complete(Unit); releaseLookup.await() }
        }
        fixture.show("a", focused = true)
        fixture.show("b")
        fixture.start()
        try {
            withTimeout(3_000) { lookupStarted.await() }
            fixture.hide("a")
            fixture.hide("b")
            releaseLookup.complete(Unit)
            fixture.pulse()
            assertTrue(fixture.restored.isEmpty())
            assertFalse("Another vanished card must not be queried", "b" in fixture.lookedUp)

            fixture.show("c")
            assertEquals("c", fixture.nextStarted().fileId)
        } finally { fixture.stop() }
    }

    @Test fun successfulRestoresAndEvictionRevisionsCannotCycleThroughVisibleCovers() = runBlocking {
        val fixture = Fixture(this)
        // Model a budget too small for these covers: every local lookup still reports missing,
        // and every completed write publishes another library revision.
        fixture.restore = {
            // Slow downloads take longer than the failure cooldown. Successful identities
            // must stay protected for the entire visible interval, not merely one minute.
            fixture.now += 61_000L
            fixture.changes.emit(Unit)
            true
        }
        fixture.show("a", focused = true)
        fixture.show("b")
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            assertEquals("b", fixture.nextStarted().fileId)
            repeat(8) { fixture.pulse() }
            assertEquals(listOf("a", "b"), fixture.restored.map { it.fileId })
            assertTrue(fixture.worker.isActive)
        } finally { fixture.stop() }
    }

    @Test fun failedCopiesObserveCooldownButANewStoredImageCanRestoreImmediately() = runBlocking {
        val fixture = Fixture(this)
        fixture.restore = { if (it.reference == "cover-v1") throw IOException("Offline fixture"); true }
        fixture.show("a")
        fixture.start()
        try {
            assertEquals(Cover("a"), fixture.nextStarted())
            assertTrue(fixture.nextFailure() is IOException)
            repeat(3) { fixture.pulse() }
            fixture.now = 60_999L
            fixture.pulse()
            assertEquals(1, fixture.restored.size)

            fixture.now = 61_000L
            fixture.pulse()
            assertEquals(Cover("a"), fixture.nextStarted())
            fixture.nextFailure()
            assertEquals(2, fixture.restored.size)

            fixture.targets["a"] = Cover("a", "cover-v2")
            fixture.pulse()
            assertEquals(Cover("a", "cover-v2"), fixture.nextStarted())
            fixture.pulse()
            assertEquals(3, fixture.restored.size)
            assertEquals(2, fixture.failures.size)
        } finally { fixture.stop() }
    }

    @Test fun aFailedVisibleCopyRetriesWithoutAnotherUiOrCatalogEvent() = runBlocking {
        val fixture = Fixture(this, retryAfterMillis = 50L, realClock = true)
        var online = false
        fixture.restore = { cover ->
            if (!online) throw IOException("Offline fixture")
            fixture.present += cover.fileId
            true
        }
        fixture.show("a")
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.nextFailure()
            // Internet returns without changing the viewport, settings or catalog.
            online = true
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.settle()
            assertEquals(2, fixture.restored.size)
            assertEquals(1, fixture.failures.size)
            assertNull("A successful retry must release its timer",
                withTimeoutOrNull(150) { fixture.started.receive() })
        } finally { fixture.stop() }
    }

    @Test fun hidingTheFailedCardStopsItsPendingRetryTimer() = runBlocking {
        val fixture = Fixture(this, retryAfterMillis = 100L, realClock = true)
        fixture.restore = { throw IOException("Offline fixture") }
        fixture.show("a")
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.nextFailure()
            fixture.hide("a")
            fixture.settle()
            val lookups = fixture.lookedUp.size
            assertNull(withTimeoutOrNull(250) { fixture.started.receive() })
            assertEquals("No retry or polling remains without a visible failed card",
                lookups, fixture.lookedUp.size)
            assertTrue(fixture.worker.isActive)
        } finally { fixture.stop() }
    }

    @Test fun resolvingOrDisablingThePendingTargetStopsFailureRetries() = runBlocking {
        val fixture = Fixture(this, retryAfterMillis = 100L, realClock = true)
        fixture.restore = { throw IOException("Offline fixture") }
        fixture.show("a")
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.nextFailure()
            // pending=null also represents a disabled provider, removed game or cover
            // supplied by another request. The card can stay visible in each case.
            fixture.targets.remove("a")
            fixture.pulse()
            val lookups = fixture.lookedUp.size
            assertNull(withTimeoutOrNull(250) { fixture.started.receive() })
            assertEquals(lookups, fixture.lookedUp.size)
        } finally { fixture.stop() }
    }

    @Test fun aCompletedMissingImageLookupDoesNotCreateATimer() = runBlocking {
        val fixture = Fixture(this, retryAfterMillis = 50L, realClock = true)
        fixture.restore = { false }
        fixture.show("a")
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.settle()
            val lookups = fixture.lookedUp.size
            assertNull("Known provider misses must not be polled repeatedly",
                withTimeoutOrNull(150) { fixture.started.receive() })
            assertEquals(lookups, fixture.lookedUp.size)
            assertTrue(fixture.failures.isEmpty())
        } finally { fixture.stop() }
    }

    @Test fun aSuccessfulIdentityCanRestoreAgainAfterLeavingTheViewport() = runBlocking {
        val fixture = Fixture(this)
        fixture.show("a")
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.now += 600_000L
            repeat(3) { fixture.pulse() }
            assertEquals(1, fixture.restored.size)

            fixture.hide("a")
            fixture.pulse()
            fixture.show("a")
            assertEquals("a", fixture.nextStarted().fileId)
            assertEquals(2, fixture.restored.size)
        } finally { fixture.stop() }
    }

    @Test fun recreatedOwnerWithSameIdsStartsANewVisibleIntervalWithoutARevision() = runBlocking {
        val fixture = Fixture(this)
        val oldOwner = Any()
        fixture.show("a", focused = true, owner = oldOwner)
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.settle()
            // No suspension between removal and re-adding the same ID. A plain ID-list
            // StateFlow can conflate away the empty list and suppress this request forever.
            fixture.hide(oldOwner)
            fixture.show("a", focused = true, owner = Any())
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.pulse()
            assertEquals(2, fixture.restored.size)
        } finally { fixture.stop() }
    }

    @Test fun viewRecreationDuringAnotherDownloadDoesNotReuseTheOldSuccessGuard() = runBlocking {
        val fixture = Fixture(this)
        val oldOwner = Any()
        val releaseSecond = CompletableDeferred<Unit>()
        fixture.restore = { cover ->
            if (cover.fileId == "b") releaseSecond.await()
            true
        }
        fixture.show("a", focused = true, owner = oldOwner)
        fixture.show("b")
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            assertEquals("b", fixture.nextStarted().fileId)
            fixture.hide(oldOwner)
            fixture.show("a", focused = true, owner = Any())
            releaseSecond.complete(Unit)

            assertEquals("a", fixture.nextStarted().fileId)
            fixture.pulse()
            assertEquals(listOf("a", "b", "a"), fixture.restored.map { it.fileId })
        } finally { fixture.stop() }
    }

    @Test fun aNewGenerationRechecksTheLocalLookupBeforeStartingItsRequest() = runBlocking {
        val fixture = Fixture(this)
        val oldOwner = Any()
        val firstLookup = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        var lookups = 0
        var lookupsAtRestore = 0
        fixture.lookup = {
            if (++lookups == 1) { firstLookup.complete(Unit); releaseLookup.await() }
        }
        fixture.restore = { lookupsAtRestore = lookups; true }
        fixture.show("a", focused = true, owner = oldOwner)
        fixture.start()
        try {
            withTimeout(3_000) { firstLookup.await() }
            fixture.hide(oldOwner)
            fixture.show("a", focused = true, owner = Any())
            releaseLookup.complete(Unit)

            assertEquals("a", fixture.nextStarted().fileId)
            assertEquals("The superseded local query cannot start the new composition's request",
                2, lookupsAtRestore)
            fixture.pulse()
            assertEquals(1, fixture.restored.size)
        } finally { fixture.stop() }
    }

    @Test fun aNewOwnerRetriesAMissingCoverWithoutResettingTheOtherVisibleOwner() = runBlocking {
        val fixture = Fixture(this)
        val home = Any()
        val library = Any()
        fixture.show("a", owner = home)
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.show("a", focused = true, owner = library)
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.hide(library)
            repeat(3) { fixture.pulse() }
            assertEquals("Removing one owner must retain the other owner's current attempt", 2,
                fixture.restored.size)

            fixture.present += "a"
            fixture.show("a", focused = true, owner = Any())
            fixture.pulse()
            assertEquals("A new owner must still reuse an existing local cover", 2, fixture.restored.size)
        } finally { fixture.stop() }
    }

    @Test fun aRecreatedViewKeepsTheActiveDownloadAndReusesItsCompletedFile() = runBlocking {
        val fixture = Fixture(this)
        val oldOwner = Any()
        val releaseDownload = CompletableDeferred<Unit>()
        var cancelled = false
        fixture.restore = { cover ->
            try {
                releaseDownload.await()
                fixture.present += cover.fileId
                true
            } finally { cancelled = !currentCoroutineContext().job.isActive }
        }
        fixture.show("a", owner = oldOwner)
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.hide(oldOwner)
            fixture.show("a", focused = true, owner = Any())
            fixture.settle()
            assertFalse(cancelled)
            releaseDownload.complete(Unit)
            fixture.pulse()
            assertFalse(cancelled)
            assertEquals("The completed file satisfies the fresh composition too", 1, fixture.restored.size)
        } finally { fixture.stop() }
    }

    @Test fun cancellingARemovedFoldersRequestKeepsTheWorkerForOtherFoldersAlive() = runBlocking {
        val fixture = Fixture(this)
        val requestJob = CompletableDeferred<Job>()
        var firstCancelled = false
        fixture.restore = {
            if (it.fileId == "a") {
                requestJob.complete(currentCoroutineContext().job)
                try { awaitCancellation() } finally { firstCancelled = true }
            }
            true
        }
        fixture.show("a", focused = true)
        fixture.show("b")
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.hide("a")
            withTimeout(3_000) { requestJob.await().cancelAndJoin() }
            assertEquals("b", fixture.nextStarted().fileId)
            assertTrue(firstCancelled)
            assertTrue(fixture.worker.isActive)
            assertTrue("Cancellation is not a provider failure", fixture.failures.isEmpty())
        } finally { fixture.stop() }
    }

    @Test fun stoppingTheWorkerCancelsItsActiveDownloadAndStartsNoNextRequest() = runBlocking {
        val fixture = Fixture(this)
        var requestCancelled = false
        fixture.restore = { try { awaitCancellation() } finally { requestCancelled = true } }
        fixture.show("a", focused = true)
        fixture.show("b")
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            withTimeout(3_000) { fixture.stop() }
            assertTrue(requestCancelled)
            assertFalse(fixture.worker.isActive)
            assertEquals(listOf("a"), fixture.restored.map { it.fileId })
        } finally { fixture.stop() }
    }

    @Test fun boundedLedgerRetainsVisibleAttemptsWhileOlderOffscreenEntriesExpire() = runBlocking {
        val fixture = Fixture(this, maxOwners = 2, maxRemembered = 2)
        fixture.show("anchor", focused = true)
        fixture.show("page-0", owner = "page")
        fixture.start()
        try {
            assertEquals("anchor", fixture.nextStarted().fileId)
            assertEquals("page-0", fixture.nextStarted().fileId)
            for (index in 1..8) {
                fixture.show("page-$index", owner = "page")
                assertEquals("page-$index", fixture.nextStarted().fileId)
                fixture.pulse()
            }
            assertEquals("The always-visible cover must not be evicted from the attempt ledger",
                1, fixture.restored.count { it.fileId == "anchor" })
            fixture.hide("anchor")
            fixture.show("page-0", owner = "page")
            assertEquals("The bounded ledger must release old offscreen attempts",
                "page-0", fixture.nextStarted().fileId)
            assertEquals(2, fixture.restored.count { it.fileId == "page-0" })
        } finally { fixture.stop() }
    }

    @Test fun returningCardsGetAFreshAttemptAfterVisibilityChangesDuringALocalLookup() = runBlocking {
        val fixture = Fixture(this, maxOwners = 2, maxRemembered = 2)
        val thirdLookup = CompletableDeferred<Unit>()
        val releaseThird = CompletableDeferred<Unit>()
        fixture.lookup = {
            if (it == "c") { thirdLookup.complete(Unit); releaseThird.await() }
        }
        fixture.show("a")
        fixture.show("b")
        fixture.start()
        try {
            assertEquals("a", fixture.nextStarted().fileId)
            assertEquals("b", fixture.nextStarted().fileId)
            fixture.hide("a")
            fixture.show("c", focused = true)
            withTimeout(3_000) { thirdLookup.await() }
            // The lookup started with b/c visible. Its result arrives with a/c visible.
            fixture.hide("b")
            fixture.show("a")
            releaseThird.complete(Unit)
            assertEquals("c", fixture.nextStarted().fileId)
            assertEquals("a", fixture.nextStarted().fileId)
            fixture.pulse()
            assertEquals("Returning a starts a new visible interval; it must not reuse the old guard",
                listOf("a", "b", "c", "a"), fixture.restored.map { it.fileId })

            fixture.hide("c")
            fixture.show("b")
            assertEquals("b", fixture.nextStarted().fileId)
        } finally { fixture.stop() }
    }
}
