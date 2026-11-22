package com.ambient.tv.ui.common

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.Locale

/** Null coordinates are a successful negative analysis, not a missing cache entry. */
internal data class ArtworkFocusPoint(val x: Float?, val y: Float?) {
    val valid: Boolean get() = (x == null && y == null) ||
        (x != null && y != null && x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
}

/**
 * Includes the actual image version (including provider URL revisions) and request identity.
 * Only this digest is persisted: neither signed URLs nor authentication headers reach disk.
 * Length prefixes prevent ambiguous URL/header concatenations. Header names are case insensitive.
 */
internal fun artworkFocusKey(url: String, headers: Map<String, String>, revision: String? = null): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    add("focus-analysis-v2")
    add(url)
    add(revision.orEmpty())
    headers.entries.map { it.key.lowercase(Locale.ROOT) to it.value }
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .forEach { (name, value) -> add(name); add(value) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Rebuildable, device-local metadata. Reads and writes run on Dispatchers.IO at the caller.
 * A full cache evicts only its coldest entries, so one new backdrop cannot discard the entire
 * user's analysis history. Reads touch an entry at most once an hour, not once per frame/card.
 */
internal class ArtworkFocusCache(
    context: Context,
    name: String = "artwork_analysis.db",
    private val maxEntries: Int = 2000,
    private val now: () -> Long = System::currentTimeMillis,
) : SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    init { require(maxEntries > 0) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE artwork_focus (
            image_key TEXT PRIMARY KEY NOT NULL, x REAL, y REAL,
            analyzed_at INTEGER NOT NULL, accessed_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX artwork_focus_lru ON artwork_focus(accessed_at, image_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun get(key: String): ArtworkFocusPoint? {
        val database = readableDatabase
        var touch = false
        val time = now()
        val result = database.rawQuery(
            "SELECT x,y,analyzed_at,accessed_at FROM artwork_focus WHERE image_key=?", arrayOf(key),
        ).use { rows ->
            if (!rows.moveToFirst()) return@use null
            // Revalidate mutable URLs eventually, also if the wall clock was moved backwards.
            if (time - rows.getLong(2) !in 0..MAX_AGE_MS) return@use null
            touch = time - rows.getLong(3) >= TOUCH_INTERVAL_MS
            ArtworkFocusPoint(
                if (rows.isNull(0)) null else rows.getFloat(0),
                if (rows.isNull(1)) null else rows.getFloat(1),
            ).takeIf { it.valid }
        }
        if (result != null && touch) database.execSQL(
            "UPDATE artwork_focus SET accessed_at=? WHERE image_key=?", arrayOf<Any>(time, key),
        )
        return result
    }

    @Synchronized
    fun put(key: String, point: ArtworkFocusPoint) {
        require(point.valid)
        val time = now()
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.insertWithOnConflict("artwork_focus", null, ContentValues().apply {
                put("image_key", key)
                put("x", point.x)
                put("y", point.y)
                put("analyzed_at", time)
                put("accessed_at", time)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            val overflow = DatabaseUtils.queryNumEntries(database, "artwork_focus") - maxEntries
            if (overflow > 0) database.execSQL(
                """DELETE FROM artwork_focus WHERE image_key IN (
                    SELECT image_key FROM artwork_focus ORDER BY accessed_at,image_key LIMIT ?)""",
                arrayOf(overflow),
            )
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun remove(key: String) {
        writableDatabase.delete("artwork_focus", "image_key=?", arrayOf(key))
    }

    private companion object {
        const val TOUCH_INTERVAL_MS = 60 * 60 * 1000L
        const val MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000L
    }
}
