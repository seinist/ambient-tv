package com.ambient.tv.ui.games

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.games.GameBiosManager
import com.ambient.tv.games.GameBiosStatus
import com.ambient.tv.games.GameCore
import com.ambient.tv.games.GameCoreExperiment
import com.ambient.tv.games.GameCoreIncompatibleException
import com.ambient.tv.games.GameCoreBuildbotConsentRequired
import com.ambient.tv.games.GameCoreSource
import com.ambient.tv.games.GameSettings
import com.ambient.tv.games.GameCoreManager
import com.ambient.tv.games.GameDirectUnavailableException
import com.ambient.tv.games.GameRomPreparer
import com.ambient.tv.games.GameStateEntry
import com.ambient.tv.games.GameStateKind
import com.ambient.tv.games.GameStateStore
import com.ambient.tv.games.GameStorage
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.PreparedGame
import com.ambient.tv.games.UnknownGameException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * One native game session. Identity must differ on every start, even for the same ROM/core:
 * a fast restart may replace Ready before Compose observes the intermediate Preparing state.
 */
class GameSession(
    val game: PreparedGame,
    /** The core playing it (the game's or the system's choice). */
    val core: GameCore,
    /** Ask GPU cores for Vulkan (setting); off after Vulkan failed in this run. */
    val vulkan: Boolean,
    val coreFile: File,
    val coreStamp: String,
    val systemDirectory: File,
    val savesDirectory: File,
    val sram: ByteArray?,
    /** The automatic state to continue from, if its core build matches. */
    val autoState: ByteArray?,
    /** Initial signed shader is verified during the existing IO preparation, not Compose. */
    val initialPackSelection: String? = null,
    val initialPackConfig: com.ambient.tv.games.RetroShaderConfig.Custom? = null,
    val coreSource: GameCoreSource = GameCoreSource.AUTO,
)

sealed interface GameUiState {
    /** Copying the game; [progress] 0…1 or null while unknown. */
    data class Preparing(val progress: Float?) : GameUiState
    /** The system's core is not installed yet: the user decides whether to load it. */
    data class NeedsCore(val game: PreparedGame, val core: GameCore, val sourceUrl: String?, val source: GameCoreSource = GameCoreSource.AUTO) : GameUiState
    data class ConfirmBuildbot(val game: PreparedGame, val core: GameCore, val reason: String) : GameUiState
    data class DownloadingCore(val game: PreparedGame, val core: GameCore, val progress: Float?) : GameUiState
    data class Ready(val session: GameSession) : GameUiState
    data class Failed(val reason: GameFailure, val detail: String? = null) : GameUiState
}

enum class GameFailure { NOT_FOUND, UNKNOWN_SYSTEM, DIRECT_ACCESS, CORE_DOWNLOAD, CORE_INCOMPATIBLE, NO_ABI, BIOS_MISSING, PREVIOUS_SESSION, START }

