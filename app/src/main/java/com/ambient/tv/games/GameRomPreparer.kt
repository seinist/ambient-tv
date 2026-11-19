package com.ambient.tv.games

import android.content.Context
import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.FileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A game ready for a core: a local file, the system that plays it and a stable key for its saves. */
data class PreparedGame(
    val fileId: String,
    val title: String,
    val file: File,
    val system: GameSystem,
    val saveKey: String,
    val sourceName: String,
    /** The original file, for the browser's recently played list. */
    val entry: FileEntry,
)

class UnknownGameException(val name: String) : IOException("No system for $name")

/**
 * Copies a game from any file source (device storage, USB, NAS) into the app cache, because cores
 * read plain files. Archives are unpacked; CUE sheets and M3U playlists bring the files they name.
 */
@Singleton
class GameRomPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: FileStore,
    private val access: FileAccess,
) {
    private val cache: File get() = File(context.cacheDir, "games/roms")

    suspend fun prepare(fileId: String, onProgress: (Float?) -> Unit): PreparedGame = withContext(Dispatchers.IO) {
        val (sourceId, path) = FileKeys.parse(fileId)
        val source = store.sources().firstOrNull { it.id == sourceId } ?: throw FileNotFoundException(path)
        access.prepare(source)
        val entry = access.stat(source, path)
        val key = sha1(fileId)
        val folder = File(cache, key)
        val marker = File(folder, ".complete")
        val stamp = "${entry.size}:${entry.modified}"
        val main = File(folder, safeName(entry.name))
        if (!(marker.isFile && marker.readText() == stamp && main.isFile)) {
            folder.deleteRecursively()
            folder.mkdirs()
            copy(source, path, entry.size, main, onProgress)
            when (GameSystems.extension(entry.name)) {
                "cue" -> copyReferenced(source, path, cueFiles(main.readText()), folder)
                "m3u" -> {
                    val discs = m3uFiles(main.readText())
                    copyReferenced(source, path, discs, folder)
                    discs.filter { GameSystems.extension(it) == "cue" }.forEach { disc ->
                        File(folder, safeName(disc)).takeIf(File::isFile)?.let { copyReferenced(source, path, cueFiles(it.readText()), folder) }
                    }
                }
            }
            marker.writeText(stamp)
        }
        folder.setLastModified(System.currentTimeMillis())
        trim(keep = folder)
        val playable = if (GameSystems.extension(main.name) in GameSystems.archiveExtensions) unpack(main, folder) else main
        val system = GameSystems.forFile(playable.name) ?: throw UnknownGameException(entry.name)
        PreparedGame(
            fileId = fileId,
            title = entry.name.substringBeforeLast('.'),
            file = playable,
            system = system,
            saveKey = key,
            sourceName = source.name,
            entry = entry,
        )
    }

    private suspend fun copy(source: FileSource, path: String, size: Long, target: File, onProgress: (Float?) -> Unit) {
        access.open(source, path).use { media ->
            val total = media.size.takeIf { it > 0 } ?: size
            target.outputStream().buffered(256 * 1024).use { out ->
                val buffer = ByteArray(1024 * 1024)
                var position = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = media.read(position, buffer, buffer.size)
                    if (count <= 0) break
                    out.write(buffer, 0, count)
                    position += count
                    onProgress(if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else null)
                }
            }
        }
    }

    /** Copies files a CUE/M3U names from the same folder; missing ones are left to the core to report. */
    private suspend fun copyReferenced(source: FileSource, path: String, names: List<String>, folder: File) {
        val parent = path.substringBeforeLast('/', "")
        names.distinct().forEach { name ->
            if (name.contains('/') || name.contains('\\') || name == "." || name == "..") return@forEach
            val target = File(folder, safeName(name))
            if (target.isFile) return@forEach
            val childPath = if (parent.isEmpty()) name else "$parent/$name"
            runCatching {
                val child: FileEntry = access.stat(source, childPath)
                copy(source, childPath, child.size, target) {}
            }
        }
    }

    /**
     * Unpacks [archive] completely into "content" next to it (once per archive version; a disc needs
     * its tracks too) and returns the file to start: playlist, cue sheet, disc image or cartridge.
     */
    private fun unpack(archive: File, folder: File): File {
        val content = File(folder, "content")
        val done = File(folder, ".unpacked")
        val files = if (done.isFile && content.isDirectory) {
            content.listFiles()?.toList().orEmpty()
        } else {
            content.deleteRecursively()
            GameArchives.extract(archive, content).also { done.writeText(archive.length().toString()) }
        }
        return GameArchives.choose(files) { it.name } ?: throw UnknownGameException(archive.name)
    }

    /** Bytes the copied games take up. */
    fun cacheBytes(): Long = cache.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Removes all copied games; saves and states stay. */
    fun clearCache() { cache.deleteRecursively() }

        /** Keeps the cache below [CACHE_LIMIT_BYTES], oldest games first. */
    private fun trim(keep: File) {
        val folders = cache.listFiles()?.filter { it.isDirectory } ?: return
        var total = folders.sumOf { folder -> folder.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
        folders.filter { it != keep }.sortedBy { it.lastModified() }.forEach { folder ->
            if (total <= CACHE_LIMIT_BYTES) return
            val size = folder.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            folder.deleteRecursively()
            total -= size
        }
    }

    companion object {
        const val CACHE_LIMIT_BYTES = 4L * 1024 * 1024 * 1024

        fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        internal fun safeName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
                .ifBlank { "game" }

        /** File names a CUE sheet refers to (`FILE "name.bin" BINARY`). */
        internal fun cueFiles(cue: String): List<String> = cue.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("FILE", ignoreCase = true)) return@mapNotNull null
            val rest = trimmed.substring(4).trim()
            if (rest.startsWith("\"")) rest.substring(1).substringBefore('"') else rest.substringBeforeLast(' ')
        }.filter { it.isNotBlank() }.toList()

        /** Disc files an M3U playlist names (comments and blank lines skipped). */
        internal fun m3uFiles(m3u: String): List<String> =
            m3u.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }
}
