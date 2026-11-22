package com.ambient.tv.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** One independently replaceable local record, never an aggregate history/favorite JSON array. */
internal data class LocalPlaybackRow(
    val key: String,
    val sourceId: String,
    val scope: String,
    val order: Long,
    val payload: String,
)

/** A malformed optional preference is preserved transactionally before its namespace starts empty. */
internal class UnreadablePlaybackLegacy(val raw: String, val sourceScopes: String, cause: Exception) :
    Exception("Unreadable legacy playback state", cause)

private class PlaybackDb(context: Context) : SQLiteOpenHelper(context, "local_playback.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE playback_rows (
            namespace TEXT NOT NULL,item_key TEXT NOT NULL,source_id TEXT NOT NULL,source_scope TEXT NOT NULL,
            sort_order INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(namespace,item_key))""")
        db.execSQL("CREATE INDEX playback_rows_recent ON playback_rows(namespace,sort_order DESC,item_key)")
        db.execSQL("CREATE TABLE playback_migrations (namespace TEXT NOT NULL PRIMARY KEY)")
        db.execSQL("""CREATE TABLE playback_recovery (namespace TEXT NOT NULL PRIMARY KEY,
            raw_payload TEXT NOT NULL,source_scopes TEXT NOT NULL,recorded_at INTEGER NOT NULL)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Missing local playback migration from $oldVersion to $newVersion")
    }
}

/**
 * One process-owned writer, also used by final reports after PlaybackService is destroyed.
 * Calls from UI/lifecycle threads only enqueue work. Namespace reads/migrations and batched
 * writes run on the same executor; a completion barrier suspends its caller instead of blocking it.
 */
