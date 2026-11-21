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
)

private class GameIndexDb(context: Context) : SQLiteOpenHelper(context, "game_library.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE game_entries (
            folder_id TEXT NOT NULL, file_id TEXT NOT NULL, source_id TEXT NOT NULL,
            path TEXT NOT NULL, filename TEXT NOT NULL, system_id TEXT NOT NULL,
            internal_title TEXT, file_size INTEGER NOT NULL, modified INTEGER NOT NULL,
            favorite INTEGER NOT NULL DEFAULT 0, last_played INTEGER NOT NULL DEFAULT 0,
            seen INTEGER NOT NULL DEFAULT 0, manual_title TEXT, verified_title TEXT,
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
            db.execSQL("ALTER TABLE game_entries ADD COLUMN probe_version INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE game_entries ADD COLUMN dat_version INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE game_entries ADD COLUMN archive_crc TEXT")
            db.execSQL("""CREATE TABLE IF NOT EXISTS game_dat_versions (
                system_id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL)""")
            db.execSQL("""INSERT OR IGNORE INTO game_dat_versions(system_id,revision)
                SELECT DISTINCT system_id,1 FROM game_dat_entries""")
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
    private val changeSignal = MutableStateFlow(0L)
    val revisions = changeSignal.asStateFlow()
    private val scanLock = kotlinx.coroutines.sync.Mutex()
    private fun changed() = changeSignal.update { it + 1 }

    fun folders(): List<GameLibraryFolder> = preferences.gameLibraryFolders.filter {
        it.sourceId.isNotBlank() && (it.systemId == "auto" || GameSystems.byId(it.systemId) != null)
    }.distinctBy { it.id }.take(200)

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
        changed()
    }

    fun removeFolder(id: String) {
        preferences.gameLibraryFolders = folders().filterNot { it.id == id }
        db.writableDatabase.delete("game_entries", "folder_id=?", arrayOf(id))
        changed()
    }

    /** Minimal catalog lookup for the sidebar; don't load every game on the UI thread. */
    fun systemIds(): List<String> {
        val current = files.sources().map { it.id }.toSet()
        val allowed = folders().filter { it.sourceId in current }.map { it.id }.toSet()
        val systems = linkedSetOf<String>()
        db.readableDatabase.rawQuery("SELECT DISTINCT folder_id,system_id FROM game_entries", null).use { rows ->
            while (rows.moveToNext()) if (rows.getString(0) in allowed) systems += rows.getString(1)
        }
        return systems.toList()
    }

    fun gridColumns(): Int = prefs.getInt("game_library_grid_columns", 8).coerceIn(4, 12)
    fun listRows(): Int = prefs.getInt("game_library_list_rows", 7).coerceIn(4, 10)
    fun setGridColumns(value: Int) {
        prefs.edit().putInt("game_library_grid_columns", value.coerceIn(4, 12)).apply()
        changed()
    }
    fun setListRows(value: Int) {
        prefs.edit().putInt("game_library_list_rows", value.coerceIn(4, 10)).apply()
        changed()
    }

    fun titleStyle(): String = prefs.getString("game_library_title_style", "auto").orEmpty()
    fun setTitleStyle(style: String) {
        require(style in setOf("auto", "internal", "filename"))
        prefs.edit().putString("game_library_title_style", style).apply()
        changed()
    }

    fun viewStyle(): String = prefs.getString("game_library_view_style", "grid").orEmpty()
    fun setViewStyle(style: String) {
        require(style in setOf("grid", "list", "rows"))
        prefs.edit().putString("game_library_view_style", style).apply()
        changed()
    }

    /**
     * An optional user-supplied DAT provides verified full names by ROM CRC32.
     * Import is transactional and limited; it never fetches a game database online.
     */
    suspend fun importDat(systemId: String, input: InputStream): Int = scanLock.withLock {
        require(GameSystems.byId(systemId) != null)
        withContext(Dispatchers.IO) {
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
                // Keep old verified titles until the next scan. That scan uses the new
                // generation and will replace positive *and* negative DAT matches.
                writable.setTransactionSuccessful()
                changed()
                imported
            } finally {
                writable.endTransaction()
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
        val checksum = runCatching {
            session.open(entry.path).use { media ->
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
        }.getOrNull() ?: return null
        return datNameFromCrc(systemId, checksum)
    }

    fun sortKey(): String = prefs.getString("game_library_sort", "name").orEmpty()
    fun sortDescending(): Boolean = prefs.getBoolean("game_library_descending", false)
    fun setSortKey(key: String) {
        require(key in setOf("name", "recent", "system"))
        prefs.edit().putString("game_library_sort", key).apply()
        changed()
    }
    fun setSortDescending(value: Boolean) {
        prefs.edit().putBoolean("game_library_descending", value).apply()
        changed()
    }

    /** A manual edit always wins over ROM headers and imported DAT titles. */
    fun setCustomTitle(fileId: String, value: String) {
        require(value.length <= 160 && value.none { it.isISOControl() })
        val normalized = value.trim().takeIf { it.isNotBlank() }
        db.writableDatabase.execSQL("UPDATE game_entries SET manual_title=? WHERE file_id=?",
            arrayOf<Any?>(normalized, fileId))
        savePortableChoice(fileId)
        changed()
    }

    /** Disk cache is loaded even if the NAS is sleeping; opening the library never wakes it. */
    fun games(): List<LibraryGame> {
        val current = files.sources().map { it.id }.toSet()
        val allowed = folders().filter { it.sourceId in current }.associateBy { it.id }
        val style = titleStyle()
        val overrides = preferences.gameLibraryOverrides.associateBy { it.sourceId to it.path }
        val result = mutableListOf<LibraryGame>()
        db.readableDatabase.rawQuery(
            "SELECT folder_id,file_id,source_id,path,filename,system_id,internal_title,file_size,modified,favorite,last_played,manual_title,verified_title,probe_version,dat_version,archive_crc FROM game_entries",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(0) !in allowed) continue
                val filename = cursor.getString(4)
                val portable = overrides[cursor.getString(2) to cursor.getString(3)]
                val custom = portable?.title?.takeIf { it.isNotBlank() }
                    ?: cursor.getString(11)?.takeIf { it.isNotBlank() }
                val verified = cursor.getString(12)?.takeIf { it.isNotBlank() }
                val internal = cursor.getString(6)?.takeIf { it.isNotBlank() }
                val title = custom ?: when (style) {
                    "filename" -> null
                    "internal" -> internal
                    else -> verified ?: internal
                }
                result += LibraryGame(
                    fileId = cursor.getString(1), folderId = cursor.getString(0),
                    sourceId = cursor.getString(2), path = cursor.getString(3),
                    filename = filename, systemId = cursor.getString(5),
                    title = title ?: displayFileName(filename),
                    internalTitle = cursor.getString(6),
                    size = cursor.getLong(7), modified = cursor.getLong(8),
                    favorite = portable?.favorite ?: (cursor.getInt(9) == 1),
                    lastPlayed = maxOf(cursor.getLong(10), portable?.lastPlayed ?: 0L),
                    customTitle = custom, verifiedTitle = verified,
                    probeVersion = cursor.getInt(13), datVersion = cursor.getLong(14),
                    archiveCrc = cursor.getString(15),
                )
            }
        }
        // Overlapping assignments are legal. Prefer the deepest matching folder
        // (e.g. NAS/Games/SNES) to a generic NAS/Games auto-detect root.
        return result.sortedWith(compareByDescending<LibraryGame> {
            allowed[it.folderId]?.path?.length ?: 0
        }.thenBy { it.fileId }).distinctBy { it.fileId }
    }

    private fun displayFileName(filename: String): String =
        filename.substringBeforeLast('.', filename)
            .replace('_', ' ').replace(Regex("\\s+"), " ").trim()

    fun setFavorite(fileId: String, value: Boolean) {
        db.writableDatabase.execSQL("UPDATE game_entries SET favorite=? WHERE file_id=?",
            arrayOf<Any>(if (value) 1 else 0, fileId))
        savePortableChoice(fileId)
        changed()
    }

    fun recordPlayed(fileId: String) {
        db.writableDatabase.execSQL("UPDATE game_entries SET last_played=? WHERE file_id=?",
            arrayOf<Any>(System.currentTimeMillis(), fileId))
        savePortableChoice(fileId)
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
                                var listing = session.list(path, limit) { it.game }
                                while (listing.more && limit < 200_000) {
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    limit = (limit * 2).coerceAtMost(200_000)
                                    listing = session.list(path, limit) { it.game }
                                }
                                if (listing.more) throw IOException(
                                    "Directory exceeds 200000 entries; the existing catalog was not changed"
                                )
                                for (entry in listing.entries) {
                                    if (entry.directory) {
                                        if (folder.recursive) pending.add(entry.path to depth + 1)
                                        continue
                                    }
                                    if (!entry.game) continue
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
                        access.gameScanSession(source).use { session ->
                            for (entry in queue) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val ext = GameSystems.extension(entry.name)
                                if (ext in setOf("cue", "gdi", "m3u")) {
                                    val refs = runCatching {
                                        val text = session.readText(entry.path, 256 * 1024)
                                        GameDiscGrouping.referenced(entry.path, text)
                                    }.getOrDefault(emptySet())
                                    if (refs.isNotEmpty()) descriptors[entry.path] = refs
                                }
                                val last = previous[entry.id]
                                val assigned = GameSystems.byId(folder.systemId)
                                val unchanged = last != null && last.size == entry.size &&
                                    last.modified == entry.modified && entry.modified != 0L
                                val cachedProbe = unchanged && last!!.probeVersion == PROBE_VERSION
                                val archive = if (ext in GameSystems.archiveExtensions && !cachedProbe) {
                                    if (ext == "rar") {
                                        // RAR title inference is filename-only. Don't open 1,000
                                        // remote archives for metadata we cannot read in-place.
                                        val nested = entry.name.substringBeforeLast('.')
                                        GameArchives.Metadata(nested, GameSystems.forFile(nested))
                                    } else runCatching {
                                        session.open(entry.path).use {
                                            GameArchives.probe(entry.name, it)
                                        }
                                    }.getOrNull()
                                } else null
                                val detected = if (cachedProbe) GameSystems.byId(last!!.systemId)
                                    else archive?.system ?: classify(entry, session, skipArchives = true)
                                val mismatch = ext == "iso" &&
                                    detected?.id in setOf("wii", "gamecube") &&
                                    detected?.id != folder.systemId
                                val system = assigned?.takeIf { !mismatch &&
                                    (ext in it.extensions || ext in GameSystems.ambiguousExtensions ||
                                        (ext in GameSystems.archiveExtensions &&
                                            (detected == null || detected.id == it.id)))
                                } ?: detected
                                if (system != null) {
                                    val internal = if (cachedProbe) last!!.internalTitle
                                        else archive?.internalTitle ?: if (ext in GameSystems.archiveExtensions)
                                            null else extractTitle(entry, session, system.id)
                                    val crc = if (cachedProbe) last!!.archiveCrc else archive?.crc32
                                    val version = datVersions[system.id] ?: 0L
                                    val verified = when {
                                        version == 0L -> null
                                        unchanged && last!!.datVersion == version -> last.verifiedTitle
                                        crc != null -> datNameFromCrc(system.id, crc)
                                        else -> datTitle(session, entry, system.id)
                                    }
                                    indexed += IndexedGame(entry, system.id, internal,
                                        last?.favorite ?: false, last?.lastPlayed ?: 0L,
                                        last?.customTitle, verified, PROBE_VERSION, version, crc)
                                }
                                val count = processed.incrementAndGet()
                                if (count % 16 == 0 || count == discovered.get())
                                    onProgress(count, discovered.get())
                            }
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
            val writable = db.writableDatabase
            writable.beginTransaction()
            try {
                val generation = System.nanoTime()
                for (game in selected) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
                    }
                    writable.insertWithOnConflict("game_entries", null, values,
                        SQLiteDatabase.CONFLICT_REPLACE)
                }
                writable.delete("game_entries", "folder_id=? AND seen!=?",
                    arrayOf(folder.id, generation.toString()))
                writable.setTransactionSuccessful()
            } finally {
                writable.endTransaction()
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

    private fun existing(folderId: String): Map<String, LibraryGame> =
        games().filter { it.folderId == folderId }.associateBy { it.fileId }

    private fun classify(entry: FileEntry, session: GameScanFileSession, skipArchives: Boolean = false): com.ambient.tv.games.GameSystem? {
        val head = if (GameSystems.extension(entry.name) == "iso") {
            runCatching { session.open(entry.path).use { media ->
                ByteArray(0x20).also { if (media.read(0, it, it.size) != it.size) return@use null }
            } }.getOrNull()
        } else null
        val ext = GameSystems.extension(entry.name)
        if (!skipArchives && ext in GameSystems.archiveExtensions) {
            val fromArchive = runCatching {
                session.open(entry.path).use { media ->
                    GameArchives.system(entry.name, media)
                }
            }.getOrNull()
            if (fromArchive != null) return fromArchive
        }
        return GameSystems.forSourceFile(entry.name, entry.path, head)
    }

    private companion object {
        const val PROBE_VERSION = 1
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
