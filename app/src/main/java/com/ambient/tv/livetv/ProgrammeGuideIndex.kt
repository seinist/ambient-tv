package com.ambient.tv.livetv

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock as withCoroutineLock
import kotlinx.coroutines.withContext

data class ProgrammeGuideMetadata(
    val loadedAt: Long,
    val fromEpochSeconds: Long,
    val toEpochSeconds: Long,
    val fromFallback: Boolean,
    val count: Long,
)

/** Hash account-bearing configuration instead of persisting provider URLs or login material. */
internal fun programmeGuideScope(parts: Iterable<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        for (shift in 24 downTo 0 step 8) digest.update((bytes.size ushr shift).toByte())
        digest.update(bytes)
    }
    val hex = "0123456789abcdef"
    return buildString(64) { digest.digest().forEach {
        val value = it.toInt() and 0xff; append(hex[value ushr 4]); append(hex[value and 15])
    } }
}

/**
 * Queryable, disposable guide metadata. A reader follows one committed generation. XML parsers
 * write small staging transactions, so a cancellation, malformed feed or exceeded limit can
 * discard the entire import without erasing yesterday's still-usable guide.
 */
@Singleton
class ProgrammeGuideIndex internal constructor(
    context: Context,
    private val clock: () -> Long,
    private val guideByteLimit: Long,
    private val totalByteLimit: Long,
) : Closeable {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context, System::currentTimeMillis, 64L * 1024 * 1024, 256L * 1024 * 1024)

    private val lock = ReentrantLock()
    private val mergeLock = Mutex()
    private val importing = mutableMapOf<String, Int>()
    private val epochs = mutableMapOf<String, Long>()
    private val database = object : SQLiteOpenHelper(context, DATABASE_NAME, null, 2) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE guide_versions (
                owner TEXT NOT NULL, generation TEXT NOT NULL, scope TEXT NOT NULL,
                created_at INTEGER NOT NULL, loaded_at INTEGER NOT NULL DEFAULT 0,
                from_epoch INTEGER NOT NULL, to_epoch INTEGER NOT NULL, fallback INTEGER NOT NULL DEFAULT 0,
                entries INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(owner,generation))""")
            db.execSQL("""CREATE TABLE guide_heads (
                owner TEXT PRIMARY KEY NOT NULL, scope TEXT NOT NULL, generation TEXT NOT NULL,
                FOREIGN KEY(owner,generation) REFERENCES guide_versions(owner,generation) ON DELETE CASCADE)""")
            db.execSQL("""CREATE TABLE guide_programmes (
                owner TEXT NOT NULL, generation TEXT NOT NULL, event_id TEXT NOT NULL,
                channel_id TEXT NOT NULL, start_epoch INTEGER NOT NULL, stop_epoch INTEGER NOT NULL,
                ordinal INTEGER NOT NULL, channel_name TEXT NOT NULL, title TEXT NOT NULL,
                subtitle TEXT, summary TEXT, description TEXT, image_url TEXT, series_link TEXT,
                dvr_id TEXT, source_id TEXT NOT NULL, bytes INTEGER NOT NULL,
                PRIMARY KEY(owner,generation,event_id),
                FOREIGN KEY(owner,generation) REFERENCES guide_versions(owner,generation) ON DELETE CASCADE)""")
            db.execSQL("CREATE INDEX guide_window ON guide_programmes(owner,generation,start_epoch,ordinal)")
            db.execSQL("CREATE INDEX guide_channel_window ON guide_programmes(owner,generation,channel_id,start_epoch,ordinal)")
            createSyncWindows(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createSyncWindows(db)
        }
        private fun createSyncWindows(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE guide_sync_windows (
                owner TEXT NOT NULL, generation TEXT NOT NULL, from_epoch INTEGER NOT NULL,
                to_epoch INTEGER NOT NULL, loaded_at INTEGER NOT NULL,
                PRIMARY KEY(owner,generation,from_epoch,to_epoch),
                FOREIGN KEY(owner,generation) REFERENCES guide_versions(owner,generation) ON DELETE CASCADE)""")
        }
    }.apply { setWriteAheadLoggingEnabled(true) }

    suspend fun metadata(owner: String, scope: String): ProgrammeGuideMetadata? = withContext(Dispatchers.IO) {
        lock.withLock { active(database.readableDatabase, owner, scope)?.second }
    }

    /** Null means no committed snapshot; an empty list is a known, successfully imported window. */
    suspend fun query(
        owner: String, scope: String, fromEpochSeconds: Long, toEpochSeconds: Long,
        channelId: String? = null, inclusiveBounds: Boolean = false,
    ): List<LiveTvProgramme>? = withContext(Dispatchers.IO) {
        require(toEpochSeconds >= fromEpochSeconds)
        val operation = currentCoroutineContext()
        lock.withLock {
            val db = database.readableDatabase
            val generation = active(db, owner, scope)?.first ?: return@withLock null
            val after = if (inclusiveBounds) ">=" else ">"
            val before = if (inclusiveBounds) "<=" else "<"
            val selection = "owner=? AND generation=? AND stop_epoch$after? AND start_epoch$before?" +
                if (channelId == null) "" else " AND channel_id=?"
            val args = mutableListOf(owner, generation, fromEpochSeconds.toString(), toEpochSeconds.toString())
            channelId?.let(args::add)
            db.query("guide_programmes", READ_COLUMNS, selection, args.toTypedArray(), null, null,
                "start_epoch,ordinal").use { rows ->
                buildList {
                    while (rows.moveToNext()) { operation.ensureActive(); add(rows.programme()) }
                }
            }
        }
    }

    /** [produce] emits records synchronously on its IO worker; only one chunk is held in RAM. */
    suspend fun replace(
        owner: String, scope: String, fromEpochSeconds: Long, toEpochSeconds: Long,
        maxEntries: Int = 300_000, fromFallback: () -> Boolean = { false }, valid: () -> Boolean = { true },
        produce: suspend ((LiveTvProgramme) -> Unit) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val operation = currentCoroutineContext()
        val writer = begin(owner, scope, fromEpochSeconds, toEpochSeconds, maxEntries, valid)
        try {
            produce { operation.ensureActive(); writer.append(it) }
            operation.ensureActive()
            writer.flush()
            publish(writer, fromFallback(), valid, operation::ensureActive)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { finish(writer) }
        }
    }

    /** TVHeadend refreshes overlapping channel windows: fresh events win, old windows stay. */
    suspend fun mergeWindow(
        owner: String, scope: String, fresh: List<LiveTvProgramme>, nowEpochSeconds: Long,
        windowSeconds: Long = 12L * 3600L, maxEntries: Int = 8_000, valid: () -> Boolean = { true },
    ) = mergeLock.withCoroutineLock {
        withContext(Dispatchers.IO) {
            val operation = currentCoroutineContext()
            val to = nowEpochSeconds + windowSeconds
            val writer = begin(owner, scope, nowEpochSeconds, to, maxEntries, valid)
            try {
                fresh.asSequence().filter { it.stopEpochSeconds > nowEpochSeconds && it.startEpochSeconds <= to }
                    .distinctBy { it.eventId }.sortedBy { it.startEpochSeconds }.take(maxEntries)
                    .forEach { operation.ensureActive(); writer.append(it) }
                writer.flush()
                lock.withLock {
                    writer.checkValid()
                    val db = database.writableDatabase
                    val old = active(db, owner, scope)?.first
                    if (old != null) transaction(db) {
                        // Copy in SQLite; no decoding/allocation of the old whole-guide objects.
                        db.execSQL("""INSERT OR IGNORE INTO guide_programmes
                            SELECT owner,?,event_id,channel_id,start_epoch,stop_epoch,ordinal+?,channel_name,title,
                                subtitle,summary,description,image_url,series_link,dvr_id,source_id,bytes
                            FROM guide_programmes WHERE owner=? AND generation=? AND stop_epoch>? AND start_epoch<=?
                            ORDER BY start_epoch,ordinal LIMIT ?""",
                            arrayOf<Any>(writer.generation, writer.ordinal, owner, old, nowEpochSeconds, to, maxEntries))
                        db.execSQL("""DELETE FROM guide_programmes WHERE owner=? AND generation=? AND event_id NOT IN
                            (SELECT event_id FROM guide_programmes WHERE owner=? AND generation=? ORDER BY start_epoch,ordinal LIMIT ?)""",
                            arrayOf<Any>(owner, writer.generation, owner, writer.generation, maxEntries))
                        db.execSQL("""UPDATE guide_versions SET
                            entries=(SELECT COUNT(*) FROM guide_programmes WHERE owner=? AND generation=?),
                            bytes=(SELECT COALESCE(SUM(bytes),0) FROM guide_programmes WHERE owner=? AND generation=?)
                            WHERE owner=? AND generation=?""",
                            arrayOf(owner, writer.generation, owner, writer.generation, owner, writer.generation))
                        writer.checkLimits(db)
                    }
                }
                publish(writer, false, valid, operation::ensureActive)
            } finally { withContext(NonCancellable + Dispatchers.IO) { finish(writer) } }
        }
    }

    /** Freshness belongs to successfully completed all-channel windows, not to the latest partial import. */
    suspend fun coversFreshWindow(owner: String, scope: String, from: Long, to: Long, maxAgeMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val db = database.readableDatabase
                val gen = active(db, owner, scope)?.first ?: return@withLock false
                val now = clock()
                var covered = from
                db.rawQuery("""SELECT from_epoch,to_epoch FROM guide_sync_windows
                    WHERE owner=? AND generation=? AND loaded_at>? AND loaded_at<=?
                    AND to_epoch>=? AND from_epoch<=? ORDER BY from_epoch,to_epoch""",
                    arrayOf(owner, gen, (now - maxAgeMs).toString(), now.toString(), from.toString(), to.toString())
                ).use { rows ->
                    while (rows.moveToNext() && rows.getLong(0) <= covered) {
                        covered = maxOf(covered, rows.getLong(1))
                        if (covered >= to) return@withLock true
                    }
                }
                false
            }
        }

    /** Reconcile only the complete, authoritative response window. A failed/cancelled import never
     * removes old programmes. Older rows outside the window remain; the index is a rolling store.
     * Stage in bounded chunks, then atomically apply changed rows to the committed generation. */
    suspend fun reconcileWindow(
        owner: String, scope: String, fresh: List<LiveTvProgramme>, from: Long, to: Long,
        nowEpochSeconds: Long, channelId: String? = null, valid: () -> Boolean = { true },
    ) = mergeLock.withCoroutineLock {
        withContext(Dispatchers.IO) {
            require(to > from)
            // A malformed or mis-scoped page is never authoritative for deletions.
            require(fresh.all { it.stopEpochSeconds > it.startEpochSeconds &&
                (channelId == null || it.channelUuid == channelId) })
            require(fresh.map { it.eventId }.toSet().size == fresh.size)
            val operation = currentCoroutineContext()
            val keepFrom = nowEpochSeconds - 2 * 3600L
            val keepTo = nowEpochSeconds + 7 * 24 * 3600L
            val cap = 150_000
            val writer = begin(owner, scope, keepFrom, keepTo, cap, valid)
            try {
                fresh.asSequence().filter { it.stopEpochSeconds > keepFrom && it.startEpochSeconds <= keepTo }
                    .filter { channelId == null || it.channelUuid == channelId }
                    .forEach { operation.ensureActive(); writer.append(it) }
                writer.flush()
                lock.withLock {
                    val db = database.writableDatabase
                    writer.checkValid()
                    val existing = active(db, owner, scope)?.first
                    if (existing == null) {
                        if (channelId == null && maxOf(from, keepFrom) < minOf(to, keepTo))
                            recordWindow(db, writer.owner, writer.generation, maxOf(from, keepFrom), minOf(to, keepTo))
                        publish(writer, false, valid, operation::ensureActive)
                    } else transaction(db) {
                        operation.ensureActive()
                        val args = arrayOf(owner, existing, from.toString(), to.toString()) +
                            (channelId?.let { arrayOf(it) } ?: emptyArray()) + arrayOf(owner, writer.generation)
                        val channel = if (channelId == null) "" else " AND channel_id=?"
                        db.execSQL("""DELETE FROM guide_programmes WHERE owner=? AND generation=?
                            AND stop_epoch>=? AND start_epoch<=?$channel AND event_id NOT IN
                            (SELECT event_id FROM guide_programmes WHERE owner=? AND generation=?)""", args)
                        db.execSQL("""INSERT OR REPLACE INTO guide_programmes
                            SELECT n.owner,?,n.event_id,n.channel_id,n.start_epoch,n.stop_epoch,n.ordinal,
                            n.channel_name,n.title,n.subtitle,n.summary,n.description,n.image_url,n.series_link,
                            n.dvr_id,n.source_id,n.bytes FROM guide_programmes n
                            WHERE n.owner=? AND n.generation=? AND NOT EXISTS (
                            SELECT 1 FROM guide_programmes o WHERE o.owner=n.owner AND o.generation=? AND o.event_id=n.event_id
                            AND o.channel_id IS n.channel_id AND o.start_epoch IS n.start_epoch AND o.stop_epoch IS n.stop_epoch
                            AND o.channel_name IS n.channel_name AND o.title IS n.title AND o.subtitle IS n.subtitle
                            AND o.summary IS n.summary AND o.description IS n.description AND o.image_url IS n.image_url
                            AND o.series_link IS n.series_link AND o.dvr_id IS n.dvr_id AND o.source_id IS n.source_id)""",
                            arrayOf(existing, owner, writer.generation, existing))
                        trimWindow(db, owner, existing, keepFrom, keepTo)
                        if (channelId == null && maxOf(from, keepFrom) < minOf(to, keepTo))
                            recordWindow(db, owner, existing, maxOf(from, keepFrom), minOf(to, keepTo))
                        db.execSQL("""UPDATE guide_versions SET from_epoch=?,to_epoch=?,loaded_at=?,fallback=0
                            WHERE owner=? AND generation=?""", arrayOf<Any>(keepFrom, keepTo, clock(), owner, existing))
                        db.rawQuery("SELECT entries,bytes FROM guide_versions WHERE owner=? AND generation=?", arrayOf(owner, existing)).use {
                            check(it.moveToFirst())
                            if (it.getLong(0) > cap || it.getLong(1) > guideByteLimit) throw IOException("Guide index is too large")
                        }
                        operation.ensureActive()
                        writer.checkValid()
                    }
                }
            } finally { withContext(NonCancellable + Dispatchers.IO) { finish(writer) } }
        }
    }

    /** An acknowledged DVR edit changes EPG recording links without invalidating the stored guide. */
    suspend fun expireSyncWindows(owner: String, scope: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val db = database.writableDatabase
            val gen = active(db, owner, scope)?.first ?: return@withLock
            db.delete("guide_sync_windows", "owner=? AND generation=?", arrayOf(owner, gen))
        }
    }

    /** Called even when the provider is offline. Keep a small history for Timeshift and remove old rows. */
    suspend fun trimExpired(owner: String, scope: String, nowEpochSeconds: Long, valid: () -> Boolean = { true }) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!valid()) return@withLock
                val db = database.writableDatabase
                val gen = active(db, owner, scope)?.first ?: return@withLock
                transaction(db) { trimWindow(db, owner, gen, nowEpochSeconds - 7200, nowEpochSeconds + 7 * 86400) }
            }
        }

    private fun trimWindow(db: SQLiteDatabase, owner: String, gen: String, from: Long, to: Long) {
        db.delete("guide_programmes", "owner=? AND generation=? AND (stop_epoch<=? OR start_epoch>?)",
            arrayOf(owner, gen, from.toString(), to.toString()))
        db.delete("guide_sync_windows", "owner=? AND generation=? AND (to_epoch<? OR loaded_at<?)",
            arrayOf(owner, gen, from.toString(), (clock() - 24 * 3600_000L).toString()))
        db.execSQL("""UPDATE guide_versions SET
            entries=(SELECT COUNT(*) FROM guide_programmes WHERE owner=? AND generation=?),
            bytes=(SELECT COALESCE(SUM(bytes),0) FROM guide_programmes WHERE owner=? AND generation=?)
            WHERE owner=? AND generation=?""", arrayOf(owner, gen, owner, gen, owner, gen))
    }

    private fun recordWindow(db: SQLiteDatabase, owner: String, gen: String, from: Long, to: Long) {
        db.execSQL("INSERT OR REPLACE INTO guide_sync_windows VALUES(?,?,?,?,?)",
            arrayOf<Any>(owner, gen, from, to, clock()))
    }

    suspend fun removeOwner(owner: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            epochs[owner] = (epochs[owner] ?: 0L) + 1
            database.writableDatabase.delete("guide_versions", "owner=?", arrayOf(owner))
        }
    }

    /**
     * A queued clear belongs to the account captured when it was requested. Do not advance
     * the owner's epoch: a different account may already be importing under that owner.
     * Removing a matching staging parent makes its late inserts/publication fail by FK.
     */
    suspend fun removeScope(owner: String, scope: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            database.writableDatabase.delete("guide_versions", "owner=? AND scope=?", arrayOf(owner, scope))
        }
    }

    /** Registry/source removal does not contact a provider; other providers' rows are untouched. */
    suspend fun retainOwners(prefix: String, owners: Set<String>, valid: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!valid()) return@withLock
            val db = database.writableDatabase
            val removed = db.rawQuery("SELECT DISTINCT owner FROM guide_versions WHERE owner LIKE ?", arrayOf("$prefix%")).use { rows ->
                buildList { while (rows.moveToNext()) rows.getString(0).takeUnless(owners::contains)?.let(::add) }
            }
            transaction(db) { removed.forEach { owner ->
                epochs[owner] = (epochs[owner] ?: 0L) + 1
                db.delete("guide_versions", "owner=?", arrayOf(owner))
            } }
        }
    }

    private fun begin(owner: String, scope: String, from: Long, to: Long, maxEntries: Int, valid: () -> Boolean): Writer = lock.withLock {
        require(owner.isNotBlank() && scope.isNotBlank() && to > from && maxEntries > 0)
        if (!valid()) throw IOException("Guide source changed")
        val db = database.writableDatabase
        // Interrupted-process staging rows are never visible and can be discarded on the next import.
        db.execSQL("""DELETE FROM guide_versions WHERE created_at<? AND NOT EXISTS
            (SELECT 1 FROM guide_heads h WHERE h.owner=guide_versions.owner AND h.generation=guide_versions.generation)""",
            arrayOf(clock() - 24L * 3600 * 1000))
        val generation = UUID.randomUUID().toString()
        db.insertOrThrow("guide_versions", null, ContentValues().apply {
            put("owner", owner); put("scope", scope); put("generation", generation)
            put("created_at", clock()); put("from_epoch", from); put("to_epoch", to)
        })
        // A later refresh/configuration wins even when two repository instances overlap.
        val epoch = (epochs[owner] ?: 0L) + 1
        epochs[owner] = epoch
        importing[owner] = (importing[owner] ?: 0) + 1
        Writer(owner, scope, generation, epoch, maxEntries, valid)
    }

    private inner class Writer(
        val owner: String, val scope: String, val generation: String, private val epoch: Long,
        private val maxEntries: Int, private val valid: () -> Boolean,
    ) {
        private val pending = ArrayList<LiveTvProgramme>(WRITE_BATCH)
        var ordinal = 0L
            private set
        var published = false
        fun checkValid() {
            if ((epochs[owner] ?: 0L) != epoch || !valid()) throw IOException("Guide source changed")
        }
        fun append(programme: LiveTvProgramme) {
            pending += programme
            if (pending.size >= WRITE_BATCH) flush()
        }
        fun flush() = lock.withLock {
            checkValid()
            if (pending.isEmpty()) return@withLock
            val db = database.writableDatabase
            transaction(db) {
                var added = 0L
                var bytes = 0L
                db.compileStatement(INSERT_PROGRAMME).use { insert ->
                    pending.forEach { raw ->
                        // The lock fences owner epochs for this whole bounded transaction.
                        // Recheck external source configuration at its end, not per programme.
                        val p = raw.bounded(sanitizeArtwork = owner == "tvheadend")
                        require(p.eventId.isNotBlank() && p.eventId.length <= 4096 && p.channelUuid.length <= 1024 && p.stopEpochSeconds > p.startEpochSeconds)
                        val text = listOf(p.channelName, p.title, p.subtitle, p.summary, p.description, p.imageUrl, p.seriesLink, p.dvrUuid, p.sourceId)
                        val rowBytes = 256 + p.eventId.toByteArray(Charsets.UTF_8).size + p.channelUuid.toByteArray(Charsets.UTF_8).size +
                            text.sumOf { it?.toByteArray(Charsets.UTF_8)?.size ?: 0 }
                        insert.clearBindings()
                        insert.bindString(1, owner); insert.bindString(2, generation); insert.bindString(3, p.eventId)
                        insert.bindString(4, p.channelUuid); insert.bindLong(5, p.startEpochSeconds); insert.bindLong(6, p.stopEpochSeconds)
                        insert.bindLong(7, ordinal++)
                        text.forEachIndexed { index, value -> if (value == null) insert.bindNull(index + 8) else insert.bindString(index + 8, value) }
                        insert.bindLong(17, rowBytes.toLong())
                        if (insert.executeInsert() != -1L) { added++; bytes += rowBytes }
                    }
                }
                db.execSQL("UPDATE guide_versions SET entries=entries+?,bytes=bytes+? WHERE owner=? AND generation=?",
                    arrayOf<Any>(added, bytes, owner, generation))
                checkLimits(db)
                checkValid()
            }
            pending.clear()
        }
        fun checkLimits(db: SQLiteDatabase) {
            db.rawQuery("SELECT entries,bytes FROM guide_versions WHERE owner=? AND generation=?", arrayOf(owner, generation)).use {
                check(it.moveToFirst()) { "Guide import was removed" }
                if (it.getLong(0) > maxEntries || it.getLong(1) > guideByteLimit) throw IOException("Guide index is too large")
            }
            val total = db.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM guide_versions", null).use { it.moveToFirst(); it.getLong(0) }
            if (total > totalByteLimit + guideByteLimit) throw IOException("Guide cache is full")
        }
    }

    private fun publish(writer: Writer, fallback: Boolean, valid: () -> Boolean, ensureActive: () -> Unit) = lock.withLock {
        val db = database.writableDatabase
        transaction(db) {
            ensureActive(); writer.checkValid()
            db.execSQL("UPDATE guide_versions SET loaded_at=?,fallback=? WHERE owner=? AND generation=?",
                arrayOf<Any>(clock(), if (fallback) 1 else 0, writer.owner, writer.generation))
            db.insertWithOnConflict("guide_heads", null, ContentValues().apply {
                put("owner", writer.owner); put("scope", writer.scope); put("generation", writer.generation)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            db.delete("guide_versions", "owner=? AND generation<>?", arrayOf(writer.owner, writer.generation))
            prune(db)
            ensureActive()
            if (!valid()) throw IOException("Guide source changed")
        }
        writer.published = true
    }

    private fun finish(writer: Writer) = lock.withLock {
        try {
            if (!writer.published) database.writableDatabase.delete("guide_versions", "owner=? AND generation=?", arrayOf(writer.owner, writer.generation))
        } finally {
            val remaining = (importing[writer.owner] ?: 1) - 1
            if (remaining <= 0) importing.remove(writer.owner) else importing[writer.owner] = remaining
        }
    }

    private fun prune(db: SQLiteDatabase) {
        val protected = importing.keys
        val candidates = db.rawQuery("SELECT owner,loaded_at,bytes FROM guide_versions WHERE loaded_at>0 ORDER BY loaded_at", null).use { rows ->
            buildList { while (rows.moveToNext()) if (rows.getString(0) !in protected)
                add(Triple(rows.getString(0), rows.getLong(1), rows.getLong(2))) }
        }
        var total = db.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM guide_versions", null).use { it.moveToFirst(); it.getLong(0) }
        for ((owner, loaded, bytes) in candidates) {
            if (total <= totalByteLimit && clock() - loaded < RETENTION_MS) continue
            db.delete("guide_versions", "owner=?", arrayOf(owner))
            total -= bytes
        }
    }

    private fun active(db: SQLiteDatabase, owner: String, scope: String): Pair<String, ProgrammeGuideMetadata>? =
        db.rawQuery("""SELECT v.generation,v.loaded_at,v.from_epoch,v.to_epoch,v.fallback,v.entries
            FROM guide_heads h JOIN guide_versions v ON v.owner=h.owner AND v.generation=h.generation
            WHERE h.owner=? AND h.scope=?""", arrayOf(owner, scope)).use { rows ->
            if (!rows.moveToFirst() || rows.getLong(1) > clock() || clock() - rows.getLong(1) >= RETENTION_MS) null
            else rows.getString(0) to ProgrammeGuideMetadata(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getInt(4) != 0, rows.getLong(5))
        }

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try { return block().also { db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }

    override fun close() = database.close()

    private companion object {
        const val DATABASE_NAME = "programme-guides-v1.db"
        const val WRITE_BATCH = 256
        const val RETENTION_MS = 30L * 24 * 3600 * 1000
        const val INSERT_PROGRAMME = "INSERT OR IGNORE INTO guide_programmes VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        val READ_COLUMNS = arrayOf("event_id", "channel_id", "channel_name", "title", "subtitle", "summary", "description",
            "start_epoch", "stop_epoch", "image_url", "series_link", "dvr_id", "source_id")
        fun Cursor.programme() = LiveTvProgramme(getString(0), getString(1), getString(2), getString(3), getString(4), getString(5),
            getString(6), getLong(7), getLong(8), getString(9), getString(10), getString(11), getString(12))
        fun LiveTvProgramme.bounded(sanitizeArtwork: Boolean) = copy(
            channelName = channelName.take(512), title = title.take(1024), subtitle = subtitle?.take(4000),
            summary = summary?.take(16_384), description = description?.take(16_384),
            // IPTV's private, source-scoped XML cache already retains provider artwork URLs.
            // Signed/query URLs must keep working after indexing; they are never logged/exported.
            imageUrl = if (sanitizeArtwork) displayImageUrl(imageUrl) else imageUrl?.takeIf { it.length <= 8192 },
            seriesLink = seriesLink?.take(4096), dvrUuid = dvrUuid?.take(1024), sourceId = sourceId.take(1024),
        )
        fun displayImageUrl(value: String?): String? = value?.takeIf { it.length <= 8192 }?.let {
            runCatching {
                val uri = java.net.URI(it)
                if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) null
                else "${uri.scheme}://${uri.rawAuthority.substringAfterLast('@')}${uri.rawPath.orEmpty()}"
            }.getOrNull()
        }
    }
}
