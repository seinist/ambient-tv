package com.ambient.tv.games

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Where an installed core came from ([GameCoreManager.SOURCE_BUILDBOT] or a file name) and when. */
@Serializable
data class GameCoreInfo(val origin: String, val installedAt: Long)

@Serializable
private data class GameCoreDeclaredOption(val key: String, val description: String)

/** A core file that cannot run on this device (wrong layout for its memory pages, not a library). */
class GameCoreIncompatibleException(message: String) : IOException(message)

/** AUTO never silently downgrades signed executable code to an unauthenticated buildbot binary. */
class GameCoreBuildbotConsentRequired(message: String) : IOException(message)

/**
 * Installed libretro cores. A core is fetched once, on the user's request, from the libretro
 * buildbot for this device's ABI, checked (ELF, page alignment) and stored read-only in app storage.
 */
@Singleton
class GameCoreManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channel: GameCoreChannel,
) {
    private val directory: File get() = File(context.noBackupFilesDir, "games/cores")
    private fun directory(source: GameCoreSource): File = source.experimentalProfile?.let { File(directory, "experiments/$it") } ?: directory

    /** The buildbot ABI folder for this device, or null when none fits. */
    val abi: String? = Build.SUPPORTED_ABIS.firstOrNull { it in BUILDBOT_ABIS && (it in setOf("arm64-v8a", "x86_64")) == android.os.Process.is64Bit() }

    fun file(core: GameCore, source: GameCoreSource = GameCoreSource.AUTO): File = File(directory(source), "${core.id}_libretro_android.so")

    fun installed(core: GameCore, source: GameCoreSource = GameCoreSource.AUTO): File? = file(core, source).takeIf { it.isFile && it.length() > 0 }

    fun sourceUrl(core: GameCore): String? = abi
        ?.takeIf { core.supportedAbis.isEmpty() || it in core.supportedAbis }
        ?.let { "$BUILDBOT/$it/${core.id}_libretro_android.so.zip" }

    fun remove(core: GameCore, source: GameCoreSource = GameCoreSource.AUTO) {
        file(core, source).let { it.setWritable(true); it.delete() }
        rollbackFile(core, source).delete()
        infoFile(core, source).delete()
        rollbackInfoFile(core, source).delete()
    }

    /** The previous working binary is kept until a replacement has been tested. */
    fun canRollback(core: GameCore, source: GameCoreSource = GameCoreSource.AUTO): Boolean = rollbackFile(core, source).isFile

    /**
     * Recover the previous installed core after a failed library load. One prior build is retained,
     * without relying on the libretro buildbot preserving its overwritten nightly archives.
     */
    fun rollback(core: GameCore, source: GameCoreSource = GameCoreSource.AUTO): Boolean {
        val old = rollbackFile(core, source)
        val current = file(core, source)
        if (!old.isFile) return false
        if (runCatching { checkElf(old, pageSize(), abi) }.isFailure) return false
        val oldInfo = rollbackInfoFile(core, source)
        val stagedInfo = File(directory(source), "${core.id}.rollback-info.tmp")
        try {
            // Prepare metadata before touching either binary. A full disk leaves the running
            // core and the previous core untouched.
            stagedInfo.delete()
            if (oldInfo.isFile) oldInfo.copyTo(stagedInfo)
            // The source and target live on the same app-private filesystem.
            // ATOMIC_MOVE with REPLACE_EXISTING maps to a single filesystem rename.
            // Never unlink the installed executable before the replacement succeeds.
            // Java NIO also provides real rename semantics in JVM/Robolectric tests.
            Files.move(
                old.toPath(), current.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            stagedInfo.delete()
            return false
        }
        current.setReadOnly()
        // A metadata write failure must not report a failed binary rollback: the old
        // executable is already installed and remains loadable. Retain the old metadata
        // until a successful replace, allowing recovery after a storage error.
        if (oldInfo.isFile) {
            if (runCatching {
                Files.move(
                    stagedInfo.toPath(), infoFile(core, source).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            }.isSuccess) {
                oldInfo.delete()
            }
        } else infoFile(core, source).delete()
        stagedInfo.delete()
        return true
    }

    /**
     * Downloads and installs [core] from [source]: the app's own signed build (checked against its
     * manifest) and/or the libretro buildbot. [onProgress] gets 0…1, or null while the size is unknown.
     */
    suspend fun download(core: GameCore, source: GameCoreSource = GameCoreSource.AUTO, onProgress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
        val abi = abi ?: throw GameCoreIncompatibleException("No core build for ${Build.SUPPORTED_ABIS.joinToString()}")
        if (core.supportedAbis.isNotEmpty() && abi !in core.supportedAbis) {
            throw GameCoreIncompatibleException("${core.name} is not available for $abi")
        }
        if (source != GameCoreSource.BUILDBOT && channel.available) {
            val found = try {
                channel.build(core, abi, source)
            } catch (error: GameCoreSignatureException) {
                // A signature failure is an integrity incident, never silently downgrade
                // AUTO to an unsigned buildbot artifact.
                throw error
            } catch (error: IOException) {
                if ((source == GameCoreSource.AMBIENT || source.experimentalProfile != null)) throw error
                throw GameCoreBuildbotConsentRequired(
                    "Signed core channel unavailable: ${error.message}. Confirm the buildbot source explicitly."
                )
            }
            if (found != null) {
                val (build, url, tag) = found
                val archive = fetchVerifiedZip(url, build.sha256, build.size, onProgress)
                try {
                    return@withContext archive.inputStream().use { input ->
                        install(core, input, zipped = true, origin = (if (source.experimentalProfile != null) SOURCE_EXPERIMENT_PREFIX else SOURCE_AMBIENT_PREFIX) + tag, source = source)
                    }
                } finally {
                    archive.delete()
                }
            }
            if ((source == GameCoreSource.AMBIENT || source.experimentalProfile != null)) throw GameCoreIncompatibleException("${core.id} is not in the app's core build for $abi")
            throw GameCoreBuildbotConsentRequired(
                "No signed ${core.id} build for $abi. Confirm the unsigned buildbot source explicitly."
            )
        }
        if ((source == GameCoreSource.AMBIENT || source.experimentalProfile != null)) {
            throw GameCoreIncompatibleException("Signed Ambient TV core channel unavailable")
        }
        if (source == GameCoreSource.AUTO) {
            throw GameCoreBuildbotConsentRequired(
                "No signed core channel configured. Confirm the unsigned buildbot source explicitly."
            )
        }
        val url = sourceUrl(core) ?: throw GameCoreIncompatibleException("No core build for ${Build.SUPPORTED_ABIS.joinToString()}")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 }
            if (total != null && total > MAX_CORE_ZIP) throw IOException("Core archive exceeds download limit")
            var read = 0L
            val counting = object : java.io.FilterInputStream(connection.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { count ->
                    if (count > 0) {
                        read += count
                        if (read > MAX_CORE_ZIP) throw IOException("Core archive exceeds download limit")
                        onProgress(total?.let { (read.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
            }
            install(core, counting.buffered(), zipped = true, origin = SOURCE_BUILDBOT)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Installs [core] from [input]: the library itself, or a ZIP holding it ([zipped]). Checked like
     * a download; [origin] is shown in settings (the buildbot or the file it came from).
     */
    suspend fun install(core: GameCore, input: InputStream, zipped: Boolean, origin: String, source: GameCoreSource = GameCoreSource.AUTO): File = withContext(Dispatchers.IO) {
        val directory = directory(source)
        directory.mkdirs()
        val partial = File(directory, "${core.id}.download")
        partial.delete()
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        try {
            fun copy(from: InputStream) = partial.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    job?.ensureActive()
                    val count = from.read(buffer)
                    if (count < 0) break
                    if (count.toLong() > MAX_CORE_BINARY - written)
                        throw GameCoreIncompatibleException("Core binary exceeds 128 MiB limit")
                    out.write(buffer, 0, count)
                    written += count
                }
            }
            if (zipped) {
                var found = false
                // Reject a ZIP containing an unrelated .so instead of installing it under
                // a trusted core ID. The streaming copy has a separate decompressed cap.
                // Also cap compressed input. A ZIP may contain enormous ignored entries
                // ahead of the matching .so, even if the extracted binary is small.
                val boundedInput = object : java.io.FilterInputStream(input) {
                    private var bytes = 0L
                    private fun limit(count: Long) {
                        bytes += count
                        if (bytes > MAX_CORE_ZIP) throw IOException("Core ZIP exceeds 128 MiB")
                    }
                    override fun read(): Int = super.read().also { if (it >= 0) limit(1) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        super.read(b, off, len).also { if (it > 0) limit(it.toLong()) }
                }
                ZipInputStream(boundedInput).use { zip ->
                    val expected = setOf("${core.id}_libretro_android.so", "${core.id}_libretro.so")
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name.replace('\\', '/').substringAfterLast('/')
                        if (name !in expected) continue
                        if (entry.size > MAX_CORE_BINARY)
                            throw GameCoreIncompatibleException("Core binary exceeds limit")
                        copy(zip)
                        found = true
                        break
                    }
                }
                if (!found) throw GameCoreIncompatibleException("No matching ${core.id} library in archive")
            } else input.use(::copy)
            checkElf(partial, pageSize(), abi)
            val target = file(core, source)
            val previous = rollbackFile(core, source)
            val oldInfo = rollbackInfoFile(core, source)
            val info = infoFile(core, source)
            val installed = target.isFile
            // Save one known previous version for rollback BEFORE replacing the core atomically.
            if (installed) {
                previous.delete()
                oldInfo.delete()
                if (!target.renameTo(previous)) throw IOException("Cannot back up previous ${core.id}")
                try {
                    if (info.isFile) info.copyTo(oldInfo, overwrite = true)
                } catch (error: Exception) {
                    previous.renameTo(target)
                    target.setReadOnly()
                    throw error
                }
            }
            try {
                if (!partial.renameTo(target)) throw IOException("Cannot install ${core.id}")
                // Android 14 refuses writable files for loaded code; never reopen the core writable.
                target.setReadOnly()
                info.writeText(json.encodeToString(GameCoreInfo.serializer(), GameCoreInfo(origin, System.currentTimeMillis())))
            } catch (error: Exception) {
                target.delete()
                if (installed) {
                    previous.renameTo(target)
                    target.setReadOnly()
                    if (oldInfo.isFile) oldInfo.copyTo(info, overwrite = true)
                }
                throw error
            }
            target
        } finally {
            partial.delete()
        }
    }

    /**
     * Streams signed core assets to a temporary file while hashing. Never allocates the
     * entire ZIP on a TV with limited Java heap; a failed signature leaves no install.
     */
    private fun fetchVerifiedZip(url: String, expectedSha: String, expectedSize: Long, onProgress: (Float?) -> Unit): File {
        if (expectedSize !in 1..MAX_CORE_ZIP) throw GameCoreSignatureException("Invalid signed core size")
        val temporary = File.createTempFile("libretro-signed-", ".zip", context.cacheDir)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 }
            if (total != null && total != expectedSize) throw GameCoreSignatureException("Core ZIP size does not match signed manifest")
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var count = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().buffered(256 * 1024).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read.toLong() > expectedSize - count) throw GameCoreSignatureException("Core ZIP exceeds signed size")
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        count += read
                        onProgress(total?.let { (count.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
            }
            if (count != expectedSize) throw GameCoreSignatureException("Core ZIP size does not match signed manifest")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha, ignoreCase = true))
                throw GameCoreSignatureException("Core ZIP checksum does not match signed manifest")
            return temporary
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    /** Where the installed core came from and when, or null (older installs, none). */
    fun info(core: GameCore, source: GameCoreSource = GameCoreSource.AUTO): GameCoreInfo? = installed(core, source)?.let {
        runCatching { json.decodeFromString(GameCoreInfo.serializer(), infoFile(core, source).readText()) }.getOrNull()
            ?: GameCoreInfo(SOURCE_BUILDBOT, it.lastModified())
    }

    /** The options [core] declared when it last ran (key → description), for settings outside a game. */
    fun saveDeclaredOptions(core: GameCore, options: List<Pair<String, String?>>) {
        if (options.isEmpty()) return
        runCatching {
            optionsDirectory.mkdirs()
            val list = options.map { (key, description) -> GameCoreDeclaredOption(key, description.orEmpty()) }
            File(optionsDirectory, "${core.id}.json").writeText(json.encodeToString(declaredSerializer, list))
        }
    }

    fun declaredOptions(core: GameCore): List<Pair<String, String>> = runCatching {
        json.decodeFromString(declaredSerializer, File(optionsDirectory, "${core.id}.json").readText()).map { it.key to it.description }
    }.getOrDefault(emptyList())

    private val optionsDirectory: File get() = File(context.noBackupFilesDir, "games/options")
    private fun infoFile(core: GameCore, source: GameCoreSource) = File(directory(source), "${core.id}.json")
    private fun rollbackFile(core: GameCore, source: GameCoreSource) = File(directory(source), "${core.id}_libretro_android.previous.so")
    private fun rollbackInfoFile(core: GameCore, source: GameCoreSource) = File(directory(source), "${core.id}.previous.json")

    companion object {
        /** [GameCoreInfo.origin] of cores from the libretro buildbot. */
        const val SOURCE_BUILDBOT = "buildbot"
        /** [GameCoreInfo.origin] prefix of cores from the app's own signed build, followed by the release tag. */
        const val SOURCE_AMBIENT_PREFIX = "ambient:"
        const val SOURCE_EXPERIMENT_PREFIX = "experiment:"
        private const val MAX_CORE_ZIP = 128L * 1024 * 1024
        private const val MAX_CORE_BINARY = 128L * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }
        private val declaredSerializer = kotlinx.serialization.builtins.ListSerializer(GameCoreDeclaredOption.serializer())
        const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"
        val BUILDBOT_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        fun pageSize(): Long = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L)

        /**
         * Checks that [file] is an ELF shared object whose loadable segments fit [pageSize]: a core
         * built for 4 KB pages fails to load on a 16 KB device.
         */
        fun checkElf(file: File, pageSize: Long, expectedAbi: String? = null) {
            RandomAccessFile(file, "r").use { elf ->
                val ident = ByteArray(16)
                elf.readFully(ident)
                if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() || ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()) {
                    throw GameCoreIncompatibleException("Not an ELF library")
                }
                if (ident[4].toInt() !in 1..2 || ident[5].toInt() !in 1..2)
                    throw GameCoreIncompatibleException("Invalid ELF class or endian format")
                val is64 = ident[4].toInt() == 2
                val little = ident[5].toInt() == 1
                fun u16(at: Long): Int { elf.seek(at); val b0 = elf.read(); val b1 = elf.read(); return if (little) b0 or (b1 shl 8) else (b0 shl 8) or b1 }
                fun u32(at: Long): Long {
                    elf.seek(at)
                    val bytes = IntArray(4) { elf.read() }
                    val ordered = if (little) bytes.reversedArray() else bytes
                    return ordered.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
                }
                fun u64(at: Long): Long = if (little) u32(at) or (u32(at + 4) shl 32) else (u32(at) shl 32) or u32(at + 4)
                if (u16(0x10) != 3) throw GameCoreIncompatibleException("ELF is not a shared library")
                val machine = u16(0x12)
                val expected = when (expectedAbi) {
                    "arm64-v8a" -> 183 to true
                    "armeabi-v7a" -> 40 to false
                    "x86_64" -> 62 to true
                    "x86" -> 3 to false
                    else -> null
                }
                if (expected != null && (machine != expected.first || is64 != expected.second)) {
                    throw GameCoreIncompatibleException("Core ELF architecture does not match $expectedAbi")
                }
                val phOffset = if (is64) u64(0x20) else u32(0x1C)
                val phEntrySize = u16(if (is64) 0x36L else 0x2AL)
                val phCount = u16(if (is64) 0x38L else 0x2CL)
                if (phCount <= 0 || phEntrySize < (if (is64) 56 else 32) ||
                    phOffset < 0 || phOffset > elf.length() ||
                    phCount.toLong() * phEntrySize > elf.length() - phOffset) {
                    throw GameCoreIncompatibleException("Invalid ELF program headers")
                }
                var foundLoad = false
                for (index in 0 until phCount) {
                    val header = phOffset + index.toLong() * phEntrySize
                    if (u32(header) != PT_LOAD) continue
                    foundLoad = true
                    val align = if (is64) u64(header + 0x30) else u32(header + 0x1C)
                    if (align < pageSize) {
                        throw GameCoreIncompatibleException("Core is aligned to $align bytes, device pages are $pageSize")
                    }
                }
                if (!foundLoad) throw GameCoreIncompatibleException("ELF has no loadable segments")
            }
        }

        private const val PT_LOAD = 1L
    }
}
