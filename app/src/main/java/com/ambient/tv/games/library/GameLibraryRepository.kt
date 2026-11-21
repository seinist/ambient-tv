package com.ambient.tv.games.library

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.FilePaths
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.data.files.WakeOnLan
import com.ambient.tv.data.local.RegularPrefs
import com.ambient.tv.data.local.AppPreferences
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import com.ambient.tv.games.GameSystems
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A reference to an existing Files source, not a new NAS connection or a separate WOL profile. */
@Serializable
data class GameLibraryFolder(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val path: String,
    /** "auto" infers the console from a known signature, suffix or unambiguous parent. */
    val systemId: String = "auto",
    val recursive: Boolean = true,
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
)

private class GameIndexDb(context: Context) : SQLiteOpenHelper(context, "game_library.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE game_entries (
            folder_id TEXT NOT NULL, file_id TEXT NOT NULL, source_id TEXT NOT NULL,
            path TEXT NOT NULL, filename TEXT NOT NULL, system_id TEXT NOT NULL,
            internal_title TEXT, file_size INTEGER NOT NULL, modified INTEGER NOT NULL,
            favorite INTEGER NOT NULL DEFAULT 0, last_played INTEGER NOT NULL DEFAULT 0,
            seen INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(folder_id, file_id))""")
        db.execSQL("CREATE INDEX game_entries_system ON game_entries(system_id)")
        db.execSQL("CREATE INDEX game_entries_source ON game_entries(source_id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
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
    private val json = Json { ignoreUnknownKeys = true }
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

    fun removeFolder(id: String) {
        preferences.gameLibraryFolders = folders().filterNot { it.id == id }
        db.writableDatabase.delete("game_entries", "folder_id=?", arrayOf(id))
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
        require(style in setOf("grid", "list"))
        prefs.edit().putString("game_library_view_style", style).apply()
        changed()
    }

    /** Disk cache is loaded even if the NAS is sleeping; opening the library never wakes it. */
    fun games(): List<LibraryGame> {
        val allowed = folders().associateBy { it.id }
        val style = titleStyle()
        val result = mutableListOf<LibraryGame>()
        db.readableDatabase.rawQuery(
            "SELECT folder_id,file_id,source_id,path,filename,system_id,internal_title,file_size,modified,favorite,last_played FROM game_entries",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(0) !in allowed) continue
                val filename = cursor.getString(4)
                val title = if (style != "filename") cursor.getString(6)?.takeIf { it.isNotBlank() } else null
                result += LibraryGame(
                    fileId = cursor.getString(1), folderId = cursor.getString(0),
                    sourceId = cursor.getString(2), path = cursor.getString(3),
                    filename = filename, systemId = cursor.getString(5),
                    title = title ?: displayFileName(filename),
                    internalTitle = cursor.getString(6),
                    size = cursor.getLong(7), modified = cursor.getLong(8),
                    favorite = cursor.getInt(9) == 1, lastPlayed = cursor.getLong(10),
                )
            }
        }
        // One source may be referenced by several overlapping folder assignments.
        return result.distinctBy { it.fileId }
    }

    private fun displayFileName(filename: String): String =
        filename.substringBeforeLast('.', filename)
            .replace('_', ' ').replace(Regex("\\s+"), " ").trim()

    fun setFavorite(fileId: String, value: Boolean) {
        db.writableDatabase.execSQL("UPDATE game_entries SET favorite=? WHERE file_id=?",
            arrayOf(if (value) 1 else 0, fileId))
        changed()
    }

    fun recordPlayed(fileId: String) {
        db.writableDatabase.execSQL("UPDATE game_entries SET last_played=? WHERE file_id=?",
            arrayOf(System.currentTimeMillis(), fileId))
        changed()
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
    suspend fun scan(folderId: String): Int = scanLock.withLock {
        withContext(Dispatchers.IO) {
            val folder = folders().firstOrNull { it.id == folderId } ?: return@withContext 0
            val source = files.sources().firstOrNull { it.id == folder.sourceId }
                ?: throw IOException("Source was removed")
            val previous = existing(folder.id)
            val selected = ArrayList<IndexedGame>()
            val pending = ArrayDeque<Pair<String, Int>>()
            val visited = hashSetOf<String>()
            pending.add(folder.path to 0)
            while (pending.isNotEmpty()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val (path, depth) = pending.removeFirst()
                if (!visited.add(path)) continue
                require(visited.size <= 50000 && depth <= 40) { "Game folder scan limit reached" }
                val listing = access.list(source, path, 20000) { it.game }
                if (listing.more) throw IOException("Folder has more than 20000 entries; index was not changed")
                for (entry in listing.entries) {
                    if (entry.directory) {
                        if (folder.recursive) pending.add(entry.path to depth + 1)
                        continue
                    }
                    if (!entry.game) continue
                    val previousGame = previous[entry.id]
                    val autoSystem = classify(entry, source)
                    val system = if (folder.systemId == "auto") autoSystem
                        else GameSystems.byId(folder.systemId)?.takeIf {
                            val ext = GameSystems.extension(entry.name)
                            ext in it.extensions || ext in GameSystems.archiveExtensions ||
                                ext in GameSystems.ambiguousExtensions
                        } ?: autoSystem
                    if (system == null) continue
                    val internalTitle = if (previousGame != null && previousGame.size == entry.size &&
                        previousGame.modified == entry.modified && entry.modified != 0L) previousGame.internalTitle
                    else extractTitle(entry, source, system.id)
                    selected += IndexedGame(entry, system.id, internalTitle,
                        previousGame?.favorite ?: false, previousGame?.lastPlayed ?: 0L)
                    require(selected.size <= 200000) { "Game library item limit reached" }
                }
            }
            val writable = db.writableDatabase
            writable.beginTransaction()
            try {
                val generation = System.currentTimeMillis()
                for (game in selected) {
                    val e = game.entry
                    val values = android.content.ContentValues().apply {
                        put("folder_id", folder.id); put("file_id", e.id); put("source_id", e.sourceId)
                        put("path", e.path); put("filename", e.name); put("system_id", game.systemId)
                        put("internal_title", game.internalTitle); put("file_size", e.size)
                        put("modified", e.modified); put("favorite", if (game.favorite) 1 else 0)
                        put("last_played", game.lastPlayed); put("seen", generation)
                    }
                    writable.insertWithOnConflict("game_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                writable.delete("game_entries", "folder_id=? AND seen!=?",
                    arrayOf(folder.id, generation.toString()))
                writable.setTransactionSuccessful()
            } finally {
                writable.endTransaction()
            }
            changed()
            selected.size
        }
    }

    private data class IndexedGame(val entry: FileEntry, val systemId: String,
        val internalTitle: String?, val favorite: Boolean, val lastPlayed: Long)

    private fun existing(folderId: String): Map<String, LibraryGame> =
        games().filter { it.folderId == folderId }.associateBy { it.fileId }

    private fun classify(entry: FileEntry, source: FileSource): com.ambient.tv.games.GameSystem? {
        val head = if (GameSystems.extension(entry.name) == "iso") {
            runCatching { access.open(source, entry.path).use { media ->
                ByteArray(0x20).also { if (media.read(0, it, it.size) != it.size) return@use null }
            } }.getOrNull()
        } else null
        return GameSystems.forSourceFile(entry.name, entry.path, head)
    }

    private fun extractTitle(entry: FileEntry, source: FileSource, system: String): String? {
        if (entry.size <= 0) return null
        return runCatching {
            access.open(source, entry.path).use { media ->
                GameRomTitles.extract(system, entry.name) { offset, count ->
                    if (offset < 0L || count <= 0 || count > 64 * 1024 ||
                        offset + count > media.size) null
                    else ByteArray(count).also {
                        if (media.read(offset, it, count) != count) return@extract null
                    }
                }
            }
        }.getOrNull()
    }

}
