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
import com.ambient.tv.ui.games.GameDetailViewModel
import com.ambient.tv.games.metadata.GameArtworkKind
import com.ambient.tv.games.metadata.GameMetadata
import com.ambient.tv.games.metadata.GameMetadataProviders
import com.ambient.tv.games.metadata.GameMetadataRepository
import com.ambient.tv.games.metadata.GameMetadataSettings
import com.ambient.tv.games.metadata.GameMetadataLocalImporter
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
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
    private fun metadataRepository(library: GameLibraryRepository): GameMetadataRepository {
        val settings = GameMetadataSettings(preferences, TokenStore(prefs, preferences))
        return GameMetadataRepository(context, library, GameMetadataProviders(settings,
            Json { ignoreUnknownKeys = true }, okhttp3.ConnectionPool(), okhttp3.Dispatcher(), context),
            settings, GameMetadataLocalImporter(library, files, access), prefs)
    }
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
            assertEquals(6, db.version)
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

    @Test fun versionFiveMigrationPreservesCatalogAndAddsMetadataWithoutRequiringAScan() {
        seed("one", verified = "DAT title", favorite = true)
        raw().use { db ->
            db.execSQL("""CREATE TABLE game_query (
                file_id TEXT NOT NULL PRIMARY KEY,folder_id TEXT NOT NULL,system_id TEXT NOT NULL,
                favorite INTEGER NOT NULL,last_played INTEGER NOT NULL,custom_title TEXT,folder_depth INTEGER NOT NULL,
                title_auto TEXT NOT NULL,title_internal TEXT NOT NULL,title_filename TEXT NOT NULL,
                sort_auto BLOB NOT NULL,sort_internal BLOB NOT NULL,sort_filename BLOB NOT NULL,sort_system BLOB NOT NULL,
                search_auto TEXT NOT NULL,search_internal TEXT NOT NULL,search_filename TEXT NOT NULL,
                search_filename_raw TEXT NOT NULL,projection_stamp TEXT NOT NULL)""")
            db.execSQL("CREATE TABLE game_query_metadata(id INTEGER NOT NULL PRIMARY KEY,signature TEXT NOT NULL)")
            db.version = 5
        }
        val library = repository()
        assertEquals("DAT title", library.game("one")!!.title)
        assertTrue(library.game("one")!!.favorite)
        val ticket = library.metadataTicket("one")!!
        assertEquals("12345678", ticket.crc32)
        assertTrue(library.saveMetadata(ticket, GameMetadata(title = "Online title", summary = "Full description", year = 1995)))
        assertEquals("Online title", library.game("one")!!.title)
        assertEquals(1995, library.game("one")!!.metadataYear)
        assertEquals("Full description", library.metadata("one")!!.summary)
        raw().use { assertEquals(6, it.version) }
    }

    @Test fun metadataLocksAndCanonicalIdentitySurviveTitleAndFavoriteEdits() {
        seed("one", verified = "DAT title")
        val library = repository()
        val ticket = library.metadataTicket("one")!!
        val selected = GameMetadata(title = "Selected", summary = "My description", provider = "libretro",
            providerGameId = "snes/Original (Europe)", canonicalTitle = "Original (Europe)",
            coverUrl = "https://example.invalid/selected.png", artworkProviders = mapOf("cover" to "screenscraper"),
            lockedFields = setOf("summary", "cover"))
        assertTrue(library.saveMetadata(ticket, selected, manual = true))
        assertTrue(library.saveMetadata(ticket, selected.copy(title = "New online title", summary = "Replacement",
            coverUrl = "https://example.invalid/automatic.png", artworkProviders = emptyMap(), lockedFields = emptySet())))
        library.setCustomTitle("one", "My short name")
        library.setFavorite("one", true)
        assertEquals("My short name", library.game("one")!!.title)
        assertEquals("My description", library.metadata("one")!!.summary)
        assertEquals("Original (Europe)", library.metadata("one")!!.canonicalTitle)
        assertEquals("https://example.invalid/selected.png", library.metadata("one")!!.coverUrl)
        assertEquals("screenscraper", library.metadata("one")!!.artworkProviders["cover"])
        assertEquals("Original (Europe)", preferences.gameLibraryOverrides.single().metadata!!.canonicalTitle)
    }

    private fun imageBytes(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jL1kAAAAASUVORK5CYII=")

    @Test fun removingOneOverlapPreservesTheOtherButTheLastRemovalPurgesAllOwnedData() {
        seed("same", folder = "root")
        seed("same")
        val library = repository()
        val ticket = library.metadataTicket("same")!!
        assertTrue(library.saveMetadata(ticket, GameMetadata(title = "Chosen"), manual = true))
        assertTrue(library.saveLookupStatus(ticket, "libretro", "query", "found", Long.MAX_VALUE))
        val original = File(context.cacheDir, "original-cover.png").apply { writeBytes(imageBytes()) }
        val staged = original.inputStream().use(library.artworkFiles::stage)
        assertTrue(library.setArtwork(ticket, GameArtworkKind.COVER, staged, pinned = true))
        val owned = File(library.game("same")!!.coverPath!!)
        assertTrue(owned.isFile)

        library.removeFolder("snes")
        assertEquals("root", library.game("same")!!.folderId)
        assertEquals("Chosen", library.metadata("same")!!.title)
        assertTrue(owned.isFile)
        assertFalse(library.saveMetadata(ticket, GameMetadata(title = "Late removed-folder result")))

        library.removeFolder("root")
        assertNull(library.game("same"))
        assertNull(library.metadata("same"))
        assertFalse(owned.exists())
        assertTrue(original.isFile)
        assertTrue(preferences.gameLibraryOverrides.isEmpty())
        for (table in listOf("game_entries", "game_query", "game_metadata", "game_artwork", "game_metadata_checks", "game_metadata_guards")) {
            raw().use { db -> db.rawQuery("SELECT COUNT(*) FROM $table", null).use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals(table, 0, rows.getInt(0))
            } }
        }
    }

    @Test fun removedThenReaddedFolderRejectsAnOldResultEvenWithTheSameRomVersion() {
        seed("one")
        val library = repository()
        val old = library.metadataTicket("one")!!
        val origin = library.folderToken("snes")!!
        library.removeFolder("snes")
        preferences.gameLibraryFolders = listOf(child)
        seed("one")
        assertNotNull(library.game("one"))
        assertNull(library.metadataTicket("one", "snes", origin))
        assertFalse(library.saveMetadata(old, GameMetadata(title = "Stale")))
        assertFalse(library.saveLookupStatus(old, "libretro", "q", "not_found", Long.MAX_VALUE))
        val staged = library.artworkFiles.stage(imageBytes().inputStream())
        assertFalse(library.setArtwork(old, GameArtworkKind.COVER, staged))
        assertTrue(library.saveMetadata(library.metadataTicket("one")!!, GameMetadata(title = "Fresh")))
        assertEquals("Fresh", library.game("one")!!.title)
    }

    @Test fun removingAnUnscannedFolderPurgesImportedChoicesOnlyAfterItsLastAssignment() {
        preferences.gameLibraryOverrides = listOf(
            GamePortableState("nas", "Games/SNES/unscanned.sfc", metadata = GameMetadata(title = "Imported")),
            GamePortableState("nas", "Games/SNES-Extras/keep.sfc", title = "Sibling"),
        )
        val library = repository()
        library.removeFolder("snes")
        assertEquals(2, preferences.gameLibraryOverrides.size)
        library.removeFolder("root")
        assertTrue(preferences.gameLibraryOverrides.isEmpty())
    }

    @Test fun replacedRomAndChangedSourceLocationsInvalidatePendingMetadata() {
        seed("one")
        val library = repository()
        val old = library.metadataTicket("one")!!
        raw().use { it.execSQL("UPDATE game_entries SET modified=456 WHERE file_id='one'") }
        assertFalse(library.saveMetadata(old, GameMetadata(title = "Previous ROM")))
        val changedRom = library.metadataTicket("one")!!
        assertTrue(library.saveMetadata(changedRom, GameMetadata(title = "Current ROM")))
        preferences.fileSources = listOf(preferences.fileSources.single().copy(share = "Different-ROMs"))
        // A full restore can reuse folder and source IDs. Source scopes still differ.
        preferences.gameLibraryFolders = listOf(root, child)
        library.reconcile()
        assertNull(library.game("one"))
        assertNull(library.metadata("one"))
        assertFalse(library.saveMetadata(changedRom, GameMetadata(title = "Wrong source")))
    }

    @Test fun pinsAreProtectedAndNegativeLookupsAreFingerprintAndExpiryScoped() {
        seed("one")
        val library = repository()
        val ticket = library.metadataTicket("one")!!
        assertTrue(library.setArtwork(ticket, GameArtworkKind.COVER,
            library.artworkFiles.stage(imageBytes().inputStream()), pinned = true))
        val original = library.artworkPath("one", GameArtworkKind.COVER)
        assertFalse(library.setArtwork(ticket, GameArtworkKind.COVER,
            library.artworkFiles.stage(imageBytes().inputStream())))
        assertEquals(original, library.artworkPath("one", GameArtworkKind.COVER))
        val now = System.currentTimeMillis()
        assertTrue(library.saveLookupStatus(ticket, "libretro", "q1", "not_found", now + 10_000))
        assertEquals("not_found", library.lookupStatus("one", "libretro", "q1", now))
        assertNull(library.lookupStatus("one", "libretro", "q2", now))
        assertNull(library.lookupStatus("one", "libretro", "q1", now + 10_001))
        assertTrue(library.saveLookupStatus(ticket, "libretro", "query", "found", Long.MAX_VALUE))
        assertTrue(library.saveLookupStatus(ticket, "libretro", "cover", "not_found", Long.MAX_VALUE))
        assertEquals("found", library.lookupStatus("one", "libretro", "query"))
        assertEquals("not_found", library.lookupStatus("one", "libretro", "cover"))
        assertEquals("not_found", library.lookupStatus("one", "libretro", "q1", now))
        for (index in 1..40) library.saveLookupStatus(ticket, "libretro", "variant-$index", "not_found", now + 20_000 + index)
        raw().use { db -> db.rawQuery("SELECT COUNT(*) FROM game_metadata_checks WHERE file_id='one'", null).use {
            assertTrue(it.moveToFirst()); assertEquals(32, it.getInt(0))
        } }
        assertTrue(library.saveLookupStatus(ticket, "libretro", "expired", "not_found", now - 1))
        assertNull(library.lookupStatus("one", "libretro", "expired", now))
        assertThrows(IllegalArgumentException::class.java) {
            library.saveLookupStatus(ticket, "libretro", "q1", "network_error", 200)
        }
    }

    @Test fun folderPagesAreStableAndIncludeMembershipHiddenByTheDeeperDisplayAssignment() {
        for (id in listOf("a", "b", "c")) { seed(id, folder = "root"); seed(id) }
        seed("outside", folder = "root", system = "gba", filename = "Outside.gba")
        val library = repository()
        assertEquals(4, library.folderGameCount("root"))
        assertEquals(3, library.folderGameCount("snes"))
        assertEquals(listOf("a", "b"), library.folderGames("snes", limit = 2).map { it.fileId })
        assertEquals(listOf("c"), library.folderGames("snes", afterFileId = "b", limit = 2).map { it.fileId })
        assertFalse(gameLibrarySql(GameLibraryQuery(folderId = "snes", limit = 2), "auto").sql.contains("payload"))
        preferences.gameLibraryFolders = listOf(root, child.copy(systemId = "gba"))
        assertEquals(0, library.folderGameCount("snes"))
        assertTrue(library.folderGames("snes").isEmpty())
        assertEquals(4, library.folderGameCount("root"))
    }

    @Test fun sidecarPointLookupsRequireExactPathsAndUniqueFilenames() {
        seed("us", filename = "Game.sfc")
        seed("eu", filename = "Game.sfc")
        seed("only", filename = "Only.sfc")
        raw().use {
            it.execSQL("UPDATE game_entries SET path='Games/SNES/US/Game.sfc' WHERE file_id='us'")
            it.execSQL("UPDATE game_entries SET path='Games/SNES/EU/Game.sfc' WHERE file_id='eu'")
        }
        val library = repository()
        assertEquals("eu", library.folderGameByPath("snes", "Games/SNES/EU/Game.sfc")!!.fileId)
        assertNull(library.folderGameByPath("snes", "Games/SNES/JP/Game.sfc"))
        assertNull(library.folderGameByUniqueFilename("snes", "Game.sfc"))
        assertEquals("only", library.folderGameByUniqueFilename("snes", "Only.sfc")!!.fileId)
        assertNull(library.folderGameByUniqueFilename("unknown", "Only.sfc"))
    }

    @Test fun manualUnlockKeepsTheCurrentImageButAllowsItsNextAutomaticReplacement() {
        seed("one")
        val library = repository()
        val ticket = library.metadataTicket("one")!!
        assertTrue(library.saveMetadata(ticket, GameMetadata(lockedFields = setOf("cover")), manual = true))
        assertTrue(library.setArtwork(ticket, GameArtworkKind.COVER,
            library.artworkFiles.stage(imageBytes().inputStream()), pinned = true))
        val original = library.artworkPath("one", GameArtworkKind.COVER)!!
        assertTrue(library.artworkPinned("one", GameArtworkKind.COVER))
        assertTrue(library.saveMetadata(ticket, GameMetadata(), manual = true))
        assertFalse(library.artworkPinned("one", GameArtworkKind.COVER))
        assertEquals(original, library.artworkPath("one", GameArtworkKind.COVER))
        assertTrue(File(original).exists())
        assertTrue(library.setArtwork(ticket, GameArtworkKind.COVER,
            library.artworkFiles.stage(imageBytes().inputStream())))
        assertNotEquals(original, library.artworkPath("one", GameArtworkKind.COVER))
        assertFalse(File(original).exists())
    }

    @Test fun metadataBatchesCoalesceOnlyMetadataChangesAndUnchangedContentDoesNotWrite() {
        seed("one")
        seed("two")
        val library = repository()
        val first = library.metadataTicket("one")!!
        val second = library.metadataTicket("two")!!
        val value = GameMetadata(title = "Provider title", summary = "Description")
        val before = library.revisions.value
        library.beginMetadataBatch()
        library.beginMetadataBatch()
        assertTrue(library.saveMetadata(first, value))
        assertTrue(library.saveMetadata(second, value))
        assertEquals(before, library.revisions.value)
        library.endMetadataBatch()
        assertEquals(before, library.revisions.value)
        library.setFavorite("one", true)
        assertEquals(before + 1, library.revisions.value)
        library.flushMetadataBatch()
        assertEquals(before + 2, library.revisions.value)
        library.endMetadataBatch()
        assertEquals(before + 2, library.revisions.value)
        val stored = library.metadata("one")!!
        assertTrue(library.saveMetadata(first, value.copy(updatedAt = stored.updatedAt + 100)))
        assertEquals(stored, library.metadata("one"))
        assertEquals(before + 2, library.revisions.value)
        library.beginMetadataBatch()
        assertTrue(library.saveMetadata(first, stored.copy(summary = "Explicit edit"), manual = true))
        assertEquals(before + 3, library.revisions.value)
        library.endMetadataBatch()
        assertEquals(before + 3, library.revisions.value)
    }

    @Test fun invalidAndOversizedArtworkLeavesNoStagedFiles() {
        val library = repository()
        assertThrows(java.io.IOException::class.java) {
            library.artworkFiles.stage("not an image".byteInputStream())
        }
        assertThrows(java.io.IOException::class.java) {
            library.artworkFiles.stage(ByteArray((com.ambient.tv.games.metadata.GameArtworkFiles.MAX_IMAGE_BYTES + 1).toInt()).inputStream())
        }
        assertTrue(File(context.cacheDir, "game_artwork_staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun automaticArtworkBudgetEvictsOldCopiesAndRetainsPins() {
        for (id in listOf("pin", "old", "new")) seed(id)
        val library = repository()
        fun image(id: String, pinned: Boolean = false) = library.setArtwork(library.metadataTicket(id)!!,
            GameArtworkKind.COVER, library.artworkFiles.stage(imageBytes().inputStream()), pinned)
        assertTrue(image("pin", pinned = true))
        assertTrue(image("old"))
        val pin = library.artworkPath("pin", GameArtworkKind.COVER)!!
        val old = library.artworkPath("old", GameArtworkKind.COVER)!!
        // Exercise retention without allocating 128 MiB just to represent cache accounting.
        raw().use { it.execSQL("UPDATE game_artwork SET bytes=?,updated_at=0 WHERE file_id='old'",
            arrayOf<Any>(com.ambient.tv.games.metadata.GameArtworkFiles.CACHE_BYTES + 1)) }
        assertTrue(image("new"))
        assertTrue(File(pin).exists())
        assertTrue(library.artworkPinned("pin", GameArtworkKind.COVER))
        assertNull(library.artworkPath("old", GameArtworkKind.COVER))
        assertNull(library.game("old")!!.coverPath)
        assertFalse(File(old).exists())
        assertNotNull(library.artworkPath("new", GameArtworkKind.COVER))
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
    @Test fun cachedDetailReloadsFavoriteAndRemovalWithoutOpeningOrWakingTheRomSource() = runBlocking {
        seed("one")
        val library = repository()
        assertTrue(library.saveMetadata(library.metadataTicket("one")!!,
            GameMetadata(title = "Cached title", summary = "Cached description", year = 1993)))
        val owner = ViewModelStore()
        val metadata = metadataRepository(library)
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val model = GameDetailViewModel(SavedStateHandle(mapOf("fileId" to "one")),
                library, metadata)
            owner.put("details", model)
            val loaded = withTimeout(10_000) { model.state.first { !it.loading } }
            assertNull(loaded.error)
            assertEquals("Cached title", loaded.game!!.title)
            assertEquals("Cached description", loaded.metadata.summary)
            assertEquals(1993, loaded.metadata.year)
            model.favorite()
            withTimeout(10_000) { model.state.first { it.game?.favorite == true && !it.busy } }
            library.removeFolder("snes")
            val removed = withTimeout(10_000) { model.state.first { it.game == null } }
            assertFalse(removed.loading)
            assertNull(removed.error)
            assertNull(library.metadata("one"))
        } finally {
            owner.clear()
            metadata.closeForTests()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun freshViewModelLoadsWithoutAPreferenceWriteAndProgressDoesNotRepeatItsSqlQuery() = runBlocking {
        seed("one")
        val library = repository()
        val owner = ViewModelStore()
        val metadata = metadataRepository(library)
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val model = GameLibraryViewModel(library, files, access, WakeMachines(preferences, DlnaStore(prefs)),
                preferences, context, metadata)
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
            metadata.closeForTests()
            Dispatchers.resetMain()
        }
    }
}
