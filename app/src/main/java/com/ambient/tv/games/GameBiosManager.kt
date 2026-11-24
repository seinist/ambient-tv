package com.ambient.tv.games

import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
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

    fun file(bios: GameBios, directory: File = storage.systemDirectory): File = File(directory, bios.file)

    /**
     * Status of every firmware file of [system] in [directory] (the shared system folder, or a RomM
     * server's for its games); checksums are cached by file size and date.
     */
    fun status(system: GameSystem, directory: File = storage.systemDirectory): List<GameBiosStatus> = system.bios.map { bios ->
        val file = file(bios, directory)
        val state = when {
            !file.isFile -> GameBiosState.MISSING
            md5Cached(file).equals(bios.md5, ignoreCase = true) -> GameBiosState.PRESENT
            else -> GameBiosState.OTHER_VERSION
        }
        GameBiosStatus(system, bios, state)
    }

    /** A required firmware file missing for a game with [extension], or null. */
    /** A firmware file [core] cannot start [system]'s game without, or null when it has what it needs. */
    fun missingRequired(system: GameSystem, extension: String, core: GameCore = system.core,
                        directory: File = storage.systemDirectory): GameBios? {
        val present = status(system, directory)
        return system.bios.firstOrNull { it.requiredFor(extension) && present.firstOrNull { st -> st.bios == it }?.state != GameBiosState.PRESENT }
            // Cores without a built-in replacement need at least one recognized firmware dump.
            ?: system.bios.takeIf { core.needsBios && it.isNotEmpty() &&
                present.none { item -> item.state == GameBiosState.PRESENT }
            }?.first()
    }

    suspend fun import(fileId: String): GameBiosImport = withContext(Dispatchers.IO) {
        val (sourceId, path) = FileKeys.parse(fileId)
        val source = store.sources().firstOrNull { it.id == sourceId } ?: throw FileNotFoundException(path)
        val name = path.substringAfterLast('/').substringAfterLast(':')
        val matches = GameSystems.bios(name)
        if (matches.isEmpty()) throw FileNotFoundException(name)
        access.prepare(source)
        val target = File(storage.systemDirectory, matches.first().second.file)
        val partial = File(target.parentFile, target.name + ".partial")
        try {
            access.open(source, path).use { media ->
                if (media.size > MAX_BIOS_BYTES) throw IOException("BIOS file exceeds limit")
                partial.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var position = 0L
                    while (true) {
                        val count = media.read(position, buffer, buffer.size)
                        if (count <= 0) break
                        if (count.toLong() > MAX_BIOS_BYTES - position)
                            throw IOException("BIOS file exceeds limit")
                        out.write(buffer, 0, count)
                        position += count
                    }
                    if (media.size > 0 && media.size != position) throw IOException("Incomplete BIOS file")
                }
            }
            val digest = md5(partial)
            if (matches.none { it.second.md5.equals(digest, ignoreCase = true) })
                throw IOException("BIOS checksum does not match any known firmware version")
            val old = File(target.parentFile, target.name + ".previous")
            old.delete()
            if (target.isFile && !target.renameTo(old)) throw IOException("Cannot back up old BIOS")
            if (!partial.renameTo(target)) {
                if (old.isFile) old.renameTo(target)
                throw IOException("Cannot store ${target.name}")
            }
            old.delete()
            checked.remove(target.path)
        } finally { partial.delete() }
        val digest = md5(target)
        GameBiosImport(
            file = target.name,
            systems = matches.map { it.first.name }.distinct(),
            verified = matches.any { it.second.md5.equals(digest, ignoreCase = true) },
        )
    }

    fun remove(bios: GameBios) {
        val target = file(bios)
        checked.remove(target.path)
        target.delete()
    }

    private fun md5Cached(file: File): String {
        val stamp = file.length() xor file.lastModified()
        checked[file.path]?.takeIf { it.first == stamp }?.let { return it.second }
        return md5(file).also { checked[file.path] = stamp to it }
    }

    companion object {
        private const val MAX_BIOS_BYTES = 32L * 1024 * 1024
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
