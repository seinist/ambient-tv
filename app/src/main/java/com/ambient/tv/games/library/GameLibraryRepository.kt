package com.ambient.tv.games.library

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.GameScanFileSession
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FilePaths
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.data.files.WakeOnLan
import com.ambient.tv.data.local.RegularPrefs
import com.ambient.tv.data.local.AppPreferences
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.GameArchives
import com.ambient.tv.games.metadata.GameArtworkFile
import com.ambient.tv.games.metadata.GameArtworkFiles
import com.ambient.tv.games.metadata.GameArtworkKind
import com.ambient.tv.games.metadata.GameMetadata
import com.ambient.tv.games.metadata.GameNameDatabases
import com.ambient.tv.games.metadata.GameMetadataProviderIds
import com.ambient.tv.games.metadata.GameMetadataStorage
import com.ambient.tv.games.metadata.GameMetadataTicket
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** A reference to an existing Files source, not a new NAS connection or a separate WOL profile. */
@Serializable
data class GameLibraryFolder(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val path: String,
    /** "auto" infers the console from a known signature, suffix or unambiguous parent. */
    val systemId: String = "auto",
    val recursive: Boolean = true,
    /** Excluded from Files by default, even on a shared video + ROM NAS source. */
    val alsoShowInFiles: Boolean = false,
)

/** Portable per-ROM user choices. The ROM bytes and local SQLite index are never synced. */
@Serializable
data class GamePortableState(
    val sourceId: String,
    val path: String,
    val title: String? = null,
    val favorite: Boolean = false,
    val lastPlayed: Long = 0L,
    /** Explicit user edits/matches only. Automatic scraper payloads remain in SQLite. */
    val metadata: GameMetadata? = null,
)

data class LibraryGame(
    val fileId: String,
    val folderId: String,
    val sourceId: String,
    val path: String,
    val filename: String,
    val systemId: String,
    val title: String,
    val internalTitle: String?,
    val size: Long,
    val modified: Long,
    val favorite: Boolean,
    val lastPlayed: Long,
    val customTitle: String? = null,
    val verifiedTitle: String? = null,
    /** Parser revision used for this ROM, to recheck older cached archives once. */
    val probeVersion: Int = 0,
    /** DAT generation last checked, including a negative match. */
    val datVersion: Long = -1L,
    val archiveCrc: String? = null,
    /** Compatibility with Settings/Game library audit: DAT check completed for this revision. */
    val datChecked: Boolean = false,
    val metadataTitle: String? = null,
    val metadataYear: Int? = null,
    val coverPath: String? = null,
    val backdropPath: String? = null,
    /** Validated local image bounds; null for a missing/legacy image awaiting IO maintenance. */
    val coverWidth: Int? = null,
    val coverHeight: Int? = null,
    val metadataRegion: String? = null,
    /** Matched provider platform namespace, used only to disambiguate grouped systems. */
    val metadataPlatform: String? = null,
    /** Headerless catalog CRC, when it differs from the original ROM checksum. */
    val datCrc: String? = null,
)

/** Preserve the previous fast-negative-match contract for existing library users/tests. */
internal fun reuseDatLookup(previous: LibraryGame?, systemId: String, size: Long, modified: Long): Boolean =
    previous != null && previous.datChecked && previous.systemId == systemId &&
        modified != 0L && previous.size == size && previous.modified == modified

/** A previously bounded 7z probe gets one checksum attempt when its DAT is first available. */
internal fun needsArchiveDatChecksum(filename: String, previous: LibraryGame?, catalogVersion: Long): Boolean =
    GameSystems.extension(filename) == "7z" && previous != null && previous.archiveCrc == null &&
        catalogVersion > 0L && previous.datVersion != catalogVersion

/** Also filter old cache rows immediately, without opening a ROM or waking its source. */
internal fun cachedGameMatchesFolder(
    folder: GameLibraryFolder,
    sourceId: String,
    filename: String,
    systemId: String,
): Boolean {
    if (folder.sourceId != sourceId || (folder.systemId != "auto" && folder.systemId != systemId)) return false
    val system = GameSystems.byId(systemId) ?: return false
    // Prior archive indices did not persist the inner filename. Preserve their offline cache;
    // the parser revision forces one content check on the next explicit refresh.
    return if (GameSystems.extension(filename) in GameSystems.archiveExtensions) GameSystems.isGame(filename)
        else GameSystems.acceptsFile(system, filename)
}

