package com.ambient.tv.games.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Rebuildable display projection of the ROM index and portable choices. No ROM, credential or
 * independent user state lives here. The source tables/preferences remain the export contract.
 * Access is serialized by GameLibraryRepository; projection and scanner writes share a transaction.
 */
internal object GameLibraryQueryIndex {
    // Bump when title/search/system-label/eligibility rules change, so old projections rebuild without a NAS scan.
    const val POLICY_VERSION = 7

    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE game_query (
            file_id TEXT NOT NULL PRIMARY KEY, folder_id TEXT NOT NULL, system_id TEXT NOT NULL,
            favorite INTEGER NOT NULL, last_played INTEGER NOT NULL, custom_title TEXT,folder_depth INTEGER NOT NULL,
            title_auto TEXT NOT NULL,title_internal TEXT NOT NULL,title_filename TEXT NOT NULL,
            sort_auto BLOB NOT NULL,sort_internal BLOB NOT NULL,sort_filename BLOB NOT NULL,
            sort_system BLOB NOT NULL,
            search_auto TEXT NOT NULL,search_internal TEXT NOT NULL,search_filename TEXT NOT NULL,
            search_filename_raw TEXT NOT NULL,projection_stamp TEXT NOT NULL,
            metadata_title TEXT,metadata_year INTEGER,cover_path TEXT,backdrop_path TEXT,
            cover_width INTEGER,cover_height INTEGER,metadata_region TEXT,metadata_platform TEXT)""")
        // Most browsing uses automatic titles. Avoid nine copies of every title/file id in
        // secondary indexes; uncommon internal/filename sorting stays in SQLite.
        db.execSQL("CREATE INDEX game_query_title_auto ON game_query(sort_auto,file_id)")
        db.execSQL("CREATE INDEX game_query_system_auto ON game_query(system_id,sort_auto,file_id)")
        db.execSQL("CREATE INDEX game_query_favorite_auto ON game_query(sort_auto,file_id) WHERE favorite=1")
        db.execSQL("CREATE INDEX game_query_recent ON game_query(last_played DESC,folder_depth DESC,file_id)")
        db.execSQL("CREATE TABLE game_query_metadata (id INTEGER NOT NULL PRIMARY KEY CHECK(id=1),signature TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS game_entries_file ON game_entries(file_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS game_entries_source_path ON game_entries(source_id,path)")
    }

    fun addMetadataColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE game_query ADD COLUMN metadata_title TEXT")
        db.execSQL("ALTER TABLE game_query ADD COLUMN metadata_year INTEGER")
        db.execSQL("ALTER TABLE game_query ADD COLUMN cover_path TEXT")
        db.execSQL("ALTER TABLE game_query ADD COLUMN backdrop_path TEXT")
    }

    fun addCoverGeometryColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE game_query ADD COLUMN cover_width INTEGER")
        db.execSQL("ALTER TABLE game_query ADD COLUMN cover_height INTEGER")
        db.execSQL("ALTER TABLE game_query ADD COLUMN metadata_region TEXT")
        db.execSQL("ALTER TABLE game_query ADD COLUMN metadata_platform TEXT")
    }

    fun signature(db: SQLiteDatabase): String? = db.rawQuery(
        "SELECT signature FROM game_query_metadata WHERE id=1", null,
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun signature(db: SQLiteDatabase, value: String) = db.execSQL(
        "INSERT OR REPLACE INTO game_query_metadata(id,signature) VALUES(1,?)", arrayOf(value),
    )

    fun put(db: SQLiteDatabase, game: LibraryGame, systemName: String, checkExisting: Boolean = true, folderDepth: Int = 0) {
        val filename = gameDisplayFileName(game.filename)
        val internal = game.internalTitle?.takeIf(String::isNotBlank)
        val verified = game.verifiedTitle?.takeIf(String::isNotBlank)
        val sourceTitles = mapOf(
            "auto" to (game.customTitle ?: game.metadataTitle?.takeIf(String::isNotBlank) ?: verified ?: internal ?: filename),
            "internal" to (game.customTitle ?: internal ?: filename),
            "filename" to (game.customTitle ?: filename),
        )
        // Manual renames are literal user choices. Other sources retain their full names in the
        // catalog while all display modes omit explicit region/language annotations.
        val titles = sourceTitles.mapValues { (_, title) ->
            if (game.customTitle != null) title else gameDisplayTitle(title)
        }
        // A rescan normally changes only game_entries.seen. Keep the title indexes untouched
        // in that case; rewriting every unchanged index page is wasted I/O on TV flash.
        val stamp = gameQueryDigest(listOf(POLICY_VERSION.toString(), game.folderId, game.systemId, game.favorite.toString(),
            game.lastPlayed.toString(), game.customTitle.orEmpty(), folderDepth.toString(), systemName, game.filename,
            game.metadataTitle.orEmpty(), game.metadataYear?.toString().orEmpty(), game.coverPath.orEmpty(),
            game.backdropPath.orEmpty(), game.coverWidth?.toString().orEmpty(), game.coverHeight?.toString().orEmpty(),
            game.metadataRegion.orEmpty(), game.metadataPlatform.orEmpty()) + sourceTitles.values + titles.values)
        if (checkExisting && db.rawQuery("SELECT projection_stamp FROM game_query WHERE file_id=?",
                arrayOf(game.fileId)).use { it.moveToFirst() && it.getString(0) == stamp }) return
        val values = ContentValues(27).apply {
            put("file_id", game.fileId)
            put("folder_id", game.folderId)
            put("system_id", game.systemId)
            put("favorite", if (game.favorite) 1 else 0)
            put("last_played", game.lastPlayed)
            put("custom_title", game.customTitle)
            put("folder_depth", folderDepth)
            titles.forEach { (style, title) ->
                put("title_$style", title)
                put("sort_$style", gameSortKey(title))
                val sourceTitle = sourceTitles.getValue(style)
                // Both the clean visible name and exact source name remain searchable, so a
                // region/language query distinguishes editions even when the filename is opaque.
                put("search_$style", gameSearchKey(if (title == sourceTitle) title else "$title\n$sourceTitle"))
            }
            put("sort_system", gameSortKey(systemName))
            put("search_filename_raw", gameSearchKey(game.filename))
            put("projection_stamp", stamp)
            put("metadata_title", game.metadataTitle)
            if (game.metadataYear == null) putNull("metadata_year") else put("metadata_year", game.metadataYear)
            put("cover_path", game.coverPath)
            put("backdrop_path", game.backdropPath)
            if (game.coverWidth == null) putNull("cover_width") else put("cover_width", game.coverWidth)
            if (game.coverHeight == null) putNull("cover_height") else put("cover_height", game.coverHeight)
            put("metadata_region", game.metadataRegion)
            put("metadata_platform", game.metadataPlatform)
        }
        db.replaceOrThrow("game_query", null, values)
    }
}
