package com.ambient.tv.data.files

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class AudioTagRecord(val entry: FileEntry, val tags: AudioTags?)

/** A present key with a null value is a successful read of a file without supported tags. */
interface AudioTagStorage {
    suspend fun read(source: FileSource, entries: List<FileEntry>): Map<String, AudioTags?>
    suspend fun write(source: FileSource, records: List<AudioTagRecord>, valid: () -> Boolean)
}

/**
 * Content/account identity, independent of labels and wake preferences. Neither a username,
 * path, host nor credentials are stored in the cache. Length framing prevents ambiguous keys.
 */
internal fun audioTagScope(source: FileSource): String = audioTagHash(listOf(
    source.id, source.kind, source.treeUri, source.host.lowercase(Locale.ROOT), source.port.toString(),
    source.share, source.rootPath, source.username, source.domain, source.guest.toString(),
    source.secure.toString(), source.serverKey, source.encrypt.toString(), source.smbVersion.toString(), source.nfsVersion.toString(),
    source.nfsUid.toString(), source.nfsGid.toString(), source.nfsMountPort.toString(), source.wireGuard.toString(),
))

private fun audioTagHash(values: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        for (shift in 24 downTo 0 step 8) digest.update((bytes.size ushr shift).toByte())
        digest.update(bytes)
    }
    val hex = "0123456789abcdef"
    return buildString(64) {
        digest.digest().forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4]); append(hex[value and 15])
        }
    }
}

/**
 * Rebuildable metadata only. Reads are bounded to the displayed folder; writes and LRU touches
 * run on IO in short transactions. File versions survive process death without reopening a NAS
 * file. Providers without size/mtime receive a short-lived entry instead of an eternal hit.
 */
