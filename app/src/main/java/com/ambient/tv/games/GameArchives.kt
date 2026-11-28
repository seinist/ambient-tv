package com.ambient.tv.games

import com.ambient.tv.data.files.SeekableMedia
import com.ambient.tv.games.library.GameRomTitles
import com.ambient.tv.games.library.GameRomChecksums
import com.github.junrar.Archive
import com.github.junrar.RarFormat
import com.github.junrar.exception.UnsupportedRarVersionException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
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
import java.util.zip.CRC32

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
                        val internalTitle: String? = null, val crc32: String? = null,
                        val systemFromHeader: Boolean = false, val datCrc32: String? = null)

    /** Bound both decompressed header reads and the number of archive entries inspected. */
    internal const val MAX_PROBE_HEADER = 72 * 1024
    internal const val MAX_PROBE_ENTRIES = 5_000
    private const val SEVEN_ZIP_PROBE_MEMORY_LIMIT_KIB = 48 * 1024

    // Commons Compress delegates these ZIP methods to the optional zstd-jni runtime.
    private fun ZipArchiveEntry.usesZstandard(): Boolean =
        method == ZipMethod.ZSTD.code || method == ZipMethod.ZSTD_DEPRECATED.code

    private fun ZipFile.canReadGameEntry(entry: ZipArchiveEntry): Boolean =
        !entry.usesZstandard() && canReadEntryData(entry)

    private fun prefixLength(system: GameSystem?, name: String): Int = when {
        // Disc signatures must also constrain explicit assignments of packed ISO files.
        GameSystems.extension(name) == "iso" -> 512
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
     * selected entry. Only an explicit DAT scan may request a missing 7z CRC for one of
     * [checksumSystems]: its first selected ROM is streamed with a 128 MiB bound, never copied.
     * RAR4/RAR5 remain filename/folder hints, not arbitrary remote extraction.
     */
    fun probe(fileName: String, media: SeekableMedia, systemId: String? = null,
              checksumSystems: Set<String> = emptySet()): Metadata? = when (GameSystems.extension(fileName)) {
        "zip" -> ZipFile.builder().setSeekableByteChannel(MediaChannel(media)).get().use { zip ->
            val candidates = zip.entries.asSequence().filter { !it.isDirectory }
                .take(MAX_PROBE_ENTRIES + 1).toList()
            if (candidates.size > MAX_PROBE_ENTRIES) return@use null
            val readable = candidates.filter { zip.canReadGameEntry(it) }
            val entry = choose(readable, systemId) { it.name } ?: return@use null
            val byName = GameSystems.forAssignedFile(entry.name, systemId,
                GameSystems.forSourceFile(entry.name, entry.name))
            val length = prefixLength(byName, entry.name)
            val prefix = if (length > 0 && entry.size >= 0) runCatching {
                zip.getInputStream(entry).use { readPrefix(it, length) }
            }.getOrNull() else null
            val system = GameSystems.forDiscHeader(entry.name, prefix) ?: byName
            val title = prefix?.let { titleFromPrefix(system, entry.name, it) }
            val crc = entry.crc.takeIf { it in 0..0xffffffffL }
                ?.toString(16)?.uppercase(Locale.ROOT)?.padStart(8, '0')
            Metadata(entry.name, system, title, crc,
                systemFromHeader = GameSystems.forDiscHeader(entry.name, prefix) != null,
                datCrc32 = GameRomChecksums.forRom(system?.id.orEmpty(), entry.size, prefix, crc).datCrc32)
        }
        "7z" -> SevenZFile.builder().setSeekableByteChannel(MediaChannel(media))
            .setMaxMemoryLimitKiB(SEVEN_ZIP_PROBE_MEMORY_LIMIT_KIB).get().use { seven ->
            val candidates = seven.entries.take(MAX_PROBE_ENTRIES + 1)
            if (candidates.size > MAX_PROBE_ENTRIES) return@use null
            val entry = choose(candidates.filterNot { it.isDirectory }, systemId) { it.name } ?: return@use null
            val byName = GameSystems.forAssignedFile(entry.name, systemId,
                GameSystems.forSourceFile(entry.name, entry.name))
            // Advancing to later entries of a solid 7z could decompress gigabytes.
            var first = seven.nextEntry
            while (first != null && !first.hasStream()) first = seven.nextEntry
            val prefixLimit = prefixLength(byName, entry.name).takeIf { it > 0 }
                ?: if (byName?.id in checksumSystems && GameRomChecksums.canHash(entry.name, entry.size)) 512 else 0
            val prefix = if (first?.name == entry.name && !first.isDirectory &&
                first.hasStream() && prefixLimit > 0) {
                val buffer = ByteArray(prefixLimit)
                var count = 0
                runCatching {
                    while (count < buffer.size) {
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                        val n = seven.read(buffer, count, buffer.size - count)
                        if (n <= 0) break
                        count += n
                    }
                    buffer.copyOf(count)
                }.getOrNull()
            } else null
            val system = GameSystems.forDiscHeader(entry.name, prefix) ?: byName
            val title = prefix?.let { titleFromPrefix(system, entry.name, it) }
            var crc = if (entry.hasCrc) entry.crcValue.takeIf { it in 0..0xffffffffL }
                ?.toString(16)?.uppercase(Locale.ROOT)?.padStart(8, '0') else null
            // Some 7z archives expose only a folder CRC, not an entry CRC. A complete
            // prefix is free; a larger first ROM is hashed only for an explicit DAT scan.
            if (crc == null && prefix != null && first?.name == entry.name &&
                GameRomChecksums.canHash(entry.name, entry.size) &&
                (prefix.size.toLong() == entry.size || system?.id in checksumSystems)) {
                val checksum = CRC32().apply { update(prefix) }
                var read = prefix.size.toLong()
                val buffer = ByteArray(128 * 1024)
                while (read < entry.size) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val count = seven.read(buffer, 0, minOf(buffer.size.toLong(), entry.size - read).toInt())
                    if (count <= 0) throw GameArchiveException("ROM checksum read truncated")
                    checksum.update(buffer, 0, count)
                    read += count
                }
                crc = GameRomChecksums.hex(checksum.value)
            }
            Metadata(entry.name, system, title, crc,
                systemFromHeader = GameSystems.forDiscHeader(entry.name, prefix) != null,
                datCrc32 = GameRomChecksums.forRom(system?.id.orEmpty(), entry.size, prefix, crc).datCrc32)
        }
        "gz" -> {
            val name = fileName.substringBeforeLast('.')
            val byName = GameSystems.forAssignedFile(name, systemId, GameSystems.forSourceFile(name, name))
            val length = prefixLength(byName, name)
            val prefix = if (length > 0) runCatching {
                GZIPInputStream(BoundedMediaInput(media, 2 * 1024 * 1024)).use {
                    readPrefix(it, length)
                }
            }.getOrNull() else null
            val system = GameSystems.forDiscHeader(name, prefix) ?: byName
            val title = prefix?.let { titleFromPrefix(system, name, it) }
            // RFC 1952 footer stores the CRC32 of the *uncompressed* ROM,
            // so a DAT match does not require reading or decompressing the whole file.
            val footer = ByteArray(8)
            val hasFooter = media.size >= 8 && media.read(media.size - 8, footer, 8) == 8
            val crc = if (hasFooter)
                java.lang.Long.toHexString(
                    (footer[0].toLong() and 255) or
                        ((footer[1].toLong() and 255) shl 8) or
                        ((footer[2].toLong() and 255) shl 16) or
                        ((footer[3].toLong() and 255) shl 24)
                ).uppercase(Locale.ROOT).padStart(8, '0') else null
            val size = if (hasFooter) ByteBuffer.wrap(footer, 4, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL else 0L
            Metadata(name, system, title, crc,
                systemFromHeader = GameSystems.forDiscHeader(name, prefix) != null,
                datCrc32 = GameRomChecksums.forRom(system?.id.orEmpty(), size, prefix, crc).datCrc32)
        }
        "rar" -> {
            // A RAR archive's original filename may already include a known ROM extension.
            val name = fileName.substringBeforeLast('.')
            Metadata(name, GameSystems.forAssignedFile(name, systemId))
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
            val allowed = minOf(length.toLong(), cap - position, media.size - position).toInt()
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
                    if (entry.usesZstandard()) {
                        throw GameArchiveException("${archive.name}: Zstandard ZIP entry is not supported: ${entry.name}")
                    }
                    val out = target(entry.name, entry.size) ?: return@forEach
                    zip.getInputStream(entry).use { input -> out.outputStream().use { copyChecked(input, it) } }
                }
            }
            "7z" -> SevenZFile.builder().setFile(archive)
                .setMaxMemoryLimitKiB(128 * 1024).get().use { seven ->
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
                // Dependency migration only: preserve the previous RAR5 rejection.
                val rar5Signature = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)
                val signature = ByteArray(rar5Signature.size)
                val hasRar5Signature = archive.inputStream().use { input ->
                    try {
                        java.io.DataInputStream(input).readFully(signature)
                        signature.contentEquals(rar5Signature)
                    } catch (_: java.io.EOFException) {
                        false
                    }
                }
                if (hasRar5Signature) {
                    throw GameArchiveException("${archive.name}: RAR5 is not supported")
                }
                Archive(archive).use { rar ->
                    if (rar.format == RarFormat.RAR50) {
                        throw GameArchiveException("${archive.name}: RAR5 is not supported")
                    }
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
            } catch (error: UnsupportedRarVersionException) {
                throw GameArchiveException("${archive.name}: unsupported RAR version")
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
     * Optimized first launch for a ZIP/7z/GZ that contains exactly one playable independent
     * ROM. Stream only the *selected decompressed entry* from the NAS into the bounded
     * local core cache: never download and store the complete compressed archive first.
     *
     * Multi-game and multi-track archives still use the legacy safe materialization path,
     * because the core may need the other referenced files or an interactive game picker.
     * A null return means "not eligible", not a partial or successful extraction.
     */
    fun stageSingle(
        archiveName: String,
        media: SeekableMedia,
        content: File,
        maxOutputBytes: Long,
        systemId: String? = null,
        onProgress: (Float?) -> Unit,
    ): File? {
        require(maxOutputBytes in 1..MAX_BYTES)
        fun write(name: String, expectedSize: Long, input: InputStream, expectedCrc: Long? = null): File {
            val relative = GameRomPreparer.safeRelativePath(name)
                ?: throw GameArchiveException("Unsafe ROM filename in compressed archive")
            if (expectedSize > maxOutputBytes) throw GameArchiveException("Unpacked ROM exceeds cache budget")
            val target = File(content, relative)
            if (target.parentFile?.mkdirs() == false && target.parentFile?.isDirectory != true)
                throw GameArchiveException("Cannot prepare ROM cache directory")
            val partial = File(target.parentFile, target.name + ".partial")
            partial.delete()
            var total = 0L
            val digest = java.util.zip.CRC32()
            try {
                input.use { source ->
                    partial.outputStream().buffered(256 * 1024).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                            val n = source.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            if (total + n > maxOutputBytes) throw GameArchiveException("Unpacked ROM exceeds cache budget")
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            total += n
                            onProgress(if (expectedSize > 0) (total.toFloat() / expectedSize)
                                .coerceIn(0f, 1f) else null)
                        }
                    }
                }
                if (total == 0L || (expectedSize >= 0L && total != expectedSize))
                    throw GameArchiveException("Truncated or empty ROM in archive")
                if (expectedCrc != null && expectedCrc != digest.value)
                    throw GameArchiveException("ZIP ROM checksum mismatch")
                if (!partial.renameTo(target)) throw GameArchiveException("Cannot complete extracted ROM")
                return target
            } finally {
                partial.delete()
            }
        }
        fun eligible(name: String): Boolean = GameSystems.forAssignedFile(name, systemId) != null &&
            GameSystems.extension(name) !in setOf("cue", "gdi", "m3u", "iso")
        return when (GameSystems.extension(archiveName)) {
            "zip" -> ZipFile.builder().setSeekableByteChannel(MediaChannel(media)).get().use { zip ->
                val files = zip.entries.asSequence().filterNot { it.isDirectory }
                    .take(MAX_PROBE_ENTRIES + 1).toList()
                if (files.size > MAX_PROBE_ENTRIES) return@use null
                if (files.any { GameSystems.extension(it.name) in setOf("cue", "gdi", "m3u") }) return@use null
                val roms = files.filter { eligible(it.name) }
                val rom = roms.singleOrNull() ?: return@use null
                if (!zip.canReadGameEntry(rom)) return@use null
                write(rom.name, rom.size, zip.getInputStream(rom),
                    rom.crc.takeIf { it in 0..0xffffffffL })
            }
            "7z" -> SevenZFile.builder().setSeekableByteChannel(MediaChannel(media))
                .setMaxMemoryLimitKiB(SEVEN_ZIP_PROBE_MEMORY_LIMIT_KIB).get().use { seven ->
                val files = seven.entries.take(MAX_PROBE_ENTRIES + 1)
                if (files.size > MAX_PROBE_ENTRIES) return@use null
                if (files.any { !it.isDirectory && GameSystems.extension(it.name) in setOf("cue", "gdi", "m3u") }) return@use null
                val roms = files.filter { !it.isDirectory && eligible(it.name) }
                if (roms.size != 1) return@use null
                val rom = roms.single()
                val first = seven.nextEntry ?: return@use null
                if (first.name != rom.name || first.isDirectory || !first.hasStream())
                    return@use null // avoid scanning huge solid predecessors
                val source = object : InputStream() {
                    override fun read(): Int {
                        val byte = ByteArray(1)
                        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
                    }
                    override fun read(bytes: ByteArray, off: Int, len: Int): Int =
                        seven.read(bytes, off, len)
                }
                write(rom.name, rom.size, source)
            }
            "gz" -> {
                val name = archiveName.substringBeforeLast('.')
                if (!eligible(name)) null
                else write(name, -1L, GZIPInputStream(object : InputStream() {
                    private var offset = 0L
                    override fun read(): Int {
                        val byte = ByteArray(1)
                        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
                    }
                    override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                        if (off < 0 || len < 0 || off > bytes.size - len) throw IndexOutOfBoundsException()
                        if (len == 0) return 0
                        val n = minOf(len.toLong(), media.size - offset).toInt()
                        if (n <= 0) return -1
                        val tmp = ByteArray(n)
                        val read = media.read(offset, tmp, n)
                        if (read > 0) {
                            tmp.copyInto(bytes, off, 0, read)
                            offset += read
                        }
                        return read
                    }
                }.buffered(128 * 1024)), null)
            }
            else -> null
        }
    }

    /**
     * The file to start among [names]: a playlist before a cue sheet before a disc image before a
     * cartridge; files of a system before unknown ones. Null when none plays.
     */
    fun <T> choose(items: List<T>, systemId: String? = null, name: (T) -> String): T? = items
        .filter {
            val filename = name(it).substringAfterLast('/').substringAfterLast('\\')
            if (systemId != null && systemId != "auto") GameSystems.forAssignedFile(filename, systemId) != null
            else GameSystems.isGame(filename) && GameSystems.extension(filename) !in extensions
        }
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
        "7z" -> SevenZFile.builder().setSeekableByteChannel(MediaChannel(media))
            .setMaxMemoryLimitKiB(SEVEN_ZIP_PROBE_MEMORY_LIMIT_KIB).get().use { seven ->
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
