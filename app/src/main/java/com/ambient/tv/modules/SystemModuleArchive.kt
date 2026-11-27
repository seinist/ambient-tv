package com.ambient.tv.modules

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Stages an authenticated archive without executing or extracting any of its
 * entries. A future loader must materialize only validated regular files into
 * a fresh private directory and verify ELF/DEX compatibility before activation.
 */
internal object SystemModuleArchive {
    private const val MAX_ENTRIES = 4096
    private const val MAX_EXPANDED = 512L * 1024 * 1024
    private val segment = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
    private val digest = Regex("[0-9a-f]{64}")

    fun stage(input: InputStream, target: File, build: SystemModuleBuild) {
        if (build.size !in 1..SystemModuleChannel.MAX_PACKAGE_BYTES ||
            !digest.matches(build.sha256)) throw SystemModuleTrustException("Invalid package metadata")
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                if (n == 0) throw IOException("Module stream made no progress")
                if (n > build.size - count) throw SystemModuleTrustException("Module exceeds signed size")
                output.write(buffer, 0, n)
                hash.update(buffer, 0, n)
                count += n
            }
            output.fd.sync()
        }
        checkDigest(count, hash.digest(), build)
        inspect(target, build)
    }

    /** Recheck a stored archive before returning it to a consumer. */
    fun verify(file: File, build: SystemModuleBuild) {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() != build.size ||
            build.size !in 1..SystemModuleChannel.MAX_PACKAGE_BYTES) {
            throw SystemModuleTrustException("Stored module size mismatch")
        }
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                if (n == 0 || n > build.size - count) throw SystemModuleTrustException("Stored module size mismatch")
                hash.update(buffer, 0, n)
                count += n
            }
        }
        checkDigest(count, hash.digest(), build)
        inspect(file, build)
    }

    private fun checkDigest(count: Long, actual: ByteArray, build: SystemModuleBuild) {
        val hex = actual.joinToString("") { "%02x".format(it.toInt() and 255) }
        if (count != build.size || hex != build.sha256)
            throw SystemModuleTrustException("Module size or SHA-256 mismatch")
    }

    /**
     * Package format v1 is a file-only ZIP. No directory records are necessary.
     * Entry names are ASCII, case-insensitively unique, and confined to known
     * roots. The complete archive is signed indirectly by its catalog digest.
     * Inspect every decompressed byte to bound bombs and detect CRC corruption.
     */
    private fun inspect(file: File, build: SystemModuleBuild) {
        preflight(file)
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            val names = mutableSetOf<String>()
            var expanded = 0L
            var hasPayload = false
            val buffer = ByteArray(64 * 1024)
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                val parts = name.split('/')
                val folded = name.lowercase(java.util.Locale.ROOT)
                if (entry.isDirectory || name.length > 512 || parts.size !in 2..12 ||
                    parts.any { !segment.matches(it) || it.endsWith('.') } ||
                    !names.add(folded) || names.size > MAX_ENTRIES ||
                    entry.size < 0 || entry.size > MAX_EXPANDED - expanded ||
                    entry.method !in setOf(java.util.zip.ZipEntry.STORED, java.util.zip.ZipEntry.DEFLATED)) {
                    throw SystemModuleTrustException("Invalid module ZIP entry")
                }
                when (parts.first()) {
                    "lib" -> {
                        if (build.abi == "any" || parts.size != 2 || !name.endsWith(".so"))
                            throw SystemModuleTrustException("Invalid native module path")
                        hasPayload = true
                    }
                    "code" -> {
                        if (parts.size != 2 || !Regex("classes([2-9]|[1-9][0-9]+)?[.]dex").matches(parts.last()))
                            throw SystemModuleTrustException("Invalid module DEX path")
                        hasPayload = true
                    }
                    "assets" -> hasPayload = true
                    "licenses" -> Unit
                    else -> throw SystemModuleTrustException("Unknown module ZIP entry root")
                }
                val crc = CRC32()
                var read = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        if (n == 0 || n > entry.size - read || n > MAX_EXPANDED - expanded)
                            throw SystemModuleTrustException("Module entry exceeds declared size")
                        crc.update(buffer, 0, n)
                        read += n
                        expanded += n
                    }
                }
                if (read != entry.size || crc.value != entry.crc)
                    throw SystemModuleTrustException("Module entry integrity mismatch")
            }
            // Reject file/directory collisions even on case-insensitive extraction targets.
            for (name in names) {
                var parent = name.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    if (parent in names) throw SystemModuleTrustException("Module ZIP path collision")
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            if (!hasPayload) throw SystemModuleTrustException("Module ZIP contains no payload")
        }
    }
    /**
     * Bound the central directory before ZipFile allocates it. v1 packages use
     * ordinary single-disk ZIP (no ZIP64), ASCII paths and regular files only.
     * Reject symlinks/special files and inconsistent local header names.
     */
    private fun preflight(file: File) = RandomAccessFile(file, "r").use { zip ->
        fun invalid(): Nothing = throw SystemModuleTrustException("Invalid module ZIP structure")
        fun u16(): Int = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(zip.readShort()))
        fun u32(): Long = Integer.toUnsignedLong(Integer.reverseBytes(zip.readInt()))
        val length = zip.length()
        if (length < 22) invalid()
        // The producer uses no archive comment; reject ambiguous trailing data.
        val end = length - 22
        zip.seek(end)
        if (u32() != 0x06054b50L || u16() != 0 || u16() != 0) invalid()
        val diskEntries = u16()
        val entries = u16()
        val centralSize = u32()
        val centralStart = u32()
        if (u16() != 0 || entries !in 1..MAX_ENTRIES || diskEntries != entries ||
            centralSize !in 46L..(8L * 1024 * 1024) ||
            centralStart > end || centralSize != end - centralStart) invalid()

        var position = centralStart
        val spans = mutableListOf<Pair<Long, Long>>()
        repeat(entries) {
            if (position > end - 46) invalid()
            zip.seek(position)
            if (u32() != 0x02014b50L) invalid()
            val madeBy = u16()
            u16() // version needed
            val flags = u16()
            val method = u16()
            u32() // time and date
            val crc = u32()
            val compressed = u32()
            val expanded = u32()
            val nameSize = u16()
            val extraSize = u16()
            val commentSize = u16()
            val disk = u16()
            u16() // internal attributes
            val external = u32()
            val local = u32()
            val next = position + 46 + nameSize + extraSize + commentSize
            // Deflate options, data descriptor and UTF-8 are the only allowed flags.
            if (flags and 0x080e.inv() != 0 || method !in setOf(0, 8) ||
                disk != 0 || nameSize !in 1..512 || next > end ||
                compressed > centralStart || expanded > MAX_EXPANDED ||
                local > centralStart - 30) invalid()
            val mode = ((external ushr 16) and 0xf000).toInt()
            if (mode != 0 && mode != 0x8000 || external and 0x10L != 0L) invalid()
            // Unknown creator systems cannot bypass regular-file semantics.
            if ((madeBy ushr 8) !in setOf(0, 3, 10, 19)) invalid()
            val name = ByteArray(nameSize).also(zip::readFully)
            if (name.any { (it.toInt() and 255) !in 32..126 }) invalid()
            zip.seek(local)
            if (u32() != 0x04034b50L) invalid()
            u16()
            if (u16() != flags || u16() != method) invalid()
            u32()
            val localCrc = u32()
            val localCompressed = u32()
            val localExpanded = u32()
            val localNameSize = u16()
            val localExtraSize = u16()
            val dataStart = local + 30 + localNameSize + localExtraSize
            if (localNameSize != nameSize || dataStart > centralStart ||
                compressed > centralStart - dataStart) invalid()
            if (!ByteArray(nameSize).also(zip::readFully).contentEquals(name)) invalid()
            if (flags and 8 == 0 &&
                (localCrc != crc || localCompressed != compressed || localExpanded != expanded)) invalid()
            var dataEnd = dataStart + compressed
            if (flags and 8 != 0) {
                if (dataEnd > centralStart - 12) invalid()
                zip.seek(dataEnd)
                var descriptorCrc = u32()
                if (descriptorCrc == 0x08074b50L) {
                    if (dataEnd > centralStart - 16) invalid()
                    descriptorCrc = u32()
                    dataEnd += 4
                }
                if (descriptorCrc != crc || u32() != compressed || u32() != expanded) invalid()
                dataEnd += 12
            }
            spans += local to dataEnd
            position = next
        }
        if (position != end) invalid()
        var lastEnd = 0L
        for ((start, stop) in spans.sortedBy { it.first }) {
            if (start != lastEnd || stop < start) invalid()
            lastEnd = stop
        }
        if (lastEnd != centralStart) invalid()
    }

}
