package com.ambient.tv.games

import com.ambient.tv.data.files.SeekableMedia
import com.github.junrar.Archive
import com.github.junrar.exception.UnsupportedRarV5Exception
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.zip.GZIPInputStream

class GameArchiveException(message: String) : IOException(message)

/**
 * Packed games: ZIP, 7z, RAR (version 4; RAR5 is not readable) and GZ. An archive is unpacked
 * completely (a disc is CUE/M3U plus tracks), flat into one folder, and the file to start is picked
 * from what is inside. ZIP and 7z can also be looked into remotely (central directory / header at
 * the end of the file), so the file browser can name the system without copying the archive.
 */
object GameArchives {
    val extensions = setOf("zip", "7z", "rar", "gz")

    /** Unpacked size a game archive may have. */
    const val MAX_BYTES = 8L * 1024 * 1024 * 1024

    /** Unpacks [archive] into [folder] (names flattened) and returns the unpacked files. */
    fun extract(archive: File, folder: File): List<File> {
        folder.mkdirs()
        var total = 0L
        val files = mutableListOf<File>()
        fun target(name: String, size: Long): File? {
            val base = GameRomPreparer.safeName(name)
            if (base.isBlank() || base.startsWith(".")) return null
            total += size.coerceAtLeast(0)
            if (total > MAX_BYTES) throw GameArchiveException("${archive.name}: too large")
            return File(folder, base).also(files::add)
        }
        when (GameSystems.extension(archive.name)) {
            "zip" -> ZipFile.builder().setFile(archive).get().use { zip ->
                zip.entries.asSequence().filter { !it.isDirectory }.forEach { entry ->
                    val out = target(entry.name, entry.size) ?: return@forEach
                    if (out.isFile && out.length() == entry.size) return@forEach
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it, 256 * 1024) } }
                }
            }
            "7z" -> SevenZFile.builder().setFile(archive).get().use { seven ->
                while (true) {
                    val entry = seven.nextEntry ?: break
                    if (entry.isDirectory || !entry.hasStream()) continue
                    val out = target(entry.name, entry.size) ?: continue
                    out.outputStream().buffered(256 * 1024).use { stream ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val count = seven.read(buffer)
                            if (count < 0) break
                            stream.write(buffer, 0, count)
                        }
                    }
                }
            }
            "rar" -> try {
                Archive(archive).use { rar ->
                    rar.fileHeaders.filter { !it.isDirectory }.forEach { header ->
                        val out = target(header.fileName.replace('\\', '/'), header.fullUnpackSize) ?: return@forEach
                        out.outputStream().buffered(256 * 1024).use { rar.extractFile(header, it) }
                    }
                }
            } catch (error: UnsupportedRarV5Exception) {
                throw GameArchiveException("${archive.name}: RAR5 is not supported")
            }
            "gz" -> {
                val out = target(archive.name.removeSuffix(".gz").removeSuffix(".GZ"), 0) ?: throw GameArchiveException(archive.name)
                GZIPInputStream(archive.inputStream().buffered(256 * 1024)).use { input ->
                    out.outputStream().use { stream ->
                        val copied = input.copyTo(stream, 256 * 1024)
                        if (copied > MAX_BYTES) throw GameArchiveException("${archive.name}: too large")
                    }
                }
            }
            else -> throw GameArchiveException(archive.name)
        }
        return files
    }

    /**
     * The file to start among [names]: a playlist before a cue sheet before a disc image before a
     * cartridge; files of a system before unknown ones. Null when none plays.
     */
    fun <T> choose(items: List<T>, name: (T) -> String): T? = items
        .filter { GameSystems.forFile(name(it)) != null }
        .minWithOrNull(compareBy({ rank(name(it)) }, { name(it).count { c -> c == '/' } }, { name(it).lowercase() }))

    private fun rank(name: String): Int = when (GameSystems.extension(name)) {
        "m3u" -> 0
        "cue" -> 1
        "chd", "pbp" -> 2
        else -> 3
    }

    /** Names inside a ZIP or 7z, read through [media] (only the directory at its end); null for others. */
    fun names(fileName: String, media: SeekableMedia): List<String>? = when (GameSystems.extension(fileName)) {
        "zip" -> ZipFile.builder().setSeekableByteChannel(MediaChannel(media)).get().use { zip ->
            zip.entries.asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        }
        "7z" -> SevenZFile.builder().setSeekableByteChannel(MediaChannel(media)).get().use { seven ->
            seven.entries.filter { !it.isDirectory }.map { it.name }
        }
        "gz" -> listOf(fileName.removeSuffix(".gz").removeSuffix(".GZ"))
        else -> null
    }

    /** The system an archive holds, by its contents; null when unknown or unreadable. */
    fun system(fileName: String, media: SeekableMedia): GameSystem? = runCatching {
        names(fileName, media)?.let { inside -> choose(inside) { it }?.let(GameSystems::forFile) }
    }.getOrNull()

    /** A read-only channel over any source's file (device, USB, NAS). */
    private class MediaChannel(private val media: SeekableMedia) : SeekableByteChannel {
        private var position = 0L
        private var open = true

        override fun read(dst: ByteBuffer): Int {
            if (position >= media.size) return -1
            val count = dst.remaining().coerceAtMost(1 shl 20)
            val buffer = ByteArray(count)
            val read = media.read(position, buffer, count)
            if (read <= 0) return -1
            dst.put(buffer, 0, read)
            position += read
            return read
        }

        override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
        override fun position(): Long = position
        override fun position(newPosition: Long): SeekableByteChannel = apply { position = newPosition }
        override fun size(): Long = media.size
        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
        override fun isOpen(): Boolean = open
        override fun close() {
            open = false
        }
    }
}
