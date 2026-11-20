package com.ambient.tv.games

import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** What a folder import took over. */
data class GameDataImportResult(
    /** Known firmware files, and how many of them match the expected dump. */
    val bios: Int,
    val verifiedBios: Int,
    /** Cores installed from the folder. */
    val cores: List<GameCore>,
    /** Further files copied into the system folder (core data such as fonts or databases). */
    val extras: Int,
    /** Files left out: games, films, music, oversized files, unreadable cores. */
    val skipped: Int,
)

/** A file in the games' system folder that is not a known firmware file. */
data class GameSystemFile(val path: String, val size: Long)

/**
 * Takes game data over from the user's storage into app storage, where it stays independent of the
 * source: libretro core files, firmware (BIOS) and the other files cores look for in their system
 * folder. A whole folder (a RetroArch "system" folder, say) is copied with its subfolders.
 */
@Singleton
class GameDataImporter @Inject constructor(
    private val storage: GameStorage,
    private val store: FileStore,
    private val access: FileAccess,
    private val cores: GameCoreManager,
    private val bios: GameBiosManager,
) {
    /** Installs the core file [fileId] ("<core>_libretro_android.so", also zipped). */
    suspend fun importCore(fileId: String): GameCore = withContext(Dispatchers.IO) {
        val (source, path) = resolve(fileId)
        val name = path.substringAfterLast('/').substringAfterLast(':')
        val core = GameSystems.coreForFile(name) ?: throw FileNotFoundException(name)
        installCore(source, path, name, core)
        core
    }

    /**
     * Copies the folder [folderId] with its subfolders: cores are installed, known firmware files land
     * in the system folder under their usual name, everything else keeps its place below it.
     */
    suspend fun importFolder(folderId: String, onProgress: (copied: Int) -> Unit = {}): GameDataImportResult = withContext(Dispatchers.IO) {
        val (source, root) = resolve(folderId)
        var biosCount = 0
        var verified = 0
        val installed = mutableListOf<GameCore>()
        var extras = 0
        var skipped = 0
        var files = 0
        var bytes = 0L

        suspend fun walk(path: String, relative: String, depth: Int) {
            val entries = access.list(source, path, MAX_FILES) { true }.entries
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                val name = entry.name
                val childRelative = if (relative.isEmpty()) safeName(name) else "$relative/${safeName(name)}"
                when {
                    entry.directory -> if (depth < MAX_DEPTH && !name.startsWith(".")) walk(entry.path, childRelative, depth + 1)
                    files >= MAX_FILES || bytes + entry.size > MAX_BYTES -> skipped++
                    GameSystems.coreForFile(name) != null -> {
                        val core = GameSystems.coreForFile(name)!!
                        if (runCatching { installCore(source, entry.path, name, core) }.isSuccess) installed += core else skipped++
                    }
                    GameSystems.isBios(name) -> {
                        val matches = GameSystems.bios(name)
                        val target = File(storage.systemDirectory, matches.first().second.file)
                        copy(source, entry.path, target)
                        biosCount++
                        val digest = GameBiosManager.md5(target)
                        if (matches.any { it.second.md5.equals(digest, ignoreCase = true) }) verified++
                    }
                    !wanted(entry) -> skipped++
                    else -> {
                        val target = File(storage.systemDirectory, childRelative)
                        if (!target.isFile || target.length() != entry.size) copy(source, entry.path, target)
                        extras++
                    }
                }
                if (!entry.directory) {
                    files++
                    bytes += entry.size
                    onProgress(files)
                }
            }
        }

        walk(root, "", 0)
        GameDataImportResult(biosCount, verified, installed.distinctBy { it.id }, extras, skipped)
    }

    /** Files in the system folder that are not known firmware files, by path below it. */
    fun systemFiles(): List<GameSystemFile> {
        val root = storage.systemDirectory
        return root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".partial") }
            .map { GameSystemFile(it.relativeTo(root).path, it.length()) }
            .filter { '/' in it.path || !GameSystems.isBios(it.path) }
            .sortedBy { it.path.lowercase() }
            .toList()
    }

    fun removeSystemFile(file: GameSystemFile) {
        val root = storage.systemDirectory.canonicalFile
        val target = File(root, file.path).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) return
        target.delete()
        // Leave no empty folders behind.
        var parent = target.parentFile
        while (parent != null && parent != root && parent.list()?.isEmpty() == true) {
            parent.delete()
            parent = parent.parentFile
        }
    }

    private suspend fun installCore(source: FileSource, path: String, name: String, core: GameCore) {
        access.prepare(source)
        cores.install(core, mediaStream(source, path), zipped = name.lowercase().endsWith(".zip"), origin = name)
    }

    private suspend fun copy(source: FileSource, path: String, target: File) {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".partial")
        mediaStream(source, path).use { input -> partial.outputStream().use { input.copyTo(it, 256 * 1024) } }
        target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("Cannot store ${target.name}")
        }
    }

    /** A plain stream over a file of any source (device, USB, NAS). */
    private suspend fun mediaStream(source: FileSource, path: String): InputStream {
        access.prepare(source)
        val media = access.open(source, path)
        return object : InputStream() {
            private var position = 0L
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val buffer = if (off == 0) b else ByteArray(len)
                val count = media.read(position, buffer, len)
                if (count <= 0) return -1
                if (off != 0) System.arraycopy(buffer, 0, b, off, count)
                position += count
                return count
            }
            override fun close() = media.close()
        }
    }

    private fun resolve(fileId: String): Pair<FileSource, String> {
        val (sourceId, path) = FileKeys.parse(fileId)
        val source = store.sources().firstOrNull { it.id == sourceId } ?: throw FileNotFoundException(path)
        return source to path
    }

    companion object {
        const val MAX_DEPTH = 4
        const val MAX_FILES = 5000
        const val MAX_BYTES = 4L * 1024 * 1024 * 1024

        /** Games, films and music stay where they are; the system folder is for core data. */
        internal fun wanted(entry: FileEntry): Boolean = !entry.game && !entry.playable && !entry.name.startsWith(".")

        internal fun safeName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "_" }
    }
}