@Singleton
class LocalPlaybackDatabase private constructor(private val context: Context?, private val persistent: Boolean) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.applicationContext, true)

    private data class Key(val namespace: String, val item: String)
    private data class Change(val row: LocalPlaybackRow?, val deletedScope: String?)
    private data class Namespace(val limit: Int, val legacy: () -> List<LocalPlaybackRow>, val cleanup: () -> Unit)
    private val lock = Any()
    private val executor = if (persistent) Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "local-playback-store").apply { isDaemon = true }
    } else null
    private val helper by lazy { PlaybackDb(requireNotNull(context)) }
    private val namespaces = mutableMapOf<String, Namespace>()
    private val pending = linkedMapOf<Key, Change>()
    private val memory = mutableMapOf<String, LinkedHashMap<String, LocalPlaybackRow>>()
    // Writer-thread confined. Replacing an existing progress row never needs a retention scan.
    private val committedKeys = mutableMapOf<String, Set<String>>()
    private var scheduled: ScheduledFuture<*>? = null
    private var failures = 0
    private val writes = AtomicLong()
    private val retentionPasses = AtomicLong()
    internal val rowWriteCount: Long get() = writes.get()
    internal val retentionPassCount: Long get() = retentionPasses.get()

    internal fun load(
        namespace: String,
        limit: Int,
        legacy: () -> List<LocalPlaybackRow>,
        cleanup: () -> Unit,
        success: (List<LocalPlaybackRow>) -> Unit,
        failure: (Throwable) -> Unit,
    ) {
        require(namespace.matches(Regex("[a-z_]{1,40}")) && limit in 1..2_000)
        val definition = Namespace(limit, legacy, cleanup)
        synchronized(lock) { namespaces[namespace] = definition }
        execute {
            try {
                val rows = if (!persistent) synchronized(lock) {
                    memory.getOrPut(namespace) {
                        val imported = try { legacy() } catch (_: UnreadablePlaybackLegacy) { emptyList() }
                        LinkedHashMap(imported.associateBy { it.key })
                    }
                        .values.sortedByDescending { it.order }.take(limit)
                } else {
                    val db = helper.writableDatabase
                    db.beginTransaction()
                    try {
                        migrate(db, namespace, definition)
                        trim(db, namespace, limit)
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                    // If the process ended after commit but before cleanup, repeat only cleanup.
                    runCatching(cleanup).onFailure { timber.log.Timber.w(it, "Playback legacy cleanup deferred") }
                    read(db, namespace, limit).also { committedKeys[namespace] = it.mapTo(mutableSetOf()) { row -> row.key } }
                }
                success(rows)
            } catch (error: Throwable) { failure(error) }
        }
    }

    internal fun put(namespace: String, row: LocalPlaybackRow, immediate: Boolean = false) {
        require(row.key.length <= 16_384 && row.payload.toByteArray(Charsets.UTF_8).size <= 128 * 1024)
        synchronized(lock) { pending[Key(namespace, row.key)] = Change(row, null) }
        schedule(if (immediate) 0 else WRITE_DELAY_MS)
    }

    internal fun delete(namespace: String, key: String, scope: String, immediate: Boolean = false) {
        synchronized(lock) {
            val id = Key(namespace, key)
            // A late removal from a retired source cannot erase a newly queued replacement.
            val waiting = pending[id]?.row
            if (waiting == null || waiting.scope == scope) pending[id] = Change(null, scope)
        }
        schedule(if (immediate) 0 else WRITE_DELAY_MS)
    }

    /** Starts an immediate asynchronous drain, without waiting on a lifecycle or UI thread. */
    fun requestFlush(): CompletableDeferred<Boolean> {
        val finished = CompletableDeferred<Boolean>()
        synchronized(lock) { scheduled?.cancel(false); scheduled = null }
        execute { finished.complete(drain()) }
        return finished
    }

    /** A final resume report waits at most this long; the writer continues independently. */
    suspend fun flush(timeoutMs: Long = 1_500): Boolean =
        withTimeoutOrNull(timeoutMs) { requestFlush().await() } ?: false

    private fun execute(action: () -> Unit) {
        if (executor == null) action() else executor.execute(action)
    }

    private fun schedule(delayMs: Long) {
        if (executor == null) { drain(); return }
        synchronized(lock) {
            if (scheduled != null && delayMs != 0L) return
            scheduled?.cancel(false)
            scheduled = executor.schedule({ drain() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun drain(): Boolean {
        val batch = synchronized(lock) {
            scheduled = null
            if (pending.isEmpty()) return true
            pending.toMap().also { pending.clear() }
        }
        try {
            if (!persistent) {
                synchronized(lock) {
                    batch.forEach { (key, change) ->
                        val rows = memory.getOrPut(key.namespace) { linkedMapOf() }
                        if (change.row != null) { rows[key.item] = change.row; writes.incrementAndGet() }
                        else if (rows[key.item]?.scope == change.deletedScope) {
                            rows.remove(key.item); writes.incrementAndGet()
                        }
                    }
                }
            } else {
                val db = helper.writableDatabase
                val definitions = synchronized(lock) { batch.keys.map { it.namespace }.distinct().associateWith { namespaces.getValue(it) } }
                val nextKeys = mutableMapOf<String, MutableSet<String>>()
                var changedRows = 0L
                db.beginTransaction()
                try {
                    // A failed first migration is retried before applying newer mutations.
                    // A successful new write can never be overwritten by a later legacy import.
                    definitions.forEach { (name, definition) -> migrate(db, name, definition) }
                    definitions.forEach { (name, definition) ->
                        nextKeys[name] = (committedKeys[name] ?: read(db, name, definition.limit).map { it.key }.toSet()).toMutableSet()
                    }
                    batch.forEach { (key, change) ->
                        if (change.row != null) {
                            write(db, key.namespace, change.row, replace = true)
                            nextKeys.getValue(key.namespace).add(key.item)
                            changedRows++
                        } else {
                            val removed = db.delete("playback_rows", "namespace=? AND item_key=? AND source_scope=?",
                                arrayOf(key.namespace, key.item, requireNotNull(change.deletedScope)))
                            if (removed != 0) nextKeys.getValue(key.namespace).remove(key.item)
                            changedRows += removed
                        }
                    }
                    definitions.forEach { (name, definition) ->
                        if (nextKeys.getValue(name).size > definition.limit) {
                            trim(db, name, definition.limit)
                            nextKeys[name] = read(db, name, definition.limit).mapTo(mutableSetOf()) { it.key }
                        }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                writes.addAndGet(changedRows)
                committedKeys.putAll(nextKeys)
                definitions.values.forEach { runCatching(it.cleanup) }
            }
            failures = 0
            return true
        } catch (error: Exception) {
            timber.log.Timber.w(error, "Local playback state write deferred")
            synchronized(lock) {
                // Newer values win. A cross-scope deletion is not a newer value for this
                // PUT: it may have arrived while the failed transaction was in flight.
                batch.forEach { (key, change) ->
                    val waiting = pending[key]
                    if (waiting == null || (change.row != null && waiting.row == null &&
                            waiting.deletedScope != change.row.scope)) pending[key] = change
                }
            }
            if (++failures <= 3) schedule(1_000L * failures)
            return false
        }
    }

    private fun migrate(db: SQLiteDatabase, namespace: String, definition: Namespace) {
        val imported = db.rawQuery("SELECT 1 FROM playback_migrations WHERE namespace=?", arrayOf(namespace))
            .use { it.moveToFirst() }
        if (imported) return
        val rows = try { definition.legacy() } catch (error: UnreadablePlaybackLegacy) {
            // One original per namespace, never a new copy on each retry. Marker and recovery
            // commit together; an IO failure leaves the legacy preference untouched.
            db.execSQL("INSERT OR IGNORE INTO playback_recovery(namespace,raw_payload,source_scopes,recorded_at) VALUES(?,?,?,?)",
                arrayOf<Any>(namespace, error.raw, error.sourceScopes, System.currentTimeMillis()))
            // Decoder exceptions may embed the original titles, paths or URLs in their message.
            // The full original belongs only in the recovery row, never in diagnostic output.
            timber.log.Timber.w("Malformed legacy playback state retained for recovery (%s)",
                error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName)
            emptyList()
        }
        rows.sortedByDescending { it.order }.take(definition.limit).forEach {
            write(db, namespace, it, replace = false)
        }
        db.execSQL("INSERT INTO playback_migrations(namespace) VALUES(?)", arrayOf(namespace))
    }

    private fun write(db: SQLiteDatabase, namespace: String, row: LocalPlaybackRow, replace: Boolean) {
        val conflict = if (replace) "REPLACE" else "IGNORE"
        db.execSQL("INSERT OR $conflict INTO playback_rows(namespace,item_key,source_id,source_scope,sort_order,payload) VALUES(?,?,?,?,?,?)",
            arrayOf<Any>(namespace, row.key, row.sourceId, row.scope, row.order, row.payload))
    }

    private fun trim(db: SQLiteDatabase, namespace: String, limit: Int) {
        db.execSQL("""DELETE FROM playback_rows WHERE namespace=? AND item_key NOT IN
            (SELECT item_key FROM playback_rows WHERE namespace=? ORDER BY sort_order DESC,item_key LIMIT ?)""",
        arrayOf<Any>(namespace, namespace, limit))
        retentionPasses.incrementAndGet()
    }

    private fun read(db: SQLiteDatabase, namespace: String, limit: Int): List<LocalPlaybackRow> =
        db.rawQuery("SELECT item_key,source_id,source_scope,sort_order,payload FROM playback_rows WHERE namespace=? ORDER BY sort_order DESC,item_key LIMIT ?",
            arrayOf(namespace, limit.toString())).use { rows -> buildList {
            while (rows.moveToNext()) add(LocalPlaybackRow(rows.getString(0), rows.getString(1), rows.getString(2), rows.getLong(3), rows.getString(4)))
        } }

    internal fun closeForTest() {
        executor?.shutdown()
        executor?.awaitTermination(5, TimeUnit.SECONDS)
        if (persistent) helper.close()
    }

    companion object {
        private const val WRITE_DELAY_MS = 250L
        /** Pure preference/domain tests use the same row API without Android SQLite or worker threads. */
        internal fun memory(): LocalPlaybackDatabase = LocalPlaybackDatabase(null, false)
    }
}
