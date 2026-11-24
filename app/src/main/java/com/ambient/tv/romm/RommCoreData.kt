package com.ambient.tv.romm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Files cores write into a server's save folder on their own, apart from the in-game save the
 * player hands them: memory cards, a console's NAND, PSP SAVEDATA, backup RAM. They go to RomM as
 * one archive per game, stored with the game's saves; each file in it keeps its date, and on
 * another device a file is replaced only by a newer copy.
 */
internal object RommCoreData {
    /** The archive's name among the game's saves on the server. */
    const val FILE_NAME = "ambient-data.zip"
    private const val MANIFEST = ".ambient-data.json"
    const val MAX_BUNDLE_BYTES = 64L * 1024 * 1024
    private const val MAX_UNPACKED_BYTES = 256L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
    private const val MAX_FILES = 20_000

    /** Caches, logs and settings belong to the device and are rebuilt by the core. */
    private val SKIPPED_DIRECTORIES = setOf(
        "cache", "caches", "shadercache", "shaders", "logs", "log", "dump", "dumps", "screenshots",
        "screenshot", "load", "textures", "temp", "tmp", "config",
    )
    private val SKIPPED_EXTENSIONS = setOf("log", "tmp", "lock", "partial", "bak")
    /** The player's own in-game saves ([com.ambient.tv.games.GameStorage.sramFile]); they sync on their own. */
    private val PLAYER_SAVE = Regex("[0-9a-f]{40}\\.srm(\\.before-server)?")

    @Serializable
    internal data class Manifest(val version: Int = 1, val files: Map<String, Long> = emptyMap())

    data class Unpacked(
        /** Paths the archive holds. */
        val paths: Set<String>,
        /** This device has a newer copy of at least one of them. */
        val keptNewer: Boolean,
    )

    fun isBundle(name: String): Boolean =
        name.startsWith("ambient-data", ignoreCase = true) && name.endsWith(".zip", ignoreCase = true)

    /** Whether [path] (relative, "/"-separated) is core data that belongs on the server. */
    fun tracked(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || '\\' in path || path.length > 1024) return false
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return false
        if (segments.any { segment -> segment.any { it.isISOControl() } }) return false
        if (segments.size == 1 && PLAYER_SAVE.matches(segments[0])) return false
        if (segments.dropLast(1).any { it.lowercase(Locale.ROOT) in SKIPPED_DIRECTORIES }) return false
        return segments.last().substringAfterLast('.', "").lowercase(Locale.ROOT) !in SKIPPED_EXTENSIONS
    }

    fun stamp(file: File): String = "${file.length()}:${file.lastModified()}"

    /** Path → [stamp] of every file below [root] that [tracked] accepts. */
    fun scan(root: File): Map<String, String> {
        val result = HashMap<String, String>()
        fun walk(directory: File, prefix: String) {
            directory.listFiles()?.forEach { child ->
                if (result.size >= MAX_FILES) return
                if (child.name.startsWith(".") || Files.isSymbolicLink(child.toPath())) return@forEach
                val path = prefix + child.name
                if (child.isDirectory) {
                    if (child.name.lowercase(Locale.ROOT) !in SKIPPED_DIRECTORIES) walk(child, "$path/")
                } else if (child.isFile && tracked(path)) {
                    result[path] = stamp(child)
                }
            }
        }
        if (root.isDirectory) walk(root, "")
        return result
    }

    /** Paths that are new or different in [now] compared with [before]. */
    fun changed(before: Map<String, String>, now: Map<String, String>): Set<String> =
        now.filter { (path, stamp) -> before[path] != stamp }.keys

    /** One value for the current copies of [paths]: whether the archive needs another upload. */
    fun digest(root: File, paths: Collection<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        paths.sorted().forEach { path ->
            val file = File(root, path)
            if (file.isFile) digest.update("$path=${stamp(file)}\n".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    /** A zip of [paths] below [root], with their dates; null when it would be too large to send. */
    fun pack(root: File, paths: Collection<String>, json: Json): ByteArray? {
        val present = paths.filter { tracked(it) && File(root, it).isFile }.sorted()
        if (present.isEmpty()) return null
        val manifest = Manifest(files = present.associateWith { File(root, it).lastModified() })
        val out = BoundedOutput(MAX_BUNDLE_BYTES)
        try {
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(json.encodeToString(Manifest.serializer(), manifest).toByteArray())
                zip.closeEntry()
                present.forEach { path ->
                    val file = File(root, path)
                    zip.putNextEntry(ZipEntry(path).apply { time = file.lastModified() })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (tooLarge: TooLargeException) {
            return null
        }
        return out.toByteArray()
    }

    /**
     * Writes the archive's files below [root] where the archive's copy is newer than this device's;
     * a newer copy here is kept (and goes to the server with the next upload).
     */
    fun unpack(bytes: ByteArray, root: File, json: Json): Unpacked {
        val base = root.canonicalFile
        var manifest = Manifest()
        val paths = linkedSetOf<String>()
        var keptNewer = false
        var budget = MAX_UNPACKED_BYTES
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (entry.name == MANIFEST) {
                    val text = readBounded(zip, MAX_MANIFEST_BYTES).decodeToString()
                    manifest = runCatching { json.decodeFromString(Manifest.serializer(), text) }.getOrDefault(manifest)
                    continue
                }
                val path = entry.name
                if (!tracked(path)) continue
                val modified = manifest.files[path] ?: entry.time.takeIf { it > 0L } ?: continue
                val target = File(base, path)
                if (!target.canonicalPath.startsWith(base.path + File.separator)) continue
                paths += path
                if (target.isDirectory) continue
                if (target.isFile && target.lastModified() >= modified) {
                    if (target.lastModified() > modified) keptNewer = true
                    continue
                }
                target.parentFile?.mkdirs()
                val partial = File(target.parentFile, target.name + ".partial")
                try {
                    partial.outputStream().use { out -> budget -= copyBounded(zip, out, budget) }
                    partial.setLastModified(modified)
                    if (!partial.renameTo(target)) throw IOException("Cannot store $path")
                } finally {
                    partial.delete()
                }
                target.setLastModified(modified)
            }
        }
        return Unpacked(paths, keptNewer)
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        copyBounded(input, out, limit)
        return out.toByteArray()
    }

    private fun copyBounded(input: InputStream, out: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            if (total > limit) throw IOException("Core data archive is too large")
            out.write(buffer, 0, count)
        }
    }

    private class TooLargeException : IOException("Core data exceeds the upload limit")

    private class BoundedOutput(private val limit: Long) : ByteArrayOutputStream() {
        override fun write(b: Int) {
            if (size() + 1L > limit) throw TooLargeException()
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (size().toLong() + len > limit) throw TooLargeException()
            super.write(b, off, len)
        }
    }
}
