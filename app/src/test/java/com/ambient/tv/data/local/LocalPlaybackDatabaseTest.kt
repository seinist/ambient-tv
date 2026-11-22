package com.ambient.tv.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class LocalPlaybackDatabaseTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var database: LocalPlaybackDatabase
    private fun row(key: String, order: Long, scope: String = "scope") = LocalPlaybackRow(key, "source", scope, order, "value-$order")

    @Before fun setUp() {
        context.deleteDatabase("local_playback.db")
        database = LocalPlaybackDatabase(context)
    }
    @After fun tearDown() { database.closeForTest(); context.deleteDatabase("local_playback.db") }

    private suspend fun load(name: String = "progress", limit: Int = 500, legacy: () -> List<LocalPlaybackRow> = { emptyList() },
                             cleanup: () -> Unit = {}): List<LocalPlaybackRow> {
        val result = CompletableDeferred<List<LocalPlaybackRow>>()
        database.load(name, limit, legacy, cleanup, { result.complete(it) }, { result.completeExceptionally(it) })
        return result.await()
    }
    private fun sql(action: (SQLiteDatabase) -> Unit) = context.openOrCreateDatabase("local_playback.db", Context.MODE_PRIVATE, null).use(action)
    private fun stored(name: String = "progress"): List<LocalPlaybackRow> {
        var result = emptyList<LocalPlaybackRow>()
        sql { db -> db.rawQuery("SELECT item_key,source_id,source_scope,sort_order,payload FROM playback_rows WHERE namespace=? ORDER BY sort_order DESC", arrayOf(name)).use { cursor ->
            result = buildList { while (cursor.moveToNext()) add(LocalPlaybackRow(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getString(4))) }
        } }
        return result
    }

    @Test fun migrationCommitsBeforeCleanupAndDoesNotRepeatAfterReopen() = runBlocking {
        var reads = 0
        var cleanups = 0
        val mainThread = Thread.currentThread()
        val loaded = load(legacy = { reads++; assertNotEquals(mainThread, Thread.currentThread()); listOf(row("movie", 7)) }, cleanup = {
            assertEquals("value-7", stored().single().payload)
            cleanups++
        })
        assertEquals(7L, loaded.single().order)
        assertEquals(1, reads)
        assertEquals(1, cleanups)
        database.closeForTest()
        database = LocalPlaybackDatabase(context)
        assertEquals(loaded, load(legacy = { error("Legacy must not be imported twice") }))
    }

    @Test fun hundredReportsCoalesceToOneRowAndNeverRunWarmRetention() = runBlocking {
        load(legacy = { (1..500).map { row("movie-$it", it.toLong()) } })
        val beforeWrites = database.rowWriteCount
        val beforeRetention = database.retentionPassCount
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = CompletableDeferred<Unit>()
        database.load("hold", 1, { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); emptyList() }, {},
            { blocker.complete(Unit) }, { blocker.completeExceptionally(it) })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            repeat(100) { database.put("progress", row("movie-250", 1_000L + it)) }
            val flush = database.requestFlush()
            assertFalse("A lifecycle flush only enqueues while the IO writer is busy", flush.isCompleted)
            release.countDown()
            assertTrue(flush.await())
            blocker.await()
        } finally { release.countDown() }
        assertEquals(1L, database.rowWriteCount - beforeWrites)
        // The extra namespace's initial load does one retention pass; the progress replacements do none.
        assertEquals(beforeRetention + 1, database.retentionPassCount)
        assertEquals(500, stored().size)
        assertEquals("value-1099", stored().single { it.key == "movie-250" }.payload)
    }

    @Test fun failedWriteRetainsLatestPendingValueAndDoesNotClearTheDatabase() = runBlocking {
        load(legacy = { listOf(row("untouched", 1)) })
        sql { it.execSQL("CREATE TRIGGER reject_progress BEFORE INSERT ON playback_rows WHEN NEW.namespace='progress' BEGIN SELECT RAISE(ABORT,'fixture failure'); END") }
        database.put("progress", row("movie", 2))
        assertFalse(database.flush())
        database.put("progress", row("movie", 3))
        sql { it.execSQL("DROP TRIGGER reject_progress") }
        assertTrue(database.flush())
        assertEquals(setOf("untouched", "movie"), stored().map { it.key }.toSet())
        assertEquals("value-3", stored().single { it.key == "movie" }.payload)
    }

    @Test fun failedMigrationLeavesLegacyAndMarkerUntouchedUntilRetry() = runBlocking {
        load("seed")
        sql { it.execSQL("CREATE TRIGGER reject_migration BEFORE INSERT ON playback_rows WHEN NEW.namespace='progress' BEGIN SELECT RAISE(ABORT,'fixture failure'); END") }
        var cleanup = false
        assertTrue(runCatching { load(legacy = { listOf(row("movie", 1)) }, cleanup = { cleanup = true }) }.isFailure)
        assertFalse(cleanup)
        sql { db ->
            db.rawQuery("SELECT COUNT(*) FROM playback_migrations WHERE namespace='progress'", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.execSQL("DROP TRIGGER reject_migration")
        }
        assertEquals("movie", load(legacy = { listOf(row("movie", 1)) }, cleanup = { cleanup = true }).single().key)
        assertTrue(cleanup)
    }

    @Test fun oldSourceDeleteCannotEraseAReplacementAndInsertionKeepsCapacityAtomic() = runBlocking {
        load(limit = 3, legacy = { listOf(row("a", 1), row("b", 2), row("c", 3)) })
        database.put("progress", row("a", 4, "replacement"))
        database.delete("progress", "a", "scope")
        database.put("progress", row("d", 5))
        assertTrue(database.flush())
        assertEquals(listOf("d", "a", "c"), stored().map { it.key })
        assertEquals("replacement", stored().single { it.key == "a" }.scope)
        database.delete("progress", "a", "scope")
        assertTrue(database.flush())
        assertEquals(3, stored().size)
    }

    @Test fun retryStartedBeforeFailedCompletionPublishesUsesItsOwnReadiness() = runBlocking {
        val failurePublishing = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (message.startsWith("Local playback state could not be primed")) {
                    failurePublishing.countDown()
                    check(releaseFailure.await(5, TimeUnit.SECONDS))
                }
            }
        }
        timber.log.Timber.plant(tree)
        var attempts = 0
        val values = LocalPlaybackRows(database, "retry_progress", 5,
            legacy = { if (++attempts == 1) error("fixture migration failure") else listOf(row("movie", 7)) },
            cleanup = {}, decode = { it }, encode = { it }, changed = {})
        try {
            values.start()
            assertTrue(failurePublishing.await(5, TimeUnit.SECONDS))
            val retry = async(start = CoroutineStart.UNDISPATCHED) { values.awaitReady() }
            releaseFailure.countDown()
            retry.await()
            values.awaitReady()
            assertEquals("value-7", values.get("movie"))
            assertEquals(2, attempts)
        } finally {
            releaseFailure.countDown()
            timber.log.Timber.uproot(tree)
        }
    }

    @Test fun lateRetiredScopeDeleteDuringWriteFailureCannotDiscardReplacementRetry() = runBlocking {
        load()
        sql { it.execSQL("CREATE TRIGGER reject_progress BEFORE INSERT ON playback_rows WHEN NEW.namespace='progress' BEGIN SELECT RAISE(ABORT,'fixture failure'); END") }
        val failurePublishing = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (message.startsWith("Local playback state write deferred")) {
                    failurePublishing.countDown()
                    check(releaseFailure.await(5, TimeUnit.SECONDS))
                }
            }
        }
        timber.log.Timber.plant(tree)
        try {
            database.put("progress", row("movie", 7, "replacement"))
            val failed = database.requestFlush()
            assertTrue(failurePublishing.await(5, TimeUnit.SECONDS))
            database.delete("progress", "movie", "retired")
            sql { it.execSQL("DROP TRIGGER reject_progress") }
            releaseFailure.countDown()
            assertFalse(failed.await())
            assertTrue(database.flush())
            assertEquals("replacement", stored().single().scope)
            assertEquals("value-7", stored().single().payload)
        } finally {
            releaseFailure.countDown()
            timber.log.Timber.uproot(tree)
        }
    }

    @Test fun unreadableLegacyIsRetainedOnceAndOnlyCommittedRecoveryPermitsCleanup() = runBlocking {
        load("seed")
        sql { it.execSQL("CREATE TRIGGER reject_recovery BEFORE INSERT ON playback_recovery BEGIN SELECT RAISE(ABORT,'fixture recovery failure'); END") }
        var cleanup = false
        val raw = "[{broken original"
        val legacy = { throw UnreadablePlaybackLegacy(raw, "{\"nas\":\"retired\"}", IllegalArgumentException("fixture JSON")) }
        assertTrue(runCatching { load(legacy = legacy, cleanup = { cleanup = true }) }.isFailure)
        assertFalse(cleanup)
        sql { db ->
            db.rawQuery("SELECT COUNT(*) FROM playback_migrations WHERE namespace='progress'", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM playback_recovery", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.execSQL("DROP TRIGGER reject_recovery")
        }
        assertTrue(load(legacy = legacy, cleanup = { cleanup = true }).isEmpty())
        assertTrue(cleanup)
        database.closeForTest()
        database = LocalPlaybackDatabase(context)
        assertTrue(load(legacy = { error("Committed recovery must not repeat") }).isEmpty())
        sql { db -> db.rawQuery("SELECT raw_payload,source_scopes FROM playback_recovery WHERE namespace='progress'", null).use {
            assertTrue(it.moveToFirst()); assertEquals(raw, it.getString(0)); assertEquals("{\"nas\":\"retired\"}", it.getString(1)); assertFalse(it.moveToNext())
        } }
    }
}
