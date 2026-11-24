package com.ambient.tv.romm

import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.games.GameBiosManager
import com.ambient.tv.games.GameRomPreparer
import com.ambient.tv.games.GameStateStore
import com.ambient.tv.games.GameStorage
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.PreparedGame
import com.ambient.tv.games.UnknownGameException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A game from a RomM server, as the player and the data mirror need it. */
data class RommGameRef(
    val serverKey: String,
    val romId: Long,
    val platformId: Long,
    /** The game's file name without extension: the name of its in-game save on the server. */
    val baseName: String,
)

/** Where a RomM game's saves and states stand compared with the server. */
enum class RommSyncState { CHECKING, SYNCED, UPLOADING, PENDING, SIGN_IN }

/** What a server file was when this device last matched its own copy to it. */
@Serializable
internal data class RommMirrorEntry(
    val serverId: Long,
    val fileName: String,
    val updatedAt: String,
    /** Size and date of the device's copy at that moment ([GameStateStore.stamp]). */
    val local: String,
)

@Serializable
internal data class RommMirror(
    val states: Map<String, RommMirrorEntry> = emptyMap(),
    val sram: RommMirrorEntry? = null,
)

@Serializable
internal data class RommFirmwareManifest(
    /** File name → "size:modified:md5" of firmware that came from the server. */
    val files: Map<String, String> = emptyMap(),
)

/**
 * Firmware, in-game saves and save states of RomM games are the server's. The player keeps working
 * on a copy on this device (one folder per server, apart from the local games); before a game starts
 * that copy is brought up to the server's, and every change is sent back. Without a connection the
 * last copy is played and the changes wait until the server answers again.
 */
