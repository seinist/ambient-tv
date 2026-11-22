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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.CRC32
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
)

/** Preserve the previous fast-negative-match contract for existing library users/tests. */
internal fun reuseDatLookup(previous: LibraryGame?, systemId: String, size: Long, modified: Long): Boolean =
    previous != null && previous.datChecked && previous.systemId == systemId &&
        modified != 0L && previous.size == size && previous.modified == modified

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

private class GameIndexDb(context: Context) : SQLiteOpenHelper(context, "game_library.db", null, 5) {
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
            archive_crc TEXT,
            PRIMARY KEY(folder_id, file_id))""")
        db.execSQL("CREATE INDEX game_entries_system ON game_entries(system_id)")
        db.execSQL("CREATE INDEX game_entries_source ON game_entries(source_id)")
        db.execSQL("""CREATE TABLE game_dat_entries (
            system_id TEXT NOT NULL, crc32 TEXT NOT NULL, title TEXT NOT NULL,
            PRIMARY KEY(system_id, crc32))""")
        db.execSQL("""CREATE TABLE game_dat_versions (
            system_id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL)""")
        GameLibraryQueryIndex.create(db)
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
    private val changeSignal = MutableStateFlow(0L)
    private val catalogRevision = java.util.concurrent.atomic.AtomicLong()
    val revisions = changeSignal.asStateFlow()
    private val scanLock = kotlinx.coroutines.sync.Mutex()
    private val queryLock = Any()
    private val queryExecutions = java.util.concurrent.atomic.AtomicLong()
    private var queryConfiguration: QueryConfiguration? = null
    /** Diagnostic counter for invalidation audits; counting does not perform database writes. */
    internal val readQueryCount: Long get() = queryExecutions.get()
    private fun changed(catalog: Boolean = true) {
        if (catalog) catalogRevision.incrementAndGet()
        changeSignal.update { it + 1 }
    }

    internal fun revisionSnapshot(includeOverrides: Boolean = true): GameLibraryRevision = GameLibraryRevision(
        catalog = catalogRevision.get(),
        folders = prefs.getString("game_library_folders", "[]").orEmpty(),
        overrides = if (includeOverrides) prefs.getString("game_library_overrides", "[]").orEmpty() else "",
        sources = prefs.getString("file_sources", "[]").orEmpty(),
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
        preferences.gameLibraryFolders = folders().filterNot { it.id == id }
        db.writableDatabase.delete("game_entries", "folder_id=?", arrayOf(id))
        // Removing an assignment can expose an overlapping parent folder. Rebuild once from
        // the new configuration instead of deleting an otherwise still playable game.
        ensureQueryIndex()
        changed()
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
     * An optional user-supplied DAT provides verified full names by ROM CRC32.
     * Import is transactional and limited; it never fetches a game database online.
     */
    suspend fun importDat(systemId: String, input: InputStream): Int = scanLock.withLock {
        require(GameSystems.byId(systemId) != null)
        withContext(Dispatchers.IO) {
            synchronized(queryLock) {
                val configuration = ensureQueryIndex()
                val writable = db.writableDatabase
                writable.beginTransaction()
                try {
                    writable.delete("game_dat_entries", "system_id=?", arrayOf(systemId))
                    val imported = input.use { stream ->
                        GameRomDatReader.parse(stream) { record ->
                            val values = android.content.ContentValues().apply {
                                put("system_id", systemId)
                                put("crc32", record.crc32)
                                put("title", record.title)
                            }
                            writable.insertWithOnConflict("game_dat_entries", null, values,
                                SQLiteDatabase.CONFLICT_REPLACE)
                        }
                    }
                    require(imported > 0) { "DAT contains no ROM CRC32 entries" }
                    val previous = writable.rawQuery(
                        "SELECT revision FROM game_dat_versions WHERE system_id=?", arrayOf(systemId)
                    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                    writable.execSQL(
                        "INSERT OR REPLACE INTO game_dat_versions(system_id,revision) VALUES(?,?)",
                        arrayOf<Any>(systemId, previous + 1)
                    )
                    // The imported DAT supersedes old verified names, including prior negatives.
                    writable.execSQL(
                        "UPDATE game_entries SET verified_title=NULL,dat_checked=0 WHERE system_id=?",
                        arrayOf<Any>(systemId)
                    )
                    val affected = writable.rawQuery("SELECT file_id FROM game_entries WHERE system_id=?",
                        arrayOf(systemId)).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
                    refreshQueryFiles(affected, configuration)
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

    private fun datTitle(session: GameScanFileSession, entry: FileEntry, systemId: String): String? {
        // Never hash a huge NAS ISO or an archive merely to populate library artwork.
        if (entry.size <= 0 || entry.size > 128L * 1024 * 1024 ||
            GameSystems.extension(entry.name) in
                (GameSystems.archiveExtensions + setOf("cue", "m3u", "gdi", "chd",
                    "iso", "cso", "zso", "wbfs", "rvz", "ciso", "pbp"))) return null
        val checksum = session.open(entry.path).use { media ->
                val crc = CRC32()
                val chunk = ByteArray(128 * 1024)
                var offset = 0L
                while (offset < entry.size) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val length = minOf(chunk.size.toLong(), entry.size - offset).toInt()
                    val read = media.read(offset, chunk, length)
                    if (read <= 0) throw IOException("ROM read truncated")
                    crc.update(chunk, 0, read)
                    offset += read
                }
                java.lang.Long.toHexString(crc.value).uppercase(Locale.ROOT).padStart(8, '0')
            }
        return datNameFromCrc(systemId, checksum)
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
                    ))
                }
            }
        }
    }

    fun game(fileId: String): LibraryGame? = games(GameLibraryQuery(fileId = fileId, limit = 1)).firstOrNull()

    /** Read configuration only once per actual preference change, not per search keystroke. */
    private data class QueryConfiguration(
        val folderJson: String,
        val overrideJson: String,
        val sourceIds: Set<String>,
        val folders: Map<String, GameLibraryFolder>,
        val overrides: Map<Pair<String, String>, GamePortableState>,
        val signature: String,
    )

    private fun currentQueryConfiguration(): QueryConfiguration {
        val folderJson = prefs.getString("game_library_folders", "[]").orEmpty()
        val overrideJson = prefs.getString("game_library_overrides", "[]").orEmpty()
        val sourceIds = files.sources().mapTo(hashSetOf()) { it.id }
        queryConfiguration?.takeIf {
            it.folderJson == folderJson && it.overrideJson == overrideJson && it.sourceIds == sourceIds
        }?.let { return it }
        val signature = gameQueryDigest(listOf(GameLibraryQueryIndex.POLICY_VERSION.toString(),
            folderJson, overrideJson) + sourceIds.sorted())
        return QueryConfiguration(folderJson, overrideJson, sourceIds,
            folders().filter { it.sourceId in sourceIds }.associateBy { it.id },
            preferences.gameLibraryOverrides.associateBy { it.sourceId to it.path }, signature)
    }

    /** A backup/import rebuilds local display data once; scans and single-ROM edits update only affected files. */
    private fun ensureQueryIndex(): QueryConfiguration {
        val configuration = currentQueryConfiguration()
        val previous = queryConfiguration
        if (configuration === previous) return configuration
        val writable = db.writableDatabase
        if (GameLibraryQueryIndex.signature(writable) != configuration.signature) {
            writable.beginTransaction()
            try {
                if (previous != null && previous.folderJson == configuration.folderJson &&
                    previous.sourceIds == configuration.sourceIds) {
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
                    writable.delete("game_query", null, null)
                    forEachIndexedGame(configuration) { game -> putQueryGame(writable, game, configuration, checkExisting = false) }
                }
                GameLibraryQueryIndex.signature(writable, configuration.signature)
                writable.setTransactionSuccessful()
            } finally { writable.endTransaction() }
        }
        queryConfiguration = configuration
        return configuration
    }

    private fun refreshQueryFiles(fileIds: Collection<String>, configuration: QueryConfiguration) {
        val writable = db.writableDatabase
        fileIds.chunked(400).forEach { ids ->
            val clause = "file_id IN (${ids.joinToString(",") { "?" }})"
            val removed = ids.toMutableSet()
            forEachIndexedGame(configuration, clause, ids.toTypedArray()) { game ->
                putQueryGame(writable, game, configuration)
                removed.remove(game.fileId)
            }
            if (removed.isNotEmpty()) writable.delete("game_query",
                "file_id IN (${removed.joinToString(",") { "?" }})", removed.toTypedArray())
        }
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
        val clause = where?.let { " WHERE $it" }.orEmpty()
        db.readableDatabase.rawQuery("""SELECT folder_id,file_id,source_id,path,filename,system_id,
            internal_title,file_size,modified,favorite,last_played,manual_title,verified_title,
            probe_version,dat_version,archive_crc,dat_checked FROM game_entries$clause ORDER BY file_id,rowid""", args).use { cursor ->
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
                    systemId = cursor.getString(5), title = custom ?: verified ?: internal ?: gameDisplayFileName(filename),
                    internalTitle = cursor.getString(6), size = cursor.getLong(7), modified = cursor.getLong(8),
                    favorite = portable?.favorite ?: (cursor.getInt(9) == 1),
                    lastPlayed = maxOf(cursor.getLong(10), portable?.lastPlayed ?: 0L),
                    customTitle = custom, verifiedTitle = verified, probeVersion = cursor.getInt(13),
                    datVersion = cursor.getLong(14), archiveCrc = cursor.getString(15), datChecked = cursor.getInt(16) == 1,
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

    private fun savePortableChoice(fileId: String) {
        db.readableDatabase.rawQuery(
            "SELECT source_id,path,manual_title,favorite,last_played FROM game_entries WHERE file_id=? LIMIT 1",
            arrayOf(fileId)
        ).use { rows ->
            if (!rows.moveToFirst()) return
            val value = GamePortableState(
                sourceId = rows.getString(0), path = rows.getString(1),
                title = rows.getString(2)?.takeIf(String::isNotBlank),
                favorite = rows.getInt(3) == 1, lastPlayed = rows.getLong(4),
            )
            val existing = preferences.gameLibraryOverrides
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
    ): Int = scanLock.withLock {
        withContext(Dispatchers.IO) {
            val folder = folders().firstOrNull { it.id == folderId } ?: return@withContext 0
            val assigned = GameSystems.byId(folder.systemId)
            fun candidate(entry: FileEntry): Boolean {
                if (entry.directory) return false
                return if (assigned == null) entry.game else GameSystems.acceptsFile(assigned, entry.name) ||
                    (GameSystems.extension(entry.name) in GameSystems.archiveExtensions && entry.game)
            }
            val source = files.sources().firstOrNull { it.id == folder.sourceId }
                ?: throw IOException("Source was removed")
            // Only explicit scans wake a NAS. All workers reuse its prepared VPN/WOL route.
            access.prepare(source)
            val previous = existing(folder.id)
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
                                val cachedProbe = unchanged && last!!.probeVersion == PROBE_VERSION &&
                                    (folder.systemId == "auto" || folder.systemId == last.systemId)
                                val archive = if (ext in GameSystems.archiveExtensions && !cachedProbe) {
                                    if (ext == "rar") {
                                        // RAR title inference is filename-only. Don't open 1,000
                                        // remote archives for metadata we cannot read in-place.
                                        val nested = entry.name.substringBeforeLast('.')
                                        GameArchives.Metadata(nested, GameSystems.forFile(nested))
                                    } else {
                                        val probed = runCatching {
                                            reader().open(entry.path).use {
                                                GameArchives.probe(entry.name, it, assigned?.id)
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
                                    cachedProbe -> GameSystems.byId(last!!.systemId)
                                    ext in GameSystems.archiveExtensions -> archive?.innerName?.let { inner ->
                                        val detected = archive.system ?: GameSystems.forSourceFile(inner, entry.path)
                                        GameSystems.forAssignedFile(inner, folder.systemId, detected,
                                            archive.systemFromHeader)
                                    }
                                    else -> classify(entry, reader(), folder.systemId)
                                }
                                if (system != null) {
                                    val internal = if (cachedProbe) last!!.internalTitle
                                        else archive?.internalTitle ?: if (ext in GameSystems.archiveExtensions)
                                            null else extractTitle(entry, reader(), system.id)
                                    val crc = if (cachedProbe) last!!.archiveCrc else archive?.crc32
                                    val version = datVersions[system.id] ?: 0L
                                    val verified = when {
                                        version == 0L -> null
                                        unchanged && last!!.datVersion == version -> last.verifiedTitle
                                        crc != null -> datNameFromCrc(system.id, crc)
                                        else -> datTitle(reader(), entry, system.id)
                                    }
                                    indexed += IndexedGame(entry, system.id, internal,
                                        last?.favorite ?: false, last?.lastPlayed ?: 0L,
                                        last?.customTitle, verified, PROBE_VERSION, version, crc)
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
            synchronized(queryLock) {
                val configuration = ensureQueryIndex()
                val writable = db.writableDatabase
                writable.beginTransaction()
                try {
                    val affected = writable.rawQuery("SELECT file_id FROM game_entries WHERE folder_id=?",
                        arrayOf(folder.id)).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } } +
                        selected.map { it.entry.id }
                    val generation = System.nanoTime()
                    for (game in selected) {
                        scanContext.ensureActive()
                        val e = game.entry
                        val values = android.content.ContentValues().apply {
                            put("folder_id", folder.id); put("file_id", e.id); put("source_id", e.sourceId)
                            put("path", e.path); put("filename", e.name); put("system_id", game.systemId)
                            put("internal_title", game.internalTitle); put("file_size", e.size)
                            put("modified", e.modified); put("favorite", if (game.favorite) 1 else 0)
                            put("last_played", game.lastPlayed); put("seen", generation)
                            put("manual_title", game.customTitle); put("verified_title", game.verifiedTitle)
                            put("probe_version", game.probeVersion); put("dat_version", game.datVersion)
                            put("archive_crc", game.archiveCrc)
                            put("dat_checked", if (game.datVersion > 0L) 1 else 0)
                        }
                        writable.insertWithOnConflict("game_entries", null, values,
                            SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    writable.delete("game_entries", "folder_id=? AND seen!=?",
                        arrayOf(folder.id, generation.toString()))
                    refreshQueryFiles(affected, configuration)
                    writable.setTransactionSuccessful()
                } finally {
                    writable.endTransaction()
                }
            }
            onProgress(processed.get(), discovered.get())
            changed()
            selected.size
        }
    }

    private data class IndexedGame(val entry: FileEntry, val systemId: String,
        val internalTitle: String?, val favorite: Boolean, val lastPlayed: Long,
        val customTitle: String?, val verifiedTitle: String?,
        val probeVersion: Int, val datVersion: Long, val archiveCrc: String?)

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
        const val PROBE_VERSION = 2
    }

    private fun extractTitle(entry: FileEntry, session: GameScanFileSession, system: String): String? {
        if (entry.size <= 0 || system !in setOf("gb", "gba", "nds", "snes", "megadrive",
                "n64", "gamecube", "wii", "psp")) return null
        val ext = GameSystems.extension(entry.name)
        if (ext in GameSystems.archiveExtensions || ext in setOf("cue", "m3u", "gdi", "chd",
                "cso", "zso", "wbfs", "rvz", "ciso")) return null
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
