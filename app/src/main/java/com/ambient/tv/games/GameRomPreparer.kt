package com.ambient.tv.games

import android.content.Context
import android.os.StatFs
import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.FilePaths
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
    /** Direct seekable source; null when the core needs a staged local file. */
    val directContent: GameDirectContent? = null,
    /** A game from a RomM server: firmware, saves and states are the server's (see RommGameData). */
    val remote: com.ambient.tv.romm.RommGameRef? = null,
    /** The selected experimental build profile; game data is shared across profiles. */
    val experimentalProfile: String? = null,
) {
    val direct: Boolean get() = directContent != null
    fun close() { directContent?.close() }
}

class UnknownGameException(val name: String) : IOException("No system for $name")
class GameDirectUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)


/**
 * Copies a game from any file source (device storage, USB, NAS) into the app cache, because cores
 * read plain files. Archives are unpacked; CUE sheets and M3U playlists bring the files they name.
 */
@Singleton
class GameRomPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: FileStore,
    private val access: FileAccess,
    private val directAccess: GameDirectAccess,
    private val remoteGames: javax.inject.Provider<com.ambient.tv.romm.RommGameData>,
) {
    private val cache: File get() = File(context.cacheDir, "games/roms")

    suspend fun prepare(fileId: String, onProgress: (Float?) -> Unit, forceCache: Boolean = false, systemOverride: String? = null): PreparedGame = withContext(Dispatchers.IO) {
        // A RomM game is downloaded from its server into the same cache (see materialize).
        if (com.ambient.tv.romm.RommKeys.owns(fileId)) return@withContext remoteGames.get().prepare(fileId, onProgress, this@GameRomPreparer)
        val (sourceId, path) = FileKeys.parse(fileId)
        val source = store.sources().firstOrNull { it.id == sourceId } ?: throw FileNotFoundException(path)
        access.prepare(source)
        val entry = access.stat(source, path)
        val key = sha1(fileId)
        // Find the system from the original source; even a multi-GB Wii ISO needs only its header.
        val isoHeader = if (GameSystems.extension(entry.name) == "iso") runCatching {
            access.open(source, path).use { media ->
                ByteArray(0x20).takeIf { media.read(0L, it, it.size) == it.size }
            }
        }.getOrNull() else null
        val detectedSystem = GameSystems.forSourceFile(entry.name, entry.path, isoHeader)
        val ext = GameSystems.extension(entry.name)
        // Use the same compatibility filter as the library. An explicit assignment never
        // launches a foreign console as a fallback. Archives are checked after extraction.
        val assignedId = systemOverride?.takeUnless { it.isBlank() || it == "auto" }
        val assigned = assignedId?.let { GameSystems.byId(it) ?: throw UnknownGameException(entry.name) }
        val sourceSystem = if (ext in GameSystems.archiveExtensions) assigned else {
            GameSystems.forAssignedFile(entry.name, assignedId, detectedSystem,
                systemFromHeader = GameSystems.forDiscHeader(entry.name, isoHeader) != null)
                ?: if (assigned != null) throw UnknownGameException(entry.name) else null
        }
        val chosenCore = sourceSystem?.let { GameSettings.state.gameCore(it, key) }
        // Only cores declaring need_fullpath qualify. Uncompressed single-file discs and GDI
        // descriptors (including each track through named VFS descriptors) need no cached copy.
        if (!forceCache && sourceSystem != null && chosenCore?.directFile == true &&
            (ext in DIRECT_EXTENSIONS || ext == "gdi") && entry.size > 0L) {
            val virtualPath = "/ambient-games/$key/${safeName(entry.name)}"
            val parts = mutableListOf<GameDirectContent>()
            val mounted = try {
                val names = if (ext == "gdi") {
                    val tracks = gdiFiles(access.readText(source, path, 256 * 1024))
                    if (tracks.isEmpty() || tracks.size > MAX_DIRECT_GDI_TRACKS ||
                        tracks.size != tracks.distinct().size) {
                        throw IOException("Unsupported or oversized multi-track GDI descriptor")
                    }
                    listOf(entry.name) + tracks
                } else listOf(entry.name)
                val parent = path.substringBeforeLast('/', "")
                names.forEach { name ->
                    // Preserve exactly the names the core will request; no path traversal or
                    // silent filename rewrites inside a game's descriptor.
                    if (name != safeName(name) || name == "." || name == ".." ||
                        '/' in name || '\\' in name) {
                        throw IOException("GDI track path is not a simple filename: $name")
                    }
                    val child = if (name == entry.name) path else FilePaths.child(parent, name)
                    parts += directAccess.open(source, child, "/ambient-games/$key/$name")
                }
                if (parts.size == 1) parts.single() else GameDirectContent(
                    parts.flatMap { it.virtualFiles }, parts.sumOf { it.bytes },
                )
            } catch (error: Exception) {
                parts.forEach(GameDirectContent::close)
                throw GameDirectUnavailableException("Direct ROM access unavailable; choose a local cache fallback", error)
            }
            return@withContext PreparedGame(
                fileId = fileId,
                title = entry.name.substringBeforeLast('.'),
                file = File(virtualPath), system = sourceSystem, saveKey = key,
                sourceName = source.name, entry = entry, directContent = mounted,
            )
        }

        val folder = File(cache, key)
        val marker = File(folder, ".complete")
        val stamp = "${entry.size}:${entry.modified}"
        val main = File(folder, safeName(entry.name))
        val archiveCached = marker.isFile && marker.readText() == stamp &&
            main.isFile && cachedCompanionsComplete(main)
        if (ext in setOf("zip", "7z", "gz")) {
            val singleMarker = File(folder, ".single.complete")
            val saved = runCatching {
                singleMarker.readLines().takeIf { it.size == 3 && it[0] == stamp }
                    ?.let { lines ->
                        val relative = safeRelativePath(lines[1]) ?: return@let null
                        File(folder, relative).takeIf { it.isFile && it.length().toString() == lines[2] &&
                            GameSystems.forPreparedFile(it, entry.path, assignedId) != null }
                    }
            }.getOrNull()
            // Do not re-copy already cached compressed archives or materialized single ROMs.
            val single = if (saved != null) saved else if (archiveCached) null else {
                folder.deleteRecursively()
                folder.mkdirs()
                val free = StatFs(context.cacheDir.absolutePath).availableBytes - CACHE_RESERVE_BYTES
                val budget = minOf(GameArchives.MAX_BYTES, CACHE_LIMIT_BYTES, free)
                if (budget <= 0) throw IOException("Insufficient cache space for ROM extraction")
                val staged = access.open(source, path).use { media ->
                    GameArchives.stageSingle(entry.name, media, File(folder, "content"),
                        budget, systemId = assignedId, onProgress = onProgress)
                }
                if (staged != null) {
                    singleMarker.writeText(
                        "$stamp\n${staged.relativeTo(folder).invariantSeparatorsPath}\n${staged.length()}"
                    )
                }
                staged
            }
            if (single != null) {
                folder.setLastModified(System.currentTimeMillis())
                trim(keep = folder)
                val chosen = GameSystems.forPreparedFile(single, entry.path, assignedId)
                    ?: throw UnknownGameException(entry.name)
                return@withContext PreparedGame(fileId = fileId,
                    title = entry.name.substringBeforeLast('.'), file = single, system = chosen,
                    saveKey = key, sourceName = source.name, entry = entry)
            }
        }
        if (!archiveCached) {
            folder.deleteRecursively()
            folder.mkdirs()
            // A manual fallback is never allowed to exhaust the device or silently evict saves.
            if (entry.size > CACHE_LIMIT_BYTES) {
                throw IOException("ROM exceeds the 4 GiB cache limit; direct access is required")
            }
            val free = StatFs(context.cacheDir.absolutePath).availableBytes
            val copyBudget = minOf(CACHE_LIMIT_BYTES, free - CACHE_RESERVE_BYTES)
            if (copyBudget <= 0 || entry.size > copyBudget) {
                throw IOException("Insufficient free space for a local game copy")
            }
            copy(source, path, entry.size, main, onProgress, copyBudget)
            when (GameSystems.extension(entry.name)) {
                "cue" -> copyReferenced(source, path, cueFiles(main.readText()), folder)
                "gdi" -> copyReferenced(source, path, gdiFiles(main.readText()), folder)
                "m3u" -> {
                    val discs = m3uFiles(main.readText())
                    copyReferenced(source, path, discs, folder)
                    discs.filter { GameSystems.extension(it) == "cue" }.forEach { disc ->
                        val relative = safeRelativePath(disc)
                            ?: throw IOException("Unsafe disc playlist entry: $disc")
                        val localCue = File(folder, relative)
                        if (!localCue.isFile) throw IOException("Missing playlist entry: $disc")
                        val descriptorPath = FilePaths.relative(listOf(path.substringBeforeLast('/', ""), relative).filter(String::isNotEmpty).joinToString("/"))
                        copyReferenced(
                            source, descriptorPath, cueFiles(localCue.readText()), folder,
                            relative.substringBeforeLast('/', ""),
                        )
                    }
                }
            }
            marker.writeText(stamp)
        }
        folder.setLastModified(System.currentTimeMillis())
        trim(keep = folder)
        val playable = if (GameSystems.extension(main.name) in GameSystems.archiveExtensions) unpack(main, folder, assignedId) else main
        trim(keep = folder)
        val system = GameSystems.forPreparedFile(playable, entry.path, assignedId)
            ?: throw UnknownGameException(entry.name)
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

    private suspend fun copy(source: FileSource, path: String, size: Long, target: File,
                             onProgress: (Float?) -> Unit, maxBytes: Long = CACHE_LIMIT_BYTES) {
        try {
            access.open(source, path).use { media ->
                val total = media.size.takeIf { it > 0 } ?: size
                target.outputStream().buffered(256 * 1024).use { out ->
                    val buffer = ByteArray(1024 * 1024)
                    var position = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = media.read(position, buffer, buffer.size)
                        if (count <= 0) break
                        if (count.toLong() > maxBytes - position) {
                            throw IOException("ROM copy exceeds available storage or cache budget")
                        }
                        out.write(buffer, 0, count)
                        position += count
                        onProgress(if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else null)
                    }
                    if (total > 0 && position < total) throw IOException("Incomplete ROM copy: $path")
                }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    /**
     * Copy every named disc sidecar and keep relative subdirectories. Missing, unsafe or
     * colliding files are errors, not silent partially playable CUE/GDI/M3U games.
     */
    private suspend fun copyReferenced(
        source: FileSource, path: String, names: List<String>, folder: File,
        relativeBase: String = "",
    ) {
        val parent = path.substringBeforeLast('/', "")
        val seen = HashMap<String, String>()
        names.forEach { name ->
            val relative = safeRelativePath(name)
                ?: throw IOException("Unsafe disc companion path: $name")
            val targetRelative = if (relativeBase.isEmpty()) relative else "$relativeBase/$relative"
            val key = targetRelative.lowercase(java.util.Locale.ROOT)
            val previous = seen.putIfAbsent(key, targetRelative)
            if (previous != null) {
                if (previous != targetRelative) throw IOException("Colliding disc companion: $name")
                return@forEach // CUE files may repeat the same FILE for several tracks.
            }
            val target = File(folder, targetRelative)
            val childPath = FilePaths.relative(listOf(parent, relative).filter(String::isNotEmpty).joinToString("/"))
            val child: FileEntry = access.stat(source, childPath)
            if (target.isFile && target.length() == child.size && child.size > 0) return@forEach
            target.parentFile?.mkdirs()
            val used = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val free = StatFs(context.cacheDir.absolutePath).availableBytes - CACHE_RESERVE_BYTES
            val budget = minOf(CACHE_LIMIT_BYTES - used, free)
            if (budget <= 0 || child.size > budget) throw IOException("Insufficient cache for disc track $name")
            copy(source, childPath, child.size, target, {}, budget)
        }
    }

    /**
     * Unpacks [archive] completely into "content" next to it (once per archive version; a disc needs
     * its tracks too) and returns the file to start: playlist, cue sheet, disc image or cartridge.
     */
    private fun unpack(archive: File, folder: File, systemId: String?): File {
        val content = File(folder, "content")
        val done = File(folder, ".unpacked")
        val recorded = runCatching { done.readLines() }.getOrNull()
        val files = if (content.isDirectory && recorded != null && recorded.firstOrNull() == archive.length().toString() &&
            recorded.size > 1 && recorded.drop(1).all { line ->
                val parts = line.split('\t', limit = 2)
                parts.size == 2 && safeRelativePath(parts[0]) == parts[0] &&
                    File(content, parts[0]).let { it.isFile && it.length().toString() == parts[1] }
            }
        ) {
            recorded.drop(1).map { line -> File(content, line.substringBefore('\t')) }
        } else {
            content.deleteRecursively()
            done.delete()
            val used = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val free = StatFs(context.cacheDir.absolutePath).availableBytes - CACHE_RESERVE_BYTES
            val budget = minOf(GameArchives.MAX_BYTES, CACHE_LIMIT_BYTES - used, free)
            if (budget <= 0) throw IOException("Insufficient cache space for extracted game")
            GameArchives.extract(archive, content, budget).also { extracted ->
                done.writeText(
                    (listOf(archive.length().toString()) +
                        extracted.map { file -> "${file.relativeTo(content).invariantSeparatorsPath}\t${file.length()}" }
                    ).joinToString("\n")
                )
            }
        }
        return GameArchives.choose(files, systemId) { it.relativeTo(content).invariantSeparatorsPath }
            ?: throw UnknownGameException(archive.name)
    }

    /**
     * A game fetched by [fetch] (a RomM download) into the shared 4 GiB cache: one folder per game,
     * kept while [stamp] still describes the server's file, archives unpacked like copied games.
     * Returns the file to start.
     */
    internal suspend fun materialize(
        fileId: String,
        name: String,
        stamp: String,
        systemId: String?,
        expectedBytes: Long,
        onProgress: (Float?) -> Unit,
        /** The source cannot be asked right now: a complete earlier download is used as it is. */
        acceptCached: Boolean = false,
        fetch: suspend (target: File, maxBytes: Long, onProgress: (Float?) -> Unit) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val folder = File(cache, sha1(fileId))
        val marker = File(folder, ".complete")
        // The marker records the file's name too: offline, the server's details may name it differently.
        val recorded = runCatching { marker.readLines() }.getOrNull()
            ?.takeIf { it.size == 2 && (it[0] == stamp || acceptCached) }
            ?.let { lines -> safeRelativePath(lines[1])?.takeIf { '/' !in it }?.let { File(folder, it) } }
            ?.takeIf { it.isFile && it.length() > 0 }
        val main = recorded ?: File(folder, safeName(name))
        if (recorded == null) {
            folder.deleteRecursively()
            folder.mkdirs()
            if (expectedBytes > CACHE_LIMIT_BYTES) throw IOException("Game exceeds the 4 GiB cache limit")
            val free = StatFs(context.cacheDir.absolutePath).availableBytes - CACHE_RESERVE_BYTES
            val budget = minOf(CACHE_LIMIT_BYTES, free)
            if (budget <= 0 || expectedBytes > budget) throw IOException("Insufficient free space for the game")
            val partial = File(folder, main.name + ".partial")
            try {
                fetch(partial, budget, onProgress)
                if (!partial.renameTo(main)) throw IOException("Cannot store ${main.name}")
            } finally { partial.delete() }
            marker.writeText("$stamp\n${main.name}")
        }
        folder.setLastModified(System.currentTimeMillis())
        trim(keep = folder)
        val playable = if (GameSystems.extension(main.name) in GameSystems.archiveExtensions) unpack(main, folder, systemId) else main
        trim(keep = folder)
        playable
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
        const val CACHE_RESERVE_BYTES = 128L * 1024 * 1024
        /** A GDI disc typically has 2-3 tracks. Avoid dozens of FDs and proxy threads. */
        const val MAX_DIRECT_GDI_TRACKS = 8
        /** Archives and multi-track CUE/GDI/M3U still require local materialization. */
        val DIRECT_EXTENSIONS = setOf("iso", "rvz", "wbfs", "ciso", "chd", "pbp",
            "cso", "zso", "wua", "wud", "wux", "cdi", "gcm", "tgc")

        fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Verify all referenced local files before trusting the completed cache marker. */
        internal fun cachedCompanionsComplete(main: File): Boolean {
            val visited = HashSet<String>()
            val active = HashSet<String>()
            fun check(descriptor: File): Boolean {
                if (!descriptor.isFile) return false
                val path = descriptor.canonicalPath
                if (path in active || visited.size > 128) return false
                if (path in visited) return true
                active.add(path)
                val names = when (GameSystems.extension(descriptor.name)) {
                    "cue" -> cueFiles(descriptor.readText())
                    "gdi" -> gdiFiles(descriptor.readText())
                    "m3u" -> m3uFiles(descriptor.readText())
                    else -> return descriptor.length() > 0
                }
                if (names.isEmpty()) return false
                val complete = names.all { name ->
                    val relative = safeRelativePath(name) ?: return@all false
                    val child = File(descriptor.parentFile, relative)
                    if (!child.isFile || child.length() <= 0) return@all false
                    when (GameSystems.extension(child.name)) {
                        "cue", "gdi", "m3u" -> check(child)
                        else -> true
                    }
                }
                active.remove(path)
                if (complete) visited.add(path)
                return complete
            }
            return runCatching { check(main) }.getOrDefault(false)
        }

        /** Reject traversal and absolute paths, but preserve safe nested disc tracks. */
        internal fun safeRelativePath(name: String): String? {
            val normalized = name.replace('\\', '/')
            if (normalized.isBlank() || normalized.startsWith('/') || ':' in normalized) return null
            val parts = normalized.split('/')
            if (parts.any { it.isBlank() || it == "." || it == ".." ||
                    it.startsWith(".") || safeName(it) != it }) return null
            return parts.joinToString("/")
        }

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

        /** Dreamcast GDI: track number, LBA, type, sector size, filename, offset. */
        internal fun gdiFiles(gdi: String): List<String> =
            gdi.lineSequence().drop(1).mapNotNull { line ->
                Regex("""^\s*\d+\s+\d+\s+\d+\s+\d+\s+("[^"]+"|\S+)\s+\d+\s*$""")
                    .matchEntire(line.trim())?.groupValues?.get(1)?.trim('"')
            }.filter(String::isNotBlank).toList()

        /** Disc files an M3U playlist names (comments and blank lines skipped). */
        internal fun m3uFiles(m3u: String): List<String> =
            m3u.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }
}
