package com.ambient.tv.games.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.ambient.tv.companion.CompanionConnectionStore
import com.ambient.tv.connectivity.HostConnectivity
import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.data.files.WakeOnLan
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.vpn.WireGuardRouteController
import com.ambient.tv.wol.WakeMachines
import com.ambient.tv.ui.games.GameLibraryViewModel
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class, manifest = Config.NONE)
class GameLibraryRepositoryQueryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("game-query-test", Context.MODE_PRIVATE)
    private lateinit var preferences: AppPreferences
    private lateinit var files: FileStore
    private lateinit var access: FileAccess
    private lateinit var wake: WakeOnLan
    private val root = GameLibraryFolder(id = "root", sourceId = "nas", path = "Games", systemId = "auto")
    private val child = GameLibraryFolder(id = "snes", sourceId = "nas", path = "Games/SNES", systemId = "snes")

    @Before fun prepareLegacyDatabaseAndConfiguredSources() {
        context.deleteDatabase("game_library.db")
        prefs.edit().clear().commit()
        preferences = AppPreferences(prefs)
        val tokens = TokenStore(prefs, preferences)
        files = FileStore(preferences, tokens, prefs)
        preferences.fileSources = listOf(FileSource(id = "nas", name = "ROMs", kind = "smb",
            host = "192.0.2.5", share = "ROMs"))
        preferences.gameLibraryFolders = listOf(root, child)
        val dlna = DlnaStore(prefs)
        val hosts = HostConnectivity(preferences, dlna, WakeMachines(preferences, dlna))
        wake = WakeOnLan(directSend = { _, _, _ -> error("A local catalog query must never wake the NAS") })
        access = FileAccess(context, files, hosts, wake,
            WireGuardRouteController(context, preferences, tokens, CompanionConnectionStore(context)))
        context.getDatabasePath("game_library.db").parentFile!!.mkdirs()
        raw().use { db ->
            db.execSQL("""CREATE TABLE game_entries (
                folder_id TEXT NOT NULL,file_id TEXT NOT NULL,source_id TEXT NOT NULL,path TEXT NOT NULL,
                filename TEXT NOT NULL,system_id TEXT NOT NULL,internal_title TEXT,file_size INTEGER NOT NULL,
                modified INTEGER NOT NULL,favorite INTEGER NOT NULL DEFAULT 0,last_played INTEGER NOT NULL DEFAULT 0,
                seen INTEGER NOT NULL DEFAULT 0,manual_title TEXT,verified_title TEXT,
                dat_checked INTEGER NOT NULL DEFAULT 0,probe_version INTEGER NOT NULL DEFAULT 0,
                dat_version INTEGER NOT NULL DEFAULT -1,archive_crc TEXT,PRIMARY KEY(folder_id,file_id))""")
            db.execSQL("CREATE INDEX game_entries_system ON game_entries(system_id)")
            db.execSQL("CREATE INDEX game_entries_source ON game_entries(source_id)")
            db.execSQL("CREATE TABLE game_dat_entries(system_id TEXT NOT NULL,crc32 TEXT NOT NULL,title TEXT NOT NULL,PRIMARY KEY(system_id,crc32))")
            db.execSQL("CREATE TABLE game_dat_versions(system_id TEXT NOT NULL PRIMARY KEY,revision INTEGER NOT NULL)")
            db.execSQL("INSERT INTO game_dat_entries VALUES('snes','12345678','Verified DAT name')")
            db.execSQL("INSERT INTO game_dat_versions VALUES('snes',7)")
            db.version = 4
        }
    }

    private fun raw(): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("game_library.db"), null)
    private fun repository() = GameLibraryRepository(context, prefs, files, preferences, access, wake)
    private fun seed(
        id: String, folder: String = "snes", system: String = "snes", filename: String = "$id.sfc",
        title: String = id, favorite: Boolean = false, played: Long = 0, source: String = "nas",
        manual: String? = null, verified: String? = null,
    ) = raw().use { db ->
        db.execSQL("""INSERT INTO game_entries(folder_id,file_id,source_id,path,filename,system_id,
            internal_title,file_size,modified,favorite,last_played,manual_title,verified_title,probe_version,
            dat_version,archive_crc,dat_checked) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf<Any?>(folder, id, source, "Games/SNES/$filename", filename, system, title, 100, 123,
                if (favorite) 1 else 0, played, manual, verified, 2, 7, "12345678", 1))
    }

    @Test fun migrationPreservesMetadataAndDeduplicatesBeforeSectionAndFavoriteFiltering() {
        seed("same", folder = "root", system = "gba", filename = "Game.gba", title = "Parent", favorite = true)
        seed("same", filename = "Game.sfc", title = "Child")
        seed("other", folder = "root", system = "gba", filename = "Other.gba")
        seed("foreign", filename = "Wrong.gba")
        seed("orphan", folder = "removed")
        seed("old-source", source = "unconfigured")
        val library = repository()
        assertEquals(listOf("same", "other"), library.games().map { it.fileId })
        assertEquals("snes", library.game("same")!!.folderId)
        assertEquals(listOf("other"), library.games(GameLibraryQuery(section = "gba")).map { it.fileId })
        assertTrue(library.games(GameLibraryQuery(section = "favorites")).isEmpty())
        raw().use { db ->
            assertEquals(5, db.version)
            db.rawQuery("SELECT revision FROM game_dat_versions WHERE system_id='snes'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(7L, it.getLong(0))
            }
            db.rawQuery("SELECT archive_crc,probe_version FROM game_entries WHERE folder_id='snes' AND file_id='same'", null).use {
                assertTrue(it.moveToFirst()); assertEquals("12345678", it.getString(0)); assertEquals(2, it.getInt(1))
            }
        }
    }

    @Test fun restoredOverridesBeatDatabaseFavoritesAndKeepTheNewestPlayTime() {
        seed("one", favorite = true, played = 50, manual = "Local title", verified = "DAT title")
        preferences.gameLibraryOverrides = listOf(GamePortableState("nas", "Games/SNES/one.sfc",
            title = "Imported title", favorite = false, lastPlayed = 20))
        val library = repository()
        assertTrue(library.games(GameLibraryQuery(section = "favorites")).isEmpty())
        assertEquals("Imported title", library.game("one")!!.title)
        assertEquals(50L, library.game("one")!!.lastPlayed)
        preferences.gameLibraryOverrides = listOf(GamePortableState("nas", "Games/SNES/one.sfc",
            title = "New imported title", favorite = true, lastPlayed = 70))
        assertEquals("New imported title", library.games(GameLibraryQuery(section = "favorites")).single().title)
        assertEquals(70L, library.game("one")!!.lastPlayed)
        library.setFavorite("one", false)
        assertEquals("New imported title", preferences.gameLibraryOverrides.single().title)
        assertEquals(70L, preferences.gameLibraryOverrides.single().lastPlayed)
        library.setCustomTitle("one", "Renamed")
        assertFalse(library.game("one")!!.favorite)
        assertEquals(70L, library.game("one")!!.lastPlayed)
    }

    @Test fun titleEditsAndRemovingTheDeepestAssignmentRefreshExistingAndReopenedRepositories() {
        seed("same", folder = "root", title = "Parent")
        seed("same", title = "Child")
        val library = repository()
        assertEquals("Child", library.game("same")!!.title)
        library.setCustomTitle("same", "My name")
        library.setFavorite("same", true)
        assertEquals("My name", library.games(GameLibraryQuery(search = "MY NAME")).single().title)
        assertTrue(preferences.gameLibraryOverrides.single().favorite)
        library.removeFolder("snes")
        assertEquals("root", library.game("same")!!.folderId)
        assertEquals("My name", repository().game("same")!!.title)
        assertTrue(repository().games(GameLibraryQuery(section = "favorites")).single().favorite)
    }

    @Test fun warmReopenAndRepeatedQueriesDoNotRebuildOrWriteTheDisplayProjection() {
        seed("one")
        repository().games()
        raw().use { db ->
            db.execSQL("CREATE TABLE projection_writes(count INTEGER NOT NULL)")
            db.execSQL("INSERT INTO projection_writes VALUES(0)")
            db.execSQL("CREATE TRIGGER watch_projection AFTER INSERT ON game_query BEGIN UPDATE projection_writes SET count=count+1; END")
        }
        val reopened = repository()
        repeat(3) {
            assertEquals(1, reopened.games(GameLibraryQuery(section = "snes", search = "one")).size)
        }
        raw().use { db -> db.rawQuery("SELECT count FROM projection_writes", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        } }
    }

    @Test fun importedAssignmentChangesAndRemovedSourcesHideCachedRowsWithoutRescanning() {
        seed("one")
        val library = repository()
        assertEquals(1, library.games().size)
        preferences.gameLibraryFolders = listOf(child.copy(systemId = "gba"))
        assertTrue(library.games().isEmpty())
        preferences.gameLibraryFolders = listOf(child)
        assertEquals(1, library.games().size)
        preferences.fileSources = emptyList()
        assertTrue(library.games().isEmpty())
    }

    @Test fun playbackPreferenceWritesAndViewOnlyChangesDoNotInvalidateCatalogReads() {
        seed("one")
        val library = repository()
        val before = library.revisionSnapshot()
        prefs.edit().putString("file_history", "[]").putString("dlna_progress", "[]")
            .putString("youtube_local_progress", "[]").putInt("unrelated_video_option", 4).commit()
        assertEquals(before, library.revisionSnapshot())
        library.setGridColumns(6)
        library.setViewStyle("list")
        assertEquals(before, library.revisionSnapshot())
        preferences.gameLibraryOverrides = listOf(GamePortableState("nas", "Games/SNES/one.sfc", favorite = true))
        assertNotEquals(before, library.revisionSnapshot())
        assertEquals(before.copy(overrides = ""), library.revisionSnapshot(includeOverrides = false))
        val imported = library.revisionSnapshot()
        library.setFavorite("one", false)
        assertNotEquals(imported, library.revisionSnapshot())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun freshViewModelLoadsWithoutAPreferenceWriteAndProgressDoesNotRepeatItsSqlQuery() = runBlocking {
        seed("one")
        val library = repository()
        val owner = ViewModelStore()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val model = GameLibraryViewModel(library, files, access, WakeMachines(preferences, DlnaStore(prefs)),
                preferences, context)
            owner.put("games", model)
            model.section("snes")
            val loaded = withTimeout(10_000) { model.state.first { it.ready } }
            assertNull(loaded.error)
            assertEquals(listOf("one"), loaded.games.map { it.fileId })
            val reads = library.readQueryCount
            assertEquals(1L, reads)

            files.record(FileEntry("nas", "Music/song.mp3", "song.mp3", false, 100, 123, "audio/mpeg"),
                position = 1000, duration = 10_000)
            prefs.edit().putInt("unrelated_video_option", 7).commit()
            // A relevant UI-only event is the processing barrier: once it is visible, the
            // preceding progress/preferences signals cannot trigger an unnoticed later read.
            model.view("list")
            withTimeout(10_000) { model.state.first { it.view == "list" } }
            assertEquals(reads, library.readQueryCount)

            model.query("missing")
            val searched = withTimeout(10_000) { model.state.first { it.query == "missing" && it.games.isEmpty() } }
            assertNull(searched.error)
            assertEquals(reads + 1, library.readQueryCount)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }
}
