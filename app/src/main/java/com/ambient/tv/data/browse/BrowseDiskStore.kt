package com.ambient.tv.data.browse

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Bounded, disposable display data. Neither credentials nor playback sources belong here. */
@Singleton
class BrowseDiskStore @Inject constructor(@ApplicationContext context: Context) {
    private val dispatcher = Executors.newSingleThreadExecutor { work ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); work.run() }, "browse-disk")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val database = object : SQLiteOpenHelper(context, "browse-display-v1.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE entries (scope TEXT NOT NULL, key TEXT NOT NULL, part INTEGER NOT NULL, payload TEXT NOT NULL, bytes INTEGER NOT NULL, stamp INTEGER NOT NULL, PRIMARY KEY(scope,key,part))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private data class Pending(val scope: String, val key: String, val valid: () -> Boolean, val encode: () -> String)
    private val pending = linkedMapOf<Pair<String, String>, Pending>()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in signal) {
                // Debounce foreground writes so UI/rendering stay responsive. If the app
                // backgrounds during the debounce, flush queued Home snapshots promptly
                // before Android has a chance to reclaim this process.
                var waitedMs = 0L
                while (BrowseWorkBudget.foreground && waitedMs < 1_500L) {
                    delay(250)
                    waitedMs += 250
                }
                while (BrowseWorkBudget.foreground && !BrowseWorkBudget.idle()) delay(500)
                while (true) {
                    val next = synchronized(pending) {
                        pending.entries.firstOrNull()?.let { pending.remove(it.key) }
                    } ?: break
                    if (!next.valid()) continue
                    try {
                        checkBrowseWriteBudget()
                        val payload = next.encode()
                        if (payload.length > 16 * 1024 * 1024 || !next.valid()) continue
                        checkBrowseWriteBudget()
                        val db = database.writableDatabase
                        db.beginTransaction()
                        try {
                            db.delete("entries", "scope=? AND key=?", arrayOf(next.scope, next.key))
                            val stamp = System.currentTimeMillis()
                            var offset = 0
                            var part = 0
                            while (offset < payload.length) {
                                checkBrowseWriteBudget()
                                var end = minOf(offset + 32 * 1024, payload.length)
                                if (end < payload.length && payload[end - 1].isHighSurrogate() && payload[end].isLowSurrogate()) end--
                                val chunk = payload.substring(offset, end)
                                db.insertOrThrow("entries", null, ContentValues().apply {
                                    put("scope", next.scope); put("key", next.key); put("part", part); put("payload", chunk)
                                    put("bytes", chunk.toByteArray(Charsets.UTF_8).size); put("stamp", stamp)
                                })
                                offset = end
                                part++
                            }
                            checkBrowseWriteBudget()
                            db.setTransactionSuccessful()
                        } finally { db.endTransaction() }
                        prune()
                    } catch (_: BrowseWritePaused) {
                        // Rollback has already restored the old entry. Never overwrite a newer queued snapshot.
                        synchronized(pending) {
                            if (pending.size < 256) pending.putIfAbsent(next.scope to next.key, next)
                        }
                        signal.trySend(Unit)
                        break
                    } catch (_: Exception) {
                        // A disposable cache failure must not break foreground loading.
                    }
                    // Yield between entries; encoding and SQLite never execute on the main thread.
                    delay(25)
                    if (BrowseWorkBudget.foreground && !BrowseWorkBudget.idle()) {
                        signal.trySend(Unit)
                        break
                    }
                }
            }
        }
    }

    fun put(scope: String, key: String, valid: () -> Boolean, encode: () -> String) {
        synchronized(pending) {
            pending[scope to key] = Pending(scope, key, valid, encode)
            while (pending.size > 256) pending.remove(pending.keys.first())
        }
        signal.trySend(Unit)
    }

    suspend fun read(scope: String, key: String? = null): Map<String, String> = withContext(dispatcher) {
        runCatching {
            val parts = linkedMapOf<String, StringBuilder>()
            database.readableDatabase.query("entries", arrayOf("key", "payload"), "scope=? AND stamp>?" + if (key != null) " AND key=?" else "",
                (listOf(scope, (System.currentTimeMillis() - RETENTION_MS).toString()) + listOfNotNull(key)).toTypedArray(), null, null, "stamp DESC, key, part ASC").use { cursor ->
                while (cursor.moveToNext()) parts.getOrPut(cursor.getString(0)) { StringBuilder() }.append(cursor.getString(1))
            }
            parts.mapValues { it.value.toString() }
        }.getOrDefault(emptyMap())
    }

    fun remove(scope: String) {
        synchronized(pending) { pending.keys.removeAll { it.first == scope } }
        this.scope.launch { runCatching { database.writableDatabase.delete("entries", "scope=?", arrayOf(scope)) } }
    }

    private fun prune() {
        checkBrowseWriteBudget()
        val db = database.writableDatabase
        db.delete("entries", "stamp<?", arrayOf((System.currentTimeMillis() - RETENTION_MS).toString()))
        // Single-item records (one per title ever opened or registered) are only an offline
        // fallback. Keep the most recent ones so they cannot crowd out list snapshots.
        db.execSQL(
            "DELETE FROM entries WHERE key LIKE 'item:%' AND (scope,key) NOT IN " +
                "(SELECT scope,key FROM entries WHERE key LIKE 'item:%' GROUP BY scope,key ORDER BY MAX(stamp) DESC LIMIT $MAX_ITEM_RECORDS)",
        )
        fun bytes(): Long = db.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM entries", null).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
        while (bytes() > 64L * 1024 * 1024) {
            checkBrowseWriteBudget()
            db.execSQL("DELETE FROM entries WHERE (scope,key) IN (SELECT scope,key FROM entries GROUP BY scope,key ORDER BY MIN(stamp) ASC LIMIT 1)")
        }
    }
    private companion object {
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_ITEM_RECORDS = 2_000
    }
}