@Singleton
class RommGameData @Inject constructor(
    private val client: RommClient,
    private val accounts: RommAccountStore,
    private val library: RommLibrary,
    private val storage: GameStorage,
    private val states: GameStateStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val uploads = ConcurrentHashMap<String, Job>()
    private val sync = MutableStateFlow<Map<String, RommSyncState>>(emptyMap())
    /** Per game (save key): the state of its server mirror, for the game's Info panel. */
    val syncStates: StateFlow<Map<String, RommSyncState>> = sync.asStateFlow()

    /** Downloads the game (or reuses the cached download) and describes it for the player. */
    suspend fun prepare(fileId: String, onProgress: (Float?) -> Unit, preparer: GameRomPreparer): PreparedGame {
        val (serverKey, romId) = RommKeys.parse(fileId) ?: throw FileNotFoundException(fileId)
        val account = accounts.account()?.takeIf { it.serverKey == serverKey }
            ?: throw FileNotFoundException("The RomM server of this game is not signed in")
        // Fresh details while the server answers; the device's catalog copy otherwise (cached game).
        val rom = try {
            client.rom(account, romId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RommAuthException) {
            throw error
        } catch (error: Exception) {
            library.rom(fileId) ?: throw error
        }
        val system = RommPlatforms.system(rom) ?: library.platform(rom.platformId)?.let(RommPlatforms::system)
            ?: throw UnknownGameException(rom.fsName)
        val fileName = rom.fsName.ifBlank { "game" }
        val name = if (rom.zipped && GameSystems.extension(fileName) != "zip") "$fileName.zip" else fileName
        // The list and the detail answer agree on these, so a cached game opens without the server.
        val stamp = "${rom.id}:${rom.fsSizeBytes}:${rom.updatedAt.orEmpty()}:${rom.zipped}"
        val file = preparer.materialize(fileId, name, stamp, system.id, rom.fsSizeBytes, onProgress) { target, maxBytes, progress ->
            client.downloadRom(account, rom, target, maxBytes, progress)
        }
        val chosen = GameSystems.forPreparedFile(file, rom.fsName, system.id) ?: system
        return PreparedGame(
            fileId = fileId,
            title = rom.displayTitle,
            file = file,
            system = chosen,
            saveKey = GameRomPreparer.sha1(fileId),
            sourceName = SOURCE_NAME,
            entry = FileEntry(sourceId = "romm-$serverKey", path = "${rom.id}/$fileName", name = fileName,
                directory = false, size = rom.fsSizeBytes),
            remote = RommGameRef(serverKey, rom.id, rom.platformId,
                rom.fsNameNoExt.ifBlank { fileName.substringBeforeLast('.') }),
        )
    }

    /**
     * Before the firmware check: the firmware RomM keeps for the game's platform. Returns false when
     * the server could not be reached (the firmware from the last time is used).
     */
    suspend fun syncFirmware(game: PreparedGame): Boolean {
        val ref = game.remote ?: return true
        try {
            val account = accounts.account()?.takeIf { it.serverKey == ref.serverKey } ?: return false
            return lock(game).withLock {
                try {
                    syncFirmware(account, game)
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    com.ambient.tv.diagnostics.DiagnosticLog.warn("romm", "firmware sync failed: ${error.message}")
                    false
                }
            }
        } finally {
            linkCoreData(storage.systemDirectory(game))
        }
    }

    /**
     * Before the core starts: the in-game save and the save states as the server has them. Returns
     * false when the server could not be reached (the last copy is played).
     */
    suspend fun beforeStart(game: PreparedGame, coreId: String, coreStamp: String): Boolean {
        val ref = game.remote ?: return true
        val account = accounts.account()?.takeIf { it.serverKey == ref.serverKey } ?: return false
        setState(game, RommSyncState.CHECKING)
        val reached = lock(game).withLock {
            try {
                pullSram(account, game, coreId)
                pullStates(account, game, coreStamp)
                setState(game, RommSyncState.SYNCED)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RommAuthException) {
                setState(game, RommSyncState.SIGN_IN)
                false
            } catch (error: Exception) {
                com.ambient.tv.diagnostics.DiagnosticLog.warn("romm", "sync before start failed: ${error.message}")
                setState(game, RommSyncState.PENDING)
                false
            }
        }
        // Changes made offline in an earlier session go up as soon as the server answers.
        if (reached) scheduleUpload(game, coreId, delayMs = 0L)
        return reached
    }

    /** After a checkpoint, a manual save, a rename or a deletion: send the changes to the server. */
    fun scheduleUpload(game: PreparedGame, coreId: String, delayMs: Long = UPLOAD_DELAY_MS) {
        val ref = game.remote ?: return
        uploads.remove(game.saveKey)?.cancel()
        uploads[game.saveKey] = scope.launch {
            delay(delayMs)
            var attempt = 0
            while (true) {
                val account = accounts.account()?.takeIf { it.serverKey == ref.serverKey }
                if (account == null) { setState(game, RommSyncState.PENDING); return@launch }
                setState(game, RommSyncState.UPLOADING)
                val done = try {
                    lock(game).withLock {
                        pushSram(account, game, coreId)
                        pushStates(account, game)
                    }
                    setState(game, RommSyncState.SYNCED)
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: RommAuthException) {
                    setState(game, RommSyncState.SIGN_IN)
                    return@launch
                } catch (error: Exception) {
                    com.ambient.tv.diagnostics.DiagnosticLog.warn("romm", "upload failed: ${error.message}")
                    setState(game, RommSyncState.PENDING)
                    false
                }
                if (done || attempt >= RETRY_DELAYS_MS.size) return@launch
                delay(RETRY_DELAYS_MS[attempt++])
            }
        }
    }

    fun syncState(game: PreparedGame): RommSyncState? = sync.value[game.saveKey]

    // Firmware ---------------------------------------------------------------------------------

    private suspend fun syncFirmware(account: RommAccount, game: PreparedGame) {
        val ref = game.remote ?: return
        val directory = storage.systemDirectory(game)
        val manifestFile = File(directory, FIRMWARE_MANIFEST)
        val manifest = read(manifestFile, RommFirmwareManifest.serializer()) ?: RommFirmwareManifest()
        val kept = mutableMapOf<String, String>()
        client.firmware(account, ref.platformId).forEach { firmware ->
            val serverName = RommFiles.safeName(firmware.fileName) ?: return@forEach
            // A known dump goes where the cores look for it, whatever RomM calls the file.
            val known = game.system.bios.firstOrNull { bios ->
                bios.file.equals(serverName, ignoreCase = true) ||
                    (firmware.md5 != null && bios.md5.equals(firmware.md5, ignoreCase = true))
            }
            val name = known?.file ?: serverName
            val target = File(directory, name)
            val recorded = manifest.files[name]
            val current = if (target.isFile) "${target.length()}:${target.lastModified()}" else null
            val sizeMatches = firmware.sizeBytes <= 0L || target.length() == firmware.sizeBytes
            val upToDate = target.isFile && sizeMatches && recorded != null &&
                recorded.substringBeforeLast(':') == current &&
                (firmware.md5 == null || recorded.substringAfterLast(':').equals(firmware.md5, ignoreCase = true))
            if (!upToDate) {
                val partial = File(directory, "$name.partial")
                try {
                    client.downloadFirmware(account, firmware, partial)
                    val digest = GameBiosManager.md5(partial)
                    if (firmware.md5 != null && !digest.equals(firmware.md5, ignoreCase = true)) {
                        throw IOException("Firmware $serverName does not match its checksum on the server")
                    }
                    target.delete()
                    if (!partial.renameTo(target)) throw IOException("Cannot store firmware $name")
                } finally { partial.delete() }
            }
            kept[name] = "${target.length()}:${target.lastModified()}:${firmware.md5 ?: GameBiosManager.md5(target)}"
        }
        // Firmware removed on the server leaves this device's copy of it, too.
        (manifest.files.keys - kept.keys).forEach { File(directory, it).delete() }
        write(manifestFile, RommFirmwareManifest.serializer(), RommFirmwareManifest(kept))
    }

    /**
     * Support folders some cores need next to their firmware (fonts, databases from a RetroArch
     * "system" folder) are linked from the shared system folder. Firmware is never linked: a RomM
     * game uses only the server's.
     */
    private fun linkCoreData(directory: File) {
        storage.systemDirectory.listFiles().orEmpty().forEach { child ->
            if (!child.isDirectory || child.name.startsWith(".")) return@forEach
            val link = File(directory, child.name)
            if (link.exists()) return@forEach
            runCatching { android.system.Os.symlink(child.absolutePath, link.absolutePath) }
        }
    }

    // In-game save (SRAM) ---------------------------------------------------------------------

    private suspend fun pullSram(account: RommAccount, game: PreparedGame, coreId: String) {
        val ref = game.remote ?: return
        val candidates = client.saves(account, ref.romId).filter { RommFiles.isSram(it.fileName) }
        val remote = candidates.filter { it.emulator == coreId }.maxByOrNull { RommLibrary.parseInstant(it.updatedAt) ?: 0L }
            ?: candidates.maxByOrNull { RommLibrary.parseInstant(it.updatedAt) ?: 0L }
            ?: return
        val mirror = readMirror(game)
        val known = mirror.sram
        if (known != null && known.serverId == remote.id && known.updatedAt == remote.updatedAt) return
        val local = storage.sramFile(game)
        val localChanged = local.isFile && (known == null || known.local != stamp(local))
        if (localChanged && local.lastModified() > (RommLibrary.parseInstant(remote.updatedAt) ?: 0L)) return
        val bytes = client.assetBytes(account, remote.downloadPath ?: "/api/saves/${remote.id}/content", MAX_SRAM_BYTES)
        if (localChanged) runCatching { local.copyTo(File(local.parentFile, local.name + ".before-server"), overwrite = true) }
        storage.write(local, bytes)
        writeMirror(game, readMirror(game).copy(sram = RommMirrorEntry(remote.id, remote.fileName, remote.updatedAt, stamp(local))))
    }

    private suspend fun pushSram(account: RommAccount, game: PreparedGame, coreId: String) {
        val ref = game.remote ?: return
        val local = storage.sramFile(game)
        if (!local.isFile || local.length() <= 0) return
        val current = stamp(local)
        val known = readMirror(game).sram
        if (known != null && known.local == current) return
        val bytes = storage.read(local) ?: return
        val name = RommFiles.safeName("${ref.baseName}.srm") ?: "game.srm"
        val uploaded = client.uploadSave(account, ref.romId, coreId, name, bytes)
        writeMirror(game, readMirror(game).copy(sram = RommMirrorEntry(uploaded.id, uploaded.fileName, uploaded.updatedAt, current)))
    }

    // Save states -----------------------------------------------------------------------------

    private suspend fun pullStates(account: RommAccount, game: PreparedGame, coreStamp: String) {
        val ref = game.remote ?: return
        val remote = client.states(account, ref.romId)
            .mapNotNull { asset -> RommStateNames.parse(asset.fileName, coreStamp)?.let { it to asset } }
            .groupBy { it.first.id }
            .mapValues { (_, copies) -> copies.maxBy { RommLibrary.parseInstant(it.second.updatedAt) ?: 0L } }
        val mirror = readMirror(game)
        val local = states.list(game).associateBy { it.id }
        val next = mirror.states.toMutableMap()
        remote.forEach { (id, pair) ->
            val (entry, asset) = pair
            val known = mirror.states[id]
            val localEntry = local[id]
            val localChanged = localEntry != null && known?.local != states.stamp(game, localEntry)
            val inSync = known != null && known.serverId == asset.id && known.fileName == asset.fileName &&
                known.updatedAt == asset.updatedAt
            when {
                inSync && localEntry != null -> Unit
                // Changed here since the last match: the upload sends it.
                localChanged && known != null && known.serverId == asset.id -> Unit
                // Both sides made this state independently: the newer one wins.
                localChanged && localEntry != null &&
                    localEntry.createdAt >= (RommLibrary.parseInstant(asset.updatedAt) ?: 0L) -> Unit
                else -> {
                    val bytes = client.assetBytes(account, asset.downloadPath ?: "/api/states/${asset.id}/content",
                        GameStateStore.MAX_STATE_BYTES)
                    val screenshot = asset.screenshot?.downloadPath?.let { path ->
                        runCatching { client.assetBytes(account, path, MAX_SCREENSHOT_BYTES) }.getOrNull()
                    }
                    states.put(game, entry, bytes, screenshot)
                    next[id] = RommMirrorEntry(asset.id, asset.fileName, asset.updatedAt, states.stamp(game, entry))
                }
            }
        }
        // States that were matched before and are gone on the server were deleted elsewhere.
        mirror.states.forEach { (id, known) ->
            if (id in remote) return@forEach
            val localEntry = local[id]
            if (localEntry != null && known.local == states.stamp(game, localEntry)) states.delete(game, localEntry)
            next.remove(id)
        }
        writeMirror(game, readMirror(game).copy(states = next))
    }

    private suspend fun pushStates(account: RommAccount, game: PreparedGame) {
        val ref = game.remote ?: return
        val local = states.list(game)
        var mirror = readMirror(game)
        for (entry in local) {
            val current = states.stamp(game, entry)
            val known = mirror.states[entry.id]
            if (known != null && known.local == current) continue
            val bytes = states.read(game, entry) ?: continue
            val screenshot = states.screenshot(game, entry)?.let(storage::read)
            val uploaded = client.uploadState(account, ref.romId, entry.coreId, RommStateNames.fileName(entry), bytes, screenshot)
            // An automatic state or a renamed one gets a new file; the old one goes.
            if (known != null && known.serverId != uploaded.id) client.deleteStates(account, listOf(known.serverId))
            mirror = mirror.copy(states = mirror.states + (entry.id to
                RommMirrorEntry(uploaded.id, uploaded.fileName, uploaded.updatedAt, current)))
            writeMirror(game, mirror)
        }
        val present = local.mapTo(hashSetOf()) { it.id }
        mirror.states.filterKeys { it !in present }.forEach { (id, known) ->
            client.deleteStates(account, listOf(known.serverId))
            mirror = mirror.copy(states = mirror.states - id)
            writeMirror(game, mirror)
        }
    }

    // Files -------------------------------------------------------------------------------------

    private fun lock(game: PreparedGame) = locks.getOrPut(game.saveKey) { Mutex() }

    private fun setState(game: PreparedGame, state: RommSyncState) = sync.update { it + (game.saveKey to state) }

    private fun mirrorFile(game: PreparedGame): File {
        val ref = requireNotNull(game.remote)
        return File(storage.remoteRoot(ref.serverKey), "mirror/${game.saveKey}.json")
    }

    private suspend fun readMirror(game: PreparedGame): RommMirror = withContext(Dispatchers.IO) {
        read(mirrorFile(game), RommMirror.serializer()) ?: RommMirror()
    }

    private suspend fun writeMirror(game: PreparedGame, mirror: RommMirror) = withContext(Dispatchers.IO) {
        write(mirrorFile(game), RommMirror.serializer(), mirror)
    }

    private fun <T> read(file: File, serializer: kotlinx.serialization.KSerializer<T>): T? = runCatching {
        if (!file.isFile) null else client.json.decodeFromString(serializer, file.readText())
    }.getOrNull()

    private fun <T> write(file: File, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        storage.write(file, client.json.encodeToString(serializer, value).toByteArray())
    }

    private fun stamp(file: File) = "${file.length()}:${file.lastModified()}"

    companion object {
        const val SOURCE_NAME = "RomM"
        private const val FIRMWARE_MANIFEST = ".romm-firmware.json"
        private const val UPLOAD_DELAY_MS = 1_500L
        private val RETRY_DELAYS_MS = longArrayOf(30_000L, 120_000L, 600_000L)
        private const val MAX_SRAM_BYTES = 32L * 1024 * 1024
        private const val MAX_SCREENSHOT_BYTES = 8L * 1024 * 1024
    }
}

/** File names that come from the server and must stay plain names in this device's folders. */
internal object RommFiles {
    fun safeName(name: String): String? {
        val plain = name.substringAfterLast('/').substringAfterLast('\\').trim()
        if (plain.isEmpty() || plain == "." || plain == ".." || plain.startsWith(".") || plain.length > 200) return null
        if (plain.any { it.isISOControl() || it in "\\/:*?\"<>|" }) return null
        return plain
    }

    fun isSram(name: String): Boolean = GameSystems.extension(name) in setOf("srm", "sav")
}
