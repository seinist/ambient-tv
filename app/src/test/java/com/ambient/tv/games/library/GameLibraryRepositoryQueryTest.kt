package com.ambient.tv.games.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
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
import com.ambient.tv.ui.games.gameOperationError
import com.ambient.tv.R
import com.ambient.tv.games.metadata.GameArtworkFailure
import com.ambient.tv.games.metadata.GameArtworkPriority
import com.ambient.tv.games.metadata.GameArtworkKind
import com.ambient.tv.games.metadata.GameArtworkFiles
import com.ambient.tv.games.metadata.GameArtworkValidationException
import com.ambient.tv.games.metadata.GameMetadata
import com.ambient.tv.games.metadata.GameMetadataBatchException
import com.ambient.tv.games.metadata.GameMetadataFailure
import com.ambient.tv.games.metadata.GameMetadataProviderException
import com.ambient.tv.games.metadata.GameMetadataProviders
import com.ambient.tv.games.metadata.GameMetadataRepository
import com.ambient.tv.games.metadata.GameMetadataSettings
import com.ambient.tv.games.metadata.GameMetadataLocalImporter
import com.ambient.tv.games.metadata.gameMetadataFingerprint
import com.ambient.tv.games.metadata.gameMetadataDigest
import com.ambient.tv.games.metadata.gameMetadataLookupFingerprint
import com.ambient.tv.games.metadata.gameMetadataQuery
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
import org.robolectric.annotation.GraphicsMode
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
    private fun metadataRepository(library: GameLibraryRepository,
                                   interceptor: okhttp3.Interceptor? = null): GameMetadataRepository {
        val settings = GameMetadataSettings(preferences, TokenStore(prefs, preferences))
        val providers = GameMetadataProviders(settings,
            Json { ignoreUnknownKeys = true }, okhttp3.ConnectionPool(), okhttp3.Dispatcher(), context)
        if (interceptor != null) {
            val field = providers.javaClass.getDeclaredField("client").apply { isAccessible = true }
            field.set(providers, (field.get(providers) as okhttp3.OkHttpClient).newBuilder()
                .addInterceptor(interceptor).build())
        }
        return GameMetadataRepository(context, library, providers,
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

    @Test fun duplicateFolderShowsExistingAssignmentInsteadOfSuggestingRetry() {
        val library = repository()
        val error = runCatching { library.addFolder(files.sources().single(), root.path, root.systemId) }
            .exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(context.getString(R.string.game_error_folder_assigned),
            gameOperationError(context, error!!, R.string.game_error_update))
        assertEquals(listOf(root, child), library.folders())
    }

    @Test fun folderLimitExplainsThatAnAssignmentMustBeRemoved() {
        preferences.gameLibraryFolders = (0 until 200).map { index ->
            root.copy(id = "folder-$index", path = "Games/$index")
        }
        val library = repository()
        val error = runCatching { library.addFolder(files.sources().single(), "Another", "auto") }
            .exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(context.getString(R.string.game_error_folder_limit),
            gameOperationError(context, error!!, R.string.game_error_update))
        assertEquals(200, library.folders().size)
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
            assertEquals(8, db.version)
            db.rawQuery("SELECT revision FROM game_dat_versions WHERE system_id='snes'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(7L, it.getLong(0))
            }
            db.rawQuery("SELECT archive_crc,probe_version FROM game_entries WHERE folder_id='snes' AND file_id='same'", null).use {
                assertTrue(it.moveToFirst()); assertEquals("12345678", it.getString(0)); assertEquals(2, it.getInt(1))
            }
        }
    }

    @Test fun migrationMakesExistingDatTitlesSearchableWithoutSourceAccess() {
        val library = repository()
        assertEquals(listOf("Verified DAT name"), library.searchDatTitles("snes", "verified_datname"))
        assertTrue(library.searchDatTitles("gba", "verified").isEmpty())
        raw().use { db ->
            assertEquals(8, db.version)
            db.rawQuery("SELECT search_title FROM game_dat_entries", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("verifieddatname", rows.getString(0))
            }
        }
    }

    @Test fun importedNamesSearchBySpacingPunctuationAndAccentsWithBoundedDistinctResults() = runBlocking {
        val library = repository()
        val dat = """<datafile>
            <game name="Super Mario World (USA)"><rom crc="11111111"/></game>
            <game name="Super Mario World (USA)"><rom crc="11111112"/></game>
            <game name="Super Mario World (Europe)"><rom crc="22222222"/></game>
            <game name="Super Mario World 2 - Yoshi’s Island (USA)"><rom crc="33333333"/></game>
            <game name="Pokémon (World)"><rom crc="44444444"/></game>
            </datafile>"""
        library.importDat("snes", dat.byteInputStream())
        assertEquals(listOf("Super Mario World (Europe)", "Super Mario World (USA)"),
            library.searchDatTitles("snes", "SUPER MARIOWORLD", 2))
        assertEquals(3, library.searchDatTitles("snes", "super_mario-world").size)
        assertEquals(listOf("Pokémon (World)"), library.searchDatTitles("snes", "pokemon"))
        assertTrue(library.searchDatTitles("gba", "Mario").isEmpty())
        assertTrue(library.searchDatTitles("snes", "_%").isEmpty())
        assertTrue(library.searchDatTitles("snes", "Mario", 0).isEmpty())
    }

    @Test fun importingDatImmediatelyMatchesCachedRawAndHeaderlessCrcWithoutReplacingUserEdits() = runBlocking {
        seed("raw", verified = "Obsolete catalog title")
        seed("headered", title = "SUPER MARIOWORLD")
        seed("manual", manual = "My title")
        seed("unknown", verified = "Obsolete guess")
        val library = repository()
        library.games() // Finish schema migration before adding the new cached checksum.
        raw().use { db ->
            db.execSQL("UPDATE game_entries SET archive_crc='DEADBEEF',dat_crc='AABBCCDD' WHERE file_id='headered'")
            db.execSQL("UPDATE game_entries SET archive_crc=NULL WHERE file_id='unknown'")
        }
        val locked = GameMetadata(title = "Locked title", summary = "My notes", lockedFields = setOf("title", "summary"))
        assertTrue(library.saveMetadata(library.metadataTicket("manual")!!, locked, manual = true))
        val dat = """<datafile>
            <game name="Raw catalog title"><rom crc="12345678"/></game>
            <game name="Super Mario World (Europe)"><rom crc="AABBCCDD"/></game>
            </datafile>"""
        library.importDat("snes", dat.byteInputStream())
        assertEquals("Raw catalog title", library.game("raw")!!.title)
        assertEquals(8L, library.game("raw")!!.datVersion)
        assertEquals("Super Mario World", library.game("headered")!!.title)
        assertEquals("My title", library.game("manual")!!.title)
        assertEquals("Locked title", library.metadata("manual")!!.title)
        assertEquals("My notes", library.metadata("manual")!!.summary)
        assertEquals(setOf("title", "summary"), library.metadata("manual")!!.lockedFields)
        assertNull(library.game("unknown")!!.verifiedTitle)
        raw().use { db ->
            db.rawQuery("SELECT archive_crc,dat_crc,dat_checked,dat_version FROM game_entries WHERE file_id='headered'", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("DEADBEEF", row.getString(0))
                assertEquals("AABBCCDD", row.getString(1))
                assertEquals(1, row.getInt(2))
                assertEquals(8L, row.getLong(3))
            }
        }
    }

    @Test fun invalidReplacementDatPreservesExistingNamesAndSearchKeys() = runBlocking {
        val library = repository()
        assertTrue(runCatching { library.importDat("snes", "<datafile/>".byteInputStream()) }.isFailure)
        assertEquals(listOf("Verified DAT name"), library.searchDatTitles("snes", "Verified"))
        assertEquals(1, library.datCatalogSizes()["snes"])
    }

    @Test fun importedDatCorrectsOnlyAutomaticLibretroDisplayTitlesEvenWhenOnlineMetadataIsOff() = runBlocking {
        preferences.gameMetadataOptions = preferences.gameMetadataOptions.copy(automatic = false)
        for (id in listOf("automatic", "matched", "locked", "gamesdb", "custom")) seed(id)
        val library = repository()
        val automatic = GameMetadata(title = "INTERNAL NAME", canonicalTitle = "INTERNAL NAME", provider = "libretro",
            providerGameId = "Nintendo - Super Nintendo Entertainment System|INTERNAL NAME", updatedAt = 123L)
        for (id in listOf("automatic", "matched", "locked", "gamesdb", "custom")) {
            val value = when (id) {
                "matched" -> automatic.copy(matchLocked = true)
                "locked" -> automatic.copy(lockedFields = setOf("title"))
                "gamesdb" -> automatic.copy(provider = "thegamesdb")
                else -> automatic
            }
            assertTrue(library.saveMetadata(library.metadataTicket(id)!!, value))
        }
        library.setCustomTitle("custom", "My custom name")
        val storedAutomatic = library.metadata("automatic")!!
        library.importDat("snes", """<datafile><game name="Super Mario World (USA)"><rom crc="12345678"/></game></datafile>""".byteInputStream())
        assertEquals("Super Mario World", library.game("automatic")!!.title)
        assertEquals("Super Mario World (USA)", library.game("automatic")!!.metadataTitle)
        assertEquals(storedAutomatic.copy(title = "Super Mario World (USA)"), library.metadata("automatic"))
        for (id in listOf("matched", "locked", "gamesdb")) {
            assertEquals("INTERNAL NAME", library.game(id)!!.title)
            assertEquals("INTERNAL NAME", library.metadata(id)!!.title)
        }
        assertEquals("My custom name", library.game("custom")!!.title)
        assertEquals("INTERNAL NAME", library.metadata("custom")!!.title)
    }

    @Test fun openingAnExistingV8CatalogRepairsStaleAutomaticNamesOnlyOnceWithoutSourceAccess() {
        preferences.gameMetadataOptions = preferences.gameMetadataOptions.copy(automatic = false)
        val ids = listOf("automatic", "matched", "locked", "gamesdb", "custom")
        for (id in ids) seed(id, title = "INTERNAL NAME", verified = "Super Mario World (USA)")
        val oldLibrary = repository()
        val automatic = GameMetadata(title = "INTERNAL NAME", canonicalTitle = "INTERNAL NAME", provider = "libretro",
            providerGameId = "Nintendo - Super Nintendo Entertainment System|INTERNAL NAME")
        for (id in ids) {
            val value = when (id) {
                "matched" -> automatic.copy(matchLocked = true)
                "locked" -> automatic.copy(lockedFields = setOf("title"))
                "gamesdb" -> automatic.copy(provider = "thegamesdb")
                else -> automatic
            }
            assertTrue(oldLibrary.saveMetadata(oldLibrary.metadataTicket(id)!!, value))
        }
        oldLibrary.setCustomTitle("custom", "My custom name")
        val storedAutomatic = oldLibrary.metadata("automatic")!!
        assertEquals("Super Mario World (USA)", oldLibrary.game("automatic")!!.verifiedTitle)
        assertEquals("INTERNAL NAME", oldLibrary.game("automatic")!!.title)
        raw().use { db ->
            assertEquals(8, db.version)
            // Reproduce the persisted display policy from the release before this repair.
            GameLibraryQueryIndex.signature(db, gameQueryDigest(listOf("3",
                prefs.getString("game_library_folders", "[]").orEmpty(),
                prefs.getString("game_library_overrides", "[]").orEmpty(),
                prefs.getString("file_playback_scopes", "{}").orEmpty()) + files.sources().map { it.id }.sorted()))
        }

        val reopened = repository()
        assertEquals("Super Mario World", reopened.game("automatic")!!.title)
        assertEquals("Super Mario World (USA)", reopened.game("automatic")!!.metadataTitle)
        // Preserve the old lookup identity/freshness so artwork can resolve for the corrected name.
        assertEquals(storedAutomatic.copy(title = "Super Mario World (USA)"), reopened.metadata("automatic"))
        for (id in listOf("matched", "locked", "gamesdb")) {
            assertEquals("INTERNAL NAME", reopened.game(id)!!.title)
            assertEquals("INTERNAL NAME", reopened.metadata(id)!!.title)
        }
        assertEquals("My custom name", reopened.game("custom")!!.title)
        assertEquals("INTERNAL NAME", reopened.metadata("custom")!!.title)
        reopened.setTitleStyle("internal")
        assertEquals("INTERNAL NAME", reopened.game("automatic")!!.title)
        reopened.setTitleStyle("filename")
        assertEquals("automatic", reopened.game("automatic")!!.title)
        reopened.setTitleStyle("auto")

        raw().use { db ->
            db.execSQL("CREATE TABLE repair_writes(count INTEGER NOT NULL)")
            db.execSQL("INSERT INTO repair_writes VALUES(0)")
            db.execSQL("CREATE TRIGGER watch_repaired_projection AFTER INSERT ON game_query BEGIN UPDATE repair_writes SET count=count+1; END")
            db.execSQL("CREATE TRIGGER watch_repaired_metadata AFTER INSERT ON game_metadata BEGIN UPDATE repair_writes SET count=count+1; END")
        }
        val stable = repository()
        repeat(3) { assertEquals("Super Mario World", stable.game("automatic")!!.title) }
        raw().use { db -> db.rawQuery("SELECT count FROM repair_writes", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        } }
    }

    @Test fun openingPolicyFiveCleansLanguageTagsOnceWithoutChangingMetadataIdentity() {
        preferences.gameMetadataOptions = preferences.gameMetadataOptions.copy(automatic = false)
        val canonical = "Super Mario World (USA) (En,de,fr) (Rev 1)"
        val oldDisplay = "Super Mario World (En,de,fr) (Rev 1)"
        seed("one", title = "Internal (Japan) (En,Ja)", filename = "opaque.sfc", verified = canonical)
        val oldLibrary = repository()
        val metadata = GameMetadata(title = canonical, canonicalTitle = canonical, provider = "libretro",
            providerGameId = "Nintendo - Super Nintendo Entertainment System|$canonical", region = "us",
            coverUrl = "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Super_Nintendo_Entertainment_System/master/Named_Boxarts/Super%20Mario%20World%20(USA)%20(En,de,fr)%20(Rev%201).png")
        assertTrue(oldLibrary.saveMetadata(oldLibrary.metadataTicket("one")!!, metadata))
        val stored = oldLibrary.metadata("one")!!
        raw().use { db ->
            // Model the previous display policy, which removed regions but kept language tags.
            db.execSQL("UPDATE game_query SET title_auto=?,sort_auto=?,search_auto=? WHERE file_id='one'",
                arrayOf(oldDisplay, gameSortKey(oldDisplay), gameSearchKey("$oldDisplay\n$canonical")))
            GameLibraryQueryIndex.signature(db, gameQueryDigest(listOf("5",
                prefs.getString("game_library_folders", "[]").orEmpty(),
                prefs.getString("game_library_overrides", "[]").orEmpty(),
                prefs.getString("file_playback_scopes", "{}").orEmpty()) + files.sources().map { it.id }.sorted()))
            db.execSQL("CREATE TABLE display_writes(projection_count INTEGER NOT NULL,metadata_count INTEGER NOT NULL)")
            db.execSQL("INSERT INTO display_writes VALUES(0,0)")
            db.execSQL("CREATE TRIGGER watch_display_projection AFTER INSERT ON game_query BEGIN UPDATE display_writes SET projection_count=projection_count+1; END")
            db.execSQL("CREATE TRIGGER watch_display_metadata AFTER INSERT ON game_metadata BEGIN UPDATE display_writes SET metadata_count=metadata_count+1; END")
        }
        val before = oldLibrary.game("one")!!
        assertEquals(oldDisplay, before.title)
        val beforeQuery = gameMetadataQuery(before, stored, "en", "us", "12345678", provider = "libretro")

        val reopened = repository()
        val displayed = reopened.game("one")!!
        assertEquals("Super Mario World (Rev 1)", displayed.title)
        assertEquals(canonical, displayed.verifiedTitle)
        assertEquals(canonical, displayed.metadataTitle)
        assertEquals("Internal (Japan) (En,Ja)", displayed.internalTitle)
        assertEquals("opaque.sfc", displayed.filename)
        assertEquals(stored, reopened.metadata("one"))
        val afterQuery = gameMetadataQuery(displayed, reopened.metadata("one"), "en", "us", "12345678", provider = "libretro")
        assertEquals(beforeQuery, afterQuery)
        assertEquals(gameMetadataFingerprint(before, beforeQuery), gameMetadataFingerprint(displayed, afterQuery))
        assertEquals(listOf("one"), reopened.games(GameLibraryQuery(search = "USA")).map { it.fileId })
        assertEquals(listOf("one"), reopened.games(GameLibraryQuery(search = "En,de,fr")).map { it.fileId })
        assertEquals(listOf("one"), reopened.games(GameLibraryQuery(search = "Super Mario World (Rev 1)")).map { it.fileId })

        val stable = repository()
        repeat(3) { assertEquals(displayed.title, stable.game("one")!!.title) }
        raw().use { db -> db.rawQuery("SELECT projection_count,metadata_count FROM display_writes", null).use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals(0, it.getInt(1))
        } }
    }

    @Test fun partialLibretroSearchReturnsLoadedCatalogNamesWithoutAnArtworkRequest() = runBlocking {
        seed("one", title = "INTERNAL NAME")
        val library = repository()
        library.importDat("snes", """<datafile><game name="Super Mario World (Europe)"><rom crc="AABBCCDD"/></game></datafile>""".byteInputStream())
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val metadata = metadataRepository(library, okhttp3.Interceptor {
            requests.incrementAndGet()
            throw AssertionError("A local DAT search must not request artwork")
        })
        try {
            val found = withTimeout(3_000) { metadata.search(library.game("one")!!, "Mario World", "libretro") }
            assertEquals(listOf("Super Mario World (Europe)"), found.map { it.metadata.title })
            assertTrue(found.single().catalogMatch)
            assertEquals(0, requests.get())
        } finally { metadata.closeForTests() }
    }

    @Test fun manuallySelectingACatalogNamePersistsItWhenAllArtworkReturnsNotFound() = runBlocking {
        seed("one", title = "INTERNAL NAME")
        val library = repository()
        library.importDat("snes", """<datafile><game name="Super Mario World (Europe)"><rom crc="AABBCCDD"/></game></datafile>""".byteInputStream())
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            requests.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(404).message("Not Found").body(byteArrayOf().toResponseBody()).build()
        })
        try {
            val game = library.game("one")!!
            val found = metadata.search(game, "Mario World", "libretro").single()
            assertTrue("An artwork-only search must hide catalog entries whose image is missing",
                metadata.search(game, "Mario World", "libretro", GameArtworkKind.COVER).isEmpty())
            assertTrue(withTimeout(5_000) { metadata.applyMatch(game, found) })
            assertEquals("Super Mario World", library.game("one")!!.title)
            assertEquals("Super Mario World (Europe)", library.metadata("one")!!.canonicalTitle)
            assertTrue(library.metadata("one")!!.matchLocked)
            assertTrue(requests.get() > 0)
            assertNull(library.artworkPath("one", GameArtworkKind.COVER))
        } finally { metadata.closeForTests() }
    }

    @Test fun catalogOnlyCombinedSystemMatchRetriesLaterNamespaceDespiteRecentEditAndCachedMiss() = runBlocking {
        seed("color", folder = "root", system = "gb", filename = "opaque.zip", title = "INTERNAL NAME")
        val library = repository()
        library.importDat("gb", """<datafile><game name="Color Game (World)"><rom crc="12345678"/></game></datafile>""".byteInputStream())
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val artworkAvailable = java.util.concurrent.atomic.AtomicBoolean(false)
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            requests.incrementAndGet()
            val found = artworkAvailable.get() && chain.request().url.pathSegments.firstOrNull() == "Nintendo - Game Boy Color"
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (found) 200 else 404).message(if (found) "OK" else "Not Found")
                .body(if (found) imageBytes().toResponseBody("image/png".toMediaType()) else byteArrayOf().toResponseBody())
                .build()
        })
        try {
            val game = library.game("color")!!
            assertFalse(withTimeout(5_000) { metadata.refresh(game) }) // Cache a genuine artwork miss.
            val candidate = metadata.search(game, "Color Game", "libretro").single()
            assertTrue(withTimeout(5_000) { metadata.applyMatch(game, candidate) })
            val selected = library.metadata("color")!!
            assertTrue(selected.matchLocked)
            assertEquals("", selected.providerGameId)
            assertNull(selected.coverUrl)
            val before = requests.get()
            artworkAvailable.set(true)
            assertTrue(withTimeout(5_000) { metadata.refresh(library.game("color")!!, force = false) })
            assertTrue("Unresolved platform must retry despite the fresh text edit and previous miss", requests.get() > before)
            val resolved = library.metadata("color")!!
            assertEquals("Nintendo - Game Boy Color|Color Game (World)", resolved.providerGameId)
            assertTrue(resolved.matchLocked)
            assertNotNull(library.artworkPath("color", GameArtworkKind.COVER))
        } finally { metadata.closeForTests() }
    }

    @Test fun automaticDatMatchNeverAdoptsArtworkForADifferentFilenameWhenVerifiedArtworkIsMissing() = runBlocking {
        seed("one", filename = "Misleading filename.sfc", title = "INTERNAL NAME")
        val library = repository()
        val verified = "Verified Game (Europe)"
        library.importDat("snes", """<datafile><game name="$verified"><rom crc="12345678"/></game></datafile>""".byteInputStream())
        val requestedNames = mutableListOf<String>()
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            val name = chain.request().url.pathSegments.last()
            requestedNames += name
            val found = name == "Misleading filename.png"
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (found) 200 else 404).message(if (found) "OK" else "Not Found")
                .body(if (found) imageBytes().toResponseBody("image/png".toMediaType()) else byteArrayOf().toResponseBody())
                .build()
        })
        try {
            assertFalse(withTimeout(5_000) { metadata.refresh(library.game("one")!!) })
            assertTrue(requestedNames.isNotEmpty())
            assertTrue("A checksum identity must not fall through to a guessed filename", requestedNames.all { it == "$verified.png" })
            assertEquals("Verified Game", library.game("one")!!.title)
            assertEquals(verified, library.game("one")!!.verifiedTitle)
            assertNull(library.metadata("one"))
            assertNull(library.artworkPath("one", GameArtworkKind.COVER))
        } finally { metadata.closeForTests() }
    }

    @Test fun cachedFoundWithAnExistingCoverStillRematchesAStaleLibretroIdentityToTheVerifiedName() = runBlocking {
        val verified = "Verified Game (Europe)"
        seed("one", filename = "Misleading filename.sfc", title = "INTERNAL NAME", verified = verified)
        val library = repository()
        val ticket = library.metadataTicket("one")!!
        val platform = "Nintendo - Super Nintendo Entertainment System"
        val stale = GameMetadata(title = verified, canonicalTitle = "Misleading filename", provider = "libretro",
            providerGameId = "$platform|Misleading filename",
            coverUrl = "https://thumbnails.libretro.com/old-cover.png")
        assertTrue(library.saveMetadata(ticket, stale))
        assertTrue(library.setArtwork(ticket, GameArtworkKind.COVER,
            library.artworkFiles.stage(imageBytes().inputStream())))
        val game = library.game("one")!!
        assertTrue(File(game.coverPath!!).isFile)
        val settings = GameMetadataSettings(preferences, TokenStore(prefs, preferences))
        val query = gameMetadataQuery(game, library.metadata("one"), settings.language(),
            settings.options().region, ticket.crc32, provider = "libretro")
        val fingerprint = gameMetadataLookupFingerprint("libretro", gameMetadataFingerprint(game, query))
        assertTrue(library.saveLookupStatus(ticket, "libretro", fingerprint, "found", Long.MAX_VALUE))
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            requests.incrementAndGet()
            val found = chain.request().url.pathSegments.last() == "$verified.png"
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (found) 200 else 404).message(if (found) "OK" else "Not Found")
                .body(if (found) imageBytes().toResponseBody("image/png".toMediaType()) else byteArrayOf().toResponseBody())
                .build()
        })
        try {
            assertTrue(withTimeout(5_000) { metadata.refresh(game, force = false) })
            assertTrue("Cached success and a local cover must not conceal a stale identity", requests.get() > 0)
            val corrected = library.metadata("one")!!
            assertEquals(verified, corrected.canonicalTitle)
            assertEquals("$platform|$verified", corrected.providerGameId)
            assertEquals("Verified Game", library.game("one")!!.title)
            assertEquals("$verified.png", corrected.coverUrl!!.toHttpUrl().pathSegments.last())
            assertNotNull(library.artworkPath("one", GameArtworkKind.COVER))
        } finally { metadata.closeForTests() }
    }

    @Test fun primaryOnlyLibretroQueryMissDoesNotSuppressTheNewOfficialMirror() = runBlocking {
        val verified = "Verified Game (Europe)"
        seed("one", verified = verified)
        val library = repository()
        val game = library.game("one")!!
        val ticket = library.metadataTicket("one")!!
        val settings = GameMetadataSettings(preferences, TokenStore(prefs, preferences))
        val query = gameMetadataQuery(game, null, settings.language(), settings.options().region,
            ticket.crc32, provider = "libretro")
        val legacyFingerprint = gameMetadataFingerprint(game, query)
        assertTrue(library.saveLookupStatus(ticket, "libretro", legacyFingerprint, "not_found", Long.MAX_VALUE))
        val requestedHosts = mutableListOf<String>()
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            val host = chain.request().url.host
            requestedHosts += host
            val found = host == "raw.githubusercontent.com"
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (found) 200 else 404).message(if (found) "OK" else "Not Found")
                .body(if (found) imageBytes().toResponseBody("image/png".toMediaType()) else byteArrayOf().toResponseBody())
                .build()
        })
        try {
            assertTrue(withTimeout(5_000) { metadata.refresh(game, force = false) })
            assertEquals(listOf("thumbnails.libretro.com", "raw.githubusercontent.com"), requestedHosts)
            assertEquals(verified, library.metadata("one")!!.canonicalTitle)
            assertEquals("thumbnails.libretro.com", library.metadata("one")!!.coverUrl!!.toHttpUrl().host)
            assertNotNull(library.artworkPath("one", GameArtworkKind.COVER))
            assertTrue(withTimeout(5_000) { metadata.refresh(library.game("one")!!, force = false) })
            assertEquals("The new cache still avoids repeated downloads", 2, requestedHosts.size)
        } finally { metadata.closeForTests() }
    }

    @Test fun primaryOnlyArtworkMissRetriesTheMirrorAndKeepsAManuallyLockedReference() = runBlocking {
        val verified = "Verified Game (Europe)"
        seed("one", verified = verified)
        val library = repository()
        val ticket = library.metadataTicket("one")!!
        val platform = "Nintendo - Super Nintendo Entertainment System"
        val reference = "https://thumbnails.libretro.com/$platform/Named_Boxarts/$verified.png".toHttpUrl().toString()
        val selected = GameMetadata(title = verified, canonicalTitle = verified, provider = "libretro",
            providerGameId = "$platform|$verified", coverUrl = reference,
            matchLocked = true, lockedFields = setOf("cover"))
        assertTrue(library.saveMetadata(ticket, selected, manual = true))
        val stored = library.metadata("one")!!
        val legacyFingerprint = gameMetadataDigest("image:cover:libretro:$reference")
        assertTrue(library.saveLookupStatus(ticket, "libretro", legacyFingerprint, "not_found", Long.MAX_VALUE))
        val requestedHosts = mutableListOf<String>()
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            val host = chain.request().url.host
            requestedHosts += host
            val found = host == "raw.githubusercontent.com"
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (found) 200 else 404).message(if (found) "OK" else "Not Found")
                .body(if (found) imageBytes().toResponseBody("image/png".toMediaType()) else byteArrayOf().toResponseBody())
                .build()
        })
        try {
            assertTrue(withTimeout(5_000) { metadata.ensureArtwork(library.game("one")!!, GameArtworkKind.COVER) })
            assertEquals(listOf("thumbnails.libretro.com", "raw.githubusercontent.com"), requestedHosts)
            assertEquals(stored, library.metadata("one"))
            assertNotNull(library.artworkPath("one", GameArtworkKind.COVER))
            assertTrue(library.artworkPinned("one", GameArtworkKind.COVER))
        } finally { metadata.closeForTests() }
    }

    @Test fun malformedLibretroCoverCanUseAValidSnapshotAndContinueTheMetadataBatch() = runBlocking {
        seed("a", title = "INTERNAL FIRST", verified = "First Game (Europe)")
        seed("b", title = "INTERNAL SECOND", verified = "Second Game (Europe)")
        val library = repository()
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            val path = chain.request().url.pathSegments
            val invalidCover = path.last() == "First Game (Europe).png" && "Named_Boxarts" in path
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(if (invalidCover)
                    "<html>Not an image</html>".toResponseBody("text/html".toMediaType())
                    else imageBytes().toResponseBody("image/png".toMediaType())).build()
        })
        val progress = mutableListOf<Pair<Int, Int>>()
        try {
            withTimeout(5_000) { metadata.enrichFolder("snes", automatic = true) { done, total -> progress += done to total } }
            assertEquals(2 to 2, progress.last())
            assertEquals("First Game (Europe)", library.metadata("a")!!.canonicalTitle)
            assertTrue("Named_Snaps" in library.metadata("a")!!.coverUrl!!.toHttpUrl().pathSegments)
            assertEquals("Second Game (Europe)", library.metadata("b")!!.canonicalTitle)
            assertNotNull(library.artworkPath("a", GameArtworkKind.COVER))
            assertNotNull(library.artworkPath("b", GameArtworkKind.COVER))
        } finally { metadata.closeForTests() }
    }

    @Test fun activeMetadataBatchPromotesFarFocusAndReordersTheNextRequestWithoutDoubleCounting() = runBlocking {
        val ids = (0 until 70).map { it.toString().padStart(3, '0') }
        ids.forEach { seed(it, verified = "Game $it (Europe)") }
        seed("foreign", folder = "root", system = "gba", filename = "foreign.gba", verified = "Foreign Game (USA)")
        val library = repository()
        val priorities = java.util.concurrent.atomic.AtomicReference(listOf("foreign", "069", "068"))
        val requested = mutableListOf<String>()
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            val name = chain.request().url.pathSegments.last()
            requested += name
            if (name == "Game 069 (Europe).png") priorities.set(listOf("067", "068"))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(imageBytes().toResponseBody("image/png".toMediaType())).build()
        })
        val progress = mutableListOf<Pair<Int, Int>>()
        try {
            withTimeout(15_000) {
                metadata.enrichFolder("snes", prioritizedFileIds = priorities::get) { done, total ->
                    progress += done to total
                }
            }
            assertEquals(listOf("069", "067", "068", "000").map { "Game $it (Europe).png" }, requested.take(4))
            assertEquals(ids.map { "Game $it (Europe).png" }.toSet(), requested.toSet())
            assertEquals("The sequential cursor must not download promoted cards again", ids.size, requested.size)
            assertEquals(0 to 70, progress.first())
            assertEquals(70 to 70, progress.last())
            assertNull("A visible game in another folder is never enriched by this batch", library.metadata("foreign"))
        } finally { metadata.closeForTests() }
    }

    @Test fun promotedFolderLookupUsesMembershipEvenWhenAChildOwnsTheDisplayRow() {
        seed("overlap", folder = "root")
        seed("overlap", folder = "snes")
        seed("child-only", folder = "snes")
        val library = repository()
        assertEquals("snes", library.folderGame("root", "overlap")!!.folderId)
        assertEquals("snes", library.folderGame("snes", "overlap")!!.folderId)
        assertNull(library.folderGame("root", "child-only"))
        assertNull(library.folderGame("snes", "overlap", afterFileId = "overlap"))
        assertNull(library.folderGame("removed", "overlap"))
    }

    @Test fun invalidMetadataForOneRomReportsAPartialBatchAndContinuesTheOtherRoms() = runBlocking {
        val brokenName = "Broken Game (Europe)"
        val validName = "Valid Game (Europe)"
        seed("99-broken", title = "INTERNAL BROKEN", verified = brokenName)
        seed("01-valid", title = "INTERNAL VALID", verified = validName)
        val library = repository()
        val requestedNames = mutableListOf<String>()
        var brokenAttemptsBeforeValid = 0
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            val name = chain.request().url.pathSegments.last()
            if (name == "$validName.png") brokenAttemptsBeforeValid = requestedNames.count { it == "$brokenName.png" }
            requestedNames += name
            val bytes = if (name == "$brokenName.png") "invalid PNG".toByteArray() else imageBytes()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(bytes.toResponseBody("image/png".toMediaType())).build()
        })
        val progress = mutableListOf<Pair<Int, Int>>()
        try {
            val error = runCatching { withTimeout(5_000) {
                metadata.enrichFolder("snes", automatic = true,
                    prioritizedFileIds = { listOf("99-broken") }) { done, total -> progress += done to total }
            } }.exceptionOrNull()
            assertTrue(error is GameMetadataBatchException)
            val partial = error as GameMetadataBatchException
            assertEquals(1, partial.failedGames)
            assertEquals(2, partial.processedGames)
            assertTrue(partial.firstFailure is GameArtworkValidationException)
            assertEquals(2 to 2, progress.last())
            assertEquals("$brokenName.png", requestedNames.first())
            assertTrue(brokenAttemptsBeforeValid > 0)
            assertEquals("The promoted failure is not retried when its normal cursor row arrives",
                brokenAttemptsBeforeValid, requestedNames.count { it == "$brokenName.png" })
            assertEquals("Broken Game", library.game("99-broken")!!.title)
            assertNull(library.metadata("99-broken"))
            assertNull(library.artworkPath("99-broken", GameArtworkKind.COVER))
            assertEquals(validName, library.metadata("01-valid")!!.canonicalTitle)
            assertNotNull(library.artworkPath("01-valid", GameArtworkKind.COVER))
            val previousRequests = requestedNames.size
            val retry = runCatching { withTimeout(5_000) {
                metadata.refresh(library.game("99-broken")!!, force = false)
            } }.exceptionOrNull()
            assertTrue(retry is GameArtworkValidationException)
            assertTrue("A failed ROM remains retryable instead of being cached as missing", requestedNames.size > previousRequests)
        } finally { metadata.closeForTests() }
    }

    @Test fun metadataBatchStillStopsImmediatelyForProviderRateLimits() = runBlocking {
        val firstName = "First Game (Europe)"
        seed("00-first", verified = firstName)
        seed("01-second", verified = "Second Game (Europe)")
        val library = repository()
        val requestedNames = mutableListOf<String>()
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            requestedNames += chain.request().url.pathSegments.last()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(429).message("Too Many Requests").body(byteArrayOf().toResponseBody()).build()
        })
        try {
            val error = runCatching { withTimeout(5_000) { metadata.enrichFolder("snes", automatic = true) } }
                .exceptionOrNull()
            assertTrue(error is GameMetadataProviderException)
            assertEquals(GameMetadataFailure.RATE_LIMIT, (error as GameMetadataProviderException).kind)
            assertEquals(listOf("$firstName.png"), requestedNames)
            assertNull(library.metadata("01-second"))
        } finally { metadata.closeForTests() }
    }

    @Test fun metadataBatchCancellationKeepsCompletedWorkAndDoesNotAttemptLaterRoms() = runBlocking {
        val firstName = "First Game (Europe)"
        val secondName = "Second Game (Europe)"
        seed("00-first", verified = firstName)
        seed("01-second", verified = secondName)
        seed("02-third", verified = "Third Game (Europe)")
        val library = repository()
        val requestedNames = mutableListOf<String>()
        val pending = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            val name = chain.request().url.pathSegments.last()
            requestedNames += name
            if (name == "$secondName.png") {
                pending.complete(Unit)
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(imageBytes().toResponseBody("image/png".toMediaType())).build()
        })
        val task = launch { metadata.enrichFolder("snes", automatic = true) }
        try {
            withTimeout(5_000) { pending.await() }
            task.cancel()
            release.countDown()
            withTimeout(5_000) { task.join() }
            assertTrue(task.isCancelled)
            assertEquals(listOf("$firstName.png", "$secondName.png"), requestedNames)
            assertNotNull(library.metadata("00-first"))
            assertNotNull(library.artworkPath("00-first", GameArtworkKind.COVER))
            assertNull(library.metadata("01-second"))
            assertNull(library.metadata("02-third"))
        } finally { release.countDown(); task.cancelAndJoin(); metadata.closeForTests() }
    }

    @Test fun rejectedImagesInOneCompatibleNamespaceDoNotBlockTheVerifiedNameInAnother() = runBlocking {
        val verified = "Color Game (World)"
        seed("color", folder = "root", system = "gb", filename = "opaque.zip", verified = verified)
        val library = repository()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            requests.incrementAndGet()
            val valid = chain.request().url.pathSegments.first() == "Nintendo - Game Boy Color"
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(if (valid) imageBytes().toResponseBody("image/png".toMediaType())
                    else "invalid image".toResponseBody("image/png".toMediaType())).build()
        })
        try {
            assertTrue(withTimeout(5_000) { metadata.refresh(library.game("color")!!) })
            assertEquals(4, requests.get())
            assertEquals("Nintendo - Game Boy Color|$verified", library.metadata("color")!!.providerGameId)
            assertEquals("Color Game", library.game("color")!!.title)
            assertNotNull(library.artworkPath("color", GameArtworkKind.COVER))
        } finally { metadata.closeForTests() }
    }

    @Test fun entirelyInvalidArtworkRemainsAnErrorAndIsNeverCachedAsAMissingGame() = runBlocking {
        seed("one", verified = "Verified Game (Europe)")
        val library = repository()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            requests.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body("<html>Not an image</html>".toResponseBody("text/html".toMediaType())).build()
        })
        try {
            repeat(2) { attempt ->
                val error = runCatching { withTimeout(5_000) { metadata.refresh(library.game("one")!!) } }.exceptionOrNull()
                assertTrue(error is GameMetadataProviderException)
                assertEquals(GameMetadataFailure.INVALID_RESPONSE, (error as GameMetadataProviderException).kind)
                assertEquals((attempt + 1) * 6, requests.get())
            }
            assertNull(library.metadata("one"))
            assertNull(library.artworkPath("one", GameArtworkKind.COVER))
            assertEquals("Verified Game", library.game("one")!!.title)
        } finally { metadata.closeForTests() }
    }

    @Test fun artworkRateLimitStillStopsWithoutTryingAlternativeImages() = runBlocking {
        seed("one", verified = "Verified Game (Europe)")
        val library = repository()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            requests.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(429).message("Too Many Requests").body(byteArrayOf().toResponseBody()).build()
        })
        try {
            val error = runCatching { withTimeout(5_000) { metadata.refresh(library.game("one")!!) } }.exceptionOrNull()
            assertTrue(error is GameMetadataProviderException)
            assertEquals(GameMetadataFailure.RATE_LIMIT, (error as GameMetadataProviderException).kind)
            assertEquals(1, requests.get())
            assertNull(library.metadata("one"))
        } finally { metadata.closeForTests() }
    }

    @Test fun rejectedArtworkDimensionsKeepTheirTypedFailureAfterTryingAlternatives() = runBlocking {
        seed("one", verified = "Verified Game (Europe)")
        val library = repository()
        val bitmap = android.graphics.Bitmap.createBitmap(8193, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val image = java.io.ByteArrayOutputStream().use { output ->
            try {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            } finally { bitmap.recycle() }
        }
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            requests.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(image.toResponseBody("image/png; charset=binary".toMediaType())).build()
        })
        try {
            val error = runCatching { withTimeout(5_000) { metadata.refresh(library.game("one")!!) } }.exceptionOrNull()
            assertTrue(error is GameArtworkValidationException)
            assertEquals(GameArtworkFailure.DIMENSIONS, (error as GameArtworkValidationException).kind)
            assertEquals(context.getString(R.string.game_error_artwork_dimensions),
                gameOperationError(context, error, R.string.game_error_provider_response))
            assertEquals(3, requests.get())
            assertNull(library.metadata("one"))
            assertTrue(File(context.cacheDir, "game_artwork_staging").listFiles().orEmpty().isEmpty())
        } finally { metadata.closeForTests() }
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
        raw().use { assertEquals(8, it.version) }
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
        // Valid 4×3 RGB PNG, independently decoded and checked for all PNG chunk CRCs.
        "iVBORw0KGgoAAAANSUhEUgAAAAQAAAADCAIAAAA7ljmRAAAAFElEQVR4nGPUSljAAANMDEgAhQMAKzgBMKX6+B0AAAAASUVORK5CYII=")

    @Test fun stagedCoverGeometryPersistsAndOverridesRegionalFallbackAcrossReopen() {
        seed("one")
        val library = repository()
        val ticket = library.metadataTicket("one")!!
        assertTrue(library.saveMetadata(ticket, GameMetadata(region = "jp", provider = "libretro",
            providerGameId = "Nintendo - Super Nintendo Entertainment System|Chosen (Japan)")))
        val staged = library.artworkFiles.stage(imageBytes().inputStream())
        assertEquals(4, staged.width)
        assertEquals(3, staged.height)
        assertTrue(library.setArtwork(ticket, GameArtworkKind.COVER, staged, pinned = true))
        val game = repository().game("one")!!
        assertEquals(4, game.coverWidth)
        assertEquals(3, game.coverHeight)
        assertEquals("jp", game.metadataRegion)
        assertEquals("Nintendo - Super Nintendo Entertainment System", game.metadataPlatform)
        assertEquals(4f / 3f, game.coverAspectRatio, 0f)
        raw().use { db -> db.rawQuery("SELECT geometry_checked FROM game_artwork", null).use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        } }
        assertTrue(library.setArtwork(ticket, GameArtworkKind.COVER, null, pinned = true))
        assertNull(library.game("one")!!.coverWidth)
        assertNull(library.game("one")!!.coverHeight)
        assertEquals(0.55f, library.game("one")!!.coverAspectRatio, 0f)
    }

    private fun createVersionSixMetadataSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE game_query (
            file_id TEXT NOT NULL PRIMARY KEY,folder_id TEXT NOT NULL,system_id TEXT NOT NULL,
            favorite INTEGER NOT NULL,last_played INTEGER NOT NULL,custom_title TEXT,folder_depth INTEGER NOT NULL,
            title_auto TEXT NOT NULL,title_internal TEXT NOT NULL,title_filename TEXT NOT NULL,
            sort_auto BLOB NOT NULL,sort_internal BLOB NOT NULL,sort_filename BLOB NOT NULL,sort_system BLOB NOT NULL,
            search_auto TEXT NOT NULL,search_internal TEXT NOT NULL,search_filename TEXT NOT NULL,
            search_filename_raw TEXT NOT NULL,projection_stamp TEXT NOT NULL,
            metadata_title TEXT,metadata_year INTEGER,cover_path TEXT,backdrop_path TEXT)""")
        db.execSQL("CREATE TABLE game_query_metadata(id INTEGER NOT NULL PRIMARY KEY,signature TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE game_metadata(file_id TEXT NOT NULL PRIMARY KEY,title TEXT,year INTEGER,
            payload TEXT NOT NULL,updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE game_artwork(file_id TEXT NOT NULL,kind TEXT NOT NULL,path TEXT NOT NULL,
            bytes INTEGER NOT NULL,pinned INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,PRIMARY KEY(file_id,kind))""")
        db.execSQL("""CREATE TABLE game_metadata_checks(file_id TEXT NOT NULL,provider TEXT NOT NULL,
            fingerprint TEXT NOT NULL,status TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(file_id,provider,fingerprint))""")
        db.execSQL("""CREATE TABLE game_metadata_guards(file_id TEXT NOT NULL PRIMARY KEY,
            fingerprint TEXT NOT NULL,epoch TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE game_folder_scopes(folder_id TEXT NOT NULL PRIMARY KEY,source_id TEXT NOT NULL,
            path TEXT NOT NULL,source_scope TEXT NOT NULL)""")
        db.version = 6
    }

    @Test fun versionSixUpgradeBackfillsOnlyLocalImageHeadersAndProjectsExistingMetadataOnce() = runBlocking {
        seed("one", favorite = true, played = 1234)
        val artwork = GameArtworkFiles(context)
        val staged = artwork.stage(imageBytes().inputStream())
        val path = artwork.commit("one", GameArtworkKind.COVER, staged)
        val metadata = GameMetadata(title = "Chosen", region = "jp", provider = "libretro",
            providerGameId = "Nintendo - Super Nintendo Entertainment System|Chosen (Japan)",
            lockedFields = setOf("title", "cover"))
        raw().use { db ->
            createVersionSixMetadataSchema(db)
            db.execSQL("INSERT INTO game_metadata VALUES(?,?,?,?,?)", arrayOf<Any?>("one", metadata.title, null,
                Json.encodeToString(GameMetadata.serializer(), metadata), 1L))
            db.execSQL("INSERT INTO game_artwork VALUES(?,?,?,?,?,?)", arrayOf<Any>("one", "cover", path, staged.bytes, 1, 1L))
        }
        val library = repository()
        assertNull(library.game("one")!!.coverWidth) // Schema opening does not decode a bitmap.
        assertNull(library.game("one")!!.metadataRegion)
        assertEquals(1, library.backfillCoverGeometry())
        val upgraded = library.game("one")!!
        assertEquals(4, upgraded.coverWidth)
        assertEquals(3, upgraded.coverHeight)
        assertEquals("jp", upgraded.metadataRegion)
        assertEquals(4f / 3f, upgraded.coverAspectRatio, 0f)
        assertEquals(1234L, upgraded.lastPlayed)
        assertTrue(upgraded.favorite)
        assertEquals(metadata, library.metadata("one"))
        assertEquals(path, upgraded.coverPath)
        assertTrue(library.artworkPinned("one", GameArtworkKind.COVER))
        val revision = library.revisions.value
        assertEquals(0, library.backfillCoverGeometry { error("An already checked cover must not be reopened") })
        assertEquals(revision, library.revisions.value)
        raw().use { assertEquals(8, it.version) }
    }

    private fun pendingGeometry(fileId: String) = raw().use { db ->
        db.execSQL("UPDATE game_artwork SET image_width=NULL,image_height=NULL,geometry_checked=0 WHERE file_id=?",
            arrayOf(fileId))
    }

    private fun pendingGeometryLibrary(count: Int, withMetadata: Boolean = false): GameLibraryRepository {
        repeat(count) { seed("geometry-$it") }
        val metadata = GameMetadata(region = "jp", provider = "libretro",
            providerGameId = "Nintendo - Super Nintendo Entertainment System|Chosen (Japan)")
        raw().use { db ->
            createVersionSixMetadataSchema(db)
            db.beginTransaction()
            try {
                repeat(count) { index ->
                    val id = "geometry-$index"
                    // These tests supply header bounds through the seam, without bitmap IO.
                    val path = File(context.cacheDir, "$id.png").absolutePath
                    db.execSQL("INSERT INTO game_artwork VALUES(?,?,?,?,?,?)",
                        arrayOf<Any>(id, "cover", path, 1L, 0, 1L))
                    if (withMetadata) db.execSQL("INSERT INTO game_metadata VALUES(?,?,?,?,?)",
                        arrayOf<Any?>(id, null, null,
                            Json.encodeToString(GameMetadata.serializer(), metadata), 1L))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return repository().also { assertEquals(count, it.games().size) }
    }

    @Test fun backfillCoalescesMetadataAndGeometryBatchesWithoutSuppressingUserEdits() = runBlocking {
        val library = pendingGeometryLibrary(65, withMetadata = true)
        val revision = library.revisions.value
        var readCount = 0
        assertEquals(65, library.backfillCoverGeometry(monotonicNanos = { 0L }) {
            readCount++
            // All metadata batches, and any previous cover batches, have committed quietly.
            assertEquals(revision + if (readCount > 33) 1 else 0, library.revisions.value)
            if (readCount == 33) {
                assertTrue(library.saveMetadata(library.metadataTicket("geometry-0")!!,
                    GameMetadata(title = "User edit", region = "us")))
                assertEquals(revision + 1, library.revisions.value)
            }
            4 to 3
        })
        assertEquals(65, readCount)
        assertEquals(revision + 2, library.revisions.value) // User edit + final maintenance flush.
        assertEquals("User edit", library.game("geometry-0")!!.metadataTitle)
        assertEquals("jp", library.game("geometry-64")!!.metadataRegion)
        assertTrue(library.games().all { it.coverWidth == 4 && it.coverHeight == 3 })
    }

    @Test fun backfillPublishesAtMostEvery500MillisecondsAndFlushesTheFinalBatch() = runBlocking {
        val library = pendingGeometryLibrary(97)
        val revision = library.revisions.value
        var now = 0L
        var readCount = 0
        assertEquals(97, library.backfillCoverGeometry(monotonicNanos = { now }) {
            readCount++
            when (readCount) {
                33 -> {
                    assertEquals(revision, library.revisions.value)
                    now = 499_999_999L
                }
                65 -> {
                    assertEquals(revision, library.revisions.value)
                    now = 500_000_000L
                }
                97 -> {
                    assertEquals(revision + 1, library.revisions.value)
                    now = 500_000_001L
                }
            }
            4 to 3
        })
        assertEquals(revision + 2, library.revisions.value)
    }

    @Test fun cancellationFlushesPreviouslyCommittedGeometryAndLeavesThePendingBatchForResume() = runBlocking {
        val library = pendingGeometryLibrary(33)
        val revision = library.revisions.value
        val entered = CompletableDeferred<Unit>()
        var readCount = 0
        val task = launch {
            library.backfillCoverGeometry(monotonicNanos = { 0L }) {
                if (++readCount == 33) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                4 to 3
            }
        }
        withTimeout(5_000) { entered.await() }
        assertEquals(revision, library.revisions.value)
        task.cancelAndJoin()
        assertEquals(revision + 1, library.revisions.value)
        assertEquals(32, library.games().count { it.coverWidth == 4 && it.coverHeight == 3 })
        assertEquals(1, library.backfillCoverGeometry(monotonicNanos = { 0L }) { 4 to 3 })
        assertEquals(revision + 2, library.revisions.value)
    }

    @Test fun failedHeaderReadFlushesPreviouslyCommittedGeometryWithoutPublishingAnIncompleteBatch() = runBlocking {
        val library = pendingGeometryLibrary(33)
        val revision = library.revisions.value
        var readCount = 0
        val failure = IllegalStateException("Header reader failed")
        val result = runCatching {
            library.backfillCoverGeometry(monotonicNanos = { 0L }) {
                if (++readCount == 33) throw failure
                4 to 3
            }
        }
        // Coroutine stacktrace recovery may copy the exception across the IO boundary.
        val thrown = result.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(failure.message, thrown?.message)
        assertEquals(revision + 1, library.revisions.value)
        assertEquals(32, library.games().count { it.coverWidth == 4 && it.coverHeight == 3 })
        assertEquals(1, library.backfillCoverGeometry(monotonicNanos = { 0L }) { 4 to 3 })
        assertEquals(revision + 2, library.revisions.value)
    }

    @Test fun backfillCannotResurrectARemovedGame() = runBlocking {
        seed("remove")
        val library = repository()
        assertTrue(library.setArtwork(library.metadataTicket("remove")!!, GameArtworkKind.COVER,
            library.artworkFiles.stage(imageBytes().inputStream()), pinned = true))
        val oldPath = library.game("remove")!!.coverPath!!
        pendingGeometry("remove")
        assertEquals(0, library.backfillCoverGeometry { path ->
            assertEquals(oldPath, path)
            // Simulate a concurrent source removal; normal reconciliation owns deletion.
            preferences.fileSources = emptyList()
            library.reconcile()
            2 to 8
        })
        assertTrue(library.games().isEmpty())
        assertNull(library.game("remove"))
        assertNull(library.artworkPath("remove", GameArtworkKind.COVER))
        assertFalse(File(oldPath).exists())
        raw().use { db -> db.rawQuery("SELECT COUNT(*) FROM game_artwork", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        } }
    }

    @Test fun replacingOneCoverDuringBackfillKeepsItsNewBoundsAndPath() = runBlocking {
        seed("one")
        val library = repository()
        val ticket = library.metadataTicket("one")!!
        library.setArtwork(ticket, GameArtworkKind.COVER, library.artworkFiles.stage(imageBytes().inputStream()), pinned = true)
        val old = library.game("one")!!.coverPath
        pendingGeometry("one")
        assertEquals(0, library.backfillCoverGeometry {
            library.setArtwork(ticket, GameArtworkKind.COVER, library.artworkFiles.stage(imageBytes().inputStream()), pinned = true)
            2 to 9
        })
        val current = library.game("one")!!
        assertNotEquals(old, current.coverPath)
        assertEquals(4, current.coverWidth)
        assertEquals(3, current.coverHeight)
    }

    @Test fun cancellationDoesNotMarkUnmeasuredImagesAndNextMaintenanceResumes() = runBlocking {
        seed("one")
        val library = repository()
        library.setArtwork(library.metadataTicket("one")!!, GameArtworkKind.COVER,
            library.artworkFiles.stage(imageBytes().inputStream()))
        pendingGeometry("one")
        val entered = CompletableDeferred<Unit>()
        val task = launch { library.backfillCoverGeometry { entered.complete(Unit); awaitCancellation() } }
        withTimeout(5_000) { entered.await() }
        task.cancelAndJoin()
        raw().use { db -> db.rawQuery("SELECT geometry_checked FROM game_artwork", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        } }
        assertEquals(1, library.backfillCoverGeometry())
        assertEquals(4f / 3f, library.game("one")!!.coverAspectRatio, 0f)
    }

    @Test fun missingOrCorruptLegacyImagesAreCheckedOnlyOnceAndUseAStableFallback() = runBlocking {
        seed("missing")
        seed("corrupt")
        val library = repository()
        for (id in listOf("missing", "corrupt")) {
            library.setArtwork(library.metadataTicket(id)!!, GameArtworkKind.COVER,
                library.artworkFiles.stage(imageBytes().inputStream()))
            val image = File(library.game(id)!!.coverPath!!)
            if (id == "missing") image.delete() else image.writeText("invalid image")
            pendingGeometry(id)
        }
        assertEquals(2, library.backfillCoverGeometry())
        for (id in listOf("missing", "corrupt")) {
            assertNull(library.game(id)!!.coverWidth)
            assertNull(library.game(id)!!.coverHeight)
            assertEquals(1.4f, library.game(id)!!.coverAspectRatio, 0f)
        }
        assertEquals(0, library.backfillCoverGeometry { error("Failed images are not retried per query") })
    }

    @Test fun portableMetadataProjectsRegionAndGroupedPlatformWithoutReadingArtwork() {
        seed("color", folder = "root", system = "gb", filename = "Game.zip")
        preferences.gameLibraryOverrides = listOf(GamePortableState("nas", "Games/SNES/Game.zip",
            metadata = GameMetadata(title = "Color game", region = "jp", provider = "libretro",
                providerGameId = "Nintendo - Game Boy Color|Color game (Japan)")))
        val game = repository().game("color")!!
        assertEquals("jp", game.metadataRegion)
        assertEquals("Nintendo - Game Boy Color", game.metadataPlatform)
        assertEquals("gbc", GameCoverFormats.platformId(game.systemId, game.filename, game.metadataPlatform))
        assertNull(game.coverWidth)
        assertEquals(0.8f, game.coverAspectRatio, 0f)
    }

    @Test fun nativeArtworkDecoderReadsMimeBoundsAndPixelsFromTheValidatedFixture() {
        // Artwork staging uses a .part suffix, so detection must inspect the actual bytes.
        val file = File(context.cacheDir, "native-artwork-fixture.part").apply { writeBytes(imageBytes()) }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assertNull(BitmapFactory.decodeFile(file.absolutePath, bounds))
            assertEquals("image/png", bounds.outMimeType)
            assertEquals(4, bounds.outWidth)
            assertEquals(3, bounds.outHeight)
            val decoded = BitmapFactory.decodeFile(file.absolutePath)
            assertNotNull(decoded)
            try {
                assertEquals(4, decoded!!.width)
                assertEquals(3, decoded.height)
                assertEquals(0xff2a60a0.toInt(), decoded.getPixel(0, 0))
            } finally { decoded?.recycle() }
        } finally { file.delete() }
    }

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
        assertEquals(3, library.folderGameCount("root", "snes"))
        assertEquals(1, library.folderGameCount("root", "gba"))
        assertEquals(listOf("outside"), library.folderGames("root", systemId = "gba").map { it.fileId })
        assertNull(library.folderGame("root", "outside", systemId = "snes"))
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

    @Test fun storedCoverIsImmediatelyAvailableAfterReopeningTheCatalog() {
        seed("one")
        val library = repository()
        val ticket = library.metadataTicket("one")!!
        assertTrue(library.saveMetadata(ticket, GameMetadata(title = "Stored name",
            provider = "libretro", coverUrl = "https://thumbnails.libretro.com/stored.png")))
        assertTrue(library.setArtwork(ticket, GameArtworkKind.COVER,
            library.artworkFiles.stage(imageBytes().inputStream())))
        val path = library.game("one")!!.coverPath!!
        val stored = library.metadata("one")

        val reopened = repository()
        reopened.reconcile()
        assertEquals(path, reopened.game("one")!!.coverPath)
        assertEquals(path, reopened.games(GameLibraryQuery(section = "snes")).single().coverPath)
        assertEquals(path, reopened.folderGames("snes").single().coverPath)
        assertEquals(stored, reopened.metadata("one"))
        assertArrayEquals(imageBytes(), File(path).readBytes())
    }

    @Test fun reopenedLibraryRestoresOnlyVisibleEvictedCoversWithoutMatchingOrRomReads() = runBlocking {
        for (id in listOf("old", "new", "offscreen", "unidentified")) seed(id)
        preferences.gameMetadataOptions = preferences.gameMetadataOptions.copy(automatic = false)
        val library = repository()
        val reference = "https://thumbnails.libretro.com/stored.png"
        for (id in listOf("old", "offscreen")) {
            assertTrue(library.saveMetadata(library.metadataTicket(id)!!, GameMetadata(
                title = "Identified $id", canonicalTitle = "Confirmed $id (Europe)", provider = "libretro",
                providerGameId = "Nintendo - Super Nintendo Entertainment System|Confirmed $id (Europe)",
                coverUrl = reference)))
        }
        assertTrue(library.setArtwork(library.metadataTicket("old")!!, GameArtworkKind.COVER,
            library.artworkFiles.stage(imageBytes().inputStream())))
        val evicted = library.game("old")!!.coverPath!!
        raw().use { it.execSQL("UPDATE game_artwork SET bytes=?,updated_at=0 WHERE file_id='old'",
            arrayOf<Any>(GameArtworkFiles.CACHE_BYTES + 1)) }
        assertTrue(library.setArtwork(library.metadataTicket("new")!!, GameArtworkKind.COVER,
            library.artworkFiles.stage(imageBytes().inputStream())))
        assertNull(library.game("old")!!.coverPath)
        assertFalse(File(evicted).exists())
        val stored = library.metadata("old")
        val retained = library.game("new")!!.coverPath
        val reopened = repository()
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val metadata = metadataRepository(reopened, okhttp3.Interceptor { chain ->
            val url = chain.request().url.toString()
            requests += url
            check(url == reference) { "Recovery must fetch only the stored image, never search for a game" }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(imageBytes().toResponseBody("image/png".toMediaType())).build()
        })
        val priority = GameArtworkPriority()
        priority.update(Any(), "new", visible = true, focused = false)
        priority.update(Any(), "offscreen", visible = false, focused = false)
        priority.update(Any(), "unidentified", visible = true, focused = false)
        priority.update(Any(), "old", visible = true, focused = true)
        try {
            metadata.startCoverRecovery(priority)
            withTimeout(10_000) { reopened.revisions.first {
                reopened.game("old")!!.coverPath?.let { File(it).isFile } == true
            } }
            assertEquals(listOf(reference), requests.toList())
            assertEquals(stored, reopened.metadata("old"))
            assertEquals(retained, reopened.game("new")!!.coverPath)
            assertNull(reopened.game("offscreen")!!.coverPath)
            assertNull(reopened.metadata("unidentified"))
            assertArrayEquals(imageBytes(), File(reopened.game("old")!!.coverPath!!).readBytes())
        } finally { metadata.closeForTests() }
    }

    @Test fun reopenedLibraryRepairsAMissingPinnedFileFromItsExistingCoverChoice() = runBlocking {
        seed("one")
        val library = repository()
        val ticket = library.metadataTicket("one")!!
        val reference = "https://thumbnails.libretro.com/chosen.png"
        assertTrue(library.saveMetadata(ticket, GameMetadata(title = "My chosen game", provider = "libretro",
            coverUrl = reference, lockedFields = setOf("title", "cover"), matchLocked = true)))
        assertTrue(library.setArtwork(ticket, GameArtworkKind.COVER,
            library.artworkFiles.stage(imageBytes().inputStream()), pinned = true))
        val missing = library.game("one")!!.coverPath!!
        val stored = library.metadata("one")
        assertTrue(File(missing).delete())
        val reopened = repository()
        assertEquals(missing, reopened.game("one")!!.coverPath)
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val metadata = metadataRepository(reopened, okhttp3.Interceptor { chain ->
            requests += chain.request().url.toString()
            check(chain.request().url.toString() == reference)
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(imageBytes().toResponseBody("image/png".toMediaType())).build()
        })
        val priority = GameArtworkPriority().apply {
            update(Any(), "one", visible = true, focused = true)
        }
        try {
            metadata.startCoverRecovery(priority)
            withTimeout(10_000) { reopened.revisions.first {
                reopened.game("one")!!.coverPath?.let { File(it).isFile } == true
            } }
            val restored = reopened.game("one")!!.coverPath!!
            assertNotEquals(missing, restored)
            assertEquals(listOf(reference), requests.toList())
            assertEquals(stored, reopened.metadata("one"))
            assertTrue(reopened.artworkPinned("one", GameArtworkKind.COVER))
            assertArrayEquals(imageBytes(), File(restored).readBytes())
        } finally { metadata.closeForTests() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun recreatedViewRestoresAMissingCoverWithinTheSameAppSession() = runBlocking {
        seed("one")
        preferences.gameMetadataOptions = preferences.gameMetadataOptions.copy(automatic = false)
        val library = repository()
        val reference = "https://thumbnails.libretro.com/known-view-cover.png"
        assertTrue(library.saveMetadata(library.metadataTicket("one")!!, GameMetadata(
            title = "Known game", provider = "libretro", coverUrl = reference)))
        val stored = library.metadata("one")
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            val url = chain.request().url.toString()
            requests += url
            check(url == reference) { "Reopening a view must not identify the game again" }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(imageBytes().toResponseBody("image/png".toMediaType())).build()
        })
        val firstOwner = ViewModelStore()
        val secondOwner = ViewModelStore()
        val priority = GameArtworkPriority()
        val firstCard = Any()
        val secondCard = Any()
        fun model() = GameLibraryViewModel(library, files, access, WakeMachines(preferences, DlnaStore(prefs)),
            preferences, context, metadata)
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            metadata.startCoverRecovery(priority)
            val first = model().also { firstOwner.put("games", it); it.section("snes") }
            priority.update(firstCard, "one", visible = true, focused = true)
            val displayed = withTimeout(10_000) { first.state.first { state ->
                state.games.singleOrNull()?.coverPath?.let { File(it).isFile } == true
            } }.games.single()
            val previousPath = displayed.coverPath!!
            assertEquals("Known game", displayed.title)
            assertEquals(listOf(reference), requests.toList())
            // The app-scoped catalog and worker survive the view. Recreate the visible
            // card immediately, as a same-frame navigation/layout change can do.
            assertTrue(File(previousPath).delete())
            firstOwner.clear()
            priority.remove(firstCard)
            priority.update(secondCard, "one", visible = true, focused = true)
            val second = model().also { secondOwner.put("games", it); it.section("snes") }
            val restored = withTimeout(10_000) { second.state.first { state ->
                state.games.singleOrNull()?.coverPath?.let { it != previousPath && File(it).isFile } == true
            } }.games.single()
            assertEquals(listOf(reference, reference), requests.toList())
            assertEquals("Known game", restored.title)
            assertEquals(stored, library.metadata("one"))
            assertArrayEquals(imageBytes(), File(restored.coverPath!!).readBytes())
        } finally {
            priority.remove(firstCard)
            priority.remove(secondCard)
            firstOwner.clear()
            secondOwner.clear()
            metadata.closeForTests()
            Dispatchers.resetMain()
        }
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
                library, metadata, files, context)
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
    @Test fun metadataPartialWarningSurvivesCatalogReloadAndClearsForTheNextSuccessfulOperation() = runBlocking {
        val brokenName = "Broken Game (Europe)"
        seed("00-broken", verified = brokenName)
        seed("01-valid", verified = "Valid Game (Europe)")
        val library = repository()
        val owner = ViewModelStore()
        val invalid = java.util.concurrent.atomic.AtomicBoolean(true)
        val metadata = metadataRepository(library, okhttp3.Interceptor { chain ->
            val bad = invalid.get() && chain.request().url.pathSegments.last() == "$brokenName.png"
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body((if (bad) "invalid PNG".toByteArray() else imageBytes())
                    .toResponseBody("image/png".toMediaType())).build()
        })
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val model = GameLibraryViewModel(library, files, access, WakeMachines(preferences, DlnaStore(prefs)),
                preferences, context, metadata)
            owner.put("games", model)
            model.section("snes")
            withTimeout(10_000) { model.state.first { it.ready } }
            model.enrichFolder(child)
            val partial = withTimeout(10_000) { model.state.first { !it.busy && it.scanProcessed == 2 && it.error != null } }
            val warning = context.getString(R.string.game_metadata_partial_failure, 1,
                context.getString(R.string.game_error_artwork_format))
            assertEquals(warning, partial.error)
            assertFalse(partial.enriching)
            assertNotNull(library.artworkPath("01-valid", GameArtworkKind.COVER))

            model.favorite(library.game("01-valid")!!)
            val reloaded = withTimeout(10_000) { model.state.first { state ->
                state.games.any { it.fileId == "01-valid" && it.favorite }
            } }
            assertEquals(warning, reloaded.error)

            invalid.set(false)
            model.enrichFolder(child)
            val complete = withTimeout(10_000) { model.state.first { !it.busy && it.scanProcessed == 2 && it.error == null } }
            assertFalse(complete.enriching)
            assertNotNull(library.artworkPath("00-broken", GameArtworkKind.COVER))
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
