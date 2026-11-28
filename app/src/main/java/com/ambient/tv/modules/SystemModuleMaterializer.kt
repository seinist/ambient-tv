package com.ambient.tv.modules

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Never extract archive modes or links. Each process receives a fresh private
 * immutable tree; no executable is loaded from a writable cache or a partial
 * extraction. The signed archive remains the source of truth.
 */
internal object SystemModuleMaterializer {
    private const val MAX_EXPANDED = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 4096
    private val segment = Regex("[A-Za-z0-9_][A-Za-z0-9_. -]{0,127}")

    data class Materialized(
        val build: SystemModuleBuild,
        val directory: File,
        val dex: List<File>,
        val native: Map<String, SystemModuleBinary.Native>,
        val hashes: Map<String, String>,
        val dexClasses: Set<String> = emptySet(),
    ) {
        fun library(name: String): File {
            if (name !in native) throw SystemModuleTrustException("Unknown module library")
            return File(directory, "lib/$name")
        }
    }

    fun prepare(pkg: SystemModuleStore.Package, root: File, pageSize: Long, hostLibraries: Set<String> = emptySet()): Materialized {
        SystemModuleArchive.verify(pkg.archive, pkg.build)
        if (!root.mkdirs() && !Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw IOException("Cannot create private materialization root")
        val dir = File(root, UUID.randomUUID().toString())
        if (!dir.mkdir()) throw IOException("Cannot create module materialization directory")
        var complete = false
        try {
            val dex = mutableListOf<File>()
            val dexClasses = mutableSetOf<String>()
            val native = linkedMapOf<String, SystemModuleBinary.Native>()
            val hashes = linkedMapOf<String, String>()
            var expanded = 0L
            val buffer = ByteArray(64 * 1024)
            ZipFile(pkg.archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val parts = entry.name.split('/')
                    if (entry.isDirectory || parts.size !in 2..12 ||
                        (entry.name != "lib/libc++_shared.so" && parts.any { !segment.matches(it) || it.endsWith('.') || it.endsWith(' ') }) ||
                        hashes.size >= MAX_ENTRIES || entry.name in hashes ||
                        entry.size < 0 || entry.size > MAX_EXPANDED - expanded)
                        throw SystemModuleTrustException("Invalid materialization entry")
                    var parent = dir
                    for (part in parts.dropLast(1)) {
                        val child = File(parent, part)
                        if (!child.mkdir() && !Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS))
                            throw SystemModuleTrustException("Invalid module directory path")
                        parent = child
                    }
                    val target = File(parent, parts.last())
                    if (!target.createNewFile()) throw SystemModuleTrustException("Duplicate materialized file")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    FileOutputStream(target).use { output ->
                        // Android 14+ requires dynamic code to be read-only before
                        // bytes are written. The already-open descriptor stays usable.
                        if (!target.setReadOnly()) throw IOException("Cannot seal module file")
                        zip.getInputStream(entry).use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n == -1) break
                                if (n == 0 || n > entry.size - written || n > MAX_EXPANDED - expanded)
                                    throw SystemModuleTrustException("Module expansion exceeds bounds")
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                written += n
                                expanded += n
                            }
                        }
                        output.fd.sync()
                    }
                    val hash = hex(digest.digest())
                    if (written != entry.size || hashFile(target) != hash)
                        throw SystemModuleTrustException("Materialized file integrity mismatch")
                    hashes[entry.name] = hash
                    when (parts.first()) {
                        "lib" -> {
                            if (parts.size != 2 || !target.name.endsWith(".so"))
                                throw SystemModuleTrustException("Invalid native file")
                            val info = SystemModuleBinary.elf(target, pkg.build.abi, pageSize)
                            if (info.soname != null && info.soname != target.name)
                                throw SystemModuleTrustException("ELF SONAME does not match package file")
                            native[target.name] = info
                        }
                        "code" -> {
                            if (parts.size != 2 || !Regex("classes([2-9]|[1-9][0-9]+)?[.]dex").matches(target.name))
                                throw SystemModuleTrustException("Invalid module DEX file")
                            for (name in SystemModuleBinary.dex(target)) {
                                if (!dexClasses.add(name)) throw SystemModuleTrustException("Duplicate class across module DEX files")
                            }
                            dex += target
                        }
                        "assets", "licenses" -> Unit
                        else -> throw SystemModuleTrustException("Unexpected materialization root")
                    }
                }
            }
            // Detect replacement of the archive while extraction was in progress.
            SystemModuleArchive.verify(pkg.archive, pkg.build)
            val orderedDex = dex.sortedBy {
                it.name.removePrefix("classes").removeSuffix(".dex").ifEmpty { "1" }.toIntOrNull()
                    ?: throw SystemModuleTrustException("Invalid DEX sequence")
            }
            if (orderedDex.map { it.name } != orderedDex.indices.map {
                    if (it == 0) "classes.dex" else "classes${it + 1}.dex"
                }) throw SystemModuleTrustException("Non-contiguous module DEX sequence")
            validateNativeDependencies(native, hostLibraries)
            val result = Materialized(pkg.build, dir, orderedDex, native.toMap(), hashes.toMap(), dexClasses.toSet())
            complete = true
            return result
        } finally {
            // No loader has seen an incomplete tree.
            if (!complete) dir.deleteRecursively()
        }
    }

    // Only public Android NDK libraries may come from the platform. Other ELF
    // dependencies must be packaged alongside their consumer; cross-classloader
    // native linking is not assumed to work with Android linker namespaces.
    private val platform = setOf(
        "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
        "libjnigraphics.so", "libz.so", "libEGL.so", "libGLESv1_CM.so",
        "libGLESv2.so", "libGLESv3.so", "libvulkan.so", "libOpenSLES.so",
        "libaaudio.so", "libmediandk.so", "libnativewindow.so", "libcamera2ndk.so",
        "libsync.so",
    )

    internal fun isPlatformLibrary(name: String): Boolean = name in platform

    internal fun validateNativeDependencies(native: Map<String, SystemModuleBinary.Native>, hostLibraries: Set<String> = emptySet()) {
        if (native.keys.any { it in platform || it in hostLibraries })
            throw SystemModuleTrustException("Package attempts to replace Android platform library")
        native.forEach { (_, binary) ->
            if (binary.needed.any { it !in native && it !in platform && it !in hostLibraries })
                throw SystemModuleTrustException("Missing or forbidden native dependency")
        }
    }

    fun verifyFiles(materialized: Materialized) {
        val root = materialized.directory
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw SystemModuleTrustException("Missing materialization directory")
        val expectedDirs = materialized.hashes.keys.flatMap { path ->
            val parts = path.split('/')
            (1 until parts.size).map { parts.take(it).joinToString("/") }
        }.toSet()
        val found = mutableSetOf<String>()
        Files.walk(root.toPath()).use { paths ->
            val iterator = paths.iterator()
            var count = 0
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == root.toPath()) continue
                if (++count > materialized.hashes.size + expectedDirs.size)
                    throw SystemModuleTrustException("Unexpected materialized path count")
                val relative = root.toPath().relativize(path).toString()
                if (Files.isSymbolicLink(path) ||
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) relative !in expectedDirs
                    else !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                        relative !in materialized.hashes || !found.add(relative))
                    throw SystemModuleTrustException("Unexpected materialized path")
            }
        }
        if (found != materialized.hashes.keys) throw SystemModuleTrustException("Missing materialized file")
        var expanded = 0L
        for ((path, expected) in materialized.hashes) {
            val file = File(materialized.directory, path)
            var cursor: File? = file
            while (cursor != null && cursor != materialized.directory) {
                if (Files.isSymbolicLink(cursor.toPath()))
                    throw SystemModuleTrustException("Materialized path contains a symbolic link")
                cursor = cursor.parentFile
            }
            if (!Files.isDirectory(materialized.directory.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                file.length() > MAX_EXPANDED - expanded || hashFile(file) != expected)
                throw SystemModuleTrustException("Materialized module file changed")
            expanded += file.length()
        }
    }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