@Singleton
class AudioTagCache internal constructor(
    context: Context,
    private val clock: () -> Long,
    private val maxEntries: Int,
    private val maxBytes: Long,
) : AudioTagStorage, Closeable {
    init { require(maxEntries > 0 && maxBytes > 0) }
    @Inject constructor(@ApplicationContext context: Context) :
        this(context, System::currentTimeMillis, MAX_ENTRIES, MAX_BYTES)

    private val lock = Mutex()
    private val fileReads = Semaphore(4)
    /** The singleton budget also covers playback and multiple retained Files view models. */
    internal suspend fun <T> withReadPermit(block: suspend () -> T): T = fileReads.withPermit { block() }
    /** Guarded by lock: old source scopes need one cleanup, not a table walk per tag batch. */
    private val cleanedScopes = LinkedHashMap<String, String>()
    private val database = object : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE audio_tags (
                scope TEXT NOT NULL, file_key TEXT NOT NULL, source_id TEXT NOT NULL,
                file_size INTEGER NOT NULL, modified INTEGER NOT NULL, format TEXT NOT NULL,
                parser_version INTEGER NOT NULL, title TEXT, artist TEXT, album TEXT,
                album_artist TEXT, track INTEGER, disc INTEGER, year INTEGER,
                saved_at INTEGER NOT NULL, used_at INTEGER NOT NULL, bytes INTEGER NOT NULL,
                PRIMARY KEY(scope,file_key))""")
            db.execSQL("CREATE INDEX audio_tags_lru ON audio_tags(used_at)")
            db.execSQL("CREATE INDEX audio_tags_source ON audio_tags(source_id,scope)")
            // Constant-time size accounting avoids rescanning the cache for each write batch.
            db.execSQL("CREATE TABLE audio_tag_size (id INTEGER PRIMARY KEY, entries INTEGER NOT NULL, bytes INTEGER NOT NULL)")
            db.execSQL("INSERT INTO audio_tag_size VALUES(1,0,0)")
            db.execSQL("""CREATE TRIGGER audio_tags_insert AFTER INSERT ON audio_tags BEGIN
                UPDATE audio_tag_size SET entries=entries+1,bytes=bytes+NEW.bytes WHERE id=1; END""")
            db.execSQL("""CREATE TRIGGER audio_tags_delete AFTER DELETE ON audio_tags BEGIN
                UPDATE audio_tag_size SET entries=entries-1,bytes=bytes-OLD.bytes WHERE id=1; END""")
            db.execSQL("""CREATE TRIGGER audio_tags_update AFTER UPDATE OF bytes ON audio_tags BEGIN
                UPDATE audio_tag_size SET bytes=bytes+NEW.bytes-OLD.bytes WHERE id=1; END""")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }.apply { setWriteAheadLoggingEnabled(true) }

    override suspend fun read(source: FileSource, entries: List<FileEntry>): Map<String, AudioTags?> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    val scope = audioTagScope(source)
                    val now = clock()
                    val result = LinkedHashMap<String, AudioTags?>()
                    val db = database.readableDatabase
                    entries.filter { it.sourceId == source.id }.chunked(QUERY_BATCH).forEach { batch ->
                        currentCoroutineContext().ensureActive()
                        val wanted = batch.associateBy { audioTagHash(listOf(it.id)) }
                        if (wanted.isEmpty()) return@forEach
                        val touches = mutableListOf<String>()
                        db.query("audio_tags", READ_COLUMNS,
                            "scope=? AND file_key IN (${placeholders(wanted.size)})",
                            (listOf(scope) + wanted.keys).toTypedArray(), null, null, null).use { rows ->
                            while (rows.moveToNext()) {
                                val key = rows.getString(0)
                                val entry = wanted[key] ?: continue
                                if (rows.getLong(1) != entry.size || rows.getLong(2) != entry.modified ||
                                    rows.getString(3) != FilePaths.extension(entry.name) ||
                                    rows.getInt(4) != AudioTagReader.CACHE_VERSION) continue
                                val saved = rows.getLong(12)
                                if (saved > now || ((entry.modified <= 0L || entry.size <= 0L) && now - saved >= UNVERSIONED_TTL_MS)) continue
                                result[entry.id] = AudioTags(
                                    rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8),
                                    if (rows.isNull(9)) null else rows.getInt(9),
                                    if (rows.isNull(10)) null else rows.getInt(10),
                                    if (rows.isNull(11)) null else rows.getInt(11),
                                ).takeUnless { it.isEmpty }
                                // A browse hit normally does no write. Coarse LRU touches are batched.
                                if (now - rows.getLong(13) >= TOUCH_INTERVAL_MS) touches += key
                            }
                        }
                        if (touches.isNotEmpty()) {
                            currentCoroutineContext().ensureActive()
                            db.update("audio_tags", ContentValues().apply { put("used_at", now) },
                                "scope=? AND file_key IN (${placeholders(touches.size)})",
                                (listOf(scope) + touches).toTypedArray())
                        }
                    }
                    result
                } catch (_: SQLiteException) {
                    // Metadata is optional; a full or damaged cache must not break browsing.
                    emptyMap()
                }
            }
        }

    override suspend fun write(source: FileSource, records: List<AudioTagRecord>, valid: () -> Boolean) {
        if (records.isEmpty()) return
        withContext(Dispatchers.IO) {
            lock.withLock {
                currentCoroutineContext().ensureActive()
                if (!valid()) return@withLock
                try {
                    val db = database.writableDatabase
                    val scope = audioTagScope(source)
                    val now = clock()
                    var committed = false
                    db.beginTransaction()
                    try {
                        records.filter { it.entry.sourceId == source.id }.forEach { (entry, value) ->
                            currentCoroutineContext().ensureActive()
                            if (!valid()) return@withLock
                            val key = audioTagHash(listOf(entry.id))
                            val tags = value?.displayBounded()
                            val values = ContentValues().apply {
                                put("scope", scope); put("file_key", key); put("source_id", source.id)
                                put("file_size", entry.size); put("modified", entry.modified)
                                put("format", FilePaths.extension(entry.name)); put("parser_version", AudioTagReader.CACHE_VERSION)
                                put("title", tags?.title); put("artist", tags?.artist); put("album", tags?.album)
                                put("album_artist", tags?.albumArtist); put("track", tags?.track)
                                put("disc", tags?.disc); put("year", tags?.year)
                                put("saved_at", now); put("used_at", now)
                                put("bytes", RECORD_OVERHEAD + listOf(tags?.title, tags?.artist, tags?.album, tags?.albumArtist)
                                    .sumOf { it?.toByteArray(Charsets.UTF_8)?.size ?: 0 })
                            }
                            // REPLACE suppresses delete triggers on older SQLite configurations.
                            if (db.update("audio_tags", values, "scope=? AND file_key=?", arrayOf(scope, key)) == 0)
                                db.insertOrThrow("audio_tags", null, values)
                        }
                        // An edited source cannot keep a second account/root's stale metadata.
                        if (cleanedScopes[source.id] != scope)
                            db.delete("audio_tags", "source_id=? AND scope<>?", arrayOf(source.id, scope))
                        prune(db)
                        currentCoroutineContext().ensureActive()
                        if (valid()) {
                            db.setTransactionSuccessful()
                            committed = true
                        }
                    } finally { db.endTransaction() }
                    if (committed) {
                        cleanedScopes[source.id] = scope
                        while (cleanedScopes.size > 200) cleanedScopes.remove(cleanedScopes.keys.first())
                    }
                } catch (_: SQLiteException) {
                    // Cache IO failure does not turn a successfully read track into a browse error.
                }
            }
        }
    }

    suspend fun removeSource(sourceId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                database.writableDatabase.delete("audio_tags", "source_id=?", arrayOf(sourceId))
                cleanedScopes.remove(sourceId)
            }
            catch (_: SQLiteException) { /* Disposable cache. */ }
        }
    }

    private fun prune(db: SQLiteDatabase) {
        while (true) {
            val (count, bytes) = db.rawQuery("SELECT entries,bytes FROM audio_tag_size WHERE id=1", null).use {
                it.moveToFirst(); it.getLong(0) to it.getLong(1)
            }
            if (count <= maxEntries && bytes <= maxBytes) return
            val batch = maxOf(count - maxEntries, if (bytes > maxBytes) (count / 20).coerceIn(1, 32) else 0)
                .coerceIn(1, 256)
            db.execSQL("DELETE FROM audio_tags WHERE rowid IN (SELECT rowid FROM audio_tags ORDER BY used_at,rowid LIMIT $batch)")
        }
    }

    override fun close() = database.close()

    private companion object {
        const val DATABASE_NAME = "audio-tags-v1.db"
        const val MAX_ENTRIES = 20_000
        const val MAX_BYTES = 32L * 1024 * 1024
        const val QUERY_BATCH = 300
        const val RECORD_OVERHEAD = 256
        const val TOUCH_INTERVAL_MS = 6L * 60 * 60 * 1000
        const val UNVERSIONED_TTL_MS = 6L * 60 * 60 * 1000
        val READ_COLUMNS = arrayOf("file_key", "file_size", "modified", "format", "parser_version",
            "title", "artist", "album", "album_artist", "track", "disc", "year", "saved_at", "used_at")
        fun placeholders(count: Int) = List(count) { "?" }.joinToString(",")
    }
}
