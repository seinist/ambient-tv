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

/** A game's core data on this device ([RommCoreData]): which files are its, and the last match. */
@Serializable
internal data class RommCoreMirror(
    val romId: Long,
    /** The core of the game's last session: the archive's emulator on the server. */
    val coreId: String,
    val files: Set<String> = emptySet(),
    /** The archive on the server when this device last matched it; local is [RommCoreData.digest]. */
    val entry: RommMirrorEntry? = null,
)

/** The save folder as it was when a game started: what the game writes afterwards is its core data. */
@Serializable
internal data class RommCoreSession(val saveKey: String, val files: Map<String, String> = emptyMap())

@Serializable
internal data class RommFirmwareManifest(
    /** Before per-platform lists: file name → "size:modified:md5" (read only, never written again). */
    val files: Map<String, String> = emptyMap(),
    /**
     * Platform id → its firmware from the server: file name → "size:modified:md5". The system folder
     * is shared by all platforms of a server, so each platform removes only what it brought.
     */
    val platforms: Map<String, Map<String, String>> = emptyMap(),
)

/** How a save state on the server and this device's copy come together before a game starts. */
internal object RommStateSync {
    enum class Action { KEEP, DOWNLOAD }

    /**
     * [known] is the last match, [localStamp] / [localCreatedAt] describe the device's copy (null
     * when it has none). A state unchanged on the server since the last match is kept as it is here,
     * also when it was deleted here: the upload then deletes it on the server.
     */
    fun action(known: RommMirrorEntry?, localStamp: String?, localCreatedAt: Long?, asset: RommAsset): Action {
        val inSync = known != null && known.serverId == asset.id && known.fileName == asset.fileName &&
            known.updatedAt == asset.updatedAt
        if (inSync) return Action.KEEP
        val localChanged = localStamp != null && known?.local != localStamp
        // Changed here since the last match: the upload sends it.
        if (localChanged && known != null && known.serverId == asset.id) return Action.KEEP
        // Both sides made this state independently: the newer one wins.
        if (localChanged && localCreatedAt != null &&
            localCreatedAt >= (RommLibrary.parseInstant(asset.updatedAt) ?: 0L)) return Action.KEEP
        return Action.DOWNLOAD
    }
}

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
    /** The save folder is shared by every game of a server; its core data change under this lock. */
    private val coreLock = Mutex()
    /** The firmware folder and its manifest are shared by every platform of a server. */
    private val firmwareLock = Mutex()
    private val uploads = ConcurrentHashMap<String, Job>()
    /** Server key → when it last failed to answer; the next steps of the same start skip it. */
    private val unreachable = ConcurrentHashMap<String, Long>()
    private val sync = MutableStateFlow<Map<String, RommSyncState>>(emptyMap())
    /** Per game (save key): the state of its server mirror, for the game's Info panel. */
    val syncStates: StateFlow<Map<String, RommSyncState>> = sync.asStateFlow()

    /** Downloads the game (or reuses the cached download) and describes it for the player. */
    suspend fun prepare(fileId: String, onProgress: (Float?) -> Unit, preparer: GameRomPreparer): PreparedGame {
        val (serverKey, romId) = RommKeys.parse(fileId) ?: throw FileNotFoundException(fileId)
        val account = accounts.account()?.takeIf { it.serverKey == serverKey }
            ?: throw FileNotFoundException("The RomM server of this game is not signed in")
        // Fresh details while the server answers; the device's catalog copy otherwise (cached game).
        var answered = true
        val rom = try {
            client.rom(account, romId).also { unreachable.remove(serverKey) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RommAuthException) {
            throw error
        } catch (error: Exception) {
            if (error !is RommHttpException) unreachable[serverKey] = System.currentTimeMillis()
            answered = false
            library.rom(fileId) ?: throw error
        }
        if (!rom.playable) throw FileNotFoundException("RomM has no file for ${rom.displayTitle}")
        val system = RommPlatforms.system(rom) ?: library.platform(rom.platformId)?.let(RommPlatforms::system)
            ?: throw UnknownGameException(rom.fsName)
        val fileName = rom.fsName.ifBlank { "game" }
        // A game folder with one file is served as that file: its name carries the extension.
        val single = rom.files.singleOrNull()?.fileName?.let(RommFiles::safeName)
        val name = when {
            rom.zipped -> if (GameSystems.extension(fileName) != "zip") "$fileName.zip" else fileName
            single != null -> single
            else -> fileName
        }
        // Without an answer from the server the downloaded copy is played, whatever the device's list says.
        val file = preparer.materialize(fileId, name, rom.contentStamp, system.id, rom.fsSizeBytes, onProgress,
            acceptCached = !answered) { target, maxBytes, progress ->
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
            if (recentlyUnreachable(ref.serverKey)) return false
            return firmwareLock.withLock {
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
        // What the last game wrote after its last upload is its own, before anything here changes.
        val earlier = coreLock.withLock { finishCoreSession(ref.serverKey) }
        val account = accounts.account()?.takeIf { it.serverKey == ref.serverKey }
        if (account == null || recentlyUnreachable(ref.serverKey)) {
            coreLock.withLock { beginCoreSession(game, coreId) }
            // Played from the last copy; the upload waits for the server.
            setState(game, RommSyncState.PENDING)
            if (account != null) scheduleUpload(game, coreId, delayMs = RETRY_DELAYS_MS.first())
            return false
        }
        setState(game, RommSyncState.CHECKING)
        val reached = lock(game).withLock {
            try {
                val saves = client.saves(account, ref.romId)
                pullSram(account, game, coreId, saves)
                pullStates(account, game, coreStamp)
                pullCoreData(account, game, coreId, saves)
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
        coreLock.withLock { beginCoreSession(game, coreId) }
        if (reached && earlier != null && earlier != game.saveKey) scope.launch {
            try {
                pushCoreData(account, ref.serverKey, earlier)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                com.ambient.tv.diagnostics.DiagnosticLog.warn("romm", "core data upload failed: ${error.message}")
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
                        pushCoreData(account, ref.serverKey, game.saveKey)
                    }
                    unreachable.remove(ref.serverKey)
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
        val platformKey = ref.platformId.toString()
        val previous = manifest.platforms[platformKey].orEmpty()
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
            val recorded = previous[name]
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
        // Firmware removed on the server leaves this device's copy of it, too, unless another
        // platform of the server still uses a file of that name.
        val others = manifest.platforms - platformKey
        val usedElsewhere = others.values.flatMapTo(hashSetOf()) { it.keys }
        (previous.keys - kept.keys - usedElsewhere).forEach { File(directory, it).delete() }
        write(manifestFile, RommFirmwareManifest.serializer(), RommFirmwareManifest(platforms = others + (platformKey to kept)))
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

    private suspend fun pullSram(account: RommAccount, game: PreparedGame, coreId: String, saves: List<RommAsset>) {
        if (game.remote == null) return
        val candidates = saves.filter { RommFiles.isSram(it.fileName) }
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
        val copies = client.states(account, ref.romId)
            .mapNotNull { asset -> RommStateNames.parse(asset.fileName, coreStamp)?.let { it to asset } }
            .groupBy { it.first.id }
            .mapValues { (_, list) -> list.sortedByDescending { RommLibrary.parseInstant(it.second.updatedAt) ?: 0L } }
        val remote = copies.mapValues { (_, list) -> list.first() }
        // Older copies of the same state (left when a device replaced it while another kept the old one).
        val superseded = copies.values.flatMap { list -> list.drop(1).map { it.second.id } }
        val mirror = readMirror(game)
        val local = states.list(game).associateBy { it.id }
        val next = mirror.states.toMutableMap()
        remote.forEach { (id, pair) ->
            val (entry, asset) = pair
            val localEntry = local[id]
            val action = RommStateSync.action(mirror.states[id], localEntry?.let { states.stamp(game, it) },
                localEntry?.createdAt, asset)
            when (action) {
                RommStateSync.Action.KEEP -> Unit
                RommStateSync.Action.DOWNLOAD -> {
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
        superseded.forEach { id ->
            try {
                client.deleteStates(account, listOf(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                com.ambient.tv.diagnostics.DiagnosticLog.warn("romm", "old state copy not removed: ${error.message}")
            }
        }
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

    // Core data (memory cards, NAND, PSP SAVEDATA) --------------------------------------------

    /** Ends the recorded session: what changed in the save folder since it began is that game's. */
    private suspend fun finishCoreSession(serverKey: String): String? = withContext(Dispatchers.IO) {
        val sessionFile = coreSessionFile(serverKey)
        val session = read(sessionFile, RommCoreSession.serializer()) ?: return@withContext null
        val root = storage.remoteSavesDirectory(serverKey)
        val mirror = readCore(serverKey, session.saveKey)
        if (mirror != null) {
            val changed = RommCoreData.changed(session.files, RommCoreData.scan(root))
            val next = mirror.copy(files = mirror.files + changed)
            if (next != mirror) writeCore(serverKey, session.saveKey, next)
        }
        sessionFile.delete()
        session.saveKey.takeIf { mirror != null }
    }

    /** Records the save folder as [game] starts. */
    private suspend fun beginCoreSession(game: PreparedGame, coreId: String) = withContext(Dispatchers.IO) {
        val ref = game.remote ?: return@withContext
        val mirror = readCore(ref.serverKey, game.saveKey) ?: RommCoreMirror(ref.romId, coreId)
        if (mirror.coreId != coreId || mirror.romId != ref.romId) {
            writeCore(ref.serverKey, game.saveKey, mirror.copy(romId = ref.romId, coreId = coreId))
        } else if (!coreMirrorFile(ref.serverKey, game.saveKey).isFile) {
            writeCore(ref.serverKey, game.saveKey, mirror)
        }
        val files = RommCoreData.scan(storage.remoteSavesDirectory(ref.serverKey))
        write(coreSessionFile(ref.serverKey), RommCoreSession.serializer(), RommCoreSession(game.saveKey, files))
    }

    /** Before the core starts: files from the game's archive on the server where they are newer. */
    private suspend fun pullCoreData(account: RommAccount, game: PreparedGame, coreId: String, saves: List<RommAsset>) {
        val ref = game.remote ?: return
        val remote = saves.filter { RommCoreData.isBundle(it.fileName) }
            .maxByOrNull { RommLibrary.parseInstant(it.updatedAt) ?: 0L } ?: return
        val known = coreLock.withLock { withContext(Dispatchers.IO) { readCore(ref.serverKey, game.saveKey) } }?.entry
        if (known != null && known.serverId == remote.id && known.updatedAt == remote.updatedAt) return
        val bytes = client.assetBytes(account, remote.downloadPath ?: "/api/saves/${remote.id}/content",
            RommCoreData.MAX_BUNDLE_BYTES)
        coreLock.withLock {
            withContext(Dispatchers.IO) {
                val root = storage.remoteSavesDirectory(ref.serverKey)
                val unpacked = RommCoreData.unpack(bytes, root, client.json)
                val mirror = readCore(ref.serverKey, game.saveKey) ?: RommCoreMirror(ref.romId, coreId)
                val files = mirror.files + unpacked.paths
                // Files only this device has, or has newer, still have to go up.
                val localOnly = (files - unpacked.paths).any { File(root, it).isFile }
                val local = if (unpacked.keptNewer || localOnly) "" else RommCoreData.digest(root, files)
                writeCore(ref.serverKey, game.saveKey, mirror.copy(files = files,
                    entry = RommMirrorEntry(remote.id, remote.fileName, remote.updatedAt, local)))
            }
        }
    }

    /** Sends a game's core data as one archive when it changed since the last match. */
    private suspend fun pushCoreData(account: RommAccount, serverKey: String, saveKey: String) {
        class Packed(val romId: Long, val coreId: String, val bytes: ByteArray, val digest: String)
        val packed = coreLock.withLock {
            withContext(Dispatchers.IO) {
                var mirror = readCore(serverKey, saveKey) ?: return@withContext null
                val root = storage.remoteSavesDirectory(serverKey)
                val session = read(coreSessionFile(serverKey), RommCoreSession.serializer())
                if (session?.saveKey == saveKey) {
                    mirror = mirror.copy(files = mirror.files + RommCoreData.changed(session.files, RommCoreData.scan(root)))
                }
                val present = mirror.files.filterTo(linkedSetOf()) { File(root, it).isFile }
                mirror = mirror.copy(files = present)
                writeCore(serverKey, saveKey, mirror)
                if (present.isEmpty()) return@withContext null
                val digest = RommCoreData.digest(root, present)
                if (mirror.entry?.local == digest) return@withContext null
                val bytes = RommCoreData.pack(root, present, client.json) ?: run {
                    com.ambient.tv.diagnostics.DiagnosticLog.warn("romm", "core data of $saveKey is too large to send")
                    return@withContext null
                }
                Packed(mirror.romId, mirror.coreId, bytes, digest)
            }
        } ?: return
        val emulator = packed.coreId.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) } ?: "ambient-tv"
        val uploaded = client.uploadSave(account, packed.romId, emulator, RommCoreData.FILE_NAME, packed.bytes)
        coreLock.withLock {
            withContext(Dispatchers.IO) {
                val mirror = readCore(serverKey, saveKey) ?: return@withContext
                writeCore(serverKey, saveKey, mirror.copy(
                    entry = RommMirrorEntry(uploaded.id, uploaded.fileName, uploaded.updatedAt, packed.digest)))
            }
        }
    }

    private fun coreMirrorFile(serverKey: String, saveKey: String) =
        File(storage.remoteRoot(serverKey), "mirror/$saveKey.core.json")

    private fun coreSessionFile(serverKey: String) = File(storage.remoteRoot(serverKey), "mirror/session.json")

    private fun readCore(serverKey: String, saveKey: String): RommCoreMirror? =
        read(coreMirrorFile(serverKey, saveKey), RommCoreMirror.serializer())

    private fun writeCore(serverKey: String, saveKey: String, mirror: RommCoreMirror) =
        write(coreMirrorFile(serverKey, saveKey), RommCoreMirror.serializer(), mirror)

    // Files -------------------------------------------------------------------------------------

    private fun lock(game: PreparedGame) = locks.getOrPut(game.saveKey) { Mutex() }

    private fun recentlyUnreachable(serverKey: String): Boolean =
        unreachable[serverKey]?.let { System.currentTimeMillis() - it < UNREACHABLE_SKIP_MS } == true

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
        /** One failed answer covers the rest of a game start, not later attempts. */
        private const val UNREACHABLE_SKIP_MS = 60_000L
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