@HiltViewModel
class GamePlayerViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val preparer: GameRomPreparer,
    private val cores: GameCoreManager,
    private val storage: GameStorage,
    private val states: GameStateStore,
    private val bios: GameBiosManager,
    private val files: FileStore,
    private val gameLibrary: com.ambient.tv.games.library.GameLibraryRepository,
    val retroAchievements: com.ambient.tv.games.RetroAchievementsClient,
    private val cheatStore: com.ambient.tv.games.GameCheatStore,
    val shaderPacks: com.ambient.tv.games.GameShaderPackStore,
    private val remoteData: com.ambient.tv.romm.RommGameData,
    private val rommLibrary: com.ambient.tv.romm.RommLibrary,
) : ViewModel() {
    private val fileId: String = checkNotNull(saved.get<String>("fileId")) { "fileId" }
    /** A game from a RomM server: its firmware, saves and states are the server's. */
    val remote: Boolean = com.ambient.tv.romm.RommKeys.owns(fileId)
    /** Per game: how its saves and states stand compared with the RomM server. */
    val remoteSync: StateFlow<Map<String, com.ambient.tv.romm.RommSyncState>> = remoteData.syncStates
    private val assignedSystem: String? = saved.get<String>("system")?.takeIf(String::isNotBlank)
    private val savedArguments = saved
    val testingCore: Boolean get() = !savedArguments.get<String>("experiment").isNullOrBlank()
    /** "Start over" from the detail page: applies to the first start only, not to later restarts. */
    private var startFresh: Boolean = saved.get<Boolean>("fresh") == true
    private val mutable = MutableStateFlow<GameUiState>(GameUiState.Preparing(null))
    val state: StateFlow<GameUiState> = mutable.asStateFlow()
    private val stateList = MutableStateFlow<List<GameStateEntry>>(emptyList())
    /** Save states of the running game: automatic, undo, then the user's newest first. */
    val saveStates: StateFlow<List<GameStateEntry>> = stateList.asStateFlow()
    private var job: Job? = null
    private var prepared: PreparedGame? = null
    private var forceCache = false
    private var directFailed = false
    private var recordedSession: GameSession? = null

    /** A failed direct mount or unsupported core can be retried with a LOCAL COPY only by consent. */
    val canUseCache: Boolean get() = directFailed && !forceCache
    fun retryWithCache() {
        forceCache = true
        load()
    }

    override fun onCleared() {
        job?.cancel()
        prepared?.close()
        super.onCleared()
    }

    init { load() }

    fun retry() = load()

    fun startRegular() {
        savedArguments["experiment"] = ""
        restart(retryVulkan = true)
    }

    /** Vulkan did not come up in this run: the game starts again with OpenGL ES. */
    private var vulkanFailed = false

    /** Starts the game again (after its core or graphics interface was changed). */
    fun restart(retryVulkan: Boolean = false) {
        if (retryVulkan) vulkanFailed = false
        load()
    }

    /** The core failed while Vulkan was asked for: try once more with OpenGL ES. */
    fun retryWithoutVulkan(): Boolean {
        if (vulkanFailed || (state.value as? GameUiState.Ready)?.session?.core?.requiresVulkan == true) return false
        vulkanFailed = true
        load()
        return true
    }

    private fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            mutable.value = GameUiState.Preparing(null)
            prepared?.close()
            prepared = null
            directFailed = false
            if (!withContext(Dispatchers.IO) { LibretroFrontendAvailability.probe() }) {
                mutable.value = GameUiState.Failed(GameFailure.START)
                return@launch
            }
            val game = try {
                preparer.prepare(fileId, { progress -> mutable.value = GameUiState.Preparing(progress) }, forceCache, assignedSystem)
            } catch (error: GameDirectUnavailableException) {
                directFailed = true
                mutable.value = GameUiState.Failed(GameFailure.DIRECT_ACCESS, error.message)
                return@launch
            } catch (error: UnknownGameException) {
                mutable.value = GameUiState.Failed(GameFailure.UNKNOWN_SYSTEM, error.name); return@launch
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value = GameUiState.Failed(GameFailure.NOT_FOUND, error.message); return@launch
            }
            prepared = game
            directFailed = game.direct
            val abi = cores.abi ?: run { mutable.value = GameUiState.Failed(GameFailure.NO_ABI); return@launch }
            val settings = GameSettings.state
            val chosen = settings.gameCore(game.system, game.saveKey)
            if (chosen.supportedAbis.isNotEmpty() && abi !in chosen.supportedAbis) {
                mutable.value = GameUiState.Failed(GameFailure.NO_ABI, chosen.name)
                return@launch
            }
            val source = if (testingCore) {
                val experiment = GameCoreExperiment.fromKey(savedArguments.get<String>("experiment").orEmpty())
                if (experiment == null ||
                    (experiment.source !in GameCoreSource.publicExperiments && !settings.coreExperimentsEnabled) ||
                    experiment.coreId != chosen.id ||
                    !experiment.source.supportsExperiment(chosen, abi)) {
                    mutable.value = GameUiState.Failed(GameFailure.CORE_INCOMPATIBLE)
                    return@launch
                }
                experiment.source
            } else settings.gameCoreSource(game.system, game.saveKey)
            // A RomM game's firmware is its server's: matched to it before the check.
            if (game.remote != null) remoteData.syncFirmware(game)
            bios.missingRequired(game.system, GameSystems.extension(game.file.name), chosen,
                storage.systemDirectory(game))?.let { missing ->
                mutable.value = GameUiState.Failed(GameFailure.BIOS_MISSING, missing.file); return@launch
            }
            // Explicit experiments remain strict; normal optimized preferences may fall back.
            val resolvedSource = try {
                if (testingCore) source else cores.resolvePreferredSource(chosen, source)
            } catch (error: GameCoreBuildbotConsentRequired) {
                mutable.value = GameUiState.ConfirmBuildbot(game, chosen, error.message.orEmpty())
                return@launch
            } catch (error: GameCoreIncompatibleException) {
                mutable.value = GameUiState.Failed(GameFailure.CORE_INCOMPATIBLE, error.message)
                return@launch
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value = GameUiState.Failed(GameFailure.CORE_DOWNLOAD, error.message)
                return@launch
            }
            val core = cores.installed(chosen, resolvedSource)
            if (core == null) mutable.value = GameUiState.NeedsCore(game, chosen, cores.sourceUrl(chosen), resolvedSource)
            else start(game, chosen, core, resolvedSource)
        }
    }

    /** The user agreed to load the core for this system. */
    fun downloadCore() = downloadCoreFrom((state.value as? GameUiState.NeedsCore)?.source ?: GameSettings.state.coreSource)
    /** User-facing confirmation after AUTO would otherwise switch to unsigned native code. */
    fun confirmBuildbotCore() = downloadCoreFrom(GameCoreSource.BUILDBOT)

    private fun downloadCoreFrom(source: GameCoreSource) {
        val (game, chosen) = when (val needed = state.value) {
            is GameUiState.NeedsCore -> needed.game to needed.core
            is GameUiState.ConfirmBuildbot -> needed.game to needed.core
            else -> return
        }
        job?.cancel()
        job = viewModelScope.launch {
            mutable.value = GameUiState.DownloadingCore(game, chosen, null)
            val file = try {
                cores.download(chosen, source) { progress ->
                    mutable.value = GameUiState.DownloadingCore(game, chosen, progress)
                }
            } catch (error: GameCoreBuildbotConsentRequired) {
                mutable.value = GameUiState.ConfirmBuildbot(game, chosen, error.message.orEmpty())
                return@launch
            } catch (error: GameCoreIncompatibleException) {
                mutable.value = GameUiState.Failed(GameFailure.CORE_INCOMPATIBLE, error.message); return@launch
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value = GameUiState.Failed(GameFailure.CORE_DOWNLOAD, error.message); return@launch
            }
            start(game, chosen, file, source)
        }
    }

    private suspend fun start(originalGame: PreparedGame, chosen: GameCore, core: File, source: GameCoreSource) {
        val game = originalGame.copy(experimentalProfile = source.experimentalProfile)
        try {
            withContext(Dispatchers.IO) {
                gameNativeSessions.awaitClosed()
                GameCheckpointIo.awaitPendingWrites()
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            mutable.value = GameUiState.Failed(GameFailure.PREVIOUS_SESSION)
            return
        }
        val initialPackSelection = GameSettings.state.packShader(game.system, game.saveKey)
        val session = withContext(Dispatchers.IO) {
            val stamp = GameStateStore.coreStamp(core)
            val legacyStamp = GameStateStore.legacyCoreStamp(core)
            // A RomM game continues from the server's saves and states (the last copy when offline).
            if (game.remote != null) remoteData.beforeStart(game, chosen.id, stamp)
            val existing = states.entry(game, GameStateKind.AUTO)
            if (startFresh && existing != null) {
                // Like a reset in the game: the last progress stays recoverable as the undo state.
                runCatching {
                    states.read(game, existing)?.let { bytes ->
                        states.save(game, GameStateKind.UNDO, bytes, null, existing.coreStamp, coreId = existing.coreId)
                    }
                }
                states.delete(game, existing)
            }
            val auto = existing?.takeIf {
                !startFresh && chosen.saveStates && GameStateStore.compatible(it, chosen.id, stamp, legacyStamp)
            }
            if (startFresh) {
                startFresh = false
                savedArguments["fresh"] = false
            }
            GameSession(
                game = game,
                core = chosen,
                vulkan = com.ambient.tv.games.GameSettings.state.vulkan(chosen) && (chosen.requiresVulkan || !vulkanFailed),
                coreFile = core,
                coreStamp = stamp,
                systemDirectory = storage.systemDirectory(game),
                savesDirectory = storage.savesDirectory(game),
                sram = storage.read(storage.sramFile(game)),
                autoState = auto?.let { states.read(game, it) },
                initialPackSelection = initialPackSelection,
                initialPackConfig = shaderPacks.config(initialPackSelection),
                coreSource = source,
            ).also { refreshStates(game) }
        }
        // "Start over" moved the automatic state: the server follows.
        if (game.remote != null) remoteData.scheduleUpload(game, chosen.id)
        mutable.value = GameUiState.Ready(session)
    }

    private fun refreshStates(game: PreparedGame) {
        // A checkpoint scan can race a manual save/delete. Retry the disk snapshot
        // when another refresh publishes first instead of restoring an older list.
        stateList.update { states.list(game) }
    }

    fun biosStatus(game: PreparedGame): List<GameBiosStatus> = bios.status(game.system, storage.systemDirectory(game))

    /** The library cover for the OSD header, like the artwork other players show; null outside the library. */
    suspend fun coverUrl(): String? = withContext(Dispatchers.IO) {
        if (remote) return@withContext runCatching { rommLibrary.game(fileId)?.coverPath }.getOrNull()
        runCatching { gameLibrary.game(fileId)?.coverPath }.getOrNull()
            ?.let { android.net.Uri.fromFile(java.io.File(it)).toString() }
    }

    /** Sends this game's saves and states to its RomM server (after a change); nothing for local games. */
    private fun upload(game: PreparedGame, coreId: String? = null) {
        if (game.remote == null) return
        val core = coreId ?: (state.value as? GameUiState.Ready)?.session?.core?.id
            ?: GameSettings.state.gameCore(game.system, game.saveKey).id
        remoteData.scheduleUpload(game, core)
    }

    /**
     * Record history only after Libretro actually renders its first game frame.
     * Missing cores, failed mounts and crashing cores must not pollute Recent games.
     */
    fun firstFrameRendered(session: GameSession) {
        if ((mutable.value as? GameUiState.Ready)?.session !== session ||
            recordedSession === session) return
        recordedSession = session
        viewModelScope.launch(Dispatchers.IO) {
            if (session.game.remote != null) {
                runCatching { rommLibrary.recordPlayed(session.game.fileId) }
                return@launch
            }
            runCatching { files.awaitReady(); files.record(session.game.entry) }
            runCatching { gameLibrary.recordPlayed(session.game.fileId) }
        }
    }

    /** The core failed to load or crashed while starting. */
    fun failed(detail: String?) {
        mutable.value = GameUiState.Failed(GameFailure.START, detail)
    }

    internal fun previousSessionUnavailable() {
        mutable.value = GameUiState.Failed(GameFailure.PREVIOUS_SESSION)
    }

    /** Removes a core (e.g. after it failed) so the next start loads it again. */
    /** A corrupt update can be replaced by its previous installed core without downloading. */
    fun rollbackCore(core: GameCore, source: GameCoreSource = GameCoreSource.AUTO): Boolean = cores.rollback(core, source)
    fun removeCore(core: GameCore, source: GameCoreSource = GameCoreSource.AUTO, keepPrevious: Boolean = false) =
        cores.remove(core, source, keepPrevious)

    fun cheats(game: PreparedGame): List<com.ambient.tv.games.GameCheat> = cheatStore.load(game)

    fun saveCheats(game: PreparedGame, cheats: List<com.ambient.tv.games.GameCheat>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { cheatStore.save(game, cheats) }
    }

    suspend fun databaseCheats(game: PreparedGame): List<com.ambient.tv.games.GameCheat> = cheatStore.fromDatabase(game)

    /** Remembers what [core] declares, so settings can offer its options outside a game. */
    fun saveDeclaredOptions(core: GameCore, options: List<Pair<String, String?>>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { cores.saveDeclaredOptions(core, options) }
    }

    /** Reserve before GL capture; disk/JPEG work outlives the screen on the serial IO queue. */
    internal fun reserveCheckpoint(session: GameSession, screenshot: Bitmap?): GameCheckpointTicket<Pair<ByteArray?, ByteArray?>> =
        GameCheckpointIo.reserve { (sram, state) ->
            sram?.let { runCatching { storage.write(storage.sramFile(session.game), it) } }
            state?.let {
                runCatching {
                    states.save(session.game, GameStateKind.AUTO, it, screenshot, session.coreStamp,
                        coreId = session.core.id)
                }
            }
            refreshStates(session.game)
            upload(session.game, session.core.id)
        }

    suspend fun saveState(session: GameSession, kind: GameStateKind, bytes: ByteArray, screenshot: Bitmap?): GameStateEntry = withContext(Dispatchers.IO) {
        val entry = states.save(session.game, kind, bytes, screenshot, session.coreStamp, coreId = session.core.id)
        refreshStates(session.game)
        upload(session.game, session.core.id)
        entry
    }

    suspend fun readState(game: PreparedGame, entry: GameStateEntry): ByteArray? =
        withContext(Dispatchers.IO) { states.read(game, entry) }

    fun screenshot(game: PreparedGame, entry: GameStateEntry): File? = states.screenshot(game, entry)

    fun latestManualState(game: PreparedGame): GameStateEntry? =
        stateList.value.filter { it.kind == GameStateKind.MANUAL }
            .maxWithOrNull(compareBy<GameStateEntry> { it.createdAt }.thenBy { it.number })

    suspend fun renameState(game: PreparedGame, entry: GameStateEntry, label: String) = withContext(Dispatchers.IO) {
        states.rename(game, entry, label)
        refreshStates(game)
        upload(game)
    }

    fun deleteState(game: PreparedGame, entry: GameStateEntry) {
        val remove = {
            states.delete(game, entry)
            refreshStates(game)
            upload(game)
        }
        // Only AUTO shares files with asynchronous checkpoints. Fence its deletion like
        // "Start over", including captures that complete late after the IO wait timed out.
        if (entry.kind == GameStateKind.AUTO) GameCheckpointIo.execute(remove)
        else viewModelScope.launch(Dispatchers.IO) { remove() }
    }

    /** "Start over": the next start does not continue from the automatic state. */
    fun clearAutoState(game: PreparedGame) {
        // Ordered after an earlier background save, so it cannot resurrect an old auto-state.
        GameCheckpointIo.execute {
            states.entry(game, GameStateKind.AUTO)?.let { states.delete(game, it) }
            refreshStates(game)
            upload(game)
        }
    }
}
