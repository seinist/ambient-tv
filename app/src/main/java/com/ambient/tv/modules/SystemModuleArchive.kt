package com.ambient.tv.modules

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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
        if (!file.isFile || file.length() != build.size ||
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
                        if (parts.size != 2 || !Regex("classes([2-9][0-9]*|1[0-9]+)?[.]dex").matches(parts.last()))
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
}