private class GameIndexDb(context: Context) : SQLiteOpenHelper(context, "game_library.db", null, 8) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE game_entries (
            folder_id TEXT NOT NULL, file_id TEXT NOT NULL, source_id TEXT NOT NULL,
            path TEXT NOT NULL, filename TEXT NOT NULL, system_id TEXT NOT NULL,
            internal_title TEXT, file_size INTEGER NOT NULL, modified INTEGER NOT NULL,
            favorite INTEGER NOT NULL DEFAULT 0, last_played INTEGER NOT NULL DEFAULT 0,
            seen INTEGER NOT NULL DEFAULT 0, manual_title TEXT, verified_title TEXT,
            dat_checked INTEGER NOT NULL DEFAULT 0,
            probe_version INTEGER NOT NULL DEFAULT 0, dat_version INTEGER NOT NULL DEFAULT -1,
            archive_crc TEXT, dat_crc TEXT,
            PRIMARY KEY(folder_id, file_id))""")
        db.execSQL("CREATE INDEX game_entries_system ON game_entries(system_id)")
        db.execSQL("CREATE INDEX game_entries_source ON game_entries(source_id)")
        db.execSQL("""CREATE TABLE game_dat_entries (
            system_id TEXT NOT NULL, crc32 TEXT NOT NULL, title TEXT NOT NULL, search_title TEXT NOT NULL,
            PRIMARY KEY(system_id, crc32))""")
        db.execSQL("""CREATE TABLE game_dat_versions (
            system_id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL)""")
        GameLibraryQueryIndex.create(db)
        GameMetadataStorage.create(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE game_entries ADD COLUMN manual_title TEXT")
            db.execSQL("ALTER TABLE game_entries ADD COLUMN verified_title TEXT")
            db.execSQL("""CREATE TABLE IF NOT EXISTS game_dat_entries (
                system_id TEXT NOT NULL, crc32 TEXT NOT NULL, title TEXT NOT NULL,
                PRIMARY KEY(system_id, crc32))""")
        }
        if (oldVersion < 3) {
            // Version 3 from main already installed this column on existing devices.
            db.execSQL("ALTER TABLE game_entries ADD COLUMN dat_checked INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE game_entries ADD COLUMN probe_version INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE game_entries ADD COLUMN dat_version INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE game_entries ADD COLUMN archive_crc TEXT")
            db.execSQL("""CREATE TABLE IF NOT EXISTS game_dat_versions (
                system_id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL)""")
            db.execSQL("""INSERT OR IGNORE INTO game_dat_versions(system_id,revision)
                SELECT DISTINCT system_id,1 FROM game_dat_entries""")
        }
        if (oldVersion < 5) GameLibraryQueryIndex.create(db)
        if (oldVersion < 6) {
            GameMetadataStorage.create(db)
            // A migration from v4 creates the current projection directly.
            if (oldVersion >= 5) GameLibraryQueryIndex.addMetadataColumns(db)
        }
        if (oldVersion < 7) {
            // Older upgrades create the current tables directly; only pre-existing tables
            // need ALTERs. No cover bitmap is decoded while the schema lock is held.
            if (oldVersion >= 6) GameMetadataStorage.addCoverGeometryColumns(db)
            if (oldVersion >= 5) GameLibraryQueryIndex.addCoverGeometryColumns(db)
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE game_entries ADD COLUMN dat_crc TEXT")
            db.execSQL("ALTER TABLE game_dat_entries ADD COLUMN search_title TEXT NOT NULL DEFAULT ''")
            db.compileStatement("UPDATE game_dat_entries SET search_title=? WHERE rowid=?").use { update ->
                db.rawQuery("SELECT rowid,title FROM game_dat_entries", null).use { rows ->
                    while (rows.moveToNext()) {
                        update.bindString(1, gameDatSearchKey(rows.getString(1)))
                        update.bindLong(2, rows.getLong(0))
                        update.executeUpdateDelete()
                    }
                }
            }
        }
    }
}

@Singleton
class GameLibraryRepository @Inject constructor(
    @ApplicationContext context: Context,
    @RegularPrefs private val prefs: SharedPreferences,
    private val files: FileStore,
    private val preferences: AppPreferences,
    private val access: FileAccess,
    private val wake: WakeOnLan,
) {
    private val db = GameIndexDb(context)
    val artworkFiles = GameArtworkFiles(context)
    private val changeSignal = MutableStateFlow(0L)
    private val catalogRevision = java.util.concurrent.atomic.AtomicLong()
    val revisions = changeSignal.asStateFlow()
    private val scanLock = kotlinx.coroutines.sync.Mutex()
    private val coverGeometryLock = kotlinx.coroutines.sync.Mutex()
    private val queryLock = Any()
    private val queryExecutions = java.util.concurrent.atomic.AtomicLong()
    private var queryConfiguration: QueryConfiguration? = null
    private var artworkReconciled = false
    private val folderEpochs = mutableMapOf<String, Long>()
    private var metadataBatchDepth = 0
    private var metadataChangePending = false
    /** Diagnostic counter for invalidation audits; counting does not perform database writes. */
    internal val readQueryCount: Long get() = queryExecutions.get()
    private fun changed(catalog: Boolean = true) {
        if (catalog) catalogRevision.incrementAndGet()
        changeSignal.update { it + 1 }
    }

    /** Folder enrichment can write many records without reloading every visible row per ROM. */
    fun beginMetadataBatch() = synchronized(queryLock) { metadataBatchDepth += 1 }

    fun flushMetadataBatch() = synchronized(queryLock) {
        if (metadataChangePending) {
            metadataChangePending = false
            changed()
        }
    }

    fun endMetadataBatch() = synchronized(queryLock) {
        check(metadataBatchDepth > 0) { "Metadata batch was not started" }
        metadataBatchDepth -= 1
        if (metadataBatchDepth == 0) flushMetadataBatch()
    }

    private fun metadataChanged() {
        if (metadataBatchDepth > 0) metadataChangePending = true else changed()
    }

    internal fun revisionSnapshot(includeOverrides: Boolean = true): GameLibraryRevision = GameLibraryRevision(
        catalog = catalogRevision.get(),
        folders = prefs.getString("game_library_folders", "[]").orEmpty(),
        overrides = if (includeOverrides) prefs.getString("game_library_overrides", "[]").orEmpty() else "",
        sources = prefs.getString("file_sources", "[]").orEmpty(),
        sourceScopes = prefs.getString("file_playback_scopes", "{}").orEmpty(),
    )

    fun folders(): List<GameLibraryFolder> = preferences.gameLibraryFolders.filter {
        it.sourceId.isNotBlank() && (it.systemId == "auto" || GameSystems.byId(it.systemId) != null)
    }.distinctBy { it.id }.take(200)

    /** Games must stay hidden until an assigned folder exists on a configured source. */
    fun hasConfiguredFolders(): Boolean {
        val sourceIds = files.sources().mapTo(hashSetOf()) { it.id }
        return folders().any { it.sourceId in sourceIds }
    }

    fun addFolder(source: FileSource, path: String, systemId: String, recursive: Boolean = true): GameLibraryFolder {
        require(files.sources().any { it == source }) { "Source changed" }
        require(systemId == "auto" || GameSystems.byId(systemId) != null) { "Unknown game system" }
        val checkedPath = if (source.kind == "local") path else FilePaths.relative(path)
        require(checkedPath.length <= 4096)
        val present = folders()
        require(present.size < 200) { "Too many game folders" }
        require(present.none { it.sourceId == source.id && it.path == checkedPath &&
            it.systemId == systemId }) { "This folder is already assigned" }
        val folder = GameLibraryFolder(sourceId = source.id, path = checkedPath,
            systemId = systemId, recursive = recursive)
        preferences.gameLibraryFolders = present + folder
        changed()
        return folder
    }

    fun setAlsoShowInFiles(id: String, visible: Boolean) {
        val existing = folders()
        if (existing.none { it.id == id }) return
        preferences.gameLibraryFolders = existing.map {
            if (it.id == id) it.copy(alsoShowInFiles = visible) else it
        }
        if (visible) preferences.filesEnabled = true
        changed()
    }

    fun removeFolder(id: String) = synchronized(queryLock) {
        val current = folders()
        val removed = current.firstOrNull { it.id == id }
        val remaining = current.filterNot { it.id == id }
        // Imported explicit choices may exist before this device has scanned the ROM.
        // Remove those too, while retaining choices still covered by another assignment.
        val unindexedChoices = if (removed == null) emptySet() else preferences.gameLibraryOverrides
            .filter { choice -> folderContains(removed, choice.sourceId, choice.path) &&
                remaining.none { folderContains(it, choice.sourceId, choice.path) } }
            .mapTo(hashSetOf()) { it.sourceId to it.path }
        // Invalidate pending scans even if an import immediately restores the same folder ID.
        folderEpochs[id] = (folderEpochs[id] ?: 0L) + 1L
        preferences.gameLibraryFolders = remaining
        // The configuration reconciliation removes catalog, metadata, checks and owned files
        // together while preserving an overlapping folder's references.
        ensureQueryIndex()
        if (purgePortableChoices(unindexedChoices)) ensureQueryIndex()
        changed()
    }

    private fun folderContains(folder: GameLibraryFolder, sourceId: String, path: String): Boolean {
        if (folder.sourceId != sourceId) return false
        val prefix = folder.path.trimEnd('/')
        val relative = if (prefix.isEmpty()) path else path.takeIf { it.startsWith("$prefix/") }
            ?.removePrefix("$prefix/") ?: return false
        if (!folder.recursive && '/' in relative) return false
        val filename = relative.substringAfterLast('/')
        val system = GameSystems.byId(folder.systemId)
        return if (system == null) GameSystems.isGame(filename) else GameSystems.acceptsFile(system, filename) ||
            (GameSystems.extension(filename) in GameSystems.archiveExtensions && GameSystems.isGame(filename))
    }

    /** Minimal catalog lookup for the sidebar; don't load every game on the UI thread. */
    fun systemIds(): List<String> {
        val current = files.sources().map { it.id }.toSet()
        val allowed = folders().filter { it.sourceId in current }.associateBy { it.id }
        val systems = linkedSetOf<String>()
        db.readableDatabase.rawQuery("SELECT DISTINCT folder_id,source_id,system_id FROM game_entries", null).use { rows ->
            while (rows.moveToNext()) {
                val folder = allowed[rows.getString(0)] ?: continue
                val systemId = rows.getString(2)
                if (folder.sourceId == rows.getString(1) && GameSystems.byId(systemId) != null &&
                    (folder.systemId == "auto" || folder.systemId == systemId)) systems += systemId
            }
        }
        return GameSystems.sortIdsByName(systems)
    }

    fun gridColumns(): Int = prefs.getInt("game_library_grid_columns", 8).coerceIn(4, 12)
    fun listRows(): Int = prefs.getInt("game_library_list_rows", 7).coerceIn(4, 10)
    fun setGridColumns(value: Int) {
        prefs.edit().putInt("game_library_grid_columns", value.coerceIn(4, 12)).apply()
        changed(catalog = false)
    }
    fun setListRows(value: Int) {
        prefs.edit().putInt("game_library_list_rows", value.coerceIn(4, 10)).apply()
        changed(catalog = false)
    }

    fun titleStyle(): String = prefs.getString("game_library_title_style", "auto").orEmpty()
    fun setTitleStyle(style: String) {
        require(style in setOf("auto", "internal", "filename"))
        prefs.edit().putString("game_library_title_style", style).apply()
        changed(catalog = false)
    }

    fun viewStyle(): String = prefs.getString("game_library_view_style", "grid").orEmpty()
    fun setViewStyle(style: String) {
        require(style in setOf("grid", "list", "rows"))
        prefs.edit().putString("game_library_view_style", style).apply()
        changed(catalog = false)
    }

    /**
     * An optional DAT provides verified full names by ROM CRC32: chosen by the user here, or
     * loaded on request from libretro-database ([importDats]). Import is transactional and
     * limited; the library itself never fetches a database.
     */
    suspend fun importDat(systemId: String, input: InputStream): Int =
        replaceDat(systemId) { insert -> input.use { GameRomDatReader.parse(it, insert) } }

    /** Downloaded name databases: all [files] of a system together replace its names. */
    suspend fun importDats(systemId: String, files: List<java.io.File>): Int =
        replaceDat(systemId) { insert -> files.sumOf { file -> file.inputStream().use { GameRomDatReader.parse(it, insert) } } }

    /** Names in each system's catalog (imported or loaded); systems without one are absent. */
    suspend fun datCatalogSizes(): Map<String, Int> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT system_id,COUNT(*) FROM game_dat_entries GROUP BY system_id", null).use { rows ->
            buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getInt(1)) }
        }
    }

    /** Folders holding games of [systemId]: the ones a new name catalog for it changes. */
    suspend fun foldersWithSystem(systemId: String): Set<String> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT DISTINCT folder_id FROM game_entries WHERE system_id=?",
            arrayOf(systemId)).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
    }

    /** Systems with games in the library. */
    suspend fun indexedSystems(): Set<String> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT DISTINCT system_id FROM game_entries", null).use { rows ->
            buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
        }
    }

    /** Local, bounded manual search. No ROM read or network lookup; callers use an IO context. */
    fun searchDatTitles(systemId: String, query: String, limit: Int = 30): List<String> {
        val key = gameDatSearchKey(query.take(256))
        if (key.isEmpty() || limit <= 0) return emptyList()
        return db.readableDatabase.rawQuery("""SELECT DISTINCT title,search_title FROM game_dat_entries
            WHERE system_id=? AND instr(search_title,?)>0
            ORDER BY CASE WHEN search_title=? THEN 0 ELSE 1 END,title COLLATE NOCASE
            LIMIT ?""", arrayOf(systemId, key, key, limit.coerceAtMost(100).toString())).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }
    }

    private suspend fun replaceDat(
        systemId: String, read: ((GameRomDatReader.Entry) -> Unit) -> Int,
    ): Int = scanLock.withLock {
        require(GameSystems.byId(systemId) != null)
        withContext(Dispatchers.IO) {
            synchronized(queryLock) {
                val configuration = ensureQueryIndex()
                val writable = db.writableDatabase
                writable.beginTransaction()
                try {
                    writable.delete("game_dat_entries", "system_id=?", arrayOf(systemId))
                    val imported = read { record ->
                        val values = android.content.ContentValues().apply {
                            put("system_id", systemId)
                            put("crc32", record.crc32)
                            put("title", record.title)
                            put("search_title", gameDatSearchKey(record.title))
                        }
                        writable.insertWithOnConflict("game_dat_entries", null, values,
                            SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    require(imported > 0) { "DAT contains no ROM CRC32 entries" }
                    val previous = writable.rawQuery(
                        "SELECT revision FROM game_dat_versions WHERE system_id=?", arrayOf(systemId)
                    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                    writable.execSQL(
                        "INSERT OR REPLACE INTO game_dat_versions(system_id,revision) VALUES(?,?)",
                        arrayOf<Any>(systemId, previous + 1)
                    )
                    // Reuse checksums already collected for these files before asking their
                    // source for anything. Manual title/metadata locks remain untouched.
                    if (GameNameDatabases.usesChecksums(systemId)) writable.execSQL(
                        """UPDATE game_entries SET verified_title=COALESCE(
                            (SELECT title FROM game_dat_entries d WHERE d.system_id=game_entries.system_id
                                AND d.crc32=game_entries.archive_crc),
                            (SELECT title FROM game_dat_entries d WHERE d.system_id=game_entries.system_id
                                AND d.crc32=game_entries.dat_crc)),
                            dat_checked=CASE WHEN archive_crc IS NOT NULL THEN 1 ELSE 0 END,
                            dat_version=CASE WHEN archive_crc IS NOT NULL THEN ? ELSE -1 END
                            WHERE system_id=?""",
                        arrayOf<Any>(previous + 1, systemId)
                    )
                    if (!GameNameDatabases.usesChecksums(systemId)) {
                        // Title-only catalogs still change lookup identity, so old negative
                        // artwork cache entries must not hide newly available release names.
                        writable.execSQL("UPDATE game_entries SET dat_version=? WHERE system_id=?",
                            arrayOf<Any>(previous + 1, systemId))
                    }
                    val affected = writable.rawQuery("SELECT file_id FROM game_entries WHERE system_id=?",
                        arrayOf(systemId)).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
                    refreshQueryFiles(affected, configuration, updateVerifiedMetadata = true)
                    writable.setTransactionSuccessful()
                    changed()
                    imported
                } finally {
                    writable.endTransaction()
                }
            }
        }
    }

    private fun catalogVersions(): Map<String, Long> {
        val versions = mutableMapOf<String, Long>()
        db.readableDatabase.rawQuery("SELECT system_id,revision FROM game_dat_versions", null).use { rows ->
            while (rows.moveToNext()) versions[rows.getString(0)] = rows.getLong(1)
        }
        return versions
    }

    private fun datNameFromCrc(systemId: String, crc32: String): String? =
        db.readableDatabase.rawQuery(
            "SELECT title FROM game_dat_entries WHERE system_id=? AND crc32=? LIMIT 1",
            arrayOf(systemId, crc32),
        ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }

    private fun datChecksums(session: GameScanFileSession, entry: FileEntry, systemId: String): GameRomChecksums.Result {
        // Never hash a huge NAS ISO or an archive merely to populate library artwork.
        if (!GameRomChecksums.canHash(entry.name, entry.size)) return GameRomChecksums.Result(null)
        return session.open(entry.path).use { media ->
            GameRomChecksums.read(systemId, entry.size, media::read)
        }
    }

    fun sortKey(): String = prefs.getString("game_library_sort", "name").orEmpty()
    fun sortDescending(): Boolean = prefs.getBoolean("game_library_descending", false)
    fun setSortKey(key: String) {
        require(key in setOf("name", "recent", "system"))
        prefs.edit().putString("game_library_sort", key).apply()
        changed(catalog = false)
    }
    fun setSortDescending(value: Boolean) {
        prefs.edit().putBoolean("game_library_descending", value).apply()
        changed(catalog = false)
    }

    /** A manual edit always wins over ROM headers and imported DAT titles. */
    fun setCustomTitle(fileId: String, value: String) {
        require(value.length <= 160 && value.none { it.isISOControl() })
        val normalized = value.trim().takeIf { it.isNotBlank() }
        editGame(fileId) { writable ->
            writable.execSQL("UPDATE game_entries SET manual_title=? WHERE file_id=?",
                arrayOf<Any?>(normalized, fileId))
        }
    }

    /** Query the offline display index; no file open, listing or Wake-on-LAN is involved. */
    fun games(query: GameLibraryQuery = GameLibraryQuery()): List<LibraryGame> = synchronized(queryLock) {
        ensureQueryIndex()
        val sql = gameLibrarySql(query, titleStyle())
        queryExecutions.incrementAndGet()
        db.readableDatabase.rawQuery(sql.sql, sql.args).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    add(LibraryGame(
                        folderId = rows.getString(0), fileId = rows.getString(1),
                        sourceId = rows.getString(2), path = rows.getString(3), filename = rows.getString(4),
                        systemId = rows.getString(5), title = rows.getString(6), internalTitle = rows.getString(7),
                        size = rows.getLong(8), modified = rows.getLong(9), favorite = rows.getInt(10) == 1,
                        lastPlayed = rows.getLong(11), customTitle = rows.getString(12), verifiedTitle = rows.getString(13),
                        metadataTitle = rows.getString(14), metadataYear = if (rows.isNull(15)) null else rows.getInt(15),
                        coverPath = rows.getString(16), backdropPath = rows.getString(17),
                        coverWidth = if (rows.isNull(18)) null else rows.getInt(18),
                        coverHeight = if (rows.isNull(19)) null else rows.getInt(19),
                        metadataRegion = rows.getString(20), metadataPlatform = rows.getString(21),
                        datVersion = rows.getLong(22),
                    ))
                }
            }
        }
    }

    fun game(fileId: String): LibraryGame? = games(GameLibraryQuery(fileId = fileId, limit = 1)).firstOrNull()

    /** IO-only hook for Settings/Companion source changes while the library screen is closed. */
    fun reconcile() = synchronized(queryLock) { ensureQueryIndex(); Unit }

    /**
     * One-time migration of cached cover headers and metadata projections. The caller may be
     * any thread: all file/SQLite work moves to IO. Each row is marked once, including missing
     * or invalid files; new artwork records arrive with validated dimensions already present.
     */
    suspend fun backfillCoverGeometry(): Int = backfillCoverGeometry { artworkFiles.dimensions(it) }

    /** Reader and clock seams keep migration races and refresh throttling deterministic in tests. */
    internal suspend fun backfillCoverGeometry(
        monotonicNanos: () -> Long = System::nanoTime,
        readBounds: suspend (String) -> Pair<Int, Int>?,
    ): Int =
        withContext(Dispatchers.IO) {
            coverGeometryLock.withLock {
                val task = kotlinx.coroutines.currentCoroutineContext()
                var updated = 0
                var dirty = false
                var lastPublishedNanos = monotonicNanos()
                // Keep this local: an unrelated favorite/artwork edit must still publish at once.
                fun publishPending(force: Boolean = false) {
                    if (!dirty) return
                    val now = monotonicNanos()
                    if (force || now - lastPublishedNanos >= 500_000_000L) {
                        metadataChanged()
                        dirty = false
                        lastPublishedNanos = now
                    }
                }
                try {
                    // Old metadata payloads already contain region/provider identity. Project them
                    // once in small batches instead of deserializing full descriptions in browse.
                    while (true) {
                        task.ensureActive()
                        val batch = synchronized(queryLock) {
                            ensureQueryIndex()
                            db.readableDatabase.rawQuery(
                                "SELECT file_id,payload FROM game_metadata WHERE region IS NULL LIMIT 32", null,
                            ).use { rows -> buildList {
                                while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1))
                            } }
                        }
                        if (batch.isEmpty()) break
                        val projected = batch.map { (id, payload) -> Triple(id, payload, GameMetadataStorage.decode(payload)) }
                        task.ensureActive()
                        synchronized(queryLock) {
                            val configuration = ensureQueryIndex()
                            val writable = db.writableDatabase
                            val changedIds = linkedSetOf<String>()
                            writable.beginTransaction()
                            try {
                                for ((id, payload, metadata) in projected) {
                                    task.ensureActive()
                                    val values = android.content.ContentValues().apply {
                                        put("region", metadata?.region.orEmpty())
                                        put("platform", metadata?.let(GameMetadataStorage::platform).orEmpty())
                                    }
                                    if (writable.update("game_metadata", values,
                                            "file_id=? AND payload=? AND region IS NULL", arrayOf(id, payload)) > 0)
                                        changedIds += id
                                }
                                refreshQueryFiles(changedIds, configuration)
                                writable.setTransactionSuccessful()
                            } finally { writable.endTransaction() }
                            if (changedIds.isNotEmpty()) {
                                dirty = true
                                publishPending()
                            }
                        }
                        kotlinx.coroutines.yield()
                    }
                    while (true) {
                        task.ensureActive()
                        val batch = synchronized(queryLock) {
                            ensureQueryIndex()
                            db.readableDatabase.rawQuery("""SELECT file_id,path FROM game_artwork
                                WHERE kind='cover' AND geometry_checked=0 LIMIT 32""", null).use { rows -> buildList {
                                while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1))
                            } }
                        }
                        if (batch.isEmpty()) break
                        val measured = batch.map { (id, path) ->
                            task.ensureActive()
                            val bounds = try { readBounds(path) } catch (_: IOException) { null }
                            Triple(id, path, bounds?.takeIf { GameArtworkFiles.validDimensions(it.first, it.second) })
                        }
                        task.ensureActive()
                        synchronized(queryLock) {
                            val configuration = ensureQueryIndex()
                            val writable = db.writableDatabase
                            val changedIds = linkedSetOf<String>()
                            writable.beginTransaction()
                            try {
                                for ((id, path, bounds) in measured) {
                                    task.ensureActive()
                                    val values = android.content.ContentValues().apply {
                                        if (bounds == null) { putNull("image_width"); putNull("image_height") }
                                        else { put("image_width", bounds.first); put("image_height", bounds.second) }
                                        put("geometry_checked", 1)
                                    }
                                    // A manual replacement owns a new UUID path. A removed folder
                                    // has no eligible record. Neither can be overwritten by late IO.
                                    if (writable.update("game_artwork", values,
                                            "file_id=? AND kind='cover' AND path=? AND geometry_checked=0",
                                            arrayOf(id, path)) > 0) changedIds += id
                                }
                                refreshQueryFiles(changedIds, configuration)
                                writable.setTransactionSuccessful()
                            } finally { writable.endTransaction() }
                            updated += changedIds.size
                            if (changedIds.isNotEmpty()) {
                                dirty = true
                                publishPending()
                            }
                        }
                        kotlinx.coroutines.yield()
                    }
                    updated
                } finally {
                    // No suspension: committed batches remain visible after cancellation or failure.
                    synchronized(queryLock) { publishPending(force = true) }
                }
            }
        }

    /** Stable pages for enrichment; a 100,000-ROM folder does not become one in-memory job. */
    fun folderGames(folderId: String, afterFileId: String? = null, limit: Int = 128, systemId: String? = null): List<LibraryGame> =
        synchronized(queryLock) {
            val folder = folders().firstOrNull { it.id == folderId } ?: return@synchronized emptyList()
            games(GameLibraryQuery(section = systemId ?: if (folder.systemId == "auto") "all" else folder.systemId,
                folderId = folderId, afterFileId = afterFileId, limit = limit.coerceIn(1, 512)))
        }

    /** Promote one visible game without reading a catalog page or touching its ROM source. */
    fun folderGame(folderId: String, fileId: String, afterFileId: String? = null, systemId: String? = null): LibraryGame? =
        synchronized(queryLock) {
            val folder = folders().firstOrNull { it.id == folderId } ?: return@synchronized null
            // Membership comes from game_entries, not the winning display row's folderId:
            // parent and child folder assignments can legitimately share the same ROM.
            games(GameLibraryQuery(section = systemId ?: if (folder.systemId == "auto") "all" else folder.systemId,
                folderId = folderId, fileId = fileId, afterFileId = afterFileId, limit = 1)).firstOrNull()
        }

    fun folderGameCount(folderId: String, systemId: String? = null): Int = synchronized(queryLock) {
        ensureQueryIndex()
        val folder = folders().firstOrNull { it.id == folderId } ?: return@synchronized 0
        val wanted = systemId ?: folder.systemId.takeUnless { it == "auto" }
        val system = if (wanted == null) "" else " AND q.system_id=?"
        val args = if (wanted == null) arrayOf(folderId) else arrayOf(folderId, wanted)
        db.readableDatabase.rawQuery("""SELECT COUNT(*) FROM game_query q WHERE EXISTS(
            SELECT 1 FROM game_entries g WHERE g.folder_id=? AND g.file_id=q.file_id)$system""", args).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /** Indexed point lookups keep streaming sidecar imports independent of library size. */
    fun folderGameByPath(folderId: String, path: String): LibraryGame? =
        folderGameLookup(folderId, "g.path", path)

    /** Opaque SAF document IDs need a name fallback; duplicate filenames deliberately fail. */
    fun folderGameByUniqueFilename(folderId: String, filename: String): LibraryGame? =
        folderGameLookup(folderId, "g.filename", filename)

    private fun folderGameLookup(folderId: String, column: String, value: String): LibraryGame? = synchronized(queryLock) {
        val folder = ensureQueryIndex().folders[folderId] ?: return@synchronized null
        val system = if (folder.systemId == "auto") "" else " AND q.system_id=? AND g.system_id=?"
        val args = mutableListOf(folderId, folder.sourceId, value)
        if (folder.systemId != "auto") { args += folder.systemId; args += folder.systemId }
        val id = db.readableDatabase.rawQuery("""SELECT g.file_id FROM game_entries g
            JOIN game_query q ON q.file_id=g.file_id
            WHERE g.folder_id=? AND g.source_id=? AND $column=?$system LIMIT 2""", args.toTypedArray()).use {
            if (!it.moveToFirst()) null else it.getString(0).takeUnless { _ -> it.moveToNext() }
        } ?: return@synchronized null
        game(id)
    }

    fun metadata(fileId: String): GameMetadata? = synchronized(queryLock) {
        ensureQueryIndex()
        GameMetadataStorage.read(db.readableDatabase, fileId)
    }

    /** Obtain on IO before starting a provider request. It reads existing catalog data only. */
    fun metadataTicket(fileId: String, originFolderId: String? = null,
                       originToken: String? = null): GameMetadataTicket? = synchronized(queryLock) {
        ensureQueryIndex()
        val game = game(fileId) ?: return@synchronized null
        val folder = folders().firstOrNull { it.id == (originFolderId ?: game.folderId) } ?: return@synchronized null
        if (originFolderId != null && (folderFingerprint(folder) != originToken ||
            !db.readableDatabase.rawQuery("SELECT 1 FROM game_entries WHERE folder_id=? AND file_id=?",
                arrayOf(folder.id, fileId)).use { it.moveToFirst() })) return@synchronized null
        val fingerprint = metadataFileFingerprint(game)
        val writable = db.writableDatabase
        val previous = writable.rawQuery("SELECT fingerprint,epoch FROM game_metadata_guards WHERE file_id=?",
            arrayOf(fileId)).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
        val epoch = previous?.takeIf { it.first == fingerprint }?.second ?: UUID.randomUUID().toString().also {
            writable.execSQL("INSERT OR REPLACE INTO game_metadata_guards(file_id,fingerprint,epoch) VALUES(?,?,?)",
                arrayOf(fileId, fingerprint, it))
        }
        val crc = writable.rawQuery("SELECT archive_crc FROM game_entries WHERE folder_id=? AND file_id=?",
            arrayOf(game.folderId, fileId)).use { if (it.moveToFirst()) it.getString(0) else null }
        GameMetadataTicket(fileId, folder.id, folderFingerprint(folder), fingerprint, epoch, crc)
    }

    /** Capture before sidecar/source IO; a removal/re-add cannot adopt the old in-flight read. */
    internal fun folderToken(folderId: String): String? = synchronized(queryLock) {
        ensureQueryIndex().folders[folderId]?.let(::folderFingerprint)
    }

    private fun metadataFileFingerprint(game: LibraryGame): String = gameQueryDigest(listOf(
        game.sourceId, game.path, game.systemId, game.size.toString(), game.modified.toString(),
        preferences.filePlaybackScopes[game.sourceId].orEmpty(),
    ))

    private fun folderFingerprint(folder: GameLibraryFolder): String = gameQueryDigest(listOf(
        folder.id, folder.sourceId, folder.path, folder.systemId, folder.recursive.toString(),
        preferences.filePlaybackScopes[folder.sourceId].orEmpty(), (folderEpochs[folder.id] ?: 0L).toString(),
    ))

    private fun accepts(ticket: GameMetadataTicket): Boolean {
        val folder = folders().firstOrNull { it.id == ticket.folderId } ?: return false
        if (folderFingerprint(folder) != ticket.folderFingerprint) return false
        val game = game(ticket.fileId) ?: return false
        if (metadataFileFingerprint(game) != ticket.fileFingerprint) return false
        return db.readableDatabase.rawQuery("SELECT epoch FROM game_metadata_guards WHERE file_id=?",
            arrayOf(ticket.fileId)).use { it.moveToFirst() && it.getString(0) == ticket.epoch }
    }

    fun saveMetadata(
        ticket: GameMetadataTicket,
        metadata: GameMetadata,
        lockedFields: Set<String> = metadata.lockedFields,
        manual: Boolean = false,
    ): Boolean = synchronized(queryLock) {
        ensureQueryIndex()
        if (!accepts(ticket)) return@synchronized false
        val writable = db.writableDatabase
        val previous = GameMetadataStorage.read(writable, ticket.fileId)
        val merged = GameMetadataStorage.merge(previous,
            metadata.copy(lockedFields = lockedFields, updatedAt = System.currentTimeMillis()), manual)
        // Sidecar rescans and a provider's unchanged response need neither a payload rewrite
        // nor a browse refresh. Explicit edits still synchronize portable choices and pins.
        if (!manual && previous?.copy(updatedAt = merged.updatedAt) == merged) return@synchronized true
        writable.beginTransaction()
        try {
            GameMetadataStorage.write(writable, ticket.fileId, merged)
            if (manual) {
                GameArtworkKind.entries.forEach { kind ->
                    val field = if (kind == GameArtworkKind.TITLE) "title_screen" else kind.key
                    writable.execSQL("UPDATE game_artwork SET pinned=? WHERE file_id=? AND kind=?",
                        arrayOf<Any>(if (field in merged.lockedFields) 1 else 0, ticket.fileId, kind.key))
                }
                savePortableChoice(ticket.fileId, merged)
            }
            refreshQueryFiles(listOf(ticket.fileId), ensureQueryIndex())
            writable.setTransactionSuccessful()
        } finally { writable.endTransaction() }
        if (manual) changed() else metadataChanged()
        true
    }

    fun artworkPath(fileId: String, kind: GameArtworkKind): String? = synchronized(queryLock) {
        ensureQueryIndex()
        db.readableDatabase.rawQuery("SELECT path FROM game_artwork WHERE file_id=? AND kind=?",
            arrayOf(fileId, kind.key)).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    fun artworkPinned(fileId: String, kind: GameArtworkKind): Boolean = synchronized(queryLock) {
        ensureQueryIndex()
        db.readableDatabase.rawQuery("SELECT pinned FROM game_artwork WHERE file_id=? AND kind=?",
            arrayOf(fileId, kind.key)).use { it.moveToFirst() && it.getInt(0) == 1 }
    }

    /** A staged image is always consumed, including stale work and attempts to replace a pin. */
    fun setArtwork(ticket: GameMetadataTicket, kind: GameArtworkKind, staged: GameArtworkFile?,
                   pinned: Boolean = false): Boolean = synchronized(queryLock) {
        ensureQueryIndex()
        if (!accepts(ticket) || (!pinned && artworkPinned(ticket.fileId, kind))) {
            staged?.let(artworkFiles::discard)
            return@synchronized false
        }
        val writable = db.writableDatabase
        val previous = artworkPath(ticket.fileId, kind)
        var nextPath: String? = null
        var committed = false
        try {
            nextPath = staged?.let { artworkFiles.commit(ticket.fileId, kind, it) }
            writable.beginTransaction()
            try {
                if (nextPath == null) writable.delete("game_artwork", "file_id=? AND kind=?", arrayOf(ticket.fileId, kind.key))
                else writable.execSQL("""INSERT OR REPLACE INTO game_artwork(file_id,kind,path,bytes,pinned,updated_at,
                    image_width,image_height,geometry_checked) VALUES(?,?,?,?,?,?,?,?,1)""",
                    arrayOf<Any>(ticket.fileId, kind.key, nextPath, staged!!.bytes,
                        if (pinned) 1 else 0, System.currentTimeMillis(), staged.width, staged.height))
                refreshQueryFiles(listOf(ticket.fileId), ensureQueryIndex())
                writable.setTransactionSuccessful()
                committed = true
            } finally { writable.endTransaction() }
        } finally {
            staged?.let(artworkFiles::discard)
            if (!committed) nextPath?.let(artworkFiles::removePath)
        }
        previous?.takeIf { it != nextPath }?.let(artworkFiles::removePath)
        trimArtwork()
        metadataChanged()
        true
    }

    fun lookupStatus(fileId: String, provider: String, fingerprint: String,
                     now: Long = System.currentTimeMillis()): String? = synchronized(queryLock) {
        ensureQueryIndex()
        db.readableDatabase.rawQuery("""SELECT status FROM game_metadata_checks
            WHERE file_id=? AND provider=? AND fingerprint=? AND expires_at>?""",
            arrayOf(fileId, provider, fingerprint, now.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    fun saveLookupStatus(ticket: GameMetadataTicket, provider: String, fingerprint: String,
                         status: String, expiresAt: Long): Boolean = synchronized(queryLock) {
        require(provider.length in 1..64 && fingerprint.length in 1..256)
        require(status in setOf("found", "not_found", "ambiguous"))
        ensureQueryIndex()
        if (!accepts(ticket)) return@synchronized false
        val writable = db.writableDatabase
        writable.beginTransaction()
        try {
            writable.execSQL("""INSERT OR REPLACE INTO game_metadata_checks
                (file_id,provider,fingerprint,status,expires_at) VALUES(?,?,?,?,?)""",
                arrayOf<Any>(ticket.fileId, provider, fingerprint, status, expiresAt))
            // Query matches and each image's miss coexist. Expired/old identity variants are
            // pruned per ROM, avoiding an unbounded history or a whole-table expiry scan.
            writable.delete("game_metadata_checks", "file_id=? AND expires_at<=?",
                arrayOf(ticket.fileId, System.currentTimeMillis().toString()))
            writable.execSQL("""DELETE FROM game_metadata_checks WHERE file_id=? AND rowid NOT IN (
                SELECT rowid FROM game_metadata_checks WHERE file_id=? ORDER BY expires_at DESC,rowid DESC LIMIT 32)""",
                arrayOf(ticket.fileId, ticket.fileId))
            writable.setTransactionSuccessful()
        } finally { writable.endTransaction() }
        true
    }

    private fun trimArtwork() {
        val writable = db.writableDatabase
        var bytes = writable.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM game_artwork WHERE pinned=0", null)
            .use { it.moveToFirst(); it.getLong(0) }
        if (bytes <= GameArtworkFiles.CACHE_BYTES) return
        val evicted = mutableListOf<Triple<String, String, String>>()
        writable.rawQuery("SELECT file_id,kind,path,bytes FROM game_artwork WHERE pinned=0 ORDER BY updated_at", null).use { rows ->
            while (bytes > GameArtworkFiles.CACHE_BYTES && rows.moveToNext()) {
                evicted += Triple(rows.getString(0), rows.getString(1), rows.getString(2))
                bytes -= rows.getLong(3)
            }
        }
        writable.beginTransaction()
        try {
            evicted.forEach { (file, kind, _) -> writable.delete("game_artwork", "file_id=? AND kind=?", arrayOf(file, kind)) }
            refreshQueryFiles(evicted.map { it.first }.toSet(), ensureQueryIndex())
            writable.setTransactionSuccessful()
        } finally { writable.endTransaction() }
        evicted.forEach { artworkFiles.removePath(it.third) }
    }

    /** Read configuration only once per actual preference change, not per search keystroke. */
    private data class QueryConfiguration(
        val folderJson: String,
        val overrideJson: String,
        val sourceIds: Set<String>,
        val sourceScopes: String,
        val folders: Map<String, GameLibraryFolder>,
        val overrides: Map<Pair<String, String>, GamePortableState>,
        val signature: String,
    )

    private fun currentQueryConfiguration(): QueryConfiguration {
        val folderJson = prefs.getString("game_library_folders", "[]").orEmpty()
        val overrideJson = prefs.getString("game_library_overrides", "[]").orEmpty()
        val sourceIds = files.sources().mapTo(hashSetOf()) { it.id }
        val sourceScopes = prefs.getString("file_playback_scopes", "{}").orEmpty()
        queryConfiguration?.takeIf {
            it.folderJson == folderJson && it.overrideJson == overrideJson && it.sourceIds == sourceIds &&
                it.sourceScopes == sourceScopes
        }?.let { return it }
        val signature = gameQueryDigest(listOf(GameLibraryQueryIndex.POLICY_VERSION.toString(),
            folderJson, overrideJson, sourceScopes) + sourceIds.sorted())
        return QueryConfiguration(folderJson, overrideJson, sourceIds, sourceScopes,
            folders().filter { it.sourceId in sourceIds }.associateBy { it.id },
            preferences.gameLibraryOverrides.associateBy { it.sourceId to it.path }, signature)
    }

    /** A backup/import rebuilds local display data once; scans and single-ROM edits update only affected files. */
    private fun ensureQueryIndex(): QueryConfiguration {
        var configuration = currentQueryConfiguration()
        val previous = queryConfiguration
        if (configuration === previous) return configuration
        val writable = db.writableDatabase
        if (GameLibraryQueryIndex.signature(writable) != configuration.signature) {
            var abandoned = emptySet<String>()
            var removedChoices = emptySet<Pair<String, String>>()
            writable.beginTransaction()
            try {
                removedChoices = pruneRemovedAssignments(writable, configuration)
                restorePortableMetadata(writable, configuration, previous)
                if (previous != null && previous.folderJson == configuration.folderJson &&
                    previous.sourceIds == configuration.sourceIds && previous.sourceScopes == configuration.sourceScopes) {
                    val changedPaths = (previous.overrides.keys + configuration.overrides.keys).filter {
                        previous.overrides[it] != configuration.overrides[it]
                    }
                    val ids = linkedSetOf<String>()
                    changedPaths.forEach { (source, path) ->
                        writable.rawQuery("SELECT file_id FROM game_entries WHERE source_id=? AND path=?",
                            arrayOf(source, path)).use { rows -> while (rows.moveToNext()) ids += rows.getString(0) }
                    }
                    refreshQueryFiles(ids, configuration)
                } else {
                    reconcileVerifiedMetadataTitles(writable, configuration)
                    writable.delete("game_query", null, null)
                    forEachIndexedGame(configuration) { game -> putQueryGame(writable, game, configuration, checkExisting = false) }
                }
                abandoned = pruneOrphanedMetadata(writable)
                GameLibraryQueryIndex.signature(writable, configuration.signature)
                writable.setTransactionSuccessful()
            } finally { writable.endTransaction() }
            // Files are removed only after the catalog transaction succeeds. A crash between
            // these steps leaves a harmless orphan, collected during the next reconciliation.
            abandoned.forEach(artworkFiles::remove)
            if (purgePortableChoices(removedChoices)) {
                configuration = currentQueryConfiguration()
                GameLibraryQueryIndex.signature(writable, configuration.signature)
            }
        }
        queryConfiguration = configuration
        if (!artworkReconciled) {
            val retained = writable.rawQuery("SELECT path FROM game_artwork", null).use { rows -> buildSet {
                while (rows.moveToNext()) add(rows.getString(0))
            } }
            artworkFiles.reconcile(retained)
            artworkReconciled = true
        }
        return configuration
    }

    /** Reconcile folder/source removal from Settings, backups and Companion without any NAS IO. */
    private fun pruneRemovedAssignments(writable: SQLiteDatabase,
                                        configuration: QueryConfiguration): Set<Pair<String, String>> {
        val storedScopes = writable.rawQuery("SELECT folder_id,source_id,path,source_scope FROM game_folder_scopes", null)
            .use { rows -> buildMap { while (rows.moveToNext()) put(rows.getString(0),
                Triple(rows.getString(1), rows.getString(2), rows.getString(3))) } }
        val currentScopes = preferences.filePlaybackScopes
        val changedFolders = configuration.folders.filter { (id, folder) ->
            storedScopes[id]?.let { it != Triple(folder.sourceId, folder.path, currentScopes[folder.sourceId].orEmpty()) } == true
        }.keys
        val groups = writable.rawQuery("SELECT DISTINCT folder_id,source_id FROM game_entries", null).use { rows -> buildList {
            while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1))
        } }
        val removedChoices = hashSetOf<Pair<String, String>>()
        groups.forEach { (folderId, sourceId) ->
            val folder = configuration.folders[folderId]
            if (folder == null || folder.sourceId != sourceId || folderId in changedFolders) {
                folderEpochs[folderId] = (folderEpochs[folderId] ?: 0L) + 1L
                removedChoices += existingChoices(writable, "folder_id=? AND source_id=?", arrayOf(folderId, sourceId), configuration)
                writable.delete("game_entries", "folder_id=? AND source_id=?", arrayOf(folderId, sourceId))
            }
        }
        storedScopes.keys.filterNot(configuration.folders::containsKey).forEach {
            writable.delete("game_folder_scopes", "folder_id=?", arrayOf(it))
        }
        configuration.folders.forEach { (id, folder) ->
            val expected = Triple(folder.sourceId, folder.path, currentScopes[folder.sourceId].orEmpty())
            if (storedScopes[id] != expected) writable.execSQL(
                "INSERT OR REPLACE INTO game_folder_scopes(folder_id,source_id,path,source_scope) VALUES(?,?,?,?)",
                arrayOf(id, expected.first, expected.second, expected.third))
        }
        return removedChoices
    }

    /** Only retain at most the existing 5,000 portable keys while streaming a large deletion. */
    private fun existingChoices(writable: SQLiteDatabase, where: String, args: Array<String>,
                                configuration: QueryConfiguration): Set<Pair<String, String>> {
        if (configuration.overrides.isEmpty()) return emptySet()
        return writable.rawQuery("SELECT source_id,path FROM game_entries WHERE $where", args).use { rows -> buildSet {
            while (rows.moveToNext()) {
                val key = rows.getString(0) to rows.getString(1)
                if (key in configuration.overrides) add(key)
            }
        } }
    }

    private fun purgePortableChoices(candidates: Set<Pair<String, String>>): Boolean {
        if (candidates.isEmpty()) return false
        val removed = candidates.filterTo(hashSetOf()) { (source, path) ->
            !db.readableDatabase.rawQuery("""SELECT 1 FROM game_entries g JOIN game_query q ON q.file_id=g.file_id
                WHERE g.source_id=? AND g.path=? LIMIT 1""", arrayOf(source, path)).use { it.moveToFirst() }
        }
        if (removed.isEmpty()) return false
        val old = preferences.gameLibraryOverrides
        val next = old.filterNot { (it.sourceId to it.path) in removed }
        if (next == old) return false
        preferences.gameLibraryOverrides = next
        return true
    }

    private fun restorePortableMetadata(writable: SQLiteDatabase, configuration: QueryConfiguration,
                                        previous: QueryConfiguration?) {
        configuration.overrides.forEach { (key, value) ->
            val incoming = value.metadata?.bounded() ?: return@forEach
            if (previous?.overrides?.get(key)?.metadata == incoming) return@forEach
            val ids = writable.rawQuery("SELECT DISTINCT file_id FROM game_entries WHERE source_id=? AND path=?",
                arrayOf(key.first, key.second)).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
            ids.forEach { id ->
                val current = GameMetadataStorage.read(writable, id)
                // A projection-policy migration must not roll back later automatic enrichment.
                val restored = if (current != null && current.updatedAt > incoming.updatedAt &&
                    current.provider == incoming.provider && current.providerGameId == incoming.providerGameId)
                    GameMetadataStorage.merge(incoming, current, manual = false) else incoming
                if (current != restored) GameMetadataStorage.write(writable, id, restored)
            }
        }
    }

    private fun pruneOrphanedMetadata(writable: SQLiteDatabase): Set<String> {
        val orphaned = writable.rawQuery("""SELECT file_id FROM (
            SELECT file_id FROM game_metadata UNION SELECT file_id FROM game_artwork
            UNION SELECT file_id FROM game_metadata_checks UNION SELECT file_id FROM game_metadata_guards) candidates
            WHERE NOT EXISTS(SELECT 1 FROM game_query q WHERE q.file_id=candidates.file_id)""", null).use { rows -> buildSet {
            while (rows.moveToNext()) add(rows.getString(0))
        } }
        orphaned.chunked(400).forEach { ids ->
            val clause = "file_id IN (${ids.joinToString(",") { "?" }})"
            for (table in listOf("game_metadata", "game_artwork", "game_metadata_checks", "game_metadata_guards"))
                writable.delete(table, clause, ids.toTypedArray())
        }
        return orphaned
    }

    private fun refreshQueryFiles(fileIds: Collection<String>, configuration: QueryConfiguration,
                                  updateVerifiedMetadata: Boolean = false) {
        val writable = db.writableDatabase
        fileIds.chunked(400).forEach { ids ->
            val clause = "file_id IN (${ids.joinToString(",") { "?" }})"
            val removed = ids.toMutableSet()
            // At most 400 selected rows. Close the source cursor before a DAT correction
            // updates a metadata row that was part of its join.
            val games = buildList { forEachIndexedGame(configuration, clause, ids.toTypedArray(), consume = ::add) }
            for (game in games) {
                val current = if (updateVerifiedMetadata) refreshVerifiedMetadataTitle(writable, game) else game
                putQueryGame(writable, current, configuration)
                removed.remove(game.fileId)
            }
            if (removed.isNotEmpty()) writable.delete("game_query",
                "file_id IN (${removed.joinToString(",") { "?" }})", removed.toTypedArray())
        }
    }

    /** Repair previously cached automatic names once when the display projection is rebuilt. */
    private fun reconcileVerifiedMetadataTitles(writable: SQLiteDatabase, configuration: QueryConfiguration) {
        var afterFileId = ""
        while (true) {
            // Keyset batches also advance past protected matches. Close both source cursors
            // before updating metadata, and leave the full projection rebuild to the caller.
            val ids = writable.rawQuery("""SELECT DISTINCT g.file_id
                FROM game_entries g JOIN game_metadata m ON m.file_id=g.file_id
                WHERE g.file_id>? AND g.verified_title IS NOT NULL AND g.verified_title!=''
                    AND m.title IS NOT NULL AND m.title!=g.verified_title
                ORDER BY g.file_id LIMIT 400""", arrayOf(afterFileId)).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            }
            if (ids.isEmpty()) return
            val clause = "file_id IN (${ids.joinToString(",") { "?" }})"
            val games = buildList { forEachIndexedGame(configuration, clause, ids.toTypedArray(), consume = ::add) }
            games.forEach { refreshVerifiedMetadataTitle(writable, it) }
            afterFileId = ids.last()
        }
    }

    /** A new catalog name is visible even with automatic online metadata disabled. */
    private fun refreshVerifiedMetadataTitle(writable: SQLiteDatabase, game: LibraryGame): LibraryGame {
        val verified = game.verifiedTitle?.takeIf(String::isNotBlank) ?: return game
        if (game.customTitle != null || game.metadataTitle.isNullOrBlank() || game.metadataTitle == verified) return game
        val metadata = GameMetadataStorage.read(writable, game.fileId) ?: return game
        if (metadata.provider != GameMetadataProviderIds.LIBRETRO || metadata.matchLocked ||
            "title" in metadata.lockedFields) return game
        // Keep the old provider identity and freshness: the next online refresh sees that
        // its canonical name differs and resolves artwork for the newly verified name.
        GameMetadataStorage.write(writable, game.fileId, metadata.copy(title = verified))
        return game.copy(title = verified, metadataTitle = verified)
    }

    private fun putQueryGame(writable: SQLiteDatabase, game: LibraryGame, configuration: QueryConfiguration,
                             checkExisting: Boolean = true) =
        GameLibraryQueryIndex.put(writable, game, GameSystems.byId(game.systemId)?.shortName ?: game.systemId,
            checkExisting, configuration.folders[game.folderId]?.path?.length ?: 0)

    /** Streaming grouping keeps only one overlap candidate in memory, even on initial migration. */
    private fun forEachIndexedGame(
        configuration: QueryConfiguration,
        where: String? = null,
        args: Array<String>? = null,
        deduplicate: Boolean = true,
        consume: (LibraryGame) -> Unit,
    ) {
        val clause = where?.let { " WHERE " + it.replace(Regex("\\b(file_id|folder_id)\\b"), "g.$1") }.orEmpty()
        db.readableDatabase.rawQuery("""SELECT g.folder_id,g.file_id,g.source_id,g.path,g.filename,g.system_id,
            g.internal_title,g.file_size,g.modified,g.favorite,g.last_played,g.manual_title,g.verified_title,
            g.probe_version,g.dat_version,g.archive_crc,g.dat_checked,m.title,m.year,c.path,b.path,
            c.image_width,c.image_height,m.region,m.platform,g.dat_crc
            FROM game_entries g LEFT JOIN game_metadata m ON m.file_id=g.file_id
            LEFT JOIN game_artwork c ON c.file_id=g.file_id AND c.kind='cover'
            LEFT JOIN game_artwork b ON b.file_id=g.file_id AND b.kind='backdrop'
            $clause ORDER BY g.file_id,g.rowid""", args).use { cursor ->
            var selected: LibraryGame? = null
            var depth = -1
            while (cursor.moveToNext()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val folder = configuration.folders[cursor.getString(0)] ?: continue
                val filename = cursor.getString(4)
                if (!cachedGameMatchesFolder(folder, cursor.getString(2), filename, cursor.getString(5))) continue
                val portable = configuration.overrides[cursor.getString(2) to cursor.getString(3)]
                val custom = portable?.title?.takeIf(String::isNotBlank)
                    ?: cursor.getString(11)?.takeIf(String::isNotBlank)
                val verified = cursor.getString(12)?.takeIf(String::isNotBlank)
                val internal = cursor.getString(6)?.takeIf(String::isNotBlank)
                val game = LibraryGame(
                    fileId = cursor.getString(1), folderId = cursor.getString(0),
                    sourceId = cursor.getString(2), path = cursor.getString(3), filename = filename,
                    systemId = cursor.getString(5), title = custom ?: cursor.getString(17)?.takeIf(String::isNotBlank)
                        ?: verified ?: internal ?: gameDisplayFileName(filename),
                    internalTitle = cursor.getString(6), size = cursor.getLong(7), modified = cursor.getLong(8),
                    favorite = portable?.favorite ?: (cursor.getInt(9) == 1),
                    lastPlayed = maxOf(cursor.getLong(10), portable?.lastPlayed ?: 0L),
                    customTitle = custom, verifiedTitle = verified, probeVersion = cursor.getInt(13),
                    datVersion = cursor.getLong(14), archiveCrc = cursor.getString(15), datChecked = cursor.getInt(16) == 1,
                    metadataTitle = cursor.getString(17), metadataYear = if (cursor.isNull(18)) null else cursor.getInt(18),
                    coverPath = cursor.getString(19), backdropPath = cursor.getString(20),
                    coverWidth = if (cursor.isNull(21)) null else cursor.getInt(21),
                    coverHeight = if (cursor.isNull(22)) null else cursor.getInt(22),
                    metadataRegion = cursor.getString(23), metadataPlatform = cursor.getString(24),
                    datCrc = cursor.getString(25),
                )
                if (!deduplicate) { consume(game); continue }
                if (selected?.fileId != game.fileId) {
                    selected?.let(consume)
                    selected = game
                    depth = folder.path.length
                } else if (folder.path.length > depth) {
                    selected = game
                    depth = folder.path.length
                }
            }
            selected?.let(consume)
        }
    }

    fun setFavorite(fileId: String, value: Boolean) = editGame(fileId) { writable ->
        writable.execSQL("UPDATE game_entries SET favorite=? WHERE file_id=?",
            arrayOf<Any>(if (value) 1 else 0, fileId))
    }

    fun recordPlayed(fileId: String) = editGame(fileId) { writable ->
        writable.execSQL("UPDATE game_entries SET last_played=? WHERE file_id=?",
            arrayOf<Any>(System.currentTimeMillis(), fileId))
    }

    private fun editGame(fileId: String, edit: (SQLiteDatabase) -> Unit) = synchronized(queryLock) {
        ensureQueryIndex()
        val effective = game(fileId) ?: return@synchronized
        val writable = db.writableDatabase
        var committed = false
        writable.beginTransaction()
        try {
            // Imported choices are authoritative. Editing one field must not replace an
            // imported name/newer play time with an older raw scanner row's other fields.
            val favorite = if (effective.favorite) 1 else 0
            writable.execSQL("""UPDATE game_entries SET manual_title=?,favorite=?,last_played=?
                WHERE file_id=? AND (manual_title IS NOT ? OR favorite!=? OR last_played!=?)""",
                arrayOf<Any?>(effective.customTitle, favorite, effective.lastPlayed, fileId,
                    effective.customTitle, favorite, effective.lastPlayed))
            edit(writable)
            savePortableChoice(fileId)
            refreshQueryFiles(listOf(fileId), ensureQueryIndex())
            writable.setTransactionSuccessful()
            committed = true
        } finally {
            writable.endTransaction()
            if (!committed) queryConfiguration = null
        }
        changed()
    }

    private fun savePortableChoice(fileId: String, metadata: GameMetadata? = null) {
        db.readableDatabase.rawQuery(
            "SELECT source_id,path,manual_title,favorite,last_played FROM game_entries WHERE file_id=? LIMIT 1",
            arrayOf(fileId)
        ).use { rows ->
            if (!rows.moveToFirst()) return
            val existing = preferences.gameLibraryOverrides
            val before = existing.firstOrNull { it.sourceId == rows.getString(0) && it.path == rows.getString(1) }
            val value = GamePortableState(
                sourceId = rows.getString(0), path = rows.getString(1),
                title = rows.getString(2)?.takeIf(String::isNotBlank),
                favorite = rows.getInt(3) == 1, lastPlayed = rows.getLong(4),
                metadata = metadata ?: before?.metadata,
            )
            preferences.gameLibraryOverrides = (existing.filterNot {
                it.sourceId == value.sourceId && it.path == value.path
            } + value).takeLast(5000)
        }
    }

    /** A launch probe is user-initiated and passive. No magic packets are sent. */
    suspend fun needsWakeForLaunch(sourceId: String): Boolean {
        val source = files.sources().firstOrNull { it.id == sourceId }
            ?: throw IOException("Source was removed")
        if (!source.network || source.wolAuto || source.wolMac.isBlank()) return false
        // Ensure the existing WireGuard route first, without automatically waking the NAS.
        access.prepare(source.copy(wolAuto = false))
        return !wake.isReachable(source)
    }

    /** Explicit manual wake uses the SAME machine coordinator and notification as Files. */
    suspend fun wakeSource(sourceId: String): Boolean = withContext(Dispatchers.IO) {
        val source = files.sources().firstOrNull { it.id == sourceId } ?: return@withContext false
        if (source.wolMac.isBlank()) return@withContext false
        if (wake.isMachineWaking(source)) return@withContext false
        access.prepare(source.copy(wolAuto = false))
        wake.wakeAndWait(source, manual = true)
    }

    /**
     * User-triggered scan, never a cold-start/background network poll. Builds a complete
     * snapshot before DB writes. Failed or truncated scans never delete cached games.
     * No ROM is copied to cache, and metadata reads are fixed-size seek requests.
     */
    suspend fun scan(
        folderId: String,
        onProgress: (processed: Int, discovered: Int) -> Unit = { _, _ -> },
    ): Int = scan(folderId, null, onProgress)

    /** Scoped scan of an auto-detected folder: preserve all other console entries and artwork. */
    suspend fun scan(
        folderId: String,
        systemId: String?,
        onProgress: (processed: Int, discovered: Int) -> Unit = { _, _ -> },
    ): Int = scanLock.withLock {
        withContext(Dispatchers.IO) {
            val folder = folders().firstOrNull { it.id == folderId } ?: return@withContext 0
            if (systemId != null && folder.systemId != "auto" && folder.systemId != systemId) return@withContext 0
            val folderRevision = synchronized(queryLock) { folderFingerprint(folder) }
            val assigned = GameSystems.byId(folder.systemId)
            val previous = existing(folder.id)
            fun candidate(entry: FileEntry): Boolean {
                if (entry.directory) return false
                if (systemId != null && previous[entry.id]?.systemId?.let { it != systemId } == true) return false
                return if (assigned == null) entry.game else GameSystems.acceptsFile(assigned, entry.name) ||
                    (GameSystems.extension(entry.name) in GameSystems.archiveExtensions && entry.game)
            }
            val source = files.sources().firstOrNull { it.id == folder.sourceId }
                ?: throw IOException("Source was removed")
            // Only explicit scans wake a NAS. All workers reuse its prepared VPN/WOL route.
            access.prepare(source)
            val datVersions = catalogVersions()
            val discovered = java.util.concurrent.atomic.AtomicInteger()
            val processed = java.util.concurrent.atomic.AtomicInteger()

            // Bounded channel avoids 200k open file handles, 200k suspended coroutines, and
            // keeping a second copy of the entire directory catalog in device RAM.
            val results = kotlinx.coroutines.coroutineScope {
                val queue = kotlinx.coroutines.channels.Channel<FileEntry>(96)
                val lister = launch(Dispatchers.IO) {
                    try {
                        access.gameScanSession(source).use { session ->
                            val pending = ArrayDeque<Pair<String, Int>>()
                            val visited = hashSetOf<String>()
                            pending.add(folder.path to 0)
                            while (pending.isNotEmpty()) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val (path, depth) = pending.removeFirst()
                                if (!visited.add(path)) continue
                                require(visited.size <= 50000 && depth <= 40) {
                                    "Game folder scan limit reached"
                                }
                                var limit = 20_000
                                var listing = session.list(path, limit, ::candidate)
                                while (listing.more && limit < 200_000) {
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    limit = (limit * 2).coerceAtMost(200_000)
                                    listing = session.list(path, limit, ::candidate)
                                }
                                if (listing.more) throw IOException(
                                    "Directory exceeds 200000 entries; the existing catalog was not changed"
                                )
                                for (entry in listing.entries) {
                                    if (entry.directory) {
                                        if (folder.recursive) pending.add(entry.path to depth + 1)
                                        continue
                                    }
                                    if (!candidate(entry)) continue
                                    val known = discovered.incrementAndGet()
                                    require(known <= 200000) { "Game library item limit reached" }
                                    queue.send(entry)
                                }
                            }
                        }
                    } finally {
                        queue.close()
                    }
                }
                val workerCount = if (source.kind == "local") 2 else 3
                val workers = (0 until workerCount).map {
                    async(Dispatchers.IO) {
                        val indexed = ArrayList<IndexedGame>()
                        val descriptors = mutableMapOf<String, Set<String>>()
                        var lease: GameScanFileSession? = null
                        fun reader(): GameScanFileSession =
                            lease ?: access.gameScanSession(source).also { lease = it }
                        try {
                            for (entry in queue) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                // Cached ROMs need no file open and no extra login; create
                                // the worker's session only on the first actual metadata read.
                                val ext = GameSystems.extension(entry.name)
                                if (ext in setOf("cue", "gdi", "m3u")) {
                                    val refs = runCatching {
                                        val text = reader().readText(entry.path, 256 * 1024)
                                        GameDiscGrouping.referenced(entry.path, text)
                                    }.getOrDefault(emptySet())
                                    if (refs.isNotEmpty()) descriptors[entry.path] = refs
                                }
                                val last = previous[entry.id]
                                val unchanged = last != null && last.size == entry.size &&
                                    last.modified == entry.modified && entry.modified != 0L
                                val needsArchiveChecksum = needsArchiveDatChecksum(entry.name, last,
                                    last?.let { datVersions[it.systemId] } ?: 0L)
                                val cachedProbe = unchanged && last.probeVersion == probeVersion(last.systemId) &&
                                    (folder.systemId == "auto" || folder.systemId == last.systemId) && !needsArchiveChecksum
                                val archive = if (ext in GameSystems.archiveExtensions && !cachedProbe) {
                                    if (ext == "rar") {
                                        // RAR title inference is filename-only. Don't open 1,000
                                        // remote archives for metadata we cannot read in-place.
                                        val nested = entry.name.substringBeforeLast('.')
                                        GameArchives.Metadata(nested, GameSystems.forFile(nested))
                                    } else {
                                        val probed = runCatching {
                                            reader().open(entry.path).use {
                                                GameArchives.probe(entry.name, it, assigned?.id, checksumSystems = datVersions.keys.filter(GameNameDatabases::usesChecksums).toSet())
                                            }
                                        }
                                        // Network/decoder errors must NOT silently delete a
                                        // previously indexed title and commit a truncated DB.
                                        probed.getOrElse { problem ->
                                            throw IOException(
                                                "Archive metadata read failed: ${entry.name}",
                                                problem,
                                            )
                                        }
                                    }
                                } else null
                                val system = when {
                                    cachedProbe -> GameSystems.byId(last.systemId)
                                    ext in GameSystems.archiveExtensions -> archive?.innerName?.let { inner ->
                                        val detected = archive.system ?: GameSystems.forSourceFile(inner, entry.path)
                                        GameSystems.forAssignedFile(inner, folder.systemId, detected,
                                            archive.systemFromHeader)
                                    }
                                    else -> classify(entry, reader(), folder.systemId)
                                }
                                if (system != null && (systemId == null || system.id == systemId)) {
                                    val internal = if (cachedProbe) last.internalTitle
                                        else archive?.internalTitle ?: if (ext in GameSystems.archiveExtensions)
                                            null else extractTitle(entry, reader(), system.id)
                                    val version = datVersions[system.id] ?: 0L
                                    val checksums = when {
                                        cachedProbe && (last.archiveCrc != null || version == 0L) ->
                                            GameRomChecksums.Result(last.archiveCrc, last.datCrc)
                                        archive != null -> GameRomChecksums.Result(archive.crc32, archive.datCrc32)
                                        version > 0L && GameNameDatabases.usesChecksums(system.id) -> datChecksums(reader(), entry, system.id)
                                        else -> GameRomChecksums.Result(null)
                                    }
                                    val verified = when {
                                        !GameNameDatabases.usesChecksums(system.id) -> last?.verifiedTitle?.takeIf { unchanged }
                                        version == 0L -> null
                                        cachedProbe && last.datVersion == version &&
                                            checksums.crc32 == last.archiveCrc && checksums.datCrc32 == last.datCrc -> last.verifiedTitle
                                        else -> checksums.crc32?.let { datNameFromCrc(system.id, it) }
                                            ?: checksums.datCrc32?.let { datNameFromCrc(system.id, it) }
                                    }
                                    indexed += IndexedGame(entry, system.id, internal,
                                        last?.favorite ?: false, last?.lastPlayed ?: 0L,
                                        last?.customTitle, verified, probeVersion(system.id), version,
                                        checksums.crc32, checksums.datCrc32)
                                }
                                val count = processed.incrementAndGet()
                                if (count % 16 == 0 || count == discovered.get())
                                    onProgress(count, discovered.get())
                            }
                        } finally {
                            lease?.close()
                        }
                        indexed to descriptors
                    }
                }
                lister.join()
                workers.awaitAll()
            }

            val selected = results.flatMapTo(ArrayList<IndexedGame>()) { it.first }
            val references = results.flatMap { it.second.entries }
                .associate { it.key to it.value }
            val visible = GameDiscGrouping.visiblePaths(
                selected.mapTo(hashSetOf()) { it.entry.path }, references)
            selected.removeAll { it.entry.path !in visible }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val scanContext = kotlinx.coroutines.currentCoroutineContext()
            val discardedPaths = mutableListOf<String>()
            var abandoned = emptySet<String>()
            var removedChoices = emptySet<Pair<String, String>>()
            val accepted = synchronized(queryLock) {
                val configuration = ensureQueryIndex()
                // Scanning can outlive removal, source edits or a backup restore. Its old
                // snapshot must never recreate the removed folder's rows or metadata.
                if (configuration.folders[folder.id] != folder || folderFingerprint(folder) != folderRevision ||
                    files.sources().none { it.sameLocation(source) && it.id == source.id }) return@synchronized false
                val writable = db.writableDatabase
                writable.beginTransaction()
                try {
                    val folderWhere = if (systemId == null) "folder_id=?" else "folder_id=? AND system_id=?"
                    val folderArgs = if (systemId == null) arrayOf(folder.id) else arrayOf(folder.id, systemId)
                    val affected = writable.rawQuery("SELECT file_id FROM game_entries WHERE $folderWhere",
                        folderArgs).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } } +
                        selected.map { it.entry.id }
                    val generation = System.nanoTime()
                    for (game in selected) {
                        scanContext.ensureActive()
                        val e = game.entry
                        val last = previous[e.id]
                        if (last != null && (last.size != e.size || last.modified != e.modified || last.systemId != game.systemId)) {
                            writable.rawQuery("SELECT path FROM game_artwork WHERE file_id=? AND pinned=0", arrayOf(e.id)).use { rows ->
                                while (rows.moveToNext()) discardedPaths += rows.getString(0)
                            }
                            writable.delete("game_artwork", "file_id=? AND pinned=0", arrayOf(e.id))
                            writable.delete("game_metadata", "file_id=?", arrayOf(e.id))
                            writable.delete("game_metadata_checks", "file_id=?", arrayOf(e.id))
                            writable.delete("game_metadata_guards", "file_id=?", arrayOf(e.id))
                        }
                        val values = android.content.ContentValues().apply {
                            put("folder_id", folder.id); put("file_id", e.id); put("source_id", e.sourceId)
                            put("path", e.path); put("filename", e.name); put("system_id", game.systemId)
                            put("internal_title", game.internalTitle); put("file_size", e.size)
                            put("modified", e.modified); put("favorite", if (game.favorite) 1 else 0)
                            put("last_played", game.lastPlayed); put("seen", generation)
                            put("manual_title", game.customTitle); put("verified_title", game.verifiedTitle)
                            put("probe_version", game.probeVersion); put("dat_version", game.datVersion)
                            put("archive_crc", game.archiveCrc)
                            put("dat_crc", game.datCrc)
                            put("dat_checked", if (game.datVersion > 0L) 1 else 0)
                        }
                        writable.insertWithOnConflict("game_entries", null, values,
                            SQLiteDatabase.CONFLICT_REPLACE)
                        configuration.overrides[e.sourceId to e.path]?.metadata?.let { portable ->
                            if (GameMetadataStorage.read(writable, e.id) == null) GameMetadataStorage.write(writable, e.id, portable)
                        }
                    }
                    removedChoices = existingChoices(writable, "$folderWhere AND seen!=?",
                        folderArgs + generation.toString(), configuration)
                    writable.delete("game_entries", "$folderWhere AND seen!=?",
                        folderArgs + generation.toString())
                    refreshQueryFiles(affected, configuration, updateVerifiedMetadata = true)
                    abandoned = pruneOrphanedMetadata(writable)
                    writable.setTransactionSuccessful()
                } finally {
                    writable.endTransaction()
                }
                abandoned.forEach(artworkFiles::remove)
                discardedPaths.forEach(artworkFiles::removePath)
                if (purgePortableChoices(removedChoices)) {
                    queryConfiguration = null
                    ensureQueryIndex()
                }
                true
            }
            if (!accepted) return@withContext 0
            onProgress(processed.get(), discovered.get())
            changed()
            selected.size
        }
    }

    private data class IndexedGame(val entry: FileEntry, val systemId: String,
        val internalTitle: String?, val favorite: Boolean, val lastPlayed: Long,
        val customTitle: String?, val verifiedTitle: String?,
        val probeVersion: Int, val datVersion: Long, val archiveCrc: String?, val datCrc: String?)

    private fun existing(folderId: String): Map<String, LibraryGame> = synchronized(queryLock) {
        val configuration = currentQueryConfiguration()
        buildMap {
            forEachIndexedGame(configuration, "folder_id=?", arrayOf(folderId), deduplicate = false) {
                put(it.fileId, it)
            }
        }
    }

    private fun classify(entry: FileEntry, session: GameScanFileSession, systemId: String): com.ambient.tv.games.GameSystem? {
        val head = if (GameSystems.extension(entry.name) == "iso") {
            runCatching { session.open(entry.path).use { media ->
                ByteArray(0x20).also { if (media.read(0, it, it.size) != it.size) return@use null }
            } }.getOrNull()
        } else null
        return GameSystems.forAssignedFile(entry.name, systemId,
            GameSystems.forSourceFile(entry.name, entry.path, head),
            systemFromHeader = GameSystems.forDiscHeader(entry.name, head) != null)
    }

    private companion object {
        const val PROBE_VERSION = 3
    }

    // Invalidate only DS, 3DS and PSP probes so existing libraries recover new titles and alternate CRCs.
    private fun probeVersion(systemId: String): Int = if (systemId in setOf("nds", "n3ds", "psp")) PROBE_VERSION + 1 else PROBE_VERSION

    private fun extractTitle(entry: FileEntry, session: GameScanFileSession, system: String): String? {
        if (entry.size <= 0 || system !in setOf("gb", "gba", "nds", "snes", "megadrive",
                "n64", "gamecube", "wii", "psp")) return null
        val ext = GameSystems.extension(entry.name)
        if (ext in GameSystems.archiveExtensions || ext in setOf("cue", "m3u", "gdi", "chd",
                "wbfs", "rvz", "ciso") || (ext in setOf("cso", "zso") && system != "psp")) return null
        return runCatching {
            session.open(entry.path).use { media ->
                GameRomTitles.extract(system, entry.name) { offset, count ->
                    if (offset < 0L || count <= 0 || count > 64 * 1024 ||
                        offset + count > media.size) null
                    else ByteArray(count).takeIf { media.read(offset, it, count) == count }
                }
            }
        }.getOrNull()
    }

}
