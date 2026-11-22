package com.ambient.tv.games.metadata

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Captured before asynchronous work. Only the repository may issue a valid ticket. */
@ConsistentCopyVisibility
data class GameMetadataTicket internal constructor(
    val fileId: String,
    val folderId: String,
    internal val folderFingerprint: String,
    internal val fileFingerprint: String,
    internal val epoch: String,
    /** Existing archive CRC; obtaining a ticket never opens the ROM. */
    val crc32: String? = null,
)

/** SQL belongs to the same transaction/lock as the ROM catalog, never to the rendering path. */
internal object GameMetadataStorage {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE game_metadata (
            file_id TEXT NOT NULL PRIMARY KEY,title TEXT,year INTEGER,
            payload TEXT NOT NULL,updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE game_artwork (
            file_id TEXT NOT NULL,kind TEXT NOT NULL,path TEXT NOT NULL,
            bytes INTEGER NOT NULL,pinned INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,
            PRIMARY KEY(file_id,kind))""")
        db.execSQL("CREATE INDEX game_artwork_retention ON game_artwork(pinned,updated_at)")
        db.execSQL("""CREATE TABLE game_metadata_checks (
            file_id TEXT NOT NULL,provider TEXT NOT NULL,fingerprint TEXT NOT NULL,
            status TEXT NOT NULL,expires_at INTEGER NOT NULL,
            PRIMARY KEY(file_id,provider,fingerprint))""")
        db.execSQL("""CREATE TABLE game_metadata_guards (
            file_id TEXT NOT NULL PRIMARY KEY,fingerprint TEXT NOT NULL,epoch TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE game_folder_scopes (
            folder_id TEXT NOT NULL PRIMARY KEY,source_id TEXT NOT NULL,
            path TEXT NOT NULL,source_scope TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS game_entries_folder_filename ON game_entries(folder_id,filename)")
    }

    fun read(db: SQLiteDatabase, fileId: String): GameMetadata? = db.rawQuery(
        "SELECT payload FROM game_metadata WHERE file_id=?", arrayOf(fileId),
    ).use { rows ->
        if (!rows.moveToFirst()) null
        else runCatching { json.decodeFromString<GameMetadata>(rows.getString(0)).bounded() }.getOrNull()
    }

    fun write(db: SQLiteDatabase, fileId: String, metadata: GameMetadata) {
        val value = metadata.bounded()
        db.replaceOrThrow("game_metadata", null, ContentValues().apply {
            put("file_id", fileId)
            put("title", value.title.takeIf(String::isNotBlank))
            if (value.year == null) putNull("year") else put("year", value.year)
            put("payload", json.encodeToString(value))
            put("updated_at", value.updatedAt)
        })
    }

    /** Automatic refresh cannot overwrite explicitly locked text or image selections. */
    fun merge(previous: GameMetadata?, incoming: GameMetadata, manual: Boolean): GameMetadata {
        val next = incoming.bounded()
        if (manual || previous == null) return next
        if (previous.matchLocked && (next.provider != previous.provider || next.providerGameId != previous.providerGameId))
            return previous
        val locked = previous.lockedFields
        return next.copy(
            title = if ("title" in locked) previous.title else next.title,
            summary = if ("summary" in locked) previous.summary else next.summary,
            year = if ("year" in locked) previous.year else next.year,
            genres = if ("genres" in locked) previous.genres else next.genres,
            developer = if ("developer" in locked) previous.developer else next.developer,
            publisher = if ("publisher" in locked) previous.publisher else next.publisher,
            players = if ("players" in locked) previous.players else next.players,
            region = if ("region" in locked) previous.region else next.region,
            coverUrl = if ("cover" in locked) previous.coverUrl else next.coverUrl,
            backdropUrl = if ("backdrop" in locked) previous.backdropUrl else next.backdropUrl,
            titleScreenUrl = if ("title_screen" in locked) previous.titleScreenUrl else next.titleScreenUrl,
            artworkProviders = next.artworkProviders.filterKeys {
                (if (it == "title") "title_screen" else it) !in locked
            } + previous.artworkProviders.filterKeys {
                (if (it == "title") "title_screen" else it) in locked
            },
            artworkSources = next.artworkSources.filterKeys {
                (if (it == "title") "title_screen" else it) !in locked
            } + previous.artworkSources.filterKeys {
                (if (it == "title") "title_screen" else it) in locked
            },
            lockedFields = locked + next.lockedFields,
            matchLocked = previous.matchLocked || next.matchLocked,
        )
    }
}
