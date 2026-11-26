package com.ambient.tv.livetv

import android.app.Application
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ProgrammeGuideIndexTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private var now = 1_000_000L
    private lateinit var index: ProgrammeGuideIndex
    private val owner = "iptv:provider"
    private val scope = "account-a"
    private fun event(id: String, channel: String = "channel", start: Long = 1000, stop: Long = 2000, title: String = id) =
        LiveTvProgramme(id, channel, channel, title, startEpochSeconds = start, stopEpochSeconds = stop, sourceId = owner)
    @Before fun before() {
        context.deleteDatabase("programme-guides-v1.db")
        index = ProgrammeGuideIndex(context, { now }, 64L * 1024 * 1024, 256L * 1024 * 1024)
    }
    @After fun after() { index.close(); context.deleteDatabase("programme-guides-v1.db") }
    private suspend fun seed(vararg events: LiveTvProgramme) = index.replace(owner, scope, 0, 100_000) { emit -> events.forEach(emit) }
    private fun versionCount(): Int = context.openOrCreateDatabase("programme-guides-v1.db", 0, null).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM guide_versions", null).use { it.moveToFirst(); it.getInt(0) }
    }

    @Test fun readersSeeOldGenerationUntilAllStagingChunksAreCommitted() = runBlocking {
        seed(event("old"))
        val staged = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = launch {
            index.replace(owner, scope, 0, 100_000) { emit ->
                repeat(300) { emit(event("new-$it")) }
                staged.complete(Unit); finish.await()
                repeat(300) { emit(event("new-${it + 300}")) }
            }
        }
        withTimeout(5_000) { staged.await() }
        assertEquals(listOf("old"), index.query(owner, scope, 0, 3000)!!.map { it.eventId })
        assertEquals(2, versionCount())
        finish.complete(Unit)
        withTimeout(5_000) { job.join() }
        assertEquals(600, index.query(owner, scope, 0, 3000)?.size)
        assertEquals(1, versionCount())
        index.close()
        index = ProgrammeGuideIndex(context, { now }, 64L * 1024 * 1024, 256L * 1024 * 1024)
        assertEquals(600L, index.metadata(owner, scope)?.count)
    }

    @Test fun malformedLaterSourceAndCancelledImportKeepOldGuideAndRemoveStagingRows() = runBlocking {
        seed(event("old"))
        try {
            index.replace(owner, scope, 0, 100_000) { emit ->
                repeat(300) { emit(event("first-feed-$it")) }
                throw IOException("Second XMLTV source truncated")
            }
            fail("A partial multi-source import must fail")
        } catch (_: IOException) { /* expected */ }
        assertEquals(listOf("old"), index.query(owner, scope, 0, 3000)!!.map { it.eventId })
        assertEquals(1, versionCount())
        val staged = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val job = launch {
            index.replace(owner, scope, 0, 100_000) { emit ->
                repeat(300) { emit(event("cancelled-$it")) }
                staged.complete(Unit); never.await()
            }
        }
        withTimeout(5_000) { staged.await() }
        job.cancelAndJoin()
        assertEquals(listOf("old"), index.query(owner, scope, 0, 3000)!!.map { it.eventId })
        assertEquals(1, versionCount())
    }

    @Test fun exactWindowAndChannelQueriesPreserveBothProvidersBoundarySemantics() = runBlocking {
        seed(event("ends-at-from", start = 900, stop = 1000), event("live", start = 950, stop = 1100),
            event("inside", start = 1100, stop = 1200), event("starts-at-to", start = 2000, stop = 2100),
            event("other-channel", channel = "other", start = 1000, stop = 1100))
        assertEquals(listOf("live", "inside"), index.query(owner, scope, 1000, 2000, "channel")!!.map { it.eventId })
        assertEquals(listOf("ends-at-from", "live", "inside", "starts-at-to"),
            index.query(owner, scope, 1000, 2000, "channel", inclusiveBounds = true)!!.map { it.eventId })
        assertEquals(3, index.query(owner, scope, 1000, 2000)?.size)
        assertTrue(index.query(owner, scope, 5000, 6000)!!.isEmpty())
        assertNull(index.query(owner, "account-b", 1000, 2000))
    }

    @Test fun rowAndByteLimitsRollbackTheNewGeneration() = runBlocking {
        seed(event("old"))
        try {
            index.replace(owner, scope, 0, 100_000, maxEntries = 2) { emit -> repeat(3) { emit(event("large-$it")) } }
            fail("Row limit must reject the import")
        } catch (_: IOException) { /* expected */ }
        assertEquals(listOf("old"), index.query(owner, scope, 0, 3000)!!.map { it.eventId })
        index.close(); index = ProgrammeGuideIndex(context, { now }, 1024, 4096)
        try {
            index.replace(owner, scope, 0, 100_000) { emit -> emit(event("oversize").copy(description = "x".repeat(3000))) }
            fail("Byte limit must reject the import")
        } catch (_: IOException) { /* expected */ }
        assertEquals(listOf("old"), index.query(owner, scope, 0, 3000)!!.map { it.eventId })
        assertEquals(1, versionCount())
    }

    @Test fun sourceRemovalInvalidatesAnAlreadyRunningWriter() = runBlocking {
        seed(event("old"))
        val staged = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var rejected = false
        val job = launch {
            try {
                index.replace(owner, scope, 0, 100_000) { emit ->
                    repeat(300) { emit(event("late-$it")) }
                    staged.complete(Unit); resume.await()
                }
            } catch (_: IOException) { rejected = true }
        }
        withTimeout(5_000) { staged.await() }
        index.removeOwner(owner)
        resume.complete(Unit)
        withTimeout(5_000) { job.join() }
        assertTrue(rejected)
        assertNull(index.query(owner, scope, 0, 3000))
        assertEquals(0, versionCount())
    }

    @Test fun olderConcurrentImportCannotPublishAfterANewerImportHasStarted() = runBlocking {
        seed(event("old"))
        val staged = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var obsoleteRejected = false
        val first = launch {
            try {
                index.replace(owner, scope, 0, 100_000) { emit ->
                    repeat(300) { emit(event("obsolete-$it")) }
                    staged.complete(Unit); resume.await()
                }
            } catch (_: IOException) { obsoleteRejected = true }
        }
        withTimeout(5_000) { staged.await() }
        index.replace(owner, scope, 0, 100_000) { it(event("newest")) }
        resume.complete(Unit)
        withTimeout(5_000) { first.join() }
        assertTrue(obsoleteRejected)
        assertEquals(listOf("newest"), index.query(owner, scope, 0, 3000)!!.map { it.eventId })
        assertEquals(1, versionCount())
    }

    @Test fun duplicateIdsKeepFirstFeedAndMergeWindowsPreferFreshWithoutDroppingOtherChannels() = runBlocking {
        seed(event("same", title = "first"), event("same", title = "second"))
        assertEquals(1L, index.metadata(owner, scope)?.count)
        assertEquals("first", index.query(owner, scope, 0, 3000)?.single()?.title)
        val tvh = "tvheadend"
        index.mergeWindow(tvh, scope, listOf(event("one", title = "old"), event("two", channel = "second", start = 1200)), 1000)
        index.mergeWindow(tvh, scope, listOf(event("one", title = "new"), event("past", start = 800, stop = 900),
            event("late", start = 1000 + 13 * 3600, stop = 1000 + 14 * 3600)), 1000)
        assertEquals(listOf("new", "two"), index.query(tvh, scope, 1000, 2000)!!.map { it.title })
        index.mergeWindow(tvh, scope, listOf(event("three", start = 1300), event("four", start = 1400)), 1000, maxEntries = 3)
        assertEquals(listOf("one", "two", "three"), index.query(tvh, scope, 1000, 2000)!!.map { it.eventId })
        index.mergeWindow(tvh, "account-b", listOf(event("other-account")), 1000)
        assertNull(index.query(tvh, scope, 1000, 2000))
        assertEquals(listOf("other-account"), index.query(tvh, "account-b", 1000, 2000)!!.map { it.eventId })
    }

    @Test fun sourceCleanupIsScopedAndEachProvidersArtworkContractIsPreserved() = runBlocking {
        val artwork = "https://user:secret@nas/imagecache/1?ticket=secret#fragment"
        seed(event("image").copy(imageUrl = artwork))
        assertEquals(artwork, index.query(owner, scope, 0, 3000)?.single()?.imageUrl)
        index.replace("tvheadend", scope, 0, 100_000) { it(event("tvh").copy(imageUrl = artwork)) }
        assertEquals("https://nas/imagecache/1", index.query("tvheadend", scope, 0, 3000)?.single()?.imageUrl)
        index.replace("iptv:second", scope, 0, 100_000) { it(event("second")) }
        index.retainOwners("iptv:", setOf(owner))
        assertNull(index.query("iptv:second", scope, 0, 3000))
        assertNotNull(index.query("tvheadend", scope, 0, 3000))
        now += 30L * 24 * 3600 * 1000
        assertNull(index.query(owner, scope, 0, 3000))
    }

    @Test fun clockRollbackCannotKeepAFutureTimestampFresh() = runBlocking {
        seed(event("future-clock"))
        now--
        assertNull(index.metadata(owner, scope))
        assertNull(index.query(owner, scope, 0, 3000))
    }

    @Test fun delayedAccountClearDoesNotEraseOrCancelTheNewAccountsImport() = runBlocking {
        val tvh = "tvheadend"
        index.replace(tvh, "account-a", 0, 100_000) { it(event("old-account")) }
        val staged = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val newer = launch {
            index.replace(tvh, "account-b", 0, 100_000) { emit ->
                repeat(300) { emit(event("new-account-$it")) }
                staged.complete(Unit)
                resume.await()
            }
        }
        withTimeout(5_000) { staged.await() }
        index.removeScope(tvh, "account-a")
        resume.complete(Unit)
        withTimeout(5_000) { newer.join() }
        assertNull(index.query(tvh, "account-a", 0, 3000))
        assertEquals(300, index.query(tvh, "account-b", 0, 3000)?.size)
        index.removeScope(tvh, "account-a")
        assertEquals(300, index.query(tvh, "account-b", 0, 3000)?.size)
    }

    @Test fun sourceConfigurationIsCheckedPerBatchInsteadOfPerProgramme() = runBlocking {
        var checks = 0
        index.replace(owner, scope, 0, 100_000, valid = { checks++; true }) { emit ->
            repeat(1024) { emit(event("bounded-$it")) }
        }
        assertTrue("Source validity must stay independent of programme count within a batch: $checks", checks < 20)
        assertEquals(1024L, index.metadata(owner, scope)?.count)
    }

    @Test fun scopedClearCannotResurrectItsOwnStagedGeneration() = runBlocking {
        seed(event("old"))
        val staged = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var rejected = false
        val writer = launch {
            rejected = runCatching {
                index.replace(owner, scope, 0, 100_000) { emit ->
                    repeat(300) { emit(event("cleared-$it")) }
                    staged.complete(Unit)
                    resume.await()
                }
            }.isFailure
        }
        withTimeout(5_000) { staged.await() }
        index.removeScope(owner, scope)
        resume.complete(Unit)
        withTimeout(5_000) { writer.join() }
        assertTrue(rejected)
        assertNull(index.query(owner, scope, 0, 3000))
        assertEquals(0, versionCount())
    }

    @Test fun channelQueryUsesItsIndexAndHydratesOnlyTheRequestedSmallWindow() = runBlocking {
        index.replace(owner, scope, 0, 100_000) { emit ->
            repeat(3000) { i -> emit(event("event-$i", channel = "channel-${i % 100}", start = 1000 + (i / 100) * 1800L,
                stop = 1000 + (i / 100 + 1) * 1800L)) }
        }
        assertEquals(4, index.query(owner, scope, 1000, 1000 + 2 * 3600, "channel-1")?.size)
        context.openOrCreateDatabase("programme-guides-v1.db", 0, null).use { db ->
            db.rawQuery("""EXPLAIN QUERY PLAN SELECT title FROM guide_programmes
                WHERE owner=? AND generation=? AND stop_epoch>? AND start_epoch<? AND channel_id=? ORDER BY start_epoch,ordinal""",
                arrayOf(owner, "generation", "1000", "8200", "channel-1")).use { rows ->
                val plan = buildString { while (rows.moveToNext()) append(rows.getString(3)) }
                assertTrue(plan, plan.contains("guide_channel_window"))
                assertTrue(plan, plan.contains("SEARCH", ignoreCase = true))
                assertFalse(plan, plan.contains("TEMP B-TREE", ignoreCase = true))
            }
        }
    }

    @Test fun rollingWindowUpdatesDeletesAndKeepsFutureProgrammes() = runBlocking {
        val tvh = "tvheadend:rolling"
        val old = event("removed", start = 11000, stop = 12000)
        val changed = event("changed", start = 13000, stop = 14000)
        val future = event("future", start = 50000, stop = 51000)
        index.reconcileWindow(tvh, scope, listOf(old, changed), 10000, 20000, 10000)
        index.reconcileWindow(tvh, scope, listOf(future), 40000, 60000, 10000)
        index.reconcileWindow(tvh, scope, listOf(changed.copy(title = "Corrected")), 10000, 20000, 10000)
        assertEquals(listOf("changed", "future"), index.query(tvh, scope, 10000, 60000)!!.map { it.eventId })
        assertEquals("Corrected", index.query(tvh, scope, 10000, 20000)!!.single().title)
        assertTrue(index.coversFreshWindow(tvh, scope, 10000, 20000, 900000))
        now += 900001
        assertFalse(index.coversFreshWindow(tvh, scope, 10000, 20000, 900000))
        index.trimExpired(tvh, scope, 30000)
        assertEquals(listOf("future"), index.query(tvh, scope, 0, 60000)!!.map { it.eventId })
    }

    @Test fun channelReconciliationDoesNotDeleteOtherChannelsOrClaimFullCoverage() = runBlocking {
        val tvh = "tvheadend:partial"
        index.reconcileWindow(tvh, scope, listOf(event("a", "a"), event("b", "b")), 1000, 3000, 1000)
        index.reconcileWindow(tvh, scope, emptyList(), 1000, 3000, 1000, channelId = "a")
        assertEquals(listOf("b"), index.query(tvh, scope, 1000, 3000)!!.map { it.eventId })
        index.reconcileWindow(tvh, "other-account", listOf(event("new", "a")), 1000, 3000, 1000, channelId = "a")
        assertFalse(index.coversFreshWindow(tvh, "other-account", 1000, 3000, 900000))
    }

    @Test fun recordingChangeExpiresOnlyFreshnessAndPreservesRows() = runBlocking {
        val tvh = "tvheadend"
        index.reconcileWindow(tvh, scope, listOf(event("one")), 1000, 3000, 1000)
        assertTrue(index.coversFreshWindow(tvh, scope, 1000, 3000, 900000))
        index.expireSyncWindows(tvh, scope)
        assertFalse(index.coversFreshWindow(tvh, scope, 1000, 3000, 900000))
        assertEquals("one", index.query(tvh, scope, 1000, 3000)!!.single().eventId)
    }

    @Test fun malformedChannelWindowCannotDeleteStoredData() = runBlocking {
        val tvh = "tvheadend"
        index.reconcileWindow(tvh, scope, listOf(event("one", "a")), 1000, 3000, 1000)
        val result = runCatching { index.reconcileWindow(tvh, scope, listOf(event("wrong", "b")), 1000, 3000, 1000, "a") }
        assertTrue(result.isFailure)
        assertEquals("one", index.query(tvh, scope, 1000, 3000)!!.single().eventId)
    }
}
