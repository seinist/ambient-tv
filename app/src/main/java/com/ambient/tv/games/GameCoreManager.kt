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
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A core file that cannot run on this device (wrong layout for its memory pages, not a library). */
class GameCoreIncompatibleException(message: String) : IOException(message)

/**
 * Installed libretro cores. A core is fetched once, on the user's request, from the libretro
 * buildbot for this device's ABI, checked (ELF, page alignment) and stored read-only in app storage.
 */
@Singleton
class GameCoreManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val directory: File get() = File(context.noBackupFilesDir, "games/cores")

    /** The buildbot ABI folder for this device, or null when none fits. */
    val abi: String? = Build.SUPPORTED_ABIS.firstOrNull { it in BUILDBOT_ABIS }

    fun file(core: GameCore): File = File(directory, "${core.id}_libretro_android.so")

    fun installed(core: GameCore): File? = file(core).takeIf { it.isFile && it.length() > 0 }

    fun sourceUrl(core: GameCore): String? = abi?.let { "$BUILDBOT/$it/${core.id}_libretro_android.so.zip" }

    fun remove(core: GameCore) {
        file(core).let { it.setWritable(true); it.delete() }
    }

    /** Downloads and installs [core]; [onProgress] gets 0…1, or null while the size is unknown. */
    suspend fun download(core: GameCore, onProgress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
        val url = sourceUrl(core) ?: throw GameCoreIncompatibleException("No core build for ${Build.SUPPORTED_ABIS.joinToString()}")
        directory.mkdirs()
        val partial = File(directory, "${core.id}.download")
        partial.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 }
            var read = 0L
            val counting = object : java.io.FilterInputStream(connection.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { count ->
                    if (count > 0) {
                        read += count
                        onProgress(total?.let { (read.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
            }
            var found = false
            ZipInputStream(counting.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.endsWith(".so")) continue
                    partial.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                    }
                    found = true
                    break
                }
            }
            if (!found) throw GameCoreIncompatibleException("No library in ${core.id} download")
            checkElf(partial, pageSize())
            val target = file(core)
            target.setWritable(true)
            target.delete()
            if (!partial.renameTo(target)) throw IOException("Cannot install ${core.id}")
            // Android 14 refuses writable files for loaded code; the core never changes in place.
            target.setReadOnly()
            target
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    companion object {
        const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"
        val BUILDBOT_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        fun pageSize(): Long = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L)

        /**
         * Checks that [file] is an ELF shared object whose loadable segments fit [pageSize]: a core
         * built for 4 KB pages fails to load on a 16 KB device.
         */
        fun checkElf(file: File, pageSize: Long) {
            RandomAccessFile(file, "r").use { elf ->
                val ident = ByteArray(16)
                elf.readFully(ident)
                if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() || ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()) {
                    throw GameCoreIncompatibleException("Not an ELF library")
                }
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
                val phOffset = if (is64) u64(0x20) else u32(0x1C)
                val phEntrySize = u16(if (is64) 0x36L else 0x2AL)
                val phCount = u16(if (is64) 0x38L else 0x2CL)
                for (index in 0 until phCount) {
                    val header = phOffset + index.toLong() * phEntrySize
                    if (u32(header) != PT_LOAD) continue
                    val align = if (is64) u64(header + 0x30) else u32(header + 0x1C)
                    if (align < pageSize) {
                        throw GameCoreIncompatibleException("Core is aligned to $align bytes, device pages are $pageSize")
                    }
                }
            }
        }

        private const val PT_LOAD = 1L
    }
}
