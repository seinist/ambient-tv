package com.ambient.tv.games

import com.ambient.tv.data.files.SeekableMedia
import com.ambient.tv.games.library.GameRomTitles
import com.github.junrar.Archive
import com.github.junrar.exception.UnsupportedRarV5Exception
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.InputStream
import java.util.Locale
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

    /** Metadata discovery needs no archive copy. CRC comes from ZIP central-directory entries. */
    data class Metadata(val innerName: String?, val system: GameSystem?,
                        val internalTitle: String? = null, val crc32: String? = null)

    /** Bound both decompressed header reads and the number of archive entries inspected. */
    internal const val MAX_PROBE_HEADER = 72 * 1024
    internal const val MAX_PROBE_ENTRIES = 5_000

    private fun prefixLength(system: GameSystem?, name: String): Int = when {
        system == null -> 0
        system.id == "snes" -> MAX_PROBE_HEADER
        system.id == "psp" && GameSystems.extension(name) == "pbp" -> MAX_PROBE_HEADER
        system.id in setOf("gb", "gba", "nds", "megadrive", "n64", "gamecube", "wii") -> 512
        else -> 0
    }

    /** Reads only a small *uncompressed* prefix, even for multi-gigabyte ROM entries. */
    private fun readPrefix(input: InputStream, size: Int): ByteArray {
        if (size <= 0) return byteArrayOf()
        val buffer = ByteArray(size.coerceAtMost(MAX_PROBE_HEADER))
        var total = 0
        while (total < buffer.size) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val got = input.read(buffer, total, buffer.size - total)
            if (got < 0) break
            if (got == 0) continue
            total += got
        }
        return buffer.copyOf(total)
    }

    private fun titleFromPrefix(system: GameSystem?, name: String, bytes: ByteArray): String? =
        system?.let { selected ->
            GameRomTitles.extract(selected.id, name) { offset, count ->
                if (offset < 0L || count < 0 || offset + count > bytes.size) null
                else bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
            }
        }

    /**
     * Probe a packed game without staging the archive locally. ZIP exposes CRC32 from its
     * central directory; ZIP/7z only decompress at most a bounded ROM-header prefix.
     * 7z solid folders may still require extra decoder work, so only probe the first
     * selected entry. RAR4/RAR5 remain filename/folder hints, not arbitrary remote extraction.
     */
    fun probe(fileName: String, media: SeekableMedia): Metadata? = when (GameSystems.extension(fileName)) {
        "zip" -> ZipFile.builder().setSeekableByteChannel(MediaChannel(media)).get().use { zip ->
            val candidates = zip.entries.asSequence().filter { !it.isDirectory }
                .take(MAX_PROBE_ENTRIES + 1).toList()
            if (candidates.size > MAX_PROBE_ENTRIES) return@use null
            val entry = choose(candidates) { it.name } ?: return@use null
            val system = GameSystems.forFile(entry.name)
            val length = prefixLength(system, entry.name)
            val title = if (length > 0 && entry.size >= 0 &&
                zip.canReadEntryData(entry)) runCatching {
                zip.getInputStream(entry).use { readPrefix(it, length) }
                    .let { titleFromPrefix(system, entry.name, it) }
            }.getOrNull() else null
            val crc = entry.crc.takeIf { it in 0..0xffffffffL }
                ?.toString(16)?.uppercase(Locale.ROOT)?.padStart(8, '0')
            Metadata(entry.name, system, title, crc)
        }
        "7z" -> SevenZFile.builder().setSeekableByteChannel(MediaChannel(media)).get().use { seven ->
            val candidates = seven.entries.take(MAX_PROBE_ENTRIES + 1)
            if (candidates.size > MAX_PROBE_ENTRIES) return@use null
            val entry = choose(candidates.filterNot { it.isDirectory }) { it.name } ?: return@use null
            val system = GameSystems.forFile(entry.name)
            // Advancing to later entries of a solid 7z could decompress gigabytes.
            val first = seven.nextEntry
            val title = if (first?.name == entry.name && !first.isDirectory &&
                first.hasStream() && prefixLength(system, entry.name) > 0) {
                val buffer = ByteArray(prefixLength(system, entry.name))
                var count = 0
                runCatching {
                    while (count < buffer.size) {
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                        val n = seven.read(buffer, count, buffer.size - count)
                        if (n <= 0) break
                        count += n
                    }
                    titleFromPrefix(system, entry.name, buffer.copyOf(count))
                }.getOrNull()
            } else null
            Metadata(entry.name, system, title)
        }
        "gz" -> {
            val name = fileName.substringBeforeLast('.')
            val system = GameSystems.forFile(name)
            val length = prefixLength(system, name)
            val title = if (length > 0) runCatching {
                GZIPInputStream(BoundedMediaInput(media, 2 * 1024 * 1024)).use {
                    titleFromPrefix(system, name, readPrefix(it, length))
                }
            }.getOrNull() else null
            Metadata(name, system, title)
        }
        "rar" -> {
            // A RAR archive's original filename may already include a known ROM extension.
            val name = fileName.substringBeforeLast('.')
            Metadata(name, GameSystems.forFile(name))
        }
        else -> null
    }

    /** Sequential GZIP input, with a strict cap on compressed bytes read over the network. */
    private class BoundedMediaInput(private val media: SeekableMedia, private val cap: Int) : InputStream() {
        private var position = 0L
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            if (position >= media.size) return -1
            if (position >= cap) throw GameArchiveException("GZIP metadata probe exceeded compressed limit")
            val allowed = minOf(length.toLong(), (cap - position).toLong(), media.size - position).toInt()
            val scratch = ByteArray(allowed)
            val got = media.read(position, scratch, allowed)
            if (got > 0) {
                scratch.copyInto(bytes, offset, 0, got)
                position += got
            }
            return got
        }
    }

    /** Unpacked size a game archive may have. */
    const val MAX_BYTES = 8L * 1024 * 1024 * 1024

    /** Unpacks [archive] into [folder] (names flattened) and returns the unpacked files. */
    fun extract(archive: File, folder: File, maxOutputBytes: Long = MAX_BYTES): List<File> {
        require(maxOutputBytes in 1..MAX_BYTES)
        folder.mkdirs()
        var total = 0L
        val names = mutableSetOf<String>()
        val files = mutableListOf<File>()
        fun target(name: String, size: Long): File? {
            // Keep safe relative paths: a CUE in Game/disc.cue must find its tracks
            // in Game/tracks/, without flattening unrelated names onto each other.
            val relative = GameRomPreparer.safeRelativePath(name)
                ?: throw GameArchiveException("${archive.name}: unsafe extracted path $name")
            if (!names.add(relative.lowercase(java.util.Locale.ROOT))) {
                throw GameArchiveException("${archive.name}: duplicate extracted file $relative")
            }
            if (size >= 0 && size > maxOutputBytes - total) {
                throw GameArchiveException("${archive.name}: extracted data too large")
            }
            val out = File(folder, relative)
            if (out.parentFile?.mkdirs() == false && out.parentFile?.isDirectory != true) {
                throw GameArchiveException("${archive.name}: cannot prepare extracted folder")
            }
            return out.also(files::add)
        }
        fun checkedWrite(out: OutputStream, bytes: ByteArray, off: Int, length: Int) {
            if (length < 0 || length.toLong() > maxOutputBytes - total) {
                throw GameArchiveException("${archive.name}: extracted data exceeds limit")
            }
            out.write(bytes, off, length)
            total += length
        }
        fun copyChecked(input: InputStream, out: OutputStream) {
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                checkedWrite(out, buffer, 0, count)
            }
        }
        when (GameSystems.extension(archive.name)) {
            "zip" -> ZipFile.builder().setFile(archive).get().use { zip ->
                zip.entries.asSequence().filter { !it.isDirectory }.forEach { entry ->
                    val out = target(entry.name, entry.size) ?: return@forEach
                    zip.getInputStream(entry).use { input -> out.outputStream().use { copyChecked(input, it) } }
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
                            checkedWrite(stream, buffer, 0, count)
                        }
                    }
                }
            }
            "rar" -> try {
                Archive(archive).use { rar ->
                    rar.fileHeaders.filter { !it.isDirectory }.forEach { header ->
                        val out = target(header.fileName.replace('\\', '/'), header.fullUnpackSize) ?: return@forEach
                        out.outputStream().buffered(256 * 1024).use { stream ->
                            val capped = object : FilterOutputStream(stream) {
                                override fun write(value: Int) {
                                    checkedWrite(stream, byteArrayOf(value.toByte()), 0, 1)
                                }
                                override fun write(b: ByteArray, off: Int, len: Int) {
                                    checkedWrite(stream, b, off, len)
                                }
                            }
                            rar.extractFile(header, capped)
                        }
                    }
                }
            } catch (error: UnsupportedRarV5Exception) {
                throw GameArchiveException("${archive.name}: RAR5 is not supported")
            }
            "gz" -> {
                val out = target(archive.name.removeSuffix(".gz").removeSuffix(".GZ"), 0) ?: throw GameArchiveException(archive.name)
                GZIPInputStream(archive.inputStream().buffered(256 * 1024)).use { input ->
                    out.outputStream().use { stream -> copyChecked(input, stream) }
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
        .filter { GameSystems.forFile(name(it)) != null || GameSystems.extension(name(it)) in GameSystems.ambiguousExtensions }
        .minWithOrNull(compareBy({ rank(name(it)) }, { name(it).count { c -> c == '/' } }, { name(it).lowercase() }))

    private fun rank(name: String): Int = when (GameSystems.extension(name)) {
        "m3u" -> 0
        "cue" -> 1
        "chd", "pbp" -> 2
        else -> 3
    }

    /** Lightweight listing of archive entry names; bounded to avoid giant untrusted indices. */
    fun names(fileName: String, media: SeekableMedia): List<String>? = when (GameSystems.extension(fileName)) {
        "zip" -> ZipFile.builder().setSeekableByteChannel(MediaChannel(media)).get().use { zip ->
            zip.entries.asSequence().filter { !it.isDirectory }
                .take(MAX_PROBE_ENTRIES + 1).map { it.name }.toList()
                .takeIf { it.size <= MAX_PROBE_ENTRIES }
        }
        "7z" -> SevenZFile.builder().setSeekableByteChannel(MediaChannel(media)).get().use { seven ->
            seven.entries.filter { !it.isDirectory }.take(MAX_PROBE_ENTRIES + 1)
                .map { it.name }.takeIf { it.size <= MAX_PROBE_ENTRIES }
        }
        "gz", "rar" -> listOf(fileName.substringBeforeLast('.'))
        else -> null
    }

    fun system(fileName: String, media: SeekableMedia): GameSystem? =
        runCatching { probe(fileName, media)?.system }.getOrNull()

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
