package com.ambient.tv.games

import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

enum class GameBiosState { PRESENT, OTHER_VERSION, MISSING }

data class GameBiosStatus(val system: GameSystem, val bios: GameBios, val state: GameBiosState)

/** Result of taking a firmware file over from a file source. */
data class GameBiosImport(val file: String, val systems: List<String>, val verified: Boolean)

/**
 * Firmware files in the cores' system directory. They come from the user's own storage: opening a
 * known file name in the file browser copies it here; its MD5 tells whether it is the expected dump.
 */
@Singleton
class GameBiosManager @Inject constructor(
    private val storage: GameStorage,
    private val store: FileStore,
    private val access: FileAccess,
) {
    private val checked = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    fun file(bios: GameBios): File = File(storage.systemDirectory, bios.file)

    /** Status of every firmware file of [system]; checksums are cached by file size and date. */
    fun status(system: GameSystem): List<GameBiosStatus> = system.bios.map { bios ->
        val file = file(bios)
        val state = when {
            !file.isFile -> GameBiosState.MISSING
            md5Cached(file).equals(bios.md5, ignoreCase = true) -> GameBiosState.PRESENT
            else -> GameBiosState.OTHER_VERSION
        }
        GameBiosStatus(system, bios, state)
    }

    /** A required firmware file missing for a game with [extension], or null. */
    /** A firmware file [core] cannot start [system]'s game without, or null when it has what it needs. */
    fun missingRequired(system: GameSystem, extension: String, core: GameCore = system.core): GameBios? =
        system.bios.firstOrNull { it.requiredFor(extension) && !file(it).isFile }
            // Cores without a built-in replacement need one of the system's firmware files.
            ?: system.bios.takeIf { core.needsBios && it.isNotEmpty() && it.none { bios -> file(bios).isFile } }?.first()

    suspend fun import(fileId: String): GameBiosImport = withContext(Dispatchers.IO) {
        val (sourceId, path) = FileKeys.parse(fileId)
        val source = store.sources().firstOrNull { it.id == sourceId } ?: throw FileNotFoundException(path)
        val name = path.substringAfterLast('/').substringAfterLast(':')
        val matches = GameSystems.bios(name)
        if (matches.isEmpty()) throw FileNotFoundException(name)
        access.prepare(source)
        val target = File(storage.systemDirectory, matches.first().second.file)
        val partial = File(target.parentFile, target.name + ".partial")
        access.open(source, path).use { media ->
            partial.outputStream().use { out ->
                val buffer = ByteArray(256 * 1024)
                var position = 0L
                while (true) {
                    val count = media.read(position, buffer, buffer.size)
                    if (count <= 0) break
                    out.write(buffer, 0, count)
                    position += count
                }
            }
        }
        target.delete()
        if (!partial.renameTo(target)) throw java.io.IOException("Cannot store ${target.name}")
        val digest = md5(target)
        GameBiosImport(
            file = target.name,
            systems = matches.map { it.first.name }.distinct(),
            verified = matches.any { it.second.md5.equals(digest, ignoreCase = true) },
        )
    }

    fun remove(bios: GameBios) {
        file(bios).delete()
    }

    private fun md5Cached(file: File): String {
        val stamp = file.length() xor file.lastModified()
        checked[file.path]?.takeIf { it.first == stamp }?.let { return it.second }
        return md5(file).also { checked[file.path] = stamp to it }
    }

    companion object {
        fun md5(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
