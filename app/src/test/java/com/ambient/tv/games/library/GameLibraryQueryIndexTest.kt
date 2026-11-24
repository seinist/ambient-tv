package com.ambient.tv.games.library

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class, manifest = Config.NONE)
class GameLibraryQueryIndexTest {
    private lateinit var db: SQLiteDatabase

    @Before fun create() {
        db = SQLiteDatabase.create(null)
        db.execSQL("""CREATE TABLE game_entries (folder_id TEXT NOT NULL,file_id TEXT NOT NULL,
            source_id TEXT NOT NULL,path TEXT NOT NULL,filename TEXT NOT NULL,system_id TEXT NOT NULL,
            internal_title TEXT,file_size INTEGER,modified INTEGER,verified_title TEXT,
            dat_version INTEGER NOT NULL DEFAULT -1,
            PRIMARY KEY(folder_id,file_id))""")
        GameLibraryQueryIndex.create(db)
    }
    @After fun close() = db.close()

    private fun game(id: String, name: String, system: String = "snes", favorite: Boolean = false, played: Long = 0) =
        LibraryGame(fileId = id, folderId = "folder", sourceId = "nas", path = "$name.sfc",
            filename = "$name.sfc", systemId = system, title = name, internalTitle = name,
            size = 1, modified = 1, favorite = favorite, lastPlayed = played)

    private fun insert(game: LibraryGame) {
        db.execSQL("INSERT INTO game_entries VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
            game.folderId, game.fileId, game.sourceId, game.path, game.filename, game.systemId,
            game.internalTitle, game.size, game.modified, game.verifiedTitle, game.datVersion))
        GameLibraryQueryIndex.put(db, game, game.systemId)
    }

    private fun names(query: GameLibraryQuery, style: String = "auto"): List<String> {
        val sql = gameLibrarySql(query, style)
        return db.rawQuery(sql.sql, sql.args).use { rows -> buildList {
            while (rows.moveToNext()) add(rows.getString(6))
        } }
    }

    @Test fun sqliteFilteringOrderingAndLimitMatchTheRequest() {
        insert(game("1", "Ärger", favorite = true, played = 5))
        insert(game("2", "alpha", system = "gba", played = 10))
        insert(game("3", "Zulu", favorite = true, played = 7))
        insert(game("4", "100%_done's"))
        assertEquals(listOf("Zulu", "Ärger"), names(GameLibraryQuery(section = "favorites")))
        assertEquals(listOf("alpha", "Zulu"), names(GameLibraryQuery(section = "recent", limit = 2)))
        assertEquals(listOf("Ärger"), names(GameLibraryQuery(search = "är")))
        assertEquals(listOf("100%_done's"), names(GameLibraryQuery(search = "%_")))
        assertEquals(listOf("alpha"), names(GameLibraryQuery(section = "gba")))
        assertTrue(names(GameLibraryQuery(section = "gba", search = "Zulu")).isEmpty())
    }

    @Test fun allTitleModesPreserveManualAndVerifiedNamePrecedence() {
        val original = game("1", "Internal").copy(filename = "Raw_Name.sfc", verifiedTitle = "Verified")
        insert(original)
        assertEquals(listOf("Verified"), names(GameLibraryQuery()))
        assertEquals(listOf("Internal"), names(GameLibraryQuery(), "internal"))
        assertEquals(listOf("Raw Name"), names(GameLibraryQuery(), "filename"))
        GameLibraryQueryIndex.put(db, original.copy(customTitle = "Own title"), "snes")
        for (style in listOf("auto", "internal", "filename")) {
            assertEquals(listOf("Own title"), names(GameLibraryQuery(), style))
        }
    }

    @Test fun unchangedRescanDoesNotRewriteTheProjectionOrItsIndexes() {
        val original = game("1", "Unchanged")
        insert(original)
        fun changes() = db.rawQuery("SELECT total_changes()", null).use { it.moveToFirst(); it.getLong(0) }
        val before = changes()
        repeat(3) { GameLibraryQueryIndex.put(db, original, "snes") }
        assertEquals(before, changes())
        GameLibraryQueryIndex.put(db, original.copy(favorite = true), "snes")
        assertEquals(before + 1, changes())
        assertEquals(listOf("Unchanged"), names(GameLibraryQuery(section = "favorites")))
    }

    @Test fun recentTiesKeepThePreviousDeepFolderOrderWhileTheRecentSortUsesTitles() {
        val shallow = game("a", "Alpha", played = 10)
        val deep = game("z", "Zulu", played = 10)
        insert(shallow); insert(deep)
        GameLibraryQueryIndex.put(db, deep, "snes", folderDepth = 30)
        assertEquals(listOf("Zulu", "Alpha"), names(GameLibraryQuery(section = "recent", descending = true)))
        assertEquals(listOf("Alpha", "Zulu"), names(GameLibraryQuery(sort = "recent")))
    }

    @Test fun largeCatalogUsesSelectionIndexesAndOnlyReturnsTheRequestedHomeWindow() {
        db.beginTransaction()
        try {
            repeat(10_000) { index -> insert(game(index.toString(), "Game ${index.toString().padStart(5, '0')}",
                system = if (index % 5 == 0) "gba" else "snes", favorite = index % 11 == 0, played = index.toLong())) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        for ((query, index) in listOf(
            GameLibraryQuery(section = "gba", limit = 24) to "game_query_system_auto",
            GameLibraryQuery(section = "favorites", limit = 24) to "game_query_favorite_auto",
            GameLibraryQuery(section = "recent", limit = 24) to "game_query_recent",
            GameLibraryQuery(limit = 24) to "game_query_title_auto",
        )) {
            val sql = gameLibrarySql(query, "auto")
            val plan = db.rawQuery("EXPLAIN QUERY PLAN ${sql.sql}", sql.args).use { rows -> buildString {
                while (rows.moveToNext()) append(rows.getString(3)).append('\n')
            } }
            assertTrue(plan, plan.contains(index))
            assertFalse(plan, plan.contains("SCAN g\n") || plan.contains("SCAN TABLE game_entries"))
            assertEquals(24, names(query).size)
        }
        assertEquals(2_000, names(GameLibraryQuery(section = "gba")).size)
    }
}
